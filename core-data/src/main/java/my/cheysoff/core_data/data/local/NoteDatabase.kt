package my.cheysoff.core_data.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// exportSchema is on (schemas land in core-data/schemas, wired up via the room.schemaLocation KSP
// arg) so future migrations can be exercised by MigrationTestHelper instead of only on a device.
// Only v5 onward is exported — v1..v4 predate the flag, so a 4 -> 5 migration test isn't possible
// retroactively; from here on every version has a committed schema to migrate from.
@Database(entities = [NoteEntity::class, FolderEntity::class], version = 6, exportSchema = true)
abstract class NoteDatabase : RoomDatabase() {
    abstract val noteDao: NoteDao
    abstract val folderDao: FolderDao

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
    }
}
