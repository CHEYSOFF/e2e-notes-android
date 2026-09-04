package my.cheysoff.core_data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import my.cheysoff.core_data.data.local.NoteDatabase
import my.cheysoff.core_data.data.sync.RoomSyncStore
import my.cheysoff.core_sync_engine.ChangePage
import my.cheysoff.core_sync_engine.ClockObserver
import my.cheysoff.core_sync_engine.PushRequest
import my.cheysoff.core_sync_engine.PushResponse
import my.cheysoff.core_sync_engine.SyncEngine
import my.cheysoff.core_sync_engine.SyncOutcome
import my.cheysoff.core_sync_engine.SyncTransport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The final review's Finding 1, against the real store and the real engine rather than a fake's
 * field.
 *
 * `SyncEngineRebaselineTest` in `:core-sync-engine` seeds `RecordingStore.storedDataVersion`
 * directly, a state no real [my.cheysoff.core_sync_engine.SyncStore] can produce: `RoomSyncStore`
 * can only ever report what `advanceCursor`'s column default put there, or what `saveDataVersion`
 * actually wrote through its real, UPDATE-only DAO method. That gap is exactly why the dead path
 * this file exists to catch survived several review rounds — every existing test could pass while
 * `SyncEngine` never once called `saveDataVersion` on any device that had not already been through
 * `MIGRATION_8_9`.
 */
@RunWith(RobolectricTestRunner::class)
class SyncEngineRoomRebaselineTest {

    private lateinit var database: NoteDatabase
    private lateinit var store: RoomSyncStore
    private val account = "acct-1"

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, NoteDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomSyncStore(
            database = database,
            noteDao = database.noteDao,
            folderDao = database.folderDao,
            sketchDao = database.sketchDao,
            syncStateDao = database.syncStateDao,
            accountId = account,
        )
    }

    @After
    fun tearDown() = database.close()

    private fun engine(transport: RecordingTransport) = SyncEngine(
        store = store,
        transport = transport,
        clock = ClockObserver {},
    )

    /** Records every `since` a pull was asked for; answers with nothing to apply. */
    private class RecordingTransport : SyncTransport {
        val sinceCalls = mutableListOf<Long>()

        override suspend fun changesSince(since: Long, limit: Int): ChangePage {
            sinceCalls += since
            return ChangePage(emptyList(), hasMore = false)
        }

        override suspend fun push(items: List<PushRequest>) = PushResponse(emptyList())
    }

    /**
     * The fresh-install shape the finding names: a device that has pulled -- so its `sync_state`
     * row exists, made by `saveCursor` exactly as production does it -- but never had a generation
     * recorded, which is the column's own `NOT NULL DEFAULT 0`. Before the fix, nothing wrote it
     * here: the write was gated on `rebaselining`, and `rebaselining` was computed from a
     * `dataVersion()` that masked this exact `0` as "already current".
     *
     * Asserted through the DAO directly, not through `store.dataVersion()`: that getter is the
     * thing under test in `RoomSyncStoreTest`, and reading through it here would let a masking bug
     * on the read side hide a real absence of the write.
     */
    @Test
    fun `a completed pull records the current generation when the store had none`() = runTest {
        store.saveCursor(12L)
        assertEquals(
            "precondition: a pulled-but-unrecorded row sits at the column default",
            0,
            database.syncStateDao.dataVersion(account),
        )

        val transport = RecordingTransport()
        val outcome = engine(transport).runPass()

        assertTrue("the pass must complete for the write to be trusted", outcome is SyncOutcome.Completed)
        assertEquals(
            "the real store must now hold the current generation",
            SyncEngine.DATA_VERSION,
            database.syncStateDao.dataVersion(account),
        )
    }

    /**
     * The other half: a device whose stored generation is genuinely behind -- seeded through the
     * real, UPDATE-only `saveDataVersion` API production calls, not a fake's field -- resets its
     * cursor to 0 on the next pass and records the current generation once that pass completes.
     */
    @Test
    fun `a device behind the current generation re-baselines through the real store`() = runTest {
        store.saveCursor(50L)
        database.syncStateDao.saveDataVersion(account, 0)

        val transport = RecordingTransport()
        val outcome = engine(transport).runPass()

        assertTrue(outcome is SyncOutcome.Completed)
        assertEquals(
            "the pull must start from the beginning to recover what was skipped",
            0L,
            transport.sinceCalls.single(),
        )
        assertEquals(
            "the generation is now recorded by the real store",
            SyncEngine.DATA_VERSION,
            store.dataVersion(),
        )
    }
}
