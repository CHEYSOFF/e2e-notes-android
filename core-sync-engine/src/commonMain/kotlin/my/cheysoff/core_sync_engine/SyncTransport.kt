package my.cheysoff.core_sync_engine

import my.cheysoff.core_domain.sync.RecordType
import my.cheysoff.core_domain.sync.SyncRecord

/**
 * The server, as the engine needs to see it: a stream of records in `seq` order, and a
 * compare-and-set write.
 *
 * ## Why the engine is not handed envelopes
 *
 * `docs/design/e2e-sync-phase3-plan.md` §4 gives the opening of an envelope three checks — it
 * decrypts, it recomputes `BlindedRecordId.compute(kId, recType, uuid)` and refuses a payload filed
 * under a different name, and it refuses a payload version this build does not know. All three need
 * `K_content`, `K_id` and a JSON parser, none of which exist in `commonMain`, and this module is
 * `commonMain` precisely so that `ConvergenceTest` runs on the JVM in milliseconds rather than on
 * an emulator.
 *
 * So the boundary is drawn one step above the ciphertext: an implementation opens the envelope and
 * hands back either the record or the **reason it could not**, and the engine owns the policy for
 * each reason ([RecordFault], and §8's F1/F2/F3). The decision of what a failed decrypt *costs* is
 * the engine's; the decrypt itself is not. An implementation that silently dropped a record it
 * could not open instead of reporting [IncomingRecord.Faulted] would defeat that, which is why
 * `Faulted` carries a `seq` and appears in the stream rather than being an exception.
 *
 * ## Failures
 *
 * Anything that stops a call from producing an answer is a [SyncTransportException]. There are
 * deliberately no nullable returns and no boolean success flags: every one of the plan's §8 failure
 * modes maps to a [TransportFault], and a fault the engine does not recognise is one it must not
 * quietly treat as an empty page.
 *
 * A `409` on push is **not** a failure — see [PushAck.Conflicted]. Neither is an empty page.
 */
interface SyncTransport {

    /**
     * One page of `GET /v1/changes?since=&limit=`, in ascending `seq` order.
     *
     * @param since the cursor: the server's monotonic sequence number, **never a timestamp**. See
     *   `Cursor` in `:core-sync-net` for why that distinction is a type there and a comment here.
     * @throws SyncTransportException with [TransportFault.CURSOR_AHEAD_OF_SERVER] when [since] is
     *   past the account's high-water mark. That is a rolled-back server seen from outside and the
     *   engine halts on it; it must never be reported as an empty page.
     */
    suspend fun changesSince(since: Long, limit: Int): ChangePage

    /**
     * `POST /v1/records` — a batch upsert with per-item compare-and-set on `baseSeq`.
     *
     * The server applies every item whose `baseSeq` still matched and reports per item, so the
     * result is read item by item. An implementation must return one [PushAck] per [PushRequest],
     * in any order; the engine matches them by identity, not by position, because a batch that came
     * back reordered would otherwise acknowledge the wrong rows.
     */
    suspend fun push(items: List<PushRequest>): PushResponse
}

/** One page of [SyncTransport.changesSince]. */
class ChangePage(
    /** Head versions only, ascending by [IncomingRecord.seq]. */
    val records: List<IncomingRecord>,
    /** True when the page was full, so another call will return more. */
    val hasMore: Boolean,
)

/** One record the server offered, opened or refused. */
sealed interface IncomingRecord {

    /** The sequence number the server gave this version. The cursor is made of these. */
    val seq: Long

    /** The envelope opened, its blinded id checked, and its payload version understood. */
    class Opened(
        override val seq: Long,
        val record: SyncRecord,
        /**
         * The `createdAt` the payload carried, or null if it did not carry one.
         *
         * Beside the record rather than inside it, deliberately. `SyncRecord` is the **merge's**
         * vocabulary and holds only clocked fields; `createdAt` is not one, because no write path
         * moves it and so it has no history to merge. It nevertheless has to reach the store, and
         * before this it did not: `SyncRecords.fromPayload` builds its fields from
         * `recType.fields`, so the value was dropped at that boundary and a device seeing a record
         * for the first time had to invent one from `updatedAt` — permanently disagreeing with the
         * device that made it (issue #90).
         *
         * Null for a record whose payload omitted it, which the store then handles as before.
         */
        val createdAt: Long?,
        /**
         * The opaque `meta` an attachment's payload carried, or null for a record type that has no
         * such column (every type but `ATTACHMENT`) and for a payload that omitted it.
         *
         * Beside the record for the same structural reason as [createdAt] and a different
         * substantive one: `meta` is a reserved escape hatch with no clock of its own
         * (`PayloadFields.META`), so it is not in `RecordType.fields` and `SyncRecords.fromPayload`
         * cannot carry it. It reaches the store on `MergedWrite.remoteMeta`.
         *
         * Defaulted to null so that a test transport constructing records by hand does not have to
         * think about a column no test record has. Production has exactly one producer of this
         * class -- `EnvelopeSyncTransport` -- and it passes the real value.
         */
        val meta: String? = null,
    ) : IncomingRecord

    /** The envelope did not survive one of §4's three checks. */
    class Faulted(override val seq: Long, val fault: RecordFault) : IncomingRecord
}

/**
 * Why a record could not be turned into a [SyncRecord], and — since each one has a different
 * response — which of the plan's §8 rows it is.
 */
enum class RecordFault {

    /**
     * `RecordEnvelope.open` returned null: the wrong key, a tampered ciphertext, or a record from
     * another account. **F1.**
     *
     * The only fault the engine tolerates, and it tolerates it by *not advancing the cursor past
     * it*: the record is counted and skipped, the readable records around it are still applied, and
     * the pass stops paging so that nothing beyond the fault is ever mistaken for delivered. A
     * handful of these is a corrupt row; more than [SyncEngine.UNREADABLE_RECORD_LIMIT] in one pass
     * is an account this device cannot read and the engine halts.
     */
    UNREADABLE,

    /**
     * The payload opened, but `HMAC(K_id, recType ‖ ":" ‖ uuid)` over its own `(recType, uuid)` is
     * not the blinded id it arrived under. **F3.**
     *
     * A server cannot produce this without the ARK, so it is a client bug and must be loud. Halts
     * on the first one.
     */
    MISLABELLED,

    /**
     * The payload's `v` is one this build does not know. **F2.**
     *
     * Halts rather than decoding what it recognises. A partially decoded payload that is then
     * re-serialised and pushed is the "silent field loss" hazard the architecture doc names, and it
     * destroys the newer device's data with no error anywhere.
     */
    UNSUPPORTED_PAYLOAD_VERSION,

    /**
     * The record opened and parsed, and names a type this build does not implement.
     *
     * The only fault the engine may page **past**. See [SyncEngine]'s pull loop for why that is
     * safe here and for nothing else: a record this build cannot represent could not have been
     * stored even if it had been accepted, so advancing past it loses nothing that was ever going
     * to be kept.
     */
    UNKNOWN_TYPE,
}

/**
 * One record to write.
 *
 * [type] and [uuid] are here rather than a blinded id because this module never computes one — the
 * implementation does, from these two values, and it is the same derivation that named the record
 * on the way in. Carrying the identity in the clear on *this* side of the seam is what lets the
 * engine match a [PushAck] back to the row it came from.
 */
class PushRequest(
    val type: RecordType,
    val uuid: String,
    /**
     * The `seq` of the version this edit was made against, or `0` asserting "this record is not on
     * the server yet". It is [StoredRecord.lastSyncedSeq] and nothing else; a value from anywhere
     * else is either a needless conflict or a lost update.
     */
    val baseSeq: Long,
    /** The version being pushed. The engine remembers it so it can tell whether the row moved. */
    val record: SyncRecord,
)

/** What happened to one item of a batch. */
sealed interface PushAck {

    val type: RecordType
    val uuid: String

    /** Written. [seq] is the sequence number the new version was given. */
    class Accepted(
        override val type: RecordType,
        override val uuid: String,
        val seq: Long,
    ) : PushAck

    /**
     * Refused because [PushRequest.baseSeq] no longer matched the record's head.
     *
     * **This is data, not an error.** [current] goes through the same `Merge.merge` call as a
     * pulled record; there is deliberately no second merge path for the conflict case, because two
     * paths are how the two of them drift apart.
     *
     * @param current the version that blocked the write, inline. **Nullable**: the server's
     *   response schema allows it to be absent, and an engine that assumed otherwise would crash on
     *   a legal response. Without it the engine can only leave the row dirty for the next pass,
     *   which pulls the blocking version the ordinary way.
     * @param currentSeq the blocking version's `seq`, or `0` when [current] is null.
     * @param currentMeta the opaque `meta` [current]'s payload carried, or null for a record type
     *   without the column and for a null [current]. Travels beside [current] for the same reason
     *   `IncomingRecord.Opened.meta` does -- it is not a clocked field, so the `SyncRecord` cannot
     *   carry it -- and it is not decoration. A conflict merge in which any local field wins leaves
     *   the row dirty (`Merge`'s `dirty = merged != remote.normalized()`), and the next push
     *   re-serialises `meta` from the local row. Without this the local row still holds the stale
     *   value and that push overwrites the server's newer `meta` account-wide -- the one path on
     *   which "a build that does not understand `meta` preserves it byte-for-byte" would otherwise
     *   be false.
     */
    class Conflicted(
        override val type: RecordType,
        override val uuid: String,
        val current: SyncRecord?,
        val currentSeq: Long,
        val currentMeta: String? = null,
    ) : PushAck
}

/** The result of one [SyncTransport.push]. */
class PushResponse(val results: List<PushAck>)

/**
 * Everything that can stop a [SyncTransport] call, as the engine classifies it.
 *
 * The transport layer this maps onto (`:core-sync-net`) has a richer exception hierarchy, and that
 * is the right shape for a layer that talks HTTP. The engine does not need the difference between a
 * DNS failure and a read timeout — it needs to know whether to stop the pass, wait, or refuse to
 * run again — so this enum is deliberately the coarser one.
 */
enum class TransportFault {

    /** No answer: DNS, connect, TLS, timeout, a dropped connection. The pass ends and retries. */
    NETWORK,

    /** The response was not something the client understands. Ends the pass; retrying may work. */
    PROTOCOL,

    /**
     * `429`, after the transport had already exhausted its own in-request retries.
     *
     * Carries the delay the server last asked for in [SyncTransportException.retryAfterMillis]. The
     * engine turns it into a [SyncOutcome.Deferred] with jitter added — see [RetryPlan].
     */
    RATE_LIMITED,

    /** A second `401` after a re-handshake. Not a stale token; another retry does not fix it. */
    UNAUTHORIZED,

    /** `403 device_revoked`. Halts: nothing this device does will work again. */
    DEVICE_REVOKED,

    /**
     * `409 cursor_ahead_of_server`.
     *
     * Halts. The client's cursor is beyond anything this server holds, which means the server was
     * restored from a backup or this is a different server. Resetting the cursor to zero instead is
     * the documented catastrophe (§8, F7): against clean rows it is indistinguishable from "the
     * account is empty", and the next pass is a mass delete.
     */
    CURSOR_AHEAD_OF_SERVER,

    /**
     * A `400` the server will give again for the same bytes: an envelope over its cap, a payload it
     * refuses to parse. Retrying is pointless and retrying forever is worse -- before this existed,
     * one record the server would not take stopped every other record on the device from ever being
     * pushed again, with nothing in the UI saying so.
     *
     * The engine only ever attributes this to a record when the batch held exactly one; see
     * [SyncEngine.LARGE_RECORD_BYTES] for why the batches that can provoke it are built to hold
     * one.
     */
    REJECTED,
}

/**
 * A [SyncTransport] call that produced no answer.
 *
 * @param retryAfterMillis how long the server asked the client to wait, for
 *   [TransportFault.RATE_LIMITED] only, and **without jitter** — the engine adds its own spread,
 *   because a delay that already has one device's jitter baked in is a delay every device inherits.
 */
class SyncTransportException(
    val fault: TransportFault,
    message: String,
    val retryAfterMillis: Long = 0L,
    cause: Throwable? = null,
) : Exception(message, cause)
