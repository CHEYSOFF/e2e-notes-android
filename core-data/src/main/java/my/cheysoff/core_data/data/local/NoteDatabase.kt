package my.cheysoff.core_data.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// exportSchema is on (schemas land in core-data/schemas, wired up via the room.schemaLocation KSP
// arg) so future migrations can be exercised by MigrationTestHelper instead of only on a device.
// Only v5 onward is exported — v1..v4 predate the flag, so a 4 -> 5 migration test isn't possible
// retroactively; from here on every version has a committed schema to migrate from.
/**
 * The schema version. Named rather than written as a literal in the annotation so a migration test
 * can assert "the chain ends at the current version" instead of hard-coding a number that goes
 * stale the moment the next migration lands — which is how Migration4to5Test came to be broken
 * without anyone noticing.
 */
internal const val NOTE_DATABASE_VERSION = 7

@Database(
    entities = [NoteEntity::class, FolderEntity::class, SyncStateEntity::class],
    version = NOTE_DATABASE_VERSION,
    exportSchema = true,
)
abstract class NoteDatabase : RoomDatabase() {
    abstract val noteDao: NoteDao
    abstract val folderDao: FolderDao
    abstract val syncStateDao: SyncStateDao

    companion object {
        /**
         * On-disk filename of the encrypted database. Existing installs already have a file with
         * this exact name, so it must never change — renaming it would orphan every user's notes.
         */
        const val DATABASE_NAME = "notes.db"

        // v1 -> v2: add isFavorite + createdAt/updatedAt. Additive, so existing notes survive.
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notes ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notes ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v2 -> v3: add the folders table. Must match Room's expected schema for FolderEntity.
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `folders` " +
                        "(`id` TEXT NOT NULL, `name` TEXT NOT NULL, `colorArgb` INTEGER, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }

        // v3 -> v4: add the serialized checklist blob. Additive, so existing notes survive.
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN checklist TEXT NOT NULL DEFAULT ''")
            }
        }

        // v4 -> v5: record how each note's body is encoded instead of sniffing it at read time.
        // Additive; no existing column is dropped or rewritten.
        //
        // The column default is 'plain', the safe direction: a genuine HTML note read as plain
        // shows raw markup (visible, recoverable), whereas plain text read as HTML is silently
        // truncated by the parser and then overwritten by the next keystroke.
        //
        // But the app has been writing HTML for a while, so leaving *every* pre-existing row at
        // 'plain' would spray markup across essentially the whole library. That's why there is a
        // single classification pass here — the only time the app ever guesses. It uses the
        // anchored looksLikeEditorHtml() rather than the old "contains a tag" regex, so a note
        // reading "Email John <john@example.com>" stays plain and survives intact.
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN contentFormat TEXT NOT NULL DEFAULT 'plain'")

                // Collect first, then update: mutating the table while its cursor is open is not
                // something SQLite guarantees anything sensible about.
                val htmlIds = mutableListOf<String>()
                // Read only a PREFIX of each body. The classifier is anchored, so it never looks
                // past the first tag, while "SELECT content" would stream every note through a
                // ~2MB CursorWindow — one pasted document over that limit throws, and a throw in
                // here rolls the migration back on every launch, permanently.
                db.query("SELECT id, substr(content, 1, 256) FROM notes").use { cursor ->
                    while (cursor.moveToNext()) {
                        val content = cursor.getString(1) ?: continue
                        if (looksLikeEditorHtml(content)) htmlIds += cursor.getString(0)
                    }
                }
                htmlIds.forEach { id ->
                    db.execSQL(
                        "UPDATE notes SET contentFormat = 'html' WHERE id = ?",
                        arrayOf<Any>(id),
                    )
                }
            }
        }

        // v5 -> v6: Trash. Notes and folders gain a tombstone (isDeleted/deletedAt) so deleting is
        // reversible, and `folders` gains the createdAt/updatedAt pair it never had.
        //
        // Purely additive ALTER TABLE ... ADD COLUMN, with no backfill pass and no data read at
        // all — unlike MIGRATION_4_5, nothing here has to be inferred from existing content.
        //
        // The defaults are what make it safe on an existing install:
        //  - isDeleted DEFAULT 0, so every note and folder already on disk stays visible. (Every
        //    read query added `WHERE isDeleted = 0` in the same change; a default of 1 would hide
        //    the entire library behind it.)
        //  - deletedAt is nullable with no default, so untrashed rows carry NULL rather than an
        //    instant in 1970 — see TrashPolicy, which refuses to age a row from a 0 or NULL stamp.
        //  - createdAt/updatedAt DEFAULT 0 on folders, matching what `notes` did in v1 -> v2. 0 is
        //    the "unset" sentinel the rest of the code already understands; SQLite requires a
        //    non-null default for a NOT NULL column added to a table that may hold rows, and the
        //    alternative — stamping every existing folder with the migration's own clock — would
        //    invent a creation time that never happened.
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN isDeleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notes ADD COLUMN deletedAt INTEGER")

                db.execSQL("ALTER TABLE folders ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE folders ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE folders ADD COLUMN isDeleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE folders ADD COLUMN deletedAt INTEGER")
            }
        }

        // v6 -> v7: the sync bookkeeping every row needs before this device can ever talk to
        // another one — a hybrid logical clock, per-field clocks, and the two flags that say
        // whether the server has seen this version of the row. Plus `sync_state`, which records
        // how far through the account's history this device has read.
        //
        // Purely additive ALTER TABLE ... ADD COLUMN plus one CREATE TABLE. Nothing is dropped,
        // nothing is rewritten, and — unlike MIGRATION_4_5 — not one byte of existing content is
        // read, so there is no classification pass to get wrong and no CursorWindow to overflow.
        // The whole risk of this migration is in six DEFAULT clauses, and one of them in
        // particular:
        //
        // ⚠️ `dirty` DEFAULTs to 1, NOT 0. ⚠️
        //
        //   Every row already on disk when this runs has, by definition, never been pushed to a
        //   server — there was no sync engine when it was written. `DEFAULT 1` says exactly that:
        //   "this row is a local change the server has not seen". `DEFAULT 0` would say the
        //   opposite, that the user's entire existing library is already safely uploaded, and the
        //   first pull would then reconcile a full local library against an account the server has
        //   never heard of. A record the server does not have, which the client believes it has
        //   already pushed, is a record that was deleted elsewhere. The library would be tombstoned
        //   note by note, on every paired device, with no undo. That is one character in this file.
        //
        //   Three places have to agree on it and all three are checked: this DDL, the Kotlin
        //   default on NoteEntity/FolderEntity, and @ColumnInfo(defaultValue = "1") — which is what
        //   makes Room compare its expectation against the real table on every open, so a wrong
        //   default here fails at startup rather than at the first sync. Migration6to7Test asserts
        //   the migrated value directly as well, because a test that would still pass with a 0 in
        //   this line is not a test of this line.
        //
        // The rest:
        //  - hlcMs/hlcCounter DEFAULT 0 and hlcNode DEFAULT '': the zero clock, which compares
        //    BELOW every real one. A migrated row therefore loses to any genuine remote edit it
        //    knows nothing about, which is the right way round — the row is still dirty, so its
        //    content is pushed and merged rather than dropped, and the first local write stamps it
        //    with a real clock. Stamping every row with the migration's own clock instead would
        //    invent a history that never happened and would make thousands of rows tie.
        //  - fieldHlc DEFAULT '': "every field of this row is at the row clock", which is exactly
        //    true of a row whose fields were all written together before any of this existed.
        //  - lastSyncedSeq DEFAULT 0: "the server has no version of this record", which is what
        //    the server itself reads a baseSeq of 0 as.
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (table in listOf("notes", "folders")) {
                    db.execSQL("ALTER TABLE $table ADD COLUMN hlcMs INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE $table ADD COLUMN hlcCounter INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE $table ADD COLUMN hlcNode TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE $table ADD COLUMN fieldHlc TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE $table ADD COLUMN dirty INTEGER NOT NULL DEFAULT 1")
                    db.execSQL("ALTER TABLE $table ADD COLUMN lastSyncedSeq INTEGER NOT NULL DEFAULT 0")
                }

                // Copied from Room's own generated DDL for SyncStateEntity (see
                // core-data/schemas/…/7.json). It has to match column for column, including the
                // backticks and the absence of DEFAULT clauses, or Room's schema validation
                // rejects the migrated database on the next open.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_state` " +
                        "(`accountId` TEXT NOT NULL, `cursor` INTEGER NOT NULL, " +
                        "`lastPullAt` INTEGER NOT NULL, PRIMARY KEY(`accountId`))"
                )
            }
        }

        /**
         * Every migration above, in order. `DataModule` spreads this into Room's builder instead
         * of listing the fields a second time, so the chain the app ships with cannot be missing a
         * step that exists here.
         *
         * `MigrationChainTest` asserts the list runs 1 -> [NOTE_DATABASE_VERSION] with no gap and
         * no repeat, so bumping the schema without writing the matching migration fails on the JVM
         * rather than on a user's device.
         *
         * The two instrumented migration tests still assemble their own lists by hand — they are
         * out of scope here, and #56 rewrites them onto MigrationTestHelper anyway.
         *
         * Declared last because a companion object initialises its properties in source order; a
         * list placed above the migrations it names would be an array of nulls.
         */
        val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
        )
    }
}
