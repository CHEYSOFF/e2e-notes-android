package my.cheysoff.core_sync_net

/**
 * Everything this client can ask the sync server to do, and nothing else.
 *
 * ## Where the line is
 *
 * This is the **transport**. It turns method calls into signed HTTP requests and responses back
 * into typed values. It does not encrypt, decrypt, parse or even look at a record body: an
 * [envelope][RemoteRecord.envelope] is a `ByteArray` on the way in and a `ByteArray` on the way out,
 * and the only thing done to it here is base64url framing. Sealing and opening belong to
 * `core-crypto/.../sync/RecordEnvelope`, and deciding what a record *means* belongs to the merge
 * engine above this. If a change to this module ever needs to know what is inside an envelope, the
 * change is in the wrong module.
 *
 * It is equally not a sync *engine*. There is no cursor persistence, no dirty tracking, no
 * scheduling, no retry-across-passes and no conflict resolution here. A `409` on push comes back as
 * data ([PushResult.Conflict], carrying the blocking version inline) rather than as a failure,
 * because resolving it is the merge engine's job and this layer has no opinion about it.
 *
 * ## Failures
 *
 * Every failure is a [SyncException]. `docs/design/e2e-sync-phase3-plan.md` calls this type
 * `SyncError`; it is named `SyncException` here because it is thrown, and in Kotlin a name ending
 * in `Error` reads as `java.lang.Error` -- something a `catch (e: Exception)` will not catch.
 *
 * Two `409`s are **not** failures and do not throw, because both are ordinary branches of a
 * healthy protocol:
 *
 *  - `409 account_exists` on [claimAccount] is [ClaimOutcome.AlreadyClaimed]. Two freshly paired
 *    devices racing to claim one account is expected; the loser proceeds as a vouched enrolment
 *    (plan §10, decision D2).
 *  - `409` on [pushRecords] is a per-item [PushResult.Conflict]. The items that did not conflict
 *    were still applied, and the server reports per item either way.
 *
 * One `409` **is** a halt: `cursor_ahead_of_server` becomes [SyncException.CursorAheadOfServer],
 * which means the server has been rolled back or the client is pointed at a different server. The
 * plan (§8, F7) requires the engine to stop rather than reset its cursor.
 *
 * ## Threading
 *
 * Every method suspends and every implementation performs its I/O off the caller's thread. None of
 * them is safe to call from a context that must not block on a network round trip -- which is all
 * of them, since a `429` back-off deliberately waits.
 */
interface SyncApi {

    /**
     * `GET /healthz`.
     *
     * Unauthenticated, and useful for exactly one thing: a "test this server address" button in
     * settings, before any key material has been committed to a server the user typed by hand.
     */
    suspend fun health(): ServerHealth

    /**
     * `POST /v1/account` -- trust-on-first-use claim, enrolling this device as the account's first.
     *
     * Self-signed: the signature is made by this device's own identity key over
     * `("claim", accountId, devicePublicKey, ts)` and proves possession of the key being installed
     * as the account's first -- and therefore vouching -- device. Naming the account at all
     * requires holding the Account Root Key, since `accountId = HKDF(ARK, ".../account")`.
     *
     * @param deviceLabel the device's human-readable name. It is **sealed before it is sent**, by
     *   the [DeviceLabelSealer][my.cheysoff.core_sync_net.auth.DeviceLabelSealer] this client was
     *   built with, so the operator stores a fixed-size blob rather than "Vova's Pixel 7". A name
     *   longer than the sealer accepts, or a device whose account key is not available, enrols with
     *   no label at all -- never with a plaintext one.
     */
    suspend fun claimAccount(accountId: String, deviceLabel: String): ClaimOutcome

    /**
     * `POST /v1/devices/authorize` -- vouch for another device.
     *
     * Called **by an already-enrolled device**, not by the joining one: the signature over
     * `("authorize", accountId, newPublicKey, ts)` is made with this client's signer, and the server
     * checks it against [voucherDeviceId]'s stored public key. A revoked voucher is refused, which
     * is the entire point of revocation.
     *
     * @param newPublicKey the joining device's public key, SEC1 uncompressed P-256 (65 bytes).
     * @param deviceLabel the **joining** device's name, sealed against the **joining** device's key
     *   before it is sent. See [claimAccount].
     */
    suspend fun authorizeDevice(
        accountId: String,
        voucherDeviceId: String,
        newPublicKey: ByteArray,
        deviceLabel: String,
    ): EnrolledDevice

    /** `GET /v1/devices` -- every device on the account, revoked ones included. */
    suspend fun listDevices(credentials: DeviceCredentials): List<RemoteDevice>

    /**
     * `DELETE /v1/devices/{id}` -- revoke a device, killing its live sessions in the same
     * transaction.
     *
     * A device may revoke itself; that is "sign out this device". Records are untouched, because
     * the server has no delete.
     */
    suspend fun revokeDevice(credentials: DeviceCredentials, deviceId: String)

    /**
     * `GET /v1/changes?since=&limit=` -- one page of the incremental pull, in `seq` order.
     *
     * @param since the cursor. See [Cursor]: it is the server's monotonic sequence number and never
     *   a timestamp.
     * @param limit page size, or null for `SyncHttpClient.DEFAULT_CHANGES_LIMIT` (32). Note this is
     *   the *client's* default, not the server's, which is 200; the server caps any value at 500.
     * @throws SyncException.CursorAheadOfServer when [since] exceeds the account's high-water mark.
     *   Halt; do not reset the cursor to zero.
     */
    suspend fun changesSince(
        credentials: DeviceCredentials,
        since: Cursor,
        limit: Int? = null,
    ): ChangesPage

    /**
     * `POST /v1/records` -- batch upsert with per-item compare-and-set on `baseSeq`.
     *
     * A conflicting item is reported, not thrown: see [PushOutcome]. Items that did not conflict
     * **were applied**, so a caller must read every result rather than branching on whether any
     * conflict occurred.
     *
     * The server refuses a batch of more than 64 items, an empty batch, and a batch naming one
     * record twice. Those are caller errors and surface as [SyncException.Server].
     */
    suspend fun pushRecords(
        credentials: DeviceCredentials,
        items: List<PushItem>,
    ): PushOutcome

    /**
     * `GET /v1/records/{id}/history?limit=` -- the most recent versions of one record, newest first.
     *
     * The safety net against a client merge bug: a bad merge is recoverable while the previous
     * ciphertext is still on the server. The server retains a bounded number of versions per record
     * (10 by default), so this is not an archive.
     */
    suspend fun history(
        credentials: DeviceCredentials,
        blindedId: String,
        limit: Int? = null,
    ): List<RemoteRecord>
}

/**
 * Which account and which enrolled device this client is acting as.
 *
 * Both values are needed on every authenticated call because both go into the session handshake:
 * the challenge is issued for a `(accountId, deviceId)` pair and the signature covers both. The
 * bearer token derived from them is held in memory by the client and never appears here.
 *
 * [deviceId] is the **server's** device identifier, assigned by [SyncApi.claimAccount] or
 * [SyncApi.authorizeDevice]. It is not the local HLC node pseudonym, and the two must not be
 * conflated -- plan §2 and §10 decision D4 are explicit that the HLC node is a separate,
 * per-account random string, because the HLC travels to the server in plaintext.
 */
class DeviceCredentials(val accountId: String, val deviceId: String)

/**
 * A position in the account's change stream.
 *
 * **This is the server's per-account monotonic `seq` and it is never a timestamp.** That is the one
 * property the whole pull protocol rests on, so it is a type rather than a `Long` parameter: the
 * constructor is private and the only ways to make one are [Cursor.START] and [Cursor.ofSeq], which
 * reads wrong at a call site that hands it a clock reading.
 *
 * Why it matters, in the server's own words: `seq` is allocated inside the same transaction that
 * inserts the row it labels, so sequence numbers become visible to readers in exactly the order
 * they were allocated. Timestamps do neither -- they collide under concurrent writes, and they go
 * backwards across an NTP step. A client that advanced its cursor with `receivedAt` would skip
 * every record whose timestamp tied with or preceded one it had already seen, permanently and
 * silently.
 */
// `kotlin.jvm.JvmInline` is named in full rather than imported, and the qualification is the point:
// it resolved bare while this module compiled only for the JVM and Android, both of which have it
// as a default import, and it does not on a Kotlin/Native target. Nothing about the type changes --
// on Apple the annotation is a no-op, because a value class is inlined by that backend regardless.
@kotlin.jvm.JvmInline
value class Cursor private constructor(val seq: Long) {

    override fun toString(): String = "Cursor(seq=$seq)"

    companion object {

        /** Before the first record. A pull from here returns the whole account. */
        val START: Cursor = Cursor(0L)

        /**
         * A cursor at server sequence number [seq].
         *
         * @throws IllegalArgumentException if [seq] is negative. The server rejects a negative
         *   `since` too; failing here means the caller sees the mistake at the call site that made
         *   it rather than as a `400` from three layers away.
         */
        fun ofSeq(seq: Long): Cursor {
            require(seq >= 0) { "a cursor is a server sequence number and cannot be negative" }
            return Cursor(seq)
        }
    }
}

/** `GET /healthz`. Not a protocol version -- it is the server build's own version string. */
class ServerHealth(val status: String, val version: String)

/** The outcome of a trust-on-first-use claim. */
sealed class ClaimOutcome {

    /** `201`: the account did not exist and this device is now its first. */
    class Claimed(val deviceId: String, val createdAt: Long) : ClaimOutcome()

    /**
     * `409 account_exists`: somebody claimed it first.
     *
     * Not an error. Two devices paired moments apart race for this, and the loser's next move is to
     * be vouched for by the winner -- see plan §10, decision D2.
     */
    object AlreadyClaimed : ClaimOutcome()
}

/** `201` from `POST /v1/devices/authorize`: the server's identifier for the newly enrolled device. */
class EnrolledDevice(val deviceId: String, val createdAt: Long)

/**
 * One row of `GET /v1/devices`.
 *
 * [publicKey] is a SEC1 uncompressed P-256 point, so a UI can render a fingerprint the user can
 * compare across devices.
 */
class RemoteDevice(
    val deviceId: String,
    /**
     * The device's name, opened from the sealed blob the server stores, or **null** when this
     * device cannot open it.
     *
     * Null is an ordinary case, not an error: the row may have been enrolled with no name, sealed
     * under a different account's key, or substituted in transit -- see `DeviceLabelCipher.open`.
     * Render such a row as unnamed and let the user identify it by [publicKey]; **do not hide it**,
     * because a device the user cannot see is a device the user cannot revoke.
     */
    val label: String?,
    val publicKey: ByteArray,
    val createdAt: Long,
    /** Null while the device is active. */
    val revokedAt: Long?,
    /** True for the device whose token made this call. */
    val isSelf: Boolean,
) {
    val isRevoked: Boolean get() = revokedAt != null
}

/**
 * One version of one record, exactly as the server holds it: a handle, a sequence number and a
 * sealed blob.
 *
 * That is the whole of it, and the shortness is the point. The record's type and its clock were
 * once fields here; the server never read either, so they moved inside [envelope] and are now
 * encrypted like the note itself. [envelope] is opaque at this layer and must stay that way.
 */
class RemoteRecord(
    val blindedId: String,
    /**
     * This version's per-account sequence number. **The cursor is made of these**, and of nothing
     * else -- see [Cursor].
     */
    val seq: Long,
    /** The sealed ciphertext. Never opened, parsed or validated by this module. */
    val envelope: ByteArray,
)

/** One page of `GET /v1/changes`. */
class ChangesPage(
    /** Head versions only, in ascending [RemoteRecord.seq] order. */
    val records: List<RemoteRecord>,
    /**
     * Where the caller's cursor belongs after applying [records] -- the largest `seq` on the page,
     * or the requested `since` when the page is empty.
     *
     * The client checks that against the records it actually received before handing it back; see
     * `ChangesPage` handling in `SyncHttpClient`.
     */
    val nextCursor: Cursor,
    /** True when the page was full, so another pull will return more. */
    val hasMore: Boolean,
)

/**
 * One record to write.
 *
 * [baseSeq] is the compare-and-set base: the `seq` of the version this edit was made against, or
 * `0` asserting "this record does not exist on the server yet". Getting it from anywhere other than
 * the row's own recorded `lastSyncedSeq` produces either a needless conflict or a lost update.
 */
class PushItem(
    val blindedId: String,
    val baseSeq: Long,
    /**
     * Sealed ciphertext, produced elsewhere. This module does not look inside it -- and the record's
     * type and clock are now in there, so an item has no third thing to carry.
     */
    val envelope: ByteArray,
)

/** What happened to one item of a batch. */
sealed class PushResult {

    /** Written. [seq] is the sequence number the new version was given. */
    class Accepted(val blindedId: String, val seq: Long) : PushResult()

    /**
     * Refused because [PushItem.baseSeq] no longer matched the record's head.
     *
     * [current] is the version that blocked the write, inline, so the caller can merge without a
     * second round trip. It is nullable because the server's response schema allows it to be
     * absent; a caller that finds it null has to fetch the record before it can merge.
     */
    class Conflict(val blindedId: String, val current: RemoteRecord?) : PushResult()
}

/**
 * The result of one `POST /v1/records`.
 *
 * **A conflict is not a failure of the batch.** The server applies every item whose `baseSeq` still
 * matched and reports per item, so the correct reading is item by item; [hasConflicts] exists for
 * logging and for deciding whether another pass is warranted, not for deciding whether the push
 * "worked".
 */
class PushOutcome(
    val results: List<PushResult>,
    /** The account's high-water mark after this batch. */
    val accountSeq: Long,
) {
    val hasConflicts: Boolean get() = results.any { it is PushResult.Conflict }
}
