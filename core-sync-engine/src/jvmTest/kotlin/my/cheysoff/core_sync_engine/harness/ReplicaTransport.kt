package my.cheysoff.core_sync_engine.harness

import my.cheysoff.core_sync_engine.ChangePage
import my.cheysoff.core_sync_engine.IncomingRecord
import my.cheysoff.core_sync_engine.PushAck
import my.cheysoff.core_sync_engine.PushRequest
import my.cheysoff.core_sync_engine.PushResponse
import my.cheysoff.core_sync_engine.SyncTransport
import my.cheysoff.core_sync_engine.SyncTransportException
import my.cheysoff.core_sync_engine.TransportFault

/**
 * One replica's view of the [FakeServer], as a [SyncTransport].
 *
 * ## No crypto, deliberately
 *
 * Records cross this seam as plaintext `SyncRecord`s and every one of them arrives as
 * [IncomingRecord.Opened]. That is the limit `e2e-sync-open-questions.md` §3 asks for in as many
 * words: *"the simulation should run over plaintext records so that a convergence failure is never
 * confused with a decryption failure."* The engine's handling of a record that will **not** open is
 * therefore not exercised here at all; `SyncEngineTest` covers it with hand-built pages, which is
 * the right shape for a rule about counting and halting.
 *
 * ## The one fault it does inject
 *
 * [loseNextAcknowledgement] is process death between the server's commit and the client reading the
 * response, which `e2e-sync-phase3-plan.md` §3.3 lists as an ordinary event rather than a disaster.
 * It is modelled where it actually happens — in the transport — so the engine meets it as the
 * network failure it is, rather than as a special harness mode.
 */
class ReplicaTransport(private val server: FakeServer) : SyncTransport {

    /**
     * When set, the next push is applied by the server and then reported as a network failure.
     *
     * The row stays dirty against a stale `baseSeq`, so the following push takes a `409` carrying
     * this device's own record straight back to it — and the merge of a record against itself has
     * to be a no-op, or the row is pushed forever.
     */
    var loseNextAcknowledgement: Boolean = false

    override suspend fun changesSince(since: Long, limit: Int): ChangePage {
        val page = server.changes(since, limit)
        return ChangePage(
            // No payloads in this harness, so no createdAt to carry. The store's fallback then
            // applies, exactly as it did before the value was carried at all -- these sweeps are
            // about merge convergence, and `TwoDeviceSyncTest` covers createdAt over a real codec.
            records = page.map {
                IncomingRecord.Opened(seq = it.seq, record = it.record, createdAt = null)
            },
            // The server has no `hasMore` of its own; a full page is the signal, which is what a
            // real client infers too.
            hasMore = page.size == limit,
        )
    }

    override suspend fun push(items: List<PushRequest>): PushResponse {
        val acks = items.map { item ->
            val key = server.keyOf(item.type, item.uuid)
            when (val result = server.put(key, item.baseSeq, item.record)) {
                is FakeServer.PutResult.Ok ->
                    PushAck.Accepted(type = item.type, uuid = item.uuid, seq = result.seq)

                is FakeServer.PutResult.Conflict -> PushAck.Conflicted(
                    type = item.type,
                    uuid = item.uuid,
                    current = result.record,
                    currentSeq = result.seq,
                )
            }
        }
        if (loseNextAcknowledgement) {
            loseNextAcknowledgement = false
            throw SyncTransportException(
                TransportFault.NETWORK,
                "the connection dropped after the server had committed the batch",
            )
        }
        return PushResponse(acks)
    }
}
