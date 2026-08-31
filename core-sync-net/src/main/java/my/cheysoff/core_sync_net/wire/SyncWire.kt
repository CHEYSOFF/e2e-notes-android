package my.cheysoff.core_sync_net.wire

import my.cheysoff.core_sync_net.ChangesPage
import my.cheysoff.core_sync_net.ClaimOutcome
import my.cheysoff.core_sync_net.Cursor
import my.cheysoff.core_sync_net.EnrolledDevice
import my.cheysoff.core_sync_net.PushItem
import my.cheysoff.core_sync_net.PushOutcome
import my.cheysoff.core_sync_net.PushResult
import my.cheysoff.core_sync_net.RemoteDevice
import my.cheysoff.core_sync_net.RemoteRecord
import my.cheysoff.core_sync_net.ServerHealth
import my.cheysoff.core_sync_net.SyncException

/**
 * **Every JSON field name this client sends or reads, and every conversion between JSON and the
 * types in [my.cheysoff.core_sync_net.SyncApi]. One file, on purpose.**
 *
 * The wire format is not settled. `hlc`, `recType` and the plaintext device `label` are all
 * candidates for removal -- the server stores them in the clear and, if it never reads them, they
 * do not belong outside the sealed envelope. `hlc` in particular tells a server operator which of
 * the user's devices made every individual edit (`server/README.md`, "Can see"). Nothing has ever
 * synced, so changing the format now costs a rebase; after Phase 3 ships it costs a migration
 * across every device.
 *
 * So the names live here as constants and every read and write of one goes through a function in
 * this file. Removing a field is then a small, complete diff -- delete a constant, delete the line
 * that writes it, delete the line that reads it -- rather than a hunt through call sites. Nothing
 * outside this file mentions a JSON field name; `WireFieldNamesAreInOnePlaceTest` is what keeps
 * that true.
 *
 * The names themselves are the server's, from `server/.../Wire.kt`. They are duplicated here rather
 * than shared because the server build is standalone and deliberately not on the Android build's
 * classpath; `SyncServerContractTest` is what proves the two copies still agree.
 *
 * ## Unknown fields are ignored here, and rejected there
 *
 * The server decodes **strictly**: an unknown field is a `400`. This client does the opposite and
 * simply does not look at fields it does not know. That asymmetry is the server's own reasoning
 * (`server/.../Wire.kt`) read from the other end: on the server, an unknown field means a client is
 * speaking a protocol this build does not implement, and ignoring one that a future version made
 * security-relevant is unnoticeable. On the client, refusing a response because it carried a field
 * added by a newer server would break every device the moment the server was upgraded first --
 * which is the normal order of a rollout.
 *
 * A field this client **does** know and cannot make sense of is still a hard failure. That is
 * [SyncException.Protocol], not a shrug.
 */
internal object SyncWire {

    // -----------------------------------------------------------------------------------------
    // Field names. Nothing outside this file may name a JSON field.
    // -----------------------------------------------------------------------------------------

    // Error body, on every non-2xx response.
    private const val ERROR = "error"
    private const val MESSAGE = "message"

    // GET /healthz
    private const val STATUS = "status"
    private const val VERSION = "version"

    // Identity and account, shared by several bodies.
    private const val ACCOUNT_ID = "accountId"
    private const val DEVICE_ID = "deviceId"
    private const val DEVICE_PUBLIC_KEY = "devicePublicKey"
    private const val NEW_PUBLIC_KEY = "newPublicKey"
    private const val VOUCHER_DEVICE_ID = "voucherDeviceId"
    private const val DEVICE_LABEL = "deviceLabel"
    private const val TS = "ts"
    private const val SIGNATURE = "signature"
    private const val CREATED_AT = "createdAt"

    // POST /v1/session/challenge, POST /v1/session
    private const val CHALLENGE = "challenge"
    private const val EXPIRES_AT = "expiresAt"
    private const val TOKEN = "token"

    // GET /v1/devices
    private const val DEVICES = "devices"
    private const val LABEL = "label"
    private const val PUBLIC_KEY = "publicKey"
    private const val REVOKED_AT = "revokedAt"
    private const val SELF = "self"

    // Records, in every direction.
    private const val BLINDED_ID = "blindedId"
    private const val REC_TYPE = "recType"
    private const val HLC = "hlc"
    private const val SEQ = "seq"
    private const val ENVELOPE = "envelope"
    private const val RECEIVED_AT = "receivedAt"

    // GET /v1/changes
    private const val RECORDS = "records"
    private const val NEXT_CURSOR = "nextCursor"
    private const val HAS_MORE = "hasMore"

    // POST /v1/records
    private const val ITEMS = "items"
    private const val BASE_SEQ = "baseSeq"
    private const val RESULTS = "results"
    private const val ACCOUNT_SEQ = "accountSeq"
    private const val CURRENT = "current"

    // Spelled the same as [STATUS] and kept separate from it deliberately: one is the liveness
    // string on `/healthz` and the other is the per-item verdict on a push. They have no reason to
    // change together, and sharing a constant would make a rename of either one silently rename
    // both.
    private const val ITEM_STATUS = "status"
    private const val STATUS_OK = "ok"
    private const val STATUS_CONFLICT = "conflict"

    // GET /v1/records/{id}/history
    private const val VERSIONS = "versions"

    // -----------------------------------------------------------------------------------------
    // Requests
    // -----------------------------------------------------------------------------------------

    /**
     * `POST /v1/account`.
     *
     * [publicKeyB64] and [signatureB64] are passed as text rather than bytes because the signature
     * covers the *text*: the server rebuilds `SignedMessage.claim(accountId, devicePublicKey, ts)`
     * from the strings in this body, so the string signed and the string sent must be the same
     * object, not two encodings of one key.
     */
    fun claimRequest(
        accountId: String,
        publicKeyB64: String,
        deviceLabel: String,
        ts: Long,
        signatureB64: String,
    ): ByteArray = JsonWriter().obj {
        field(ACCOUNT_ID, accountId)
        field(DEVICE_PUBLIC_KEY, publicKeyB64)
        field(DEVICE_LABEL, deviceLabel)
        field(TS, ts)
        field(SIGNATURE, signatureB64)
    }.toBytes()

    /** `POST /v1/devices/authorize`. See [claimRequest] for why the key travels as text. */
    fun authorizeRequest(
        accountId: String,
        newPublicKeyB64: String,
        deviceLabel: String,
        ts: Long,
        voucherDeviceId: String,
        signatureB64: String,
    ): ByteArray = JsonWriter().obj {
        field(ACCOUNT_ID, accountId)
        field(NEW_PUBLIC_KEY, newPublicKeyB64)
        field(DEVICE_LABEL, deviceLabel)
        field(TS, ts)
        field(VOUCHER_DEVICE_ID, voucherDeviceId)
        field(SIGNATURE, signatureB64)
    }.toBytes()

    /** `POST /v1/session/challenge`. */
    fun challengeRequest(accountId: String, deviceId: String): ByteArray = JsonWriter().obj {
        field(ACCOUNT_ID, accountId)
        field(DEVICE_ID, deviceId)
    }.toBytes()

    /** `POST /v1/session`. */
    fun sessionRequest(
        accountId: String,
        deviceId: String,
        challenge: String,
        signatureB64: String,
    ): ByteArray = JsonWriter().obj {
        field(ACCOUNT_ID, accountId)
        field(DEVICE_ID, deviceId)
        field(CHALLENGE, challenge)
        field(SIGNATURE, signatureB64)
    }.toBytes()

    /**
     * `POST /v1/records`.
     *
     * [PushItem.envelope] is base64url-encoded here and nowhere else. This is the only thing this
     * module ever does to an envelope.
     */
    fun upsertRequest(items: List<PushItem>): ByteArray = JsonWriter().obj {
        arrayField(ITEMS, items) { item ->
            field(BLINDED_ID, item.blindedId)
            field(REC_TYPE, item.recType)
            field(HLC, item.hlc)
            field(BASE_SEQ, item.baseSeq)
            field(ENVELOPE, Base64Codec.encodeUrl(item.envelope))
        }
    }.toBytes()

    // -----------------------------------------------------------------------------------------
    // Responses
    // -----------------------------------------------------------------------------------------

    /** The `{"error","message"}` body every non-2xx status carries. */
    class ServerErrorBody(val code: String, val message: String)

    /**
     * Reads an error body, or returns null if the body is not one.
     *
     * Null rather than a throw because a non-2xx status with an unreadable body still has to be
     * reported *as that status*. A `502` from a misconfigured reverse proxy is an HTML page, and
     * "the sync server returned 502" is a far more useful thing to tell a caller than "the response
     * was not JSON".
     */
    fun decodeError(body: ByteArray): ServerErrorBody? = try {
        val obj = JsonReader.parse(body.decodeToString()) as? JsonValue.Obj ?: return null
        val code = (obj.fields[ERROR] as? JsonValue.Str)?.value ?: return null
        val message = (obj.fields[MESSAGE] as? JsonValue.Str)?.value.orEmpty()
        ServerErrorBody(code, message)
    } catch (_: JsonParseException) {
        null
    }

    fun decodeHealth(body: ByteArray): ServerHealth = readObject(body, "healthz").let {
        ServerHealth(status = it.string(STATUS), version = it.string(VERSION))
    }

    fun decodeClaim(body: ByteArray): ClaimOutcome.Claimed = readObject(body, "account").let {
        ClaimOutcome.Claimed(deviceId = it.string(DEVICE_ID), createdAt = it.long(CREATED_AT))
    }

    fun decodeAuthorize(body: ByteArray): EnrolledDevice = readObject(body, "authorize").let {
        EnrolledDevice(deviceId = it.string(DEVICE_ID), createdAt = it.long(CREATED_AT))
    }

    /** A single-use nonce for this device to sign. */
    class Challenge(val challenge: String, val expiresAt: Long)

    fun decodeChallenge(body: ByteArray): Challenge = readObject(body, "challenge").let {
        Challenge(challenge = it.string(CHALLENGE), expiresAt = it.long(EXPIRES_AT))
    }

    /**
     * A bearer token and its expiry.
     *
     * No `toString` override and no `data class`: the compiler-generated `toString` of a data class
     * would print the token, and that is one accidental log line away from a credential in a bug
     * report.
     */
    class SessionToken(val token: String, val expiresAt: Long)

    fun decodeSession(body: ByteArray): SessionToken = readObject(body, "session").let {
        SessionToken(token = it.string(TOKEN), expiresAt = it.long(EXPIRES_AT))
    }

    fun decodeDevices(body: ByteArray): List<RemoteDevice> =
        readObject(body, "devices").array(DEVICES).map { element ->
            val device = element.asObject(DEVICES)
            RemoteDevice(
                deviceId = device.string(DEVICE_ID),
                label = device.string(LABEL),
                publicKey = device.base64(PUBLIC_KEY),
                createdAt = device.long(CREATED_AT),
                revokedAt = device.longOrNull(REVOKED_AT),
                isSelf = device.bool(SELF),
            )
        }

    /**
     * `GET /v1/changes`.
     *
     * ## The cursor invariant, checked here
     *
     * `nextCursor` is read from the response and then **verified against the records that came with
     * it**: it must be the largest `seq` on the page, or exactly the `since` that was requested when
     * the page is empty, and it must never go backwards. The records themselves must be strictly
     * ascending in `seq`.
     *
     * That check is cheap and it is the one that matters. The cursor is the server's monotonic
     * sequence number and never a timestamp -- `seq` is allocated inside the same transaction as
     * the insert it labels, so every reader sees the same order, which is a property no timestamp
     * has. A client that advanced its cursor with `receivedAt` instead would skip every record
     * whose timestamp tied with one already seen, silently and permanently. Reading the number from
     * the wrong field, on either side of the wire, breaks one of these three relations, so this
     * refuses the page rather than storing a cursor that will lose data.
     *
     * @param requestedSince the `since` that was sent, needed to check the empty-page case.
     */
    fun decodeChanges(body: ByteArray, requestedSince: Long): ChangesPage {
        val obj = readObject(body, "changes")
        val records = obj.array(RECORDS).map { it.asObject(RECORDS).toRecord() }
        val nextCursor = obj.long(NEXT_CURSOR)

        var previous = requestedSince
        for (record in records) {
            if (record.seq <= previous) {
                throw SyncException.Protocol(
                    "the sync server returned a change page that is not strictly ordered by seq"
                )
            }
            previous = record.seq
        }
        val expected = records.lastOrNull()?.seq ?: requestedSince
        if (nextCursor != expected) {
            throw SyncException.Protocol(
                "the sync server's next cursor does not match the records on the page"
            )
        }
        return ChangesPage(
            records = records,
            nextCursor = Cursor.ofSeq(nextCursor),
            hasMore = obj.bool(HAS_MORE),
        )
    }

    /**
     * `POST /v1/records`, for both the `200` and the `409`.
     *
     * The per-item results have the same shape either way, which is why one decoder serves both:
     * a `409` means at least one item conflicted, not that the batch failed. An item this client
     * does not recognise the status of is a protocol error rather than an assumed success --
     * treating an unknown status as `ok` would clear a row's dirty flag on a write that never
     * happened, which is a silently lost note.
     */
    fun decodeUpsert(body: ByteArray): PushOutcome {
        val obj = readObject(body, "records")
        val results = obj.array(RESULTS).map { element ->
            val item = element.asObject(RESULTS)
            val blindedId = item.string(BLINDED_ID)
            when (val status = item.string(ITEM_STATUS)) {
                STATUS_OK -> PushResult.Accepted(blindedId, item.long(SEQ))
                STATUS_CONFLICT -> PushResult.Conflict(
                    blindedId,
                    item.objectOrNull(CURRENT)?.toRecord(),
                )
                else -> throw SyncException.Protocol(
                    "the sync server reported an unknown push status '$status'"
                )
            }
        }
        return PushOutcome(results = results, accountSeq = obj.long(ACCOUNT_SEQ))
    }

    fun decodeHistory(body: ByteArray): List<RemoteRecord> =
        readObject(body, "history").array(VERSIONS).map { it.asObject(VERSIONS).toRecord() }

    /** One record, in whichever body it appears -- a change page, a conflict, or a history entry. */
    private fun JsonValue.Obj.toRecord(): RemoteRecord = RemoteRecord(
        blindedId = string(BLINDED_ID),
        recType = string(REC_TYPE),
        hlc = string(HLC),
        seq = long(SEQ),
        envelope = base64(ENVELOPE),
        receivedAt = long(RECEIVED_AT),
    )

    // -----------------------------------------------------------------------------------------
    // Typed field access
    //
    // Every accessor fails with SyncException.Protocol naming the field, and nothing else. A
    // response that is the wrong shape is a client and server that disagree, and the field name is
    // the whole diagnostic -- so it is included, while the value never is: an envelope, a token or
    // a signature in an exception message is a secret in a crash report.
    // -----------------------------------------------------------------------------------------

    private fun readObject(body: ByteArray, what: String): JsonValue.Obj {
        val value = try {
            JsonReader.parse(body.decodeToString())
        } catch (e: JsonParseException) {
            throw SyncException.Protocol("the sync server's $what response is not valid JSON", e)
        }
        return value as? JsonValue.Obj
            ?: throw SyncException.Protocol("the sync server's $what response is not a JSON object")
    }

    private fun malformed(field: String): Nothing =
        throw SyncException.Protocol("the sync server's response has no usable '$field' field")

    private fun JsonValue.Obj.string(field: String): String =
        (fields[field] as? JsonValue.Str)?.value ?: malformed(field)

    private fun JsonValue.Obj.long(field: String): Long =
        (fields[field] as? JsonValue.Num)?.raw?.toLongOrNull() ?: malformed(field)

    /** A field that is a number, or `null`, or absent -- `revokedAt` is all three in practice. */
    private fun JsonValue.Obj.longOrNull(field: String): Long? = when (val value = fields[field]) {
        null, JsonValue.Null -> null
        is JsonValue.Num -> value.raw.toLongOrNull() ?: malformed(field)
        else -> malformed(field)
    }

    private fun JsonValue.Obj.bool(field: String): Boolean =
        (fields[field] as? JsonValue.Bool)?.value ?: malformed(field)

    private fun JsonValue.Obj.array(field: String): List<JsonValue> =
        (fields[field] as? JsonValue.Arr)?.items ?: malformed(field)

    private fun JsonValue.Obj.objectOrNull(field: String): JsonValue.Obj? =
        when (val value = fields[field]) {
            null, JsonValue.Null -> null
            is JsonValue.Obj -> value
            else -> malformed(field)
        }

    /** A base64url field decoded to bytes: public keys and sealed envelopes. */
    private fun JsonValue.Obj.base64(field: String): ByteArray =
        Base64Codec.decodeUrl(string(field)) ?: malformed(field)

    private fun JsonValue.asObject(field: String): JsonValue.Obj =
        this as? JsonValue.Obj ?: malformed(field)
}
