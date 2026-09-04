package my.cheysoff.core_data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import my.cheysoff.core_data.data.local.NoteDatabase
import my.cheysoff.core_data.data.local.NoteEntity
import my.cheysoff.core_data.data.sync.RoomSyncStore
import my.cheysoff.core_domain.sync.Hlc
import my.cheysoff.core_domain.sync.RecordType
import my.cheysoff.core_sync_engine.HaltReason
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The sync store's rules, on the database the app actually ships: **SQLCipher, on disk**.
 *
 * ## Why this exists next to `RoomSyncStoreTest`
 *
 * That file already runs every one of these rules against real SQLite under Robolectric, and the
 * standing argument in this module — [NoteDaoTest] and [Migration4to5Test] both make it — is that
 * SQLCipher is an open-helper swap that changes nothing about what a statement does. That argument
 * is correct and it is also exactly the kind of thing that is worth checking once, on the one class
 * whose whole job is a conditional `UPDATE` and a transaction, because the cost of it being wrong
 * is a `dirty` flag cleared over an edit the user made and no error anywhere.
 *
 * So this is deliberately a small subset: §3.2's two rules and their atomicity, the transaction in
 * `applyMerged`, and the halt surviving a **close and reopen** of a real file — which is the one
 * thing no in-memory test can check at all, and which is the property the engine's whole
 * crash-safety argument rests on.
 *
 * Run:
 *
 *     ./gradlew :core-data:assembleDebugAndroidTest
 *     adb install -r -t core-data/build/outputs/apk/androidTest/debug/core-data-debug-androidTest.apk
 *     adb shell am instrument -w \
 *       -e class my.cheysoff.core_data.SyncStoreSqlCipherTest \
 *       my.cheysoff.core_data.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class SyncStoreSqlCipherTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "sync_store_cipher_test.db"

    /** A fixed key. Nothing here is testing key management; the point is that the file is real. */
    private val passphrase = "correct horse battery staple".toByteArray()

    private lateinit var db: NoteDatabase
    private lateinit var store: RoomSyncStore

    @Before
    fun setUp() {
        ctx.deleteDatabase(dbName)
        db = open()
        store = storeOver(db)
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
        ctx.deleteDatabase(dbName)
    }

    private fun open(): NoteDatabase =
        Room.databaseBuilder(ctx, NoteDatabase::class.java, dbName)
            // A fresh copy of the key each time: SQLCipher's helper retains the array it is given.
            .openHelperFactory(SupportOpenHelperFactory(passphrase.copyOf()))
            .addMigrations(*NoteDatabase.ALL_MIGRATIONS)
            .build()

    private fun storeOver(database: NoteDatabase) = RoomSyncStore(
        database, database.noteDao, database.folderDao, database.sketchDao, database.syncStateDao,
        accountId = "acct",
    )

    private fun note(rowClock: Hlc, content: String = "milk", dirty: Boolean = true) = NoteEntity(
        id = "n1",
        title = "Groceries",
        content = content,
        contentFormat = "plain",
        checklist = "",
        isPinned = false,
        isFavorite = false,
        folderId = null,
        createdAt = 50,
        updatedAt = 100,
        hlcMs = rowClock.ms,
        hlcCounter = rowClock.counter,
        hlcNode = rowClock.node,
        dirty = dirty,
    )

    @Test
    fun anUnchangedRowIsPublishedByItsAcknowledgement() = runBlocking {
        db.noteDao.applyRemoteNote(note(Hlc(1_000, 0, "nodeA")))

        store.acknowledgePush(RecordType.NOTE, "n1", Hlc(1_000, 0, "nodeA"), seq = 7L, contentBaseline = null)

        val row = db.noteDao.noteRow("n1")!!
        assertFalse(row.dirty)
        assertEquals(7L, row.lastSyncedSeq)
    }

    /**
     * §3.2 rule 1 and rule 2 together, in the case they disagree: the user typed while the push was
     * in flight, so `dirty` must stay set and `lastSyncedSeq` must still move. Getting the second
     * half wrong costs a guaranteed `409` on every subsequent pass; getting the first half wrong
     * costs the edit.
     */
    @Test
    fun anEditDuringThePushKeepsTheRowDirtyAndStillRecordsTheSeq() = runBlocking {
        db.noteDao.applyRemoteNote(note(Hlc(1_000, 0, "nodeA")))
        db.noteDao.applyRemoteNote(note(Hlc(2_000, 0, "nodeA"), content = "milk, eggs"))

        store.acknowledgePush(RecordType.NOTE, "n1", Hlc(1_000, 0, "nodeA"), seq = 7L, contentBaseline = null)

        val row = db.noteDao.noteRow("n1")!!
        assertTrue("the newer edit must still be pushed", row.dirty)
        assertEquals(7L, row.lastSyncedSeq)
        assertEquals("milk, eggs", row.content)
    }

    /**
     * One statement, on the real engine.
     *
     * An `AFTER UPDATE` trigger fires once per row per statement, so the count is exactly the
     * number of update statements that matched. A two-statement implementation satisfies every
     * other assertion in this file and counts 2 here.
     */
    @Test
    fun theAcknowledgementIsOneStatement() = runBlocking {
        db.noteDao.applyRemoteNote(note(Hlc(1_000, 0, "nodeA")))
        val raw = db.openHelper.writableDatabase
        raw.execSQL("CREATE TABLE IF NOT EXISTS update_audit (n INTEGER)")
        raw.execSQL("DELETE FROM update_audit")
        raw.execSQL(
            "CREATE TRIGGER update_audit_trg AFTER UPDATE ON notes " +
                "BEGIN INSERT INTO update_audit VALUES (1); END"
        )
        try {
            store.acknowledgePush(RecordType.NOTE, "n1", Hlc(1_000, 0, "nodeA"), seq = 7L, contentBaseline = null)
        } finally {
            raw.execSQL("DROP TRIGGER update_audit_trg")
        }
        raw.query("SELECT COUNT(*) FROM update_audit").use {
            it.moveToFirst()
            assertEquals("two statements would have counted 2", 1, it.getInt(0))
        }
    }

    /**
     * A halt survives the process, which is what makes it a halt rather than a mood.
     *
     * The whole file is closed and reopened, so this is the only test anywhere that checks the
     * property the engine's design depends on: *"an engine that forgot its halt on process death
     * would resume syncing against precisely the server it refused to trust"*. An in-memory
     * database cannot express it.
     */
    @Test
    fun aHaltSurvivesClosingAndReopeningTheFile() = runBlocking {
        store.recordHalt(HaltReason.SERVER_ROLLED_BACK)
        store.saveCursor(12L)
        db.close()

        db = open()
        store = storeOver(db)
        assertEquals(HaltReason.SERVER_ROLLED_BACK, store.halt())
        assertEquals(12L, store.cursor())
    }

    /** The cursor is forwards-only in SQL, so a reopened file cannot be talked backwards either. */
    @Test
    fun theCursorNeverMovesBackwardsAcrossAReopen() = runBlocking {
        store.saveCursor(12L)
        db.close()

        db = open()
        store = storeOver(db)
        store.saveCursor(5L)
        assertEquals(12L, store.cursor())
    }

    /**
     * The merged row and its conflict copy are one transaction. A winner written without the copy
     * that holds the body it displaced is the one outcome the whole conflict-copy design exists to
     * prevent.
     */
    @Test
    fun aMergedRowAndItsConflictCopyAreWrittenTogether() = runBlocking {
        db.noteDao.applyRemoteNote(note(Hlc(1_000, 0, "nodeA")))

        store.applyMerged(
            my.cheysoff.core_sync_engine.MergedWrite(
                record = RecordsForTest.note("n1", "winner", Hlc(3_000, 0, "nodeB")),
                dirty = false,
                seq = 4L,
                contentBaseline = Hlc(3_000, 0, "nodeB"),
                conflictCopy = RecordsForTest.note("copy-1", "loser", Hlc(2_000, 0, "nodeA")),
            )
        )

        assertEquals("winner", db.noteDao.noteRow("n1")!!.content)
        val copy = db.noteDao.noteRow("copy-1")!!
        assertEquals("loser", copy.content)
        assertTrue(copy.dirty)
        assertEquals(0L, copy.lastSyncedSeq)
    }

    @Test
    fun anUnsetBaselineReadsAsAbsentOnDisk() = runBlocking {
        db.noteDao.applyRemoteNote(note(Hlc(1_000, 0, "nodeA")))
        assertNull(store.load(RecordType.NOTE, "n1")!!.contentBaseline)
    }

    companion object {
        /**
         * SQLCipher's native library, loaded once for the whole class.
         *
         * `MainApplication` does this at startup in the app; an instrumented test has no
         * application of ours, so without it every `open()` fails on `UnsatisfiedLinkError` rather
         * than on anything to do with the store.
         */
        @JvmStatic
        @BeforeClass
        fun loadSqlCipher() {
            System.loadLibrary("sqlcipher")
        }
    }
}
