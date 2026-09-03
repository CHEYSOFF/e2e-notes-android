package my.cheysoff.core_sync_engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recovering records a previous build skipped.
 *
 * The forward-compatibility change lets a device page past record types it does not implement,
 * which leaves its cursor beyond records it never stored. Without this, upgrading that device would
 * never show it those records: they are behind its cursor forever.
 *
 * Re-pulling from 0 is safe against the rollback guard, and the reason is worth keeping in front of
 * whoever changes this: `changesSince` serves **head** versions only. So every record either
 * arrives at the clock this device already holds — equal, not lower, so
 * `remote.rowClock < local.rowClock` is false — or newer. Nothing that a clean row would reject.
 * This is NOT the cursor reset that `RejectReason.ROLLBACK_SUSPECTED` forbids: that one is a
 * response to a server that has stopped being trustworthy, where an emptied account would read as
 * "delete everything". Here the client asks for the replay and the server is known-good.
 */
class SyncEngineRebaselineTest {

    @Test
    fun `a device behind the current generation re-pulls from zero`() = runBlocking {
        val store = RecordingStore()
        store.saveCursor(50L)
        store.storedDataVersion = SyncEngine.DATA_VERSION - 1
        val transport = ScriptedTransport()

        engine(store, transport).runPass()

        assertEquals("the pull must start from the beginning", 0L, transport.pulls.single().since)
        assertEquals("and the generation is now recorded", SyncEngine.DATA_VERSION, store.storedDataVersion)
    }

    @Test
    fun `a device already at the current generation does not re-pull`() = runBlocking {
        val store = RecordingStore()
        store.saveCursor(50L)
        store.storedDataVersion = SyncEngine.DATA_VERSION
        val transport = ScriptedTransport()

        engine(store, transport).runPass()

        assertEquals("an ordinary pass resumes at the cursor", 50L, transport.pulls.single().since)
    }

    /**
     * The version is written only by a pass that finished. A re-baseline interrupted halfway
     * through must run again, or the device records that it has caught up while still missing the
     * records the interruption cut off.
     */
    @Test
    fun `an interrupted re-baseline does not record the generation`() = runBlocking {
        val store = RecordingStore()
        store.saveCursor(50L)
        store.storedDataVersion = SyncEngine.DATA_VERSION - 1
        val transport = ScriptedTransport(
            failOnPull = SyncTransportException(TransportFault.NETWORK, "the connection dropped"),
        )

        val outcome = engine(store, transport).runPass()

        assertTrue(
            "precondition: a network failure defers rather than halting or completing",
            outcome is SyncOutcome.Deferred,
        )
        assertEquals(
            "precondition: it is the network failure the transport was scripted to throw",
            TransportFault.NETWORK,
            (outcome as SyncOutcome.Deferred).fault,
        )
        assertEquals(
            "so the next launch must re-baseline again",
            SyncEngine.DATA_VERSION - 1,
            store.storedDataVersion,
        )
    }

    @Test
    fun `a re-baseline only happens once`() = runBlocking {
        val store = RecordingStore()
        store.storedDataVersion = SyncEngine.DATA_VERSION - 1
        store.saveCursor(50L)
        val transport = ScriptedTransport()

        engine(store, transport).runPass()
        engine(store, transport).runPass()

        assertEquals(0L, transport.pulls[0].since)
        assertEquals(
            "the second pass resumes where the device actually was, not merely somewhere nonzero",
            50L,
            transport.pulls[1].since,
        )
    }

    private fun engine(
        store: SyncStore,
        transport: SyncTransport,
        clock: ClockObserver = RecordingClock(),
        retryPlan: RetryPlan = RetryPlan(RetryJitter.NONE),
        batchLimit: Int = SyncEngine.DEFAULT_BATCH_LIMIT,
        pageLimit: Int = SyncEngine.DEFAULT_PAGE_LIMIT,
        maxRounds: Int = SyncEngine.DEFAULT_MAX_ROUNDS,
    ) = SyncEngine(
        store = store,
        transport = transport,
        clock = clock,
        retryPlan = retryPlan,
        batchLimit = batchLimit,
        pageLimit = pageLimit,
        maxRounds = maxRounds,
    )
}
