package my.cheysoff.core_sync_engine

import kotlinx.coroutines.runBlocking
import my.cheysoff.core_domain.sync.RecordSize
import my.cheysoff.core_domain.sync.RecordType
import my.cheysoff.core_domain.sync.SyncRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The byte-budgeted half of [push]: a batch must never carry more than [SyncEngine.PUSH_BYTE_BUDGET]
 * estimated bytes, a record over [SyncEngine.LARGE_RECORD_BYTES] always travels alone, and a `400`
 * the server gives for exactly one such record must not stop the rest of the pass.
 */
class SyncEnginePushBudgetTest {

    @Test
    fun `a batch is cut by bytes before it is cut by count`() = runBlocking {
        val store = RecordingStore()
        repeat(8) { index ->
            store.put(stored(bigNote("n$index", contentBytes = 1_000_000), dirty = true))
        }
        val transport = ScriptedTransport()

        engine(store, transport).pushOnce()

        assertTrue(transport.pushes.size > 1)
        transport.pushes.forEach { batch ->
            val totalBytes = batch.sumOf { RecordSize.estimateBytes(it.record) }
            assertTrue(totalBytes <= SyncEngine.PUSH_BYTE_BUDGET)
        }
    }

    @Test
    fun `a record larger than the whole budget is still pushed alone`() = runBlocking {
        val store = RecordingStore()
        store.put(stored(bigNote("huge", contentBytes = 5_000_000), dirty = true))
        val transport = ScriptedTransport()

        engine(store, transport).pushOnce()

        assertEquals(1, transport.pushes.size)
        val batch = transport.pushes.single()
        assertEquals(1, batch.size)
        assertEquals("huge", batch.single().uuid)
    }

    @Test
    fun `a rejected single record is skipped and the pass continues`() = runBlocking {
        val store = RecordingStore()
        store.put(stored(note(uuid = "small1"), dirty = true))
        store.put(stored(note(uuid = "small2"), dirty = true))
        store.put(stored(bigNote("large", contentBytes = 300_000), dirty = true))
        val transport = RejectingTransport(rejectedUuid = "large")

        val outcome = engine(store, transport).pushOnce()

        val stats = (outcome as SyncOutcome.Completed).stats
        assertEquals(1, stats.rejected)
        assertEquals(2, stats.pushed)
        assertTrue(store.load(RecordType.NOTE, "large")!!.dirty)
    }

    /**
     * The session-scoped memory exists so a permanently-too-large record is not re-sealed and
     * re-uploaded every single pass while nothing about it has changed -- see
     * [SyncEngine.MAX_REMEMBERED_REJECTIONS]'s KDoc. It must not survive an edit, because an edit
     * is exactly the "something changed, try again" signal the design is built around.
     */
    @Test
    fun `a rejected record is skipped on the next pass and retried after being edited`() = runBlocking {
        val store = RecordingStore()
        store.put(stored(bigNote("large", contentBytes = 300_000), dirty = true))
        val transport = RejectingTransport(rejectedUuid = "large")
        val syncEngine = engine(store, transport)

        val first = (syncEngine.pushOnce() as SyncOutcome.Completed).stats
        assertEquals(1, first.rejected)
        assertEquals(1, transport.pushes.size)

        transport.pushes.clear()
        val second = (syncEngine.pushOnce() as SyncOutcome.Completed).stats
        assertEquals("still counted, even though nothing was sent", 1, second.rejected)
        assertTrue("skipped before it was even sealed -- no network call at all", transport.pushes.isEmpty())

        // Editing the record -- a new row clock -- is a different key, so it is retried.
        store.put(stored(bigNote("large", contentBytes = 300_000).copy(rowClock = hlc(2)), dirty = true))
        transport.pushes.clear()
        syncEngine.pushOnce()
        assertEquals(1, transport.pushes.size)
    }

    private fun engine(store: SyncStore, transport: SyncTransport) = SyncEngine(
        store = store,
        transport = transport,
        clock = RecordingClock(),
        retryPlan = RetryPlan(RetryJitter.NONE),
    )

    /** A note whose `content` field alone is [contentBytes] long. */
    private fun bigNote(uuid: String, contentBytes: Int): SyncRecord =
        note(uuid = uuid, content = "x".repeat(contentBytes))

    /**
     * A transport that refuses, with [TransportFault.REJECTED], any push batch containing
     * [rejectedUuid] and accepts every other batch outright.
     */
    private class RejectingTransport(private val rejectedUuid: String) : SyncTransport {

        val pushes = mutableListOf<List<PushRequest>>()

        override suspend fun changesSince(since: Long, limit: Int): ChangePage =
            ChangePage(emptyList(), hasMore = false)

        override suspend fun push(items: List<PushRequest>): PushResponse {
            pushes += items
            if (items.any { it.uuid == rejectedUuid }) {
                throw SyncTransportException(TransportFault.REJECTED, "invalid_envelope")
            }
            return PushResponse(items.map { PushAck.Accepted(it.type, it.uuid, seq = 1L) })
        }
    }
}
