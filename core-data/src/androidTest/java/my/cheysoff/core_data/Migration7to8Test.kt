package my.cheysoff.core_data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import my.cheysoff.core_data.data.local.NOTE_DATABASE_VERSION
import my.cheysoff.core_data.data.local.NoteDatabase
import my.cheysoff.core_data.data.sync.RoomSyncStore
import my.cheysoff.core_domain.sync.RecordType
import my.cheysoff.core_sync_engine.ClockObserver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MIGRATION_7_8 — `notes.contentSyncedHlc` and `sync_state.haltReason` — against a v7 database
 * built by hand from the DDL committed at `core-data/schemas/…/7.json`.
 *
 * Modelled on [Migration6to7Test], including running on a PLAIN SQLite file: SQLCipher is an
 * open-helper swap and changes nothing about what an `ALTER TABLE` does.
 *
 * ## What is at risk, and it is the defaults again
 *
 * Two `ADD COLUMN`s either run or they do not, and Room's own validation on open catches a wrong
 * name or type before any assertion here. The risk is the DEFAULT clause, and for both columns the
 * empty string is not a placeholder — it is a distinct, load-bearing reading:
 *
 *  - `contentSyncedHlc = ''` means **no agreement with the server is recorded**, which makes the
 *    merge fall back to its conservative conflict-copy rule. The wrong default here is not `1` or
 *    `0`, it is a *clock*: `0-0-` would parse as `Hlc.ZERO` and claim this device and the server
 *    once agreed on a body at the beginning of time, and the merge would then treat an unpushed
 *    local body as an already-published ancestor and discard it — without a conflict copy, because
 *    it would believe nothing was being lost. That is the one failure mode the conflict-copy design
 *    exists to prevent, arriving through the migration.
 *  - `haltReason = ''` means **healthy**. Any non-empty value stops the engine, so a default that
 *    was not empty would leave every upgraded install unable to sync with no way to find out why.
 */
@RunWith(AndroidJUnit4::class)
class Migration7to8Test {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration_7_8_test.db"

    /** v7's exported `notes` DDL, copied from schemas/…/7.json. */
    private val v7Notes = "CREATE TABLE IF NOT EXISTS `notes` (`id` TEXT NOT NULL, " +
        "`title` TEXT NOT NULL, `content` TEXT NOT NULL, `contentFormat` TEXT NOT NULL, " +
        "`checklist` TEXT NOT NULL, `isPinned` INTEGER NOT NULL, `isFavorite` INTEGER NOT NULL, " +
        "`folderId` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
        "`isDeleted` INTEGER NOT NULL, `deletedAt` INTEGER, " +
        "`hlcMs` INTEGER NOT NULL DEFAULT 0, `hlcCounter` INTEGER NOT NULL DEFAULT 0, " +
        "`hlcNode` TEXT NOT NULL DEFAULT '', `fieldHlc` TEXT NOT NULL DEFAULT '', " +
        "`dirty` INTEGER NOT NULL DEFAULT 1, `lastSyncedSeq` INTEGER NOT NULL DEFAULT 0, " +
        "PRIMARY KEY(`id`))"

    private val v7Folders = "CREATE TABLE IF NOT EXISTS `folders` (`id` TEXT NOT NULL, " +
        "`name` TEXT NOT NULL, `colorArgb` INTEGER, `createdAt` INTEGER NOT NULL, " +
        "`updatedAt` INTEGER NOT NULL, `isDeleted` INTEGER NOT NULL, `deletedAt` INTEGER, " +
        "`hlcMs` INTEGER NOT NULL DEFAULT 0, `hlcCounter` INTEGER NOT NULL DEFAULT 0, " +
        "`hlcNode` TEXT NOT NULL DEFAULT '', `fieldHlc` TEXT NOT NULL DEFAULT '', " +
        "`dirty` INTEGER NOT NULL DEFAULT 1, `lastSyncedSeq` INTEGER NOT NULL DEFAULT 0, " +
        "PRIMARY KEY(`id`))"

    private val v7SyncState = "CREATE TABLE IF NOT EXISTS `sync_state` (`accountId` TEXT NOT NULL, " +
        "`cursor` INTEGER NOT NULL, `lastPullAt` INTEGER NOT NULL, PRIMARY KEY(`accountId`))"

    @Before
    fun setUp() {
        ctx.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        ctx.deleteDatabase(dbName)
    }

    private fun seedV7() {
        val path = ctx.getDatabasePath(dbName)
        path.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(path, null)
        db.execSQL(v7Notes)
        db.execSQL(v7Folders)
        db.execSQL(v7SyncState)
        // A row that has ALREADY synced under v7: clean, with a real clock and a real seq. It is
        // the interesting one, because it is the row whose baseline the migration has to leave
        // unrecorded rather than invent.
        db.execSQL(
            "INSERT INTO notes (id, title, content, contentFormat, checklist, isPinned, " +
                "isFavorite, folderId, createdAt, updatedAt, isDeleted, deletedAt, " +
                "hlcMs, hlcCounter, hlcNode, fieldHlc, dirty, lastSyncedSeq) " +
                "VALUES ('synced', 't', 'body', 'plain', '', 0, 0, NULL, 1000, 2000, 0, NULL, " +
                "5000, 2, 'nodeA', '', 0, 42)"
        )
        db.execSQL(
            "INSERT INTO notes (id, title, content, contentFormat, checklist, isPinned, " +
                "isFavorite, folderId, createdAt, updatedAt, isDeleted, deletedAt, " +
                "hlcMs, hlcCounter, hlcNode, fieldHlc, dirty, lastSyncedSeq) " +
                "VALUES ('unsynced', 't2', 'body2', 'plain', '', 0, 0, NULL, 1000, 2000, 0, NULL, " +
                "0, 0, '', '', 1, 0)"
        )
        db.execSQL("INSERT INTO sync_state (accountId, cursor, lastPullAt) VALUES ('acct', 42, 99)")
        db.execSQL("PRAGMA user_version = 7")
        db.close()
    }

    private fun openMigrated(): NoteDatabase =
        Room.databaseBuilder(ctx, NoteDatabase::class.java, dbName)
            // The whole chain, spread rather than listed: a hand-written list is a second copy of
            // `ALL_MIGRATIONS` that goes stale silently, which is how Migration4to5Test came to be
            // red for the life of schema v6.
            .addMigrations(*NoteDatabase.ALL_MIGRATIONS)
            .build()

    /**
     * Room validates the real table shape against the entities on open, DEFAULT clauses included,
     * so a migration that added either column with the wrong default throws here.
     */
    @Test
    fun migrationRunsAndPreservesEveryRow() {
        seedV7()
        val db = openMigrated()
        try {
            assertEquals(NOTE_DATABASE_VERSION, db.openHelper.readableDatabase.version)
            db.openHelper.readableDatabase.query("SELECT count(*) FROM notes").use { c ->
                c.moveToFirst()
                assertEquals(2, c.getInt(0))
            }
        } finally {
            db.close()
        }
    }

    /**
     * The sync bookkeeping v7 established survives untouched. A migration that reset `dirty` or
     * `lastSyncedSeq` on the way through would either re-upload the whole library or, far worse,
     * declare an unpushed row already published.
     */
    @Test
    fun everyV7SyncColumnSurvivesUnchanged() {
        seedV7()
        val db = openMigrated()
        try {
            db.openHelper.readableDatabase
                .query("SELECT dirty, lastSyncedSeq, hlcMs, hlcCounter, hlcNode FROM notes WHERE id = 'synced'")
                .use { c ->
                    c.moveToFirst()
                    assertEquals(0, c.getInt(0))
                    assertEquals(42L, c.getLong(1))
                    assertEquals(5000L, c.getLong(2))
                    assertEquals(2, c.getInt(3))
                    assertEquals("nodeA", c.getString(4))
                }
            db.openHelper.readableDatabase.query("SELECT cursor FROM sync_state WHERE accountId = 'acct'")
                .use { c ->
                    c.moveToFirst()
                    assertEquals(42L, c.getLong(0))
                }
        } finally {
            db.close()
        }
    }

    /**
     * Every migrated row carries `''`, and the store reads that as **null** — no agreement
     * recorded — rather than as a clock.
     *
     * Asserted through [RoomSyncStore.load] rather than by reading the column, because null is what
     * the merge is handed and null is what makes it conservative. A migration that wrote `0-0-`
     * would put a parseable clock in the column, and the merge would read it as "this device and
     * the server already agreed on a body at the zero clock" — which is true of nothing and would
     * let an unpushed body be replaced with no conflict copy.
     */
    @Test
    fun everyMigratedRowHasNoRecordedContentBaseline() {
        seedV7()
        val db = openMigrated()
        try {
            db.openHelper.readableDatabase.query("SELECT id, contentSyncedHlc FROM notes").use { c ->
                while (c.moveToNext()) {
                    assertEquals("row ${c.getString(0)}", "", c.getString(1))
                }
            }

            val store = RoomSyncStore(db, db.noteDao, db.folderDao, db.sketchDao, db.syncStateDao, "acct", ClockObserver {})
            runBlocking {
                listOf("synced", "unsynced").forEach { id ->
                    assertNull("$id claimed a baseline", store.load(RecordType.NOTE, id)!!.contentBaseline)
                }
            }
        } finally {
            db.close()
        }
    }

    /**
     * An upgraded install is not halted. `haltReason` is a string the engine reads on every pass,
     * and anything non-empty stops it for good — so a default that was not `''` would leave every
     * existing user unable to sync with nothing anywhere saying why.
     */
    @Test
    fun anUpgradedInstallIsNotHalted() {
        seedV7()
        val db = openMigrated()
        try {
            db.openHelper.readableDatabase.query("SELECT haltReason FROM sync_state").use { c ->
                c.moveToFirst()
                assertEquals("", c.getString(0))
            }
            val store = RoomSyncStore(db, db.noteDao, db.folderDao, db.sketchDao, db.syncStateDao, "acct", ClockObserver {})
            runBlocking { assertNull(store.halt()) }
        } finally {
            db.close()
        }
    }

    /**
     * A migrated database still accepts writes through the sync store.
     *
     * Room's validation on open proves the columns are the right shape; this proves the statements
     * that use them actually run against the migrated table. `ALTER TABLE … ADD COLUMN` leaves the
     * new column at the end of the row, and a store built on positional assumptions rather than on
     * names would pass every assertion above and fail here.
     */
    @Test
    fun theSyncStoreCanWriteToAMigratedDatabase() {
        seedV7()
        val db = openMigrated()
        try {
            val store = RoomSyncStore(db, db.noteDao, db.folderDao, db.sketchDao, db.syncStateDao, "acct", ClockObserver {})
            runBlocking {
                store.acknowledgePush(
                    type = RecordType.NOTE,
                    uuid = "unsynced",
                    sealedRowClock = my.cheysoff.core_domain.sync.Hlc(0, 0, ""),
                    seq = 77L,
                    contentBaseline = my.cheysoff.core_domain.sync.Hlc(6000, 0, "nodeA"),
                )
                val stored = store.load(RecordType.NOTE, "unsynced")!!
                assertEquals(77L, stored.lastSyncedSeq)
                assertTrue("the row was unchanged, so the push published it", !stored.dirty)
                assertEquals(
                    my.cheysoff.core_domain.sync.Hlc(6000, 0, "nodeA"),
                    stored.contentBaseline,
                )
            }
        } finally {
            db.close()
        }
    }
}
