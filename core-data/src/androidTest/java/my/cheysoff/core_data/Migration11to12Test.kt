package my.cheysoff.core_data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.cheysoff.core_data.data.local.NOTE_DATABASE_VERSION
import my.cheysoff.core_data.data.local.NoteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MIGRATION_11_12 — the brand-new `events` table — against a v11 database built by hand from the
 * DDL committed at `core-data/schemas/…/11.json`.
 *
 * Modelled on [Migration10to11Test], including running on a PLAIN (unencrypted) SQLite file:
 * SQLCipher is an open-helper swap and changes nothing about what a `CREATE TABLE` does.
 *
 * ## What is at risk
 *
 * The `CREATE TABLE`, its two indices, and the DEFAULT clauses. `events` has no rows when this
 * migration runs, so there is nothing to backfill, but the defaults decide what the first row
 * inserted afterwards looks like:
 *
 * ⚠️ **`dirty` must default to 1 and `isDeleted` to 0.** A row defaulting to clean is never picked
 * up by `EventDao.dirtyEvents` and so never syncs — silently, because nothing fails loudly when a
 * row is simply never looked at again. A row defaulting to deleted vanishes from the calendar that
 * just created it. Both are pinned in three places that must agree: this DDL, `EventEntity`'s
 * Kotlin default, and its `@ColumnInfo(defaultValue = …)`.
 *
 * The fixture seeds every v11 table **with its indices**. Room validates a migrated database
 * against the whole entity and `TableInfo` covers indices, so a table seeded without them fails as
 * "Migration didn't properly handle: <table>" — pointing at the migration when the fault is in the
 * fixture. That exact trap cost a debugging session on `sketches` during the previous migration.
 */
@RunWith(AndroidJUnit4::class)
class Migration11to12Test {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration_11_12_test.db"

    private val v11Notes = "CREATE TABLE IF NOT EXISTS `notes` (`id` TEXT NOT NULL, " +
        "`title` TEXT NOT NULL, `content` TEXT NOT NULL, `contentFormat` TEXT NOT NULL, " +
        "`checklist` TEXT NOT NULL, `isPinned` INTEGER NOT NULL, `isFavorite` INTEGER NOT NULL, " +
        "`folderId` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
        "`isDeleted` INTEGER NOT NULL, `deletedAt` INTEGER, " +
        "`hlcMs` INTEGER NOT NULL DEFAULT 0, `hlcCounter` INTEGER NOT NULL DEFAULT 0, " +
        "`hlcNode` TEXT NOT NULL DEFAULT '', `fieldHlc` TEXT NOT NULL DEFAULT '', " +
        "`dirty` INTEGER NOT NULL DEFAULT 1, `lastSyncedSeq` INTEGER NOT NULL DEFAULT 0, " +
        "`contentSyncedHlc` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`id`))"

    private val v11Folders = "CREATE TABLE IF NOT EXISTS `folders` (`id` TEXT NOT NULL, " +
        "`name` TEXT NOT NULL, `colorArgb` INTEGER, `createdAt` INTEGER NOT NULL, " +
        "`updatedAt` INTEGER NOT NULL, `isDeleted` INTEGER NOT NULL, `deletedAt` INTEGER, " +
        "`hlcMs` INTEGER NOT NULL DEFAULT 0, `hlcCounter` INTEGER NOT NULL DEFAULT 0, " +
        "`hlcNode` TEXT NOT NULL DEFAULT '', `fieldHlc` TEXT NOT NULL DEFAULT '', " +
        "`dirty` INTEGER NOT NULL DEFAULT 1, `lastSyncedSeq` INTEGER NOT NULL DEFAULT 0, " +
        "PRIMARY KEY(`id`))"

    private val v11SyncState = "CREATE TABLE IF NOT EXISTS `sync_state` (`accountId` TEXT NOT NULL, " +
        "`cursor` INTEGER NOT NULL, `lastPullAt` INTEGER NOT NULL, " +
        "`haltReason` TEXT NOT NULL DEFAULT '', `dataVersion` INTEGER NOT NULL DEFAULT 0, " +
        "PRIMARY KEY(`accountId`))"

    private val v11Sketches = "CREATE TABLE IF NOT EXISTS `sketches` (" +
        "`uuid` TEXT NOT NULL, `noteId` TEXT NOT NULL, `anchor` INTEGER NOT NULL, " +
        "`sortOrder` INTEGER NOT NULL, `strokes` TEXT NOT NULL, " +
        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
        "`isDeleted` INTEGER NOT NULL DEFAULT 0, `deletedAt` INTEGER, " +
        "`hlcMs` INTEGER NOT NULL, `hlcCounter` INTEGER NOT NULL, " +
        "`hlcNode` TEXT NOT NULL, `fieldHlc` TEXT NOT NULL DEFAULT '', " +
        "`dirty` INTEGER NOT NULL DEFAULT 1, `lastSyncedSeq` INTEGER NOT NULL DEFAULT 0, " +
        "PRIMARY KEY(`uuid`))"

    private val v11SketchesIndex =
        "CREATE INDEX IF NOT EXISTS `index_sketches_noteId` ON `sketches` (`noteId`)"

    private val v11Attachments = "CREATE TABLE IF NOT EXISTS `attachments` (" +
        "`uuid` TEXT NOT NULL, `noteId` TEXT NOT NULL, `anchor` INTEGER NOT NULL, " +
        "`sortOrder` INTEGER NOT NULL, `mimeType` TEXT NOT NULL, " +
        "`width` INTEGER NOT NULL, `height` INTEGER NOT NULL, `bytes` BLOB NOT NULL, " +
        "`thumbWidth` INTEGER NOT NULL, `thumbHeight` INTEGER NOT NULL, " +
        "`thumbBytes` BLOB NOT NULL, `createdAt` INTEGER NOT NULL, " +
        "`updatedAt` INTEGER NOT NULL, `isDeleted` INTEGER NOT NULL DEFAULT 0, " +
        "`deletedAt` INTEGER, `hlcMs` INTEGER NOT NULL, `hlcCounter` INTEGER NOT NULL, " +
        "`hlcNode` TEXT NOT NULL, `fieldHlc` TEXT NOT NULL DEFAULT '', " +
        "`dirty` INTEGER NOT NULL DEFAULT 1, `lastSyncedSeq` INTEGER NOT NULL DEFAULT 0, " +
        "`meta` TEXT NOT NULL DEFAULT '', " +
        "PRIMARY KEY(`uuid`))"

    private val v11AttachmentsIndex =
        "CREATE INDEX IF NOT EXISTS `index_attachments_noteId` ON `attachments` (`noteId`)"

    @Before
    fun setUp() {
        ctx.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        ctx.deleteDatabase(dbName)
    }

    private fun seedV11() {
        val path = ctx.getDatabasePath(dbName)
        path.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(path, null)
        db.execSQL(v11Notes)
        db.execSQL(v11Folders)
        db.execSQL(v11SyncState)
        db.execSQL(v11Sketches)
        db.execSQL(v11SketchesIndex)
        db.execSQL(v11Attachments)
        db.execSQL(v11AttachmentsIndex)
        db.execSQL(
            "INSERT INTO notes (id, title, content, contentFormat, checklist, isPinned, " +
                "isFavorite, folderId, createdAt, updatedAt, isDeleted, deletedAt, " +
                "hlcMs, hlcCounter, hlcNode, fieldHlc, dirty, lastSyncedSeq, contentSyncedHlc) " +
                "VALUES ('note-1', 'title', 'body', 'plain', '', 0, 0, NULL, 1000, 2000, 0, NULL, " +
                "0, 0, '', '', 1, 0, '')"
        )
        db.execSQL("PRAGMA user_version = 11")
        db.close()
    }

    private fun openMigrated(): NoteDatabase =
        Room.databaseBuilder(ctx, NoteDatabase::class.java, dbName)
            // The whole chain, spread rather than listed: a hand-built list is a second copy of
            // `ALL_MIGRATIONS` that goes stale silently, and one already missed a step once.
            .addMigrations(*NoteDatabase.ALL_MIGRATIONS)
            .build()

    /**
     * The migration runs, Room accepts the result as v12, the pre-existing note survives untouched,
     * and the new `events` table exists and is empty.
     */
    @Test
    fun migrationRunsPreservesTheNoteAndCreatesAnEmptyEventsTable() {
        seedV11()
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

            db.openHelper.readableDatabase.query("SELECT count(*) FROM events").use { c ->
                c.moveToFirst()
                assertEquals("a fresh events table must start empty", 0, c.getInt(0))
            }
        } finally {
            db.close()
        }
    }

    /**
     * ⚠️ The one that matters: a row inserted after the migration, naming neither `dirty` nor
     * `isDeleted`, must come back `dirty = 1` and `isDeleted = 0`.
     *
     * A row defaulting to clean is never picked up by `EventDao.dirtyEvents` and never leaves the
     * device that made it. A row defaulting to deleted is invisible on the calendar that created
     * it. Both failures are silent, which is why this asserts against a really inserted row rather
     * than against `PRAGMA table_info`'s `dflt_value`.
     */
    @Test
    fun anEventInsertedAfterMigrationDefaultsToDirtyAndNotDeleted() {
        seedV11()
        val db = openMigrated()
        try {
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO events (uuid, title, startAt, endAt, startLocal, endLocal, timeZone, " +
                    "isAllDay, location, description, descriptionFormat, reminders, recurrence, " +
                    "linkedNoteId, shareToGoogle, colorArgb, createdAt, updatedAt, " +
                    "hlcMs, hlcCounter, hlcNode) " +
                    "VALUES ('ev-1', 'Dentist', 1000, 2000, '2026-09-08T09:00:00', " +
                    "'2026-09-08T10:00:00', 'Europe/Moscow', 0, '', '', 'plain', '', '', " +
                    "NULL, 0, NULL, 1000, 2000, 0, 0, '')"
            )

            db.openHelper.readableDatabase
                .query("SELECT dirty, isDeleted, meta, fieldHlc, lastSyncedSeq FROM events WHERE uuid = 'ev-1'")
                .use { c ->
                    c.moveToFirst()
                    assertEquals("an event that defaults to clean would never sync", 1, c.getInt(0))
                    assertEquals("an event that defaults to deleted is invisible", 0, c.getInt(1))
                    assertEquals("", c.getString(2))
                    assertEquals("", c.getString(3))
                    assertEquals(0L, c.getLong(4))
                }
        } finally {
            db.close()
        }
    }

    /**
     * Both indices exist after the migration.
     *
     * Room's own validation would catch a missing one, but only as "Migration didn't properly
     * handle: events", which reads as the table being wrong rather than as an index being absent.
     * Asserting them by name is what makes that distinction at the moment it breaks.
     */
    @Test
    fun bothEventIndicesExist() {
        seedV11()
        val db = openMigrated()
        try {
            val names = mutableSetOf<String>()
            db.openHelper.readableDatabase
                .query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'events'")
                .use { c ->
                    while (c.moveToNext()) names += c.getString(0)
                }

            assertEquals(
                "both indices must survive the migration",
                setOf("index_events_startAt", "index_events_linkedNoteId"),
                names.filter { it.startsWith("index_events") }.toSet(),
            )
        } finally {
            db.close()
        }
    }
}
