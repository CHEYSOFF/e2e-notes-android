package my.cheysoff.core_data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import my.cheysoff.core_data.data.local.NOTE_DATABASE_VERSION
import my.cheysoff.core_data.data.local.NoteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MIGRATION_10_11 — the brand-new `attachments` table — against a v10 database built by hand from
 * the DDL committed at `core-data/schemas/…/10.json`.
 *
 * Modelled on [Migration6to7Test] and [Migration7to8Test], including running on a PLAIN
 * (unencrypted) SQLite file: SQLCipher is an open-helper swap and changes nothing about what a
 * `CREATE TABLE` does.
 *
 * ## What is at risk
 *
 * The `CREATE TABLE` itself, and — the same story as every migration before it — the DEFAULT
 * clauses. `attachments` has no rows at the moment this migration runs, so there is nothing to
 * backfill, but the defaults still matter for the very first row anything inserts afterwards:
 *
 * ⚠️ **`dirty` must default to 1, `isDeleted` must default to 0.** A row that defaults to clean
 * never gets picked up by [my.cheysoff.core_data.data.local.AttachmentDao.dirtyAttachments] and
 * therefore never syncs — silently, because nothing fails loudly when a row simply never gets
 * looked at again. `isDeleted` defaulting to 1 would hide every freshly imported attachment from
 * its own note. Both are pinned in three places that all have to agree: this DDL
 * (`MIGRATION_10_11`), `AttachmentEntity`'s Kotlin default, and its `@ColumnInfo(defaultValue = …)`
 * — a mismatch fails Room's schema validation at startup rather than at the first sync.
 */
@RunWith(AndroidJUnit4::class)
class Migration10to11Test {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration_10_11_test.db"

    /** v10's exported `notes` DDL, copied from schemas/…/10.json. */
    private val v10Notes = "CREATE TABLE IF NOT EXISTS `notes` (`id` TEXT NOT NULL, " +
        "`title` TEXT NOT NULL, `content` TEXT NOT NULL, `contentFormat` TEXT NOT NULL, " +
        "`checklist` TEXT NOT NULL, `isPinned` INTEGER NOT NULL, `isFavorite` INTEGER NOT NULL, " +
        "`folderId` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
        "`isDeleted` INTEGER NOT NULL, `deletedAt` INTEGER, " +
        "`hlcMs` INTEGER NOT NULL DEFAULT 0, `hlcCounter` INTEGER NOT NULL DEFAULT 0, " +
        "`hlcNode` TEXT NOT NULL DEFAULT '', `fieldHlc` TEXT NOT NULL DEFAULT '', " +
        "`dirty` INTEGER NOT NULL DEFAULT 1, `lastSyncedSeq` INTEGER NOT NULL DEFAULT 0, " +
        "`contentSyncedHlc` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`id`))"

    /** v10's exported `folders` DDL, copied from schemas/…/10.json. */
    private val v10Folders = "CREATE TABLE IF NOT EXISTS `folders` (`id` TEXT NOT NULL, " +
        "`name` TEXT NOT NULL, `colorArgb` INTEGER, `createdAt` INTEGER NOT NULL, " +
        "`updatedAt` INTEGER NOT NULL, `isDeleted` INTEGER NOT NULL, `deletedAt` INTEGER, " +
        "`hlcMs` INTEGER NOT NULL DEFAULT 0, `hlcCounter` INTEGER NOT NULL DEFAULT 0, " +
        "`hlcNode` TEXT NOT NULL DEFAULT '', `fieldHlc` TEXT NOT NULL DEFAULT '', " +
        "`dirty` INTEGER NOT NULL DEFAULT 1, `lastSyncedSeq` INTEGER NOT NULL DEFAULT 0, " +
        "PRIMARY KEY(`id`))"

    /** v10's exported `sync_state` DDL, copied from schemas/…/10.json. */
    private val v10SyncState = "CREATE TABLE IF NOT EXISTS `sync_state` (`accountId` TEXT NOT NULL, " +
        "`cursor` INTEGER NOT NULL, `lastPullAt` INTEGER NOT NULL, " +
        "`haltReason` TEXT NOT NULL DEFAULT '', `dataVersion` INTEGER NOT NULL DEFAULT 0, " +
        "PRIMARY KEY(`accountId`))"

    /** v10's exported `sketches` DDL, copied from schemas/…/10.json. */
    private val v10Sketches = "CREATE TABLE IF NOT EXISTS `sketches` (" +
        "`uuid` TEXT NOT NULL, `noteId` TEXT NOT NULL, `anchor` INTEGER NOT NULL, " +
        "`sortOrder` INTEGER NOT NULL, `strokes` TEXT NOT NULL, " +
        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
        "`isDeleted` INTEGER NOT NULL DEFAULT 0, `deletedAt` INTEGER, " +
        "`hlcMs` INTEGER NOT NULL, `hlcCounter` INTEGER NOT NULL, " +
        "`hlcNode` TEXT NOT NULL, `fieldHlc` TEXT NOT NULL DEFAULT '', " +
        "`dirty` INTEGER NOT NULL DEFAULT 1, `lastSyncedSeq` INTEGER NOT NULL DEFAULT 0, " +
        "PRIMARY KEY(`uuid`))"

    /**
     * v10's index on `sketches`, which the table DDL above does not carry.
     *
     * Room validates a migrated database against the whole entity, and `TableInfo` includes
     * indices — so seeding the table without this fails as "Migration didn't properly handle:
     * sketches", pointing at the migration when the fault is in the fixture. Any table seeded here
     * needs its indices as well as its columns.
     */
    private val v10SketchesIndex =
        "CREATE INDEX IF NOT EXISTS `index_sketches_noteId` ON `sketches` (`noteId`)"

    @Before
    fun setUp() {
        ctx.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        ctx.deleteDatabase(dbName)
    }

    private fun seedV10() {
        val path = ctx.getDatabasePath(dbName)
        path.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(path, null)
        db.execSQL(v10Notes)
        db.execSQL(v10Folders)
        db.execSQL(v10SyncState)
        db.execSQL(v10Sketches)
        db.execSQL(v10SketchesIndex)
        db.execSQL(
            "INSERT INTO notes (id, title, content, contentFormat, checklist, isPinned, " +
                "isFavorite, folderId, createdAt, updatedAt, isDeleted, deletedAt, " +
                "hlcMs, hlcCounter, hlcNode, fieldHlc, dirty, lastSyncedSeq, contentSyncedHlc) " +
                "VALUES ('note-1', 'title', 'body', 'plain', '', 0, 0, NULL, 1000, 2000, 0, NULL, " +
                "0, 0, '', '', 1, 0, '')"
        )
        db.execSQL("PRAGMA user_version = 10")
        db.close()
    }

    private fun openMigrated(): NoteDatabase =
        Room.databaseBuilder(ctx, NoteDatabase::class.java, dbName)
            // The whole chain, spread rather than listed: a hand-built list is a second copy of
            // `ALL_MIGRATIONS` that goes stale silently — a hand-built list already missed
            // `MIGRATION_9_10` once, and it passed while doing so.
            .addMigrations(*NoteDatabase.ALL_MIGRATIONS)
            .build()

    /**
     * The migration runs, Room accepts the result as v11, the pre-existing note survives
     * untouched, and the new `attachments` table exists and is empty.
     */
    @Test
    fun migrationRunsPreservesTheNoteAndCreatesAnEmptyAttachmentsTable() {
        seedV10()
        val db = openMigrated()
        try {
            assertEquals(NOTE_DATABASE_VERSION, db.openHelper.readableDatabase.version)

            db.openHelper.readableDatabase.query("SELECT title, content FROM notes WHERE id = 'note-1'")
                .use { c ->
                    assertEquals("the pre-existing note did not survive the migration", 1, c.count)
                    c.moveToFirst()
                    assertEquals("title", c.getString(0))
                    assertEquals("body", c.getString(1))
                }

            db.openHelper.readableDatabase.query("SELECT count(*) FROM attachments").use { c ->
                c.moveToFirst()
                assertEquals("a fresh attachments table must start empty", 0, c.getInt(0))
            }
        } finally {
            db.close()
        }
    }

    /**
     * ⚠️ The one that matters: a row inserted after the migration, specifying neither `dirty` nor
     * `isDeleted`, must come back `dirty = 1` and `isDeleted = 0`.
     *
     * A row that defaults to clean is never picked up by `AttachmentDao.dirtyAttachments` and so
     * never syncs — nothing throws, nothing logs, the photo simply never leaves the device that
     * made it. A row that defaults to deleted vanishes from its own note's rail. Both failures are
     * silent, which is exactly why this is asserted directly against a real inserted row rather
     * than only against `PRAGMA table_info`'s `dflt_value` column.
     */
    @Test
    fun anAttachmentInsertedAfterMigrationDefaultsToDirtyAndNotDeleted() {
        seedV10()
        val db = openMigrated()
        try {
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO attachments (uuid, noteId, anchor, sortOrder, mimeType, width, " +
                    "height, bytes, thumbWidth, thumbHeight, thumbBytes, createdAt, updatedAt, " +
                    "hlcMs, hlcCounter, hlcNode) " +
                    "VALUES ('att-1', 'note-1', 0, 0, 'image/jpeg', 100, 200, x'00', 10, 20, " +
                    "x'00', 3000, 4000, 5000, 1, 'nodeA')"
            )

            db.openHelper.readableDatabase
                .query("SELECT dirty, isDeleted, deletedAt FROM attachments WHERE uuid = 'att-1'")
                .use { c ->
                    assertEquals("the inserted attachment row went missing", 1, c.count)
                    c.moveToFirst()
                    assertEquals(
                        "a freshly inserted attachment must default to dirty, or it never syncs",
                        1,
                        c.getInt(0),
                    )
                    assertEquals(
                        "a freshly inserted attachment must default to not-deleted",
                        0,
                        c.getInt(1),
                    )
                    assertTrue("deletedAt must default to NULL", c.isNull(2))
                }

            // Read back through the DAO too: this proves the entity mapping (Kotlin defaults,
            // @ColumnInfo defaultValue, and the DDL) all agree, not just the raw column.
            runBlocking {
                val row = db.attachmentDao.attachmentRow("att-1")
                assertTrue("attachmentRow returned nothing for a row that exists", row != null)
                assertTrue("AttachmentEntity.dirty must read back true", row!!.dirty)
                assertTrue("AttachmentEntity.isDeleted must read back false", !row.isDeleted)
            }
        } finally {
            db.close()
        }
    }
}
