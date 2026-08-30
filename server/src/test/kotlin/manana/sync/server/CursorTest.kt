package manana.sync.server

import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The cursor is the server's per-account monotonic `seq`, and it is never a timestamp.
 *
 * These are the tests for the one property a client cannot recover from if it is wrong: a record
 * that a pull skips is a note that silently disappears from a device, with no error anywhere and no
 * way to notice until the user goes looking for it.
 */
class CursorTest {

    private val envelope = "sealed".toByteArray()

    /**
     * Sixty concurrent single-item pushes. Every one is accepted, every `seq` is distinct, and the
     * set of allocated seqs is exactly `1..60` -- no gaps, no repeats.
     *
     * A gap would not be harmless: a client that pulled, saw seq 7 and 9, and set its cursor to 9
     * has no way to tell whether 8 was never allocated or was allocated and not yet visible.
     */
    @Test
    fun concurrentPushesGetDistinctContiguousSeqs() = serverTest { harness ->
        val me = enrol(harness)
        val writers = 60
        val seqs = Collections.synchronizedList(ArrayList<Long>())

        coroutineScope {
            repeat(writers) { index ->
                launch {
                    val response = push(me.token, upsertItem(blindedId(index), envelope, baseSeq = 0))
                    assertEquals(200, response.status.value, response.bodyAsText())
                    val body: UpsertResponse = response.decode()
                    seqs.add(body.results.single().seq!!)
                }
            }
        }

        assertEquals(writers, seqs.size)
        assertEquals(writers, seqs.toSet().size)
        assertEquals((1L..writers.toLong()).toList(), seqs.sorted())
        assertEquals(writers.toLong(), harness.store.lastSeq(me.accountId))
    }

    /**
     * A client pulling incrementally while other devices are writing must not step over a record.
     *
     * Eight writers push ten records each while a ninth coroutine pulls in pages of three from
     * whatever cursor it currently holds, exactly as a real client's loop would. When the writers
     * finish, the puller drains. It must have seen all eighty.
     *
     * This is the test that would fail if `seq` were allocated outside the transaction that inserts
     * the row, because the puller would then be able to observe a later seq before an earlier one
     * and advance past it forever.
     */
    @Test
    fun aPullerInterleavedWithConcurrentWritersMissesNothing() = serverTest { harness ->
        val me = enrol(harness)
        val writers = 8
        val perWriter = 10
        val total = writers * perWriter
        val seen = Collections.synchronizedSet(LinkedHashSet<String>())
        val writingDone = AtomicBoolean(false)

        coroutineScope {
            val writerJobs = (0 until writers).map { writer ->
                launch {
                    repeat(perWriter) { index ->
                        val response = push(
                            me.token,
                            upsertItem(blindedId(writer * 1000 + index), envelope, baseSeq = 0),
                        )
                        assertEquals(200, response.status.value, response.bodyAsText())
                    }
                }
            }
            val puller = launch {
                var cursor = 0L
                var quietPasses = 0
                var guard = 0
                // Two consecutive empty pages after the writers finished means the drain is
                // complete. The guard is only there so a bug fails the test instead of hanging it.
                while (quietPasses < 2 && guard++ < 5_000) {
                    val page: ChangesResponse =
                        client.getAuth("/v1/changes?since=$cursor&limit=3", me.token).decode()
                    page.records.forEach { seen.add(it.blindedId) }
                    cursor = page.nextCursor
                    quietPasses =
                        if (page.records.isEmpty() && writingDone.get()) quietPasses + 1 else 0
                    yield()
                }
            }
            writerJobs.joinAll()
            writingDone.set(true)
            puller.join()
        }

        assertEquals(total, seen.size)
    }

    /**
     * The cursor must not be a timestamp.
     *
     * The clock is frozen for the whole test, so every record is stored with an identical
     * `receivedAt`. A timestamp cursor would make all five records indistinguishable in ordering:
     * a client that received the first and set `since` to its timestamp would then either miss the
     * other four (with a strict `>`) or re-fetch everything forever (with `>=`). `seq` keeps them
     * strictly ordered anyway.
     */
    @Test
    fun theCursorIsNotATimestampSoSimultaneousWritesStillOrder() = serverTest { harness ->
        val me = enrol(harness)
        val frozenAt = harness.clock.now
        repeat(5) { push(me.token, upsertItem(blindedId(it), envelope, baseSeq = 0)) }

        val pull: ChangesResponse = client.getAuth("/v1/changes?since=0", me.token).decode()
        assertEquals(5, pull.records.size)
        assertTrue(pull.records.all { it.receivedAt == frozenAt }, "the clock did not move")
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), pull.records.map { it.seq })

        // And an incremental pull from the middle returns exactly the tail, which is the thing a
        // timestamp cursor could not do here.
        val tail: ChangesResponse = client.getAuth("/v1/changes?since=2", me.token).decode()
        assertEquals(listOf(3L, 4L, 5L), tail.records.map { it.seq })
    }

    /**
     * A cursor ahead of the server is refused rather than answered with an empty page.
     *
     * That happens when the server has been restored from an older backup, or when the client is
     * pointed at a different server. Both need the client to stop and re-baseline; "no changes" is
     * the answer that would let a rolled-back server look healthy indefinitely.
     */
    @Test
    fun aCursorAheadOfTheServerIsRejected() = serverTest { harness ->
        val me = enrol(harness)
        push(me.token, upsertItem(blindedId(1), envelope, 0))

        val response = client.getAuth("/v1/changes?since=99", me.token)
        assertEquals(409, response.status.value)
        assertEquals("cursor_ahead_of_server", response.errorCode())
    }

    @Test
    fun anUpdateMovesARecordToTheEndOfTheCursorOrder() = serverTest { harness ->
        val me = enrol(harness)
        val first = blindedId(1)
        val second = blindedId(2)
        push(me.token, upsertItem(first, envelope, 0))
        push(me.token, upsertItem(second, envelope, 0))
        push(me.token, upsertItem(first, "updated".toByteArray(), baseSeq = 1))

        val pull: ChangesResponse = client.getAuth("/v1/changes?since=0", me.token).decode()
        assertEquals(listOf(second, first), pull.records.map { it.blindedId })
        assertEquals(listOf(2L, 3L), pull.records.map { it.seq })

        // A client already at cursor 2 sees only the updated record.
        val incremental: ChangesResponse = client.getAuth("/v1/changes?since=2", me.token).decode()
        assertEquals(listOf(first), incremental.records.map { it.blindedId })
    }
}
