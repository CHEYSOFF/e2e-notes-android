package my.cheysoff.core_sync_codec

import my.cheysoff.core_domain.sync.FieldClocks
import my.cheysoff.core_domain.sync.RecordType
import my.cheysoff.core_sync_engine.ChangePage
import my.cheysoff.core_sync_engine.IncomingRecord
import my.cheysoff.core_sync_engine.PushAck
import my.cheysoff.core_sync_engine.PushRequest
import my.cheysoff.core_sync_engine.PushResponse
import my.cheysoff.core_sync_engine.RecordFault
import my.cheysoff.core_sync_engine.SyncTransport
import my.cheysoff.core_sync_engine.SyncTransportException
import my.cheysoff.core_sync_engine.TransportFault
import my.cheysoff.core_sync_net.Cursor
import my.cheysoff.core_sync_net.DeviceCredentials
import my.cheysoff.core_sync_net.PushItem
import my.cheysoff.core_sync_net.PushResult
import my.cheysoff.core_sync_net.SyncApi
import my.cheysoff.core_sync_net.SyncException

/**
 * The sync engine's `SyncTransport`, over the HTTP client and the record codec.
 *
 * This is the seam `:core-sync-engine` was written not to cross. The engine deals in `SyncRecord`s
 * and never sees an envelope; the client deals in opaque blobs and never sees a note. Both halves
 * meet here, in the module that already owns the conversion between the two — [RecordCodec] turns a
 * record into a blob and [SyncRecords] turns a payload into a record, and this class is those two
 * pointed at a `SyncApi`.
 *
 * It lives here rather than in either app because **both** apps need exactly this object and neither
 * can lend it to the other: `:app` is an Android application and `:desktop` is a JVM one. A second
 * copy on the desktop would be a second reading of what a `409` means, what an unopenable record
 * means, and which `SyncException` is fatal — the class of divergence that produces a note one
 * device can write and the other cannot see, which is the failure this whole module exists to stop.
 *
 * ## A record that will not open is data, not an exception
 *
 * Every failure to turn a stored blob into a record comes back as [IncomingRecord.Faulted] with a
 * [RecordFault], **in the stream, carrying its `seq`**. That shape is the whole of §8's F1: the
 * engine counts the fault, skips the record, applies the readable ones around it, and refuses to
 * advance its cursor past it. An implementation that quietly dropped an unreadable record instead
 * would let the cursor sail past it, and the record would never be offered again — a note silently
 * absent from this device forever.
 *
 * ## Everything else is a `SyncTransportException`
 *
 * The engine understands seven coarse [TransportFault]s and nothing else, and a fault it does not
 * recognise must never be mistaken for an empty page — an empty page from a server that actually
 * refused the request reads as "the account has nothing", which is one merge away from a mass
 * delete. So every `SyncException` is mapped, and anything else that escapes the client (its
 * `require` checks throw `IllegalArgumentException`) is mapped too, to `PROTOCOL`.
 *
 * @param createdAtOf the record's `createdAt`, which `SyncRecord` does not carry and the payload
 *   does. Supplied by the store rather than invented here; see `SyncRecords` for why the column is
 *   in one and not the other.
 */
class EnvelopeSyncTransport(
    private val api: SyncApi,
    private val credentials: DeviceCredentials,
    private val codec: RecordCodec,
    private val createdAtOf: suspend (RecordType, String) -> Long?,
) : SyncTransport {

    override suspend fun changesSince(since: Long, limit: Int): ChangePage = translating {
        val page = api.changesSince(credentials, Cursor.ofSeq(since), limit)
        ChangePage(
            records = page.records.map { remote ->
                when (val opened = codec.open(remote.blindedId, remote.envelope)) {
                    // `createdAt` is read straight off the payload rather than out of the record:
                    // it is not a clocked field, so `fromPayload` does not carry it. It is the one
                    // value here that belongs to the record rather than to the merge.
                    is OpenResult.Ok -> SyncRecords.fromPayload(opened.payload)
                        ?.let {
                            IncomingRecord.Opened(
                                seq = remote.seq,
                                record = it,
                                createdAt = opened.payload
                                    .field(PayloadFields.CREATED_AT)
                                    ?.toLongOrNull(),
                            )
                        }
                    // Authentic bytes this build cannot turn into a record. Reported as UNREADABLE
                    // rather than as its own fault because the engine's response is the right one:
                    // count it, skip it, do not advance past it, and halt if there is a stream of
                    // them. It is not MISLABELLED — nothing about the record's identity is wrong —
                    // and it is not a version refusal, which the codec reports separately.
                        ?: IncomingRecord.Faulted(remote.seq, RecordFault.UNREADABLE)

                    OpenResult.Unreadable -> IncomingRecord.Faulted(remote.seq, RecordFault.UNREADABLE)
                    is OpenResult.Malformed -> IncomingRecord.Faulted(remote.seq, RecordFault.UNREADABLE)
                    OpenResult.Mislabelled -> IncomingRecord.Faulted(remote.seq, RecordFault.MISLABELLED)
                    is OpenResult.UnsupportedVersion ->
                        IncomingRecord.Faulted(remote.seq, RecordFault.UNSUPPORTED_PAYLOAD_VERSION)
                    is OpenResult.UnknownType ->
                        IncomingRecord.Faulted(remote.seq, RecordFault.UNKNOWN_TYPE)
                }
            },
            hasMore = page.hasMore,
        )
    }

    override suspend fun push(items: List<PushRequest>): PushResponse = translating {
        // The blinded id is the only name the server knows, and it is not reversible, so the map
        // back to (type, uuid) has to be kept here. The engine matches acks by identity rather
        // than by position precisely because a response may come back reordered.
        val identities = HashMap<String, PushRequest>(items.size)
        val payloads = items.map { item ->
            val createdAt = createdAtOf(item.type, item.uuid)
                // Only reachable if the row were deleted between `dirtyRecords()` and here, which
                // one pass cannot do. `updatedAt` is the same fallback the receiving side uses, so
                // the two agree rather than each inventing something.
                ?: item.record.valueOf(FieldClocks.UPDATED_AT).parts[0]?.toLongOrNull() ?: 0L
            val sealed = codec.seal(SyncRecords.toPayload(item.record, createdAt))
            identities[sealed.blindedId] = item
            PushItem(blindedId = sealed.blindedId, baseSeq = item.baseSeq, envelope = sealed.envelope)
        }

        val outcome = api.pushRecords(credentials, payloads)
        PushResponse(
            outcome.results.mapNotNull { result ->
                when (result) {
                    is PushResult.Accepted -> identities[result.blindedId]?.let {
                        PushAck.Accepted(it.type, it.uuid, result.seq)
                    }

                    is PushResult.Conflict -> identities[result.blindedId]?.let { sent ->
                        val blocking = result.current
                        val record = blocking?.let { current ->
                            (codec.open(current.blindedId, current.envelope) as? OpenResult.Ok)
                                ?.let { SyncRecords.fromPayload(it.payload) }
                        }
                        // A conflict whose inline version will not open is reported with no record
                        // rather than as a fault: the row simply stays dirty and the next pull
                        // fetches the blocking version the ordinary way, which is slower and
                        // always correct. Faulting here would halt on a record the engine has a
                        // perfectly good second route to.
                        PushAck.Conflicted(
                            type = sent.type,
                            uuid = sent.uuid,
                            current = record,
                            currentSeq = if (record == null) 0L else blocking!!.seq,
                        )
                    }
                }
            }
        )
    }

    /**
     * Runs [body], turning everything it can throw into a [SyncTransportException].
     *
     * The `else` branch is the one that earns its place: `SyncHttpClient` validates its arguments
     * with `require`, so a batch of 65 or a malformed blinded id arrives as an
     * `IllegalArgumentException` rather than as a `SyncException`. Letting that escape would take
     * down whatever coroutine is running the pass instead of ending the pass, and the engine's
     * promise that it never throws would stop being true one layer up.
     */
    private inline fun <T> translating(body: () -> T): T = try {
        body()
    } catch (e: SyncException) {
        throw SyncTransportException(
            fault = faultOf(e),
            message = e.message ?: e::class.java.simpleName,
            retryAfterMillis = (e as? SyncException.RateLimited)?.retryAfterMillis ?: 0L,
            cause = e,
        )
    } catch (e: SyncTransportException) {
        throw e
    } catch (e: Exception) {
        throw SyncTransportException(TransportFault.PROTOCOL, "the sync client refused the request", cause = e)
    }

    private fun faultOf(e: SyncException): TransportFault = when (e) {
        is SyncException.Network -> TransportFault.NETWORK
        // Not NETWORK. A pin mismatch is a server presenting a certificate this account has never
        // agreed to, and a fault the engine merely waits on would have it retried every minute
        // forever against exactly that server.
        is SyncException.PinMismatch -> TransportFault.PROTOCOL
        is SyncException.RateLimited -> TransportFault.RATE_LIMITED
        is SyncException.Unauthorized -> TransportFault.UNAUTHORIZED
        SyncException.DeviceRevoked -> TransportFault.DEVICE_REVOKED
        is SyncException.CursorAheadOfServer -> TransportFault.CURSOR_AHEAD_OF_SERVER
        // A 400: the server will refuse these exact bytes again, so retrying is pointless. Every
        // other `Server` status (a 500, say) is transient in a way a 400 is not, so only this one
        // status leaves PROTOCOL for REJECTED.
        is SyncException.Server -> if (e.status == 400) TransportFault.REJECTED else TransportFault.PROTOCOL
        else -> TransportFault.PROTOCOL
    }
}
