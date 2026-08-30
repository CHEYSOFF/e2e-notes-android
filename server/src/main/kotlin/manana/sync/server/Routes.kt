package manana.sync.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json

/**
 * The whole HTTP surface.
 *
 * ### What this server is
 *
 * A per-account, append-only blob store with optimistic concurrency. It stores sealed envelopes
 * under blinded record IDs, hands them back in the order it received them, and refuses a write
 * whose compare-and-set base has moved. **It never learns what a note is** -- it does not decrypt,
 * does not parse an envelope, and has no code that could.
 *
 * ### The two properties every route is written around
 *
 * **The cursor is a per-account monotonic `seq`, never a timestamp.** Timestamps collide, and they
 * go backwards across an NTP step or a clock change, either of which silently loses a record for a
 * client that has already moved its cursor past it. `seq` is allocated inside the same transaction
 * as the insert (see [SyncStore]), so ordering is the same for every reader.
 *
 * **There is no delete endpoint.** A deletion is an ordinary upsert whose *plaintext* carries a
 * tombstone flag, sealed inside the envelope. The server cannot distinguish it from an edit, which
 * is simultaneously the privacy property (deletions are not observable) and the simplicity property
 * (there is one write path, so there is one thing to get right).
 */
fun Application.syncModule(deps: ServerDeps) {

    install(StatusPages) {
        // A failure that escapes a handler must not put a stack trace, a SQL fragment or a class
        // name on the wire; every one of those tells an attacker about the deployment, and none of
        // them helps a client. The operator gets the exception on stderr through slf4j; the client
        // gets five words.
        exception<Throwable> { call, cause ->
            deps.log.warn("unhandled ${cause::class.simpleName}")
            call.respondText(
                errorJson("internal_error", "The server failed to handle this request."),
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    routing {
        get("/healthz") {
            serve(deps, "GET /healthz") {
                Reply(
                    HttpStatusCode.OK,
                    JSON.encodeToString(HealthResponse("ok", ServerConfig.BUILD_VERSION)),
                )
            }
        }

        post("/v1/account") { serve(deps, "POST /v1/account") { claimAccount(deps, it) } }

        post("/v1/devices/authorize") {
            serve(deps, "POST /v1/devices/authorize") { authorizeDevice(deps, it) }
        }

        post("/v1/session/challenge") {
            serve(deps, "POST /v1/session/challenge") { sessionChallenge(deps, it) }
        }

        post("/v1/session") { serve(deps, "POST /v1/session") { openSession(deps, it) } }

        get("/v1/devices") {
            serve(deps, "GET /v1/devices") { authenticated(deps) { listDevices(deps, it) } }
        }

        delete("/v1/devices/{id}") {
            serve(deps, "DELETE /v1/devices/{id}") {
                authenticated(deps) { revokeDevice(deps, it, call.parameters["id"]) }
            }
        }

        get("/v1/changes") {
            serve(deps, "GET /v1/changes") {
                authenticated(deps) {
                    changes(
                        deps,
                        it,
                        call.request.queryParameters["since"],
                        call.request.queryParameters["limit"],
                    )
                }
            }
        }

        post("/v1/records") {
            serve(deps, "POST /v1/records") { body ->
                authenticated(deps) { upsertRecords(deps, it, body) }
            }
        }

        get("/v1/records/{id}/history") {
            serve(deps, "GET /v1/records/{id}/history") {
                authenticated(deps) {
                    history(
                        deps,
                        it,
                        call.parameters["id"],
                        call.request.queryParameters["limit"],
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------
// Plumbing
// -------------------------------------------------------------------------------------------

/** One response, ready to be written. [retryAfterSeconds] becomes a `Retry-After` header. */
private class Reply(
    val status: HttpStatusCode,
    val json: String,
    val retryAfterSeconds: Long? = null,
)

/**
 * Strict JSON. Unknown fields are rejected rather than ignored -- see [ErrorBody]'s KDoc for why
 * the server takes the opposite position from the client here.
 */
private val JSON = Json { encodeDefaults = true }

private fun errorJson(code: String, message: String): String =
    JSON.encodeToString(ErrorBody(code, message))

private fun error(status: HttpStatusCode, code: String, message: String) =
    Reply(status, errorJson(code, message))

private val BAD_REQUEST = HttpStatusCode.BadRequest
private val UNAUTHORIZED = HttpStatusCode.Unauthorized

/**
 * Runs one endpoint: rate-limit, read a bounded body, run the handler off the event loop, write the
 * reply, log one line naming the *route template* rather than the path.
 *
 * The rate limit here is keyed on the caller's address and applies to every request including the
 * unauthenticated ones, which is where a brute-force loop would live. Authenticated endpoints take
 * a second, per-account permit inside [authenticated].
 */
private suspend fun RoutingContext.serve(
    deps: ServerDeps,
    routeTemplate: String,
    handler: suspend RoutingContext.(ByteArray) -> Reply,
) {
    val started = deps.clock.nowMillis()
    var requestBytes = 0

    val reply: Reply = when (val decision = deps.rateLimiter.check("ip:" + call.request.origin.remoteAddress)) {
        is RateDecision.Throttled -> Reply(
            HttpStatusCode.TooManyRequests,
            errorJson("rate_limited", "Too many requests. Retry after the advertised delay."),
            decision.retryAfterSeconds,
        )

        RateDecision.Allowed -> {
            val body = call.readBounded(deps.config.maxRequestBytes)
            if (body == null) {
                error(
                    HttpStatusCode.PayloadTooLarge,
                    "payload_too_large",
                    "Request body exceeds the configured limit.",
                )
            } else {
                requestBytes = body.size
                // Every store call is blocking JDBC behind a lock. Running handlers on the IO
                // dispatcher keeps a slow transaction from parking a request-handling thread.
                withContext(Dispatchers.IO) { handler(body) }
            }
        }
    }

    reply.retryAfterSeconds?.let {
        call.response.headers.append(HttpHeaders.RetryAfter, it.toString())
    }
    call.respondText(reply.json, ContentType.Application.Json, reply.status)

    deps.log.request(
        method = call.request.origin.method.value,
        routeTemplate = routeTemplate,
        status = reply.status.value,
        durationMillis = deps.clock.nowMillis() - started,
        requestBytes = requestBytes,
        responseBytes = reply.json.toByteArray(Charsets.UTF_8).size,
    )
}

/**
 * Reads at most [max] bytes of request body, returning null if there are more.
 *
 * The declared `Content-Length` is checked first so an oversized upload is refused without being
 * read, and the actual byte count is checked as well so a chunked body that lies about its size --
 * or does not declare one -- is refused too. Neither check trusts the other.
 */
private suspend fun ApplicationCall.readBounded(max: Int): ByteArray? {
    val declared = request.contentLength()
    if (declared != null && declared > max) return null
    val bytes = request.receiveChannel().readRemaining((max + 1).toLong()).readByteArray()
    return if (bytes.size > max) null else bytes
}

/** Parses [bytes] as [T], or null. Never throws: a malformed body is a `400`, not a `500`. */
private inline fun <reified T> parse(bytes: ByteArray): T? = try {
    JSON.decodeFromString<T>(bytes.decodeToString())
} catch (_: Exception) {
    null
}

private val MALFORMED_BODY =
    error(BAD_REQUEST, "malformed_request", "Request body is not valid JSON for this endpoint.")

/**
 * Resolves the bearer token, takes a per-account rate-limit permit, and runs [block].
 *
 * A missing, malformed, expired, unknown **or revoked** token all produce the same `401` with the
 * same message. Distinguishing them would tell an unauthenticated caller which account IDs exist.
 */
private suspend fun RoutingContext.authenticated(
    deps: ServerDeps,
    block: suspend RoutingContext.(SessionRow) -> Reply,
): Reply {
    val header = call.request.headers[HttpHeaders.Authorization]
        ?: return error(UNAUTHORIZED, "unauthorized", "A bearer token is required.")
    if (!header.startsWith("Bearer ")) {
        return error(UNAUTHORIZED, "unauthorized", "A bearer token is required.")
    }
    val token = header.substring("Bearer ".length).trim()
    if (token.isEmpty() || token.length > deps.config.maxTokenChars) {
        return error(UNAUTHORIZED, "unauthorized", "A bearer token is required.")
    }
    val session = deps.store.sessionByTokenHash(sha256Hex(token.toByteArray(Charsets.UTF_8)))
        ?: return error(UNAUTHORIZED, "unauthorized", "A bearer token is required.")

    val decision = deps.rateLimiter.check("account:" + session.accountId)
    if (decision is RateDecision.Throttled) {
        return Reply(
            HttpStatusCode.TooManyRequests,
            errorJson("rate_limited", "Too many requests. Retry after the advertised delay."),
            decision.retryAfterSeconds,
        )
    }
    return block(session)
}

// -------------------------------------------------------------------------------------------
// Validation helpers
// -------------------------------------------------------------------------------------------

/** `accountId` must be base64url that decodes to exactly 16 bytes -- the protocol's own shape. */
private fun validAccountId(value: String): Boolean =
    B64.decodeExactly(value, ServerConfig.ACCOUNT_ID_BYTES) != null

/**
 * A blinded record ID is opaque to the server, so it is validated only for shape: non-empty,
 * bounded, and drawn from the base64url alphabet. That is enough for it to be a safe path segment
 * and a safe database key, and it stops short of assuming a length, so a future record type with a
 * differently sized ID does not need a server change.
 */
private fun validBlindedId(value: String, maxChars: Int): Boolean =
    value.isNotEmpty() && value.length <= maxChars && value.all {
        it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_'
    }

private fun validOpaqueLabel(value: String, maxChars: Int): Boolean = value.length <= maxChars

/** True while [ts] is within the configured window of the server's own clock, in either direction. */
private fun freshTimestamp(deps: ServerDeps, ts: Long): Boolean {
    val skew = deps.clock.nowMillis() - ts
    return skew <= deps.config.signatureWindowMillis && -skew <= deps.config.signatureWindowMillis
}

/**
 * Records that a signed message has been seen, returning false if it had been seen already.
 *
 * Called only **after** the signature verifies, so a forged message can never consume a replay slot
 * and a valid one is spent exactly once.
 */
private fun claimReplaySlot(deps: ServerDeps, message: ByteArray): Boolean =
    deps.store.claimSignature(
        messageHash = sha256Hex(message),
        expiresAt = deps.clock.nowMillis() + 2 * deps.config.signatureWindowMillis,
    )

private fun RecordVersion.toDto() = RecordDto(
    blindedId = blindedId,
    recType = recType,
    hlc = hlc,
    seq = seq,
    envelope = B64.encode(envelope),
    receivedAt = receivedAt,
)

// -------------------------------------------------------------------------------------------
// Endpoints
// -------------------------------------------------------------------------------------------

/**
 * `POST /v1/account` -- trust-on-first-use account claim, enrolling the first device.
 *
 * There is no authorisation step and there cannot be one: this is the moment the account comes into
 * existence. What makes it safe is that `accountId` is `HKDF(ARK, "manana/sync/v1/account")`, so
 * naming an account requires holding its root key. An attacker cannot squat what they cannot
 * compute.
 *
 * The request is nevertheless signed, by the enrolling device's own key, over
 * `("claim", accountId, devicePublicKey, ts)`. That is proof of possession: it stops a key the
 * caller does not hold from being installed as the account's first -- and therefore vouching --
 * device, and it puts the claim under the same freshness and replay rules as everything else.
 */
private fun claimAccount(deps: ServerDeps, body: ByteArray): Reply {
    val request = parse<ClaimRequest>(body) ?: return MALFORMED_BODY

    if (!validAccountId(request.accountId)) {
        return error(BAD_REQUEST, "invalid_account_id", "accountId is not a 16-byte base64url value.")
    }
    if (!validOpaqueLabel(request.deviceLabel, deps.config.maxLabelChars)) {
        return error(BAD_REQUEST, "invalid_label", "deviceLabel is too long.")
    }
    val publicKey = B64.decodeExactly(request.devicePublicKey, P256Verify.POINT_SIZE_BYTES)
        ?: return error(BAD_REQUEST, "invalid_public_key", "devicePublicKey is not a SEC1 P-256 point.")
    if (P256Verify.decodePublicKey(publicKey) == null) {
        return error(BAD_REQUEST, "invalid_public_key", "devicePublicKey is not a SEC1 P-256 point.")
    }
    val signature = B64.decodeOrNull(request.signature)
        ?: return error(BAD_REQUEST, "malformed_base64", "signature is not valid base64url.")
    if (!freshTimestamp(deps, request.ts)) {
        return error(UNAUTHORIZED, "stale_timestamp", "ts is outside the accepted window.")
    }

    val message = SignedMessage.claim(request.accountId, request.devicePublicKey, request.ts)
    if (!P256Verify.verify(publicKey, message, signature)) {
        return error(UNAUTHORIZED, "bad_signature", "The signature did not verify.")
    }
    if (!claimReplaySlot(deps, message)) {
        return error(UNAUTHORIZED, "replay_detected", "This signed request has already been used.")
    }

    val deviceId = deps.store.claimAccount(request.accountId, publicKey, request.deviceLabel)
        ?: return error(HttpStatusCode.Conflict, "account_exists", "That account is already claimed.")

    return Reply(
        HttpStatusCode.Created,
        JSON.encodeToString(ClaimResponse(request.accountId, deviceId, deps.clock.nowMillis())),
    )
}

/**
 * `POST /v1/devices/authorize` -- enrolment by vouching.
 *
 * An already-enrolled device signs `("authorize", accountId, newPubKey, ts)` with its P-256
 * Keystore key. The server holds only public keys, so it can check that signature and can do
 * nothing else with it: **a full read of this server's database yields no ability to write or to
 * impersonate a device.**
 *
 * A revoked device cannot vouch. That check is the point of revocation -- a stolen phone that is
 * revoked must not be able to add a fresh device and re-enter the account through it.
 */
private fun authorizeDevice(deps: ServerDeps, body: ByteArray): Reply {
    val request = parse<AuthorizeRequest>(body) ?: return MALFORMED_BODY

    if (!validAccountId(request.accountId)) {
        return error(BAD_REQUEST, "invalid_account_id", "accountId is not a 16-byte base64url value.")
    }
    if (!validOpaqueLabel(request.deviceLabel, deps.config.maxLabelChars)) {
        return error(BAD_REQUEST, "invalid_label", "deviceLabel is too long.")
    }
    val newKey = B64.decodeExactly(request.newPublicKey, P256Verify.POINT_SIZE_BYTES)
        ?: return error(BAD_REQUEST, "invalid_public_key", "newPublicKey is not a SEC1 P-256 point.")
    if (P256Verify.decodePublicKey(newKey) == null) {
        return error(BAD_REQUEST, "invalid_public_key", "newPublicKey is not a SEC1 P-256 point.")
    }
    val signature = B64.decodeOrNull(request.signature)
        ?: return error(BAD_REQUEST, "malformed_base64", "signature is not valid base64url.")

    val voucher = deps.store.device(request.accountId, request.voucherDeviceId)
        ?: return error(HttpStatusCode.NotFound, "unknown_device", "No such vouching device.")
    if (voucher.revokedAt != null) {
        return error(HttpStatusCode.Forbidden, "device_revoked", "The vouching device is revoked.")
    }
    if (!freshTimestamp(deps, request.ts)) {
        return error(UNAUTHORIZED, "stale_timestamp", "ts is outside the accepted window.")
    }

    val message = SignedMessage.authorize(request.accountId, request.newPublicKey, request.ts)
    if (!P256Verify.verify(voucher.publicKey, message, signature)) {
        return error(UNAUTHORIZED, "bad_signature", "The signature did not verify.")
    }
    if (!claimReplaySlot(deps, message)) {
        return error(UNAUTHORIZED, "replay_detected", "This signed request has already been used.")
    }

    val deviceId = deps.store.enrolDevice(request.accountId, newKey, request.deviceLabel)
        ?: return error(HttpStatusCode.Conflict, "device_exists", "That key is already enrolled.")

    return Reply(
        HttpStatusCode.Created,
        JSON.encodeToString(AuthorizeResponse(deviceId, deps.clock.nowMillis())),
    )
}

/**
 * `POST /v1/session/challenge` -- hand out a single-use nonce for a device to sign.
 *
 * Unauthenticated, because it is the first half of authentication. It does reveal whether a given
 * `(accountId, deviceId)` pair exists, which is acceptable only because both values are unguessable
 * 128-bit strings; it is stated in the README rather than left to be discovered.
 */
private fun sessionChallenge(deps: ServerDeps, body: ByteArray): Reply {
    val request = parse<ChallengeRequest>(body) ?: return MALFORMED_BODY
    if (!validAccountId(request.accountId)) {
        return error(BAD_REQUEST, "invalid_account_id", "accountId is not a 16-byte base64url value.")
    }
    val device = deps.store.device(request.accountId, request.deviceId)
        ?: return error(HttpStatusCode.NotFound, "unknown_device", "No such device.")
    if (device.revokedAt != null) {
        return error(HttpStatusCode.Forbidden, "device_revoked", "That device is revoked.")
    }

    val challenge = Ids.random(32)
    val expiresAt = deps.clock.nowMillis() + deps.config.challengeTtlMillis
    deps.store.createChallenge(request.accountId, request.deviceId, challenge, expiresAt)
    return Reply(HttpStatusCode.OK, JSON.encodeToString(ChallengeResponse(challenge, expiresAt)))
}

/**
 * `POST /v1/session` -- redeem a signed challenge for a bearer token.
 *
 * The challenge is consumed on lookup, **before** the signature is checked, so a captured request
 * body is worthless: replaying it finds no challenge and gets a `401`. A wrong signature also burns
 * the challenge, which costs an honest client one extra round trip and costs an online guesser the
 * ability to try twice against the same nonce.
 *
 * The token is returned once and stored only as a SHA-256 digest, so the database holds nothing
 * that can be presented back to this server.
 */
private fun openSession(deps: ServerDeps, body: ByteArray): Reply {
    val request = parse<SessionRequest>(body) ?: return MALFORMED_BODY
    val signature = B64.decodeOrNull(request.signature)
        ?: return error(BAD_REQUEST, "malformed_base64", "signature is not valid base64url.")

    val badChallenge =
        error(UNAUTHORIZED, "bad_challenge", "The challenge is unknown, expired or already used.")
    val claimed = deps.store.consumeChallenge(request.challenge) ?: return badChallenge
    if (claimed.accountId != request.accountId || claimed.deviceId != request.deviceId) {
        return badChallenge
    }
    val device = deps.store.device(request.accountId, request.deviceId) ?: return badChallenge
    if (device.revokedAt != null) {
        return error(HttpStatusCode.Forbidden, "device_revoked", "That device is revoked.")
    }

    val message = SignedMessage.session(request.accountId, request.deviceId, request.challenge)
    if (!P256Verify.verify(device.publicKey, message, signature)) {
        return error(UNAUTHORIZED, "bad_signature", "The signature did not verify.")
    }

    val token = Ids.random(32)
    val expiresAt = deps.clock.nowMillis() + deps.config.sessionTtlMillis
    deps.store.createSession(
        accountId = request.accountId,
        deviceId = request.deviceId,
        tokenHash = sha256Hex(token.toByteArray(Charsets.UTF_8)),
        expiresAt = expiresAt,
    )
    return Reply(HttpStatusCode.OK, JSON.encodeToString(SessionResponse(token, expiresAt)))
}

/** `GET /v1/devices` -- what is enrolled, including revoked entries, so a UI can show history. */
private fun listDevices(deps: ServerDeps, session: SessionRow): Reply {
    val devices = deps.store.listDevices(session.accountId).map {
        DeviceDto(
            deviceId = it.deviceId,
            label = it.label,
            publicKey = B64.encode(it.publicKey),
            createdAt = it.createdAt,
            revokedAt = it.revokedAt,
            self = it.deviceId == session.deviceId,
        )
    }
    return Reply(HttpStatusCode.OK, JSON.encodeToString(DevicesResponse(devices)))
}

/**
 * `DELETE /v1/devices/{id}` -- revoke.
 *
 * A device may revoke itself; that is "sign out this device" and there is no reason to forbid it.
 * Revocation kills the device's live sessions in the same transaction, so a token issued a moment
 * earlier stops working at once rather than at its next expiry.
 *
 * **Records are untouched.** Revoking a device removes its ability to read and write; it does not
 * and cannot remove data, because the server has no delete. Re-keying an account after a device
 * is lost is a client-side operation over a new `accountId`.
 */
private fun revokeDevice(deps: ServerDeps, session: SessionRow, deviceId: String?): Reply {
    if (deviceId.isNullOrEmpty() || deviceId.length > 64) {
        return error(BAD_REQUEST, "invalid_device_id", "Device id is missing or malformed.")
    }
    val revoked = deps.store.revokeDevice(session.accountId, deviceId)
    if (!revoked) return error(HttpStatusCode.NotFound, "unknown_device", "No such device.")
    return Reply(HttpStatusCode.OK, JSON.encodeToString(RevokeResponse(deviceId)))
}

/**
 * `GET /v1/changes?since=<seq>&limit=<n>` -- incremental pull, ordered by the account's monotonic
 * `seq`.
 *
 * A `since` greater than the account's high-water mark is rejected with `409`, not silently
 * answered with an empty page. That case has exactly two causes and both need the client to stop:
 * the client is talking to a server restored from an older backup, or to a different server
 * entirely. Answering "no changes" would let a rolled-back server look healthy while the client sat
 * on a cursor it could never satisfy.
 */
private fun changes(deps: ServerDeps, session: SessionRow, sinceParam: String?, limitParam: String?): Reply {
    val since = (sinceParam ?: "0").toLongOrNull()
        ?: return error(BAD_REQUEST, "invalid_cursor", "since must be a non-negative integer.")
    if (since < 0) {
        return error(BAD_REQUEST, "invalid_cursor", "since must be a non-negative integer.")
    }
    val limit = when (val raw = limitParam) {
        null -> deps.config.defaultChangesLimit
        else -> raw.toIntOrNull()?.takeIf { it in 1..deps.config.maxChangesLimit }
            ?: return error(BAD_REQUEST, "invalid_limit", "limit is out of range.")
    }

    val lastSeq = deps.store.lastSeq(session.accountId)
    if (since > lastSeq) {
        return error(
            HttpStatusCode.Conflict,
            "cursor_ahead_of_server",
            "The cursor is ahead of this server. Re-baseline before syncing again.",
        )
    }

    val records = deps.store.changesSince(session.accountId, since, limit)
    val nextCursor = records.lastOrNull()?.seq ?: since
    return Reply(
        HttpStatusCode.OK,
        JSON.encodeToString(
            ChangesResponse(records.map { it.toDto() }, nextCursor, records.size == limit)
        ),
    )
}

/**
 * `POST /v1/records` -- batch upsert with per-item compare-and-set on `baseSeq`.
 *
 * Each item is applied only if `baseSeq` still equals the record's head seq (0 asserting "not yet
 * present"). An item whose base has moved comes back as `"conflict"` with the blocking version's
 * envelope inline, so the client can merge without a second round trip. Items that did not conflict
 * are applied.
 *
 * The response is `409` if any item conflicted and `200` if none did; the per-item results are the
 * same either way, so a client can read one shape regardless.
 *
 * Envelopes are checked for size and for nothing else. **The server does not parse them and must
 * not start**: the moment it understands a byte of an envelope, it is no longer true that a
 * compromised server learns nothing.
 */
private fun upsertRecords(deps: ServerDeps, session: SessionRow, body: ByteArray): Reply {
    val request = parse<UpsertRequest>(body) ?: return MALFORMED_BODY
    if (request.items.isEmpty()) {
        return error(BAD_REQUEST, "empty_batch", "items must not be empty.")
    }
    if (request.items.size > deps.config.maxBatchItems) {
        return error(BAD_REQUEST, "batch_too_large", "Too many items in one batch.")
    }
    if (request.items.map { it.blindedId }.toSet().size != request.items.size) {
        // Two writes to one record in one batch would make the second item's `baseSeq` refer to a
        // version created moments earlier in the same request, which is a shape no client needs and
        // that nobody would enjoy reasoning about. Refuse it outright.
        return error(BAD_REQUEST, "duplicate_record_in_batch", "A record appears twice in one batch.")
    }

    val items = ArrayList<UpsertItem>(request.items.size)
    for (item in request.items) {
        if (!validBlindedId(item.blindedId, deps.config.maxBlindedIdChars)) {
            return error(BAD_REQUEST, "invalid_blinded_id", "blindedId is missing or malformed.")
        }
        if (item.recType.isEmpty() || item.recType.length > deps.config.maxRecTypeChars) {
            return error(BAD_REQUEST, "invalid_rec_type", "recType is missing or too long.")
        }
        if (item.hlc.isEmpty() || item.hlc.length > deps.config.maxHlcChars) {
            return error(BAD_REQUEST, "invalid_hlc", "hlc is missing or too long.")
        }
        if (item.baseSeq < 0) {
            return error(BAD_REQUEST, "invalid_base_seq", "baseSeq must not be negative.")
        }
        val envelope = B64.decodeOrNull(item.envelope)
            ?: return error(BAD_REQUEST, "malformed_base64", "envelope is not valid base64url.")
        if (envelope.isEmpty() || envelope.size > deps.config.maxEnvelopeBytes) {
            return error(BAD_REQUEST, "invalid_envelope", "envelope is empty or too large.")
        }
        items += UpsertItem(item.blindedId, item.recType, item.hlc, item.baseSeq, envelope)
    }

    val outcomes = deps.store.upsertBatch(session.accountId, items)
    val results = outcomes.map { outcome ->
        when (outcome) {
            is UpsertOutcome.Ok ->
                UpsertResultItem(outcome.blindedId, "ok", seq = outcome.seq)

            is UpsertOutcome.Conflict ->
                UpsertResultItem(outcome.blindedId, "conflict", current = outcome.current?.toDto())
        }
    }
    val anyConflict = outcomes.any { it is UpsertOutcome.Conflict }
    return Reply(
        if (anyConflict) HttpStatusCode.Conflict else HttpStatusCode.OK,
        JSON.encodeToString(UpsertResponse(results, deps.store.lastSeq(session.accountId))),
    )
}

/**
 * `GET /v1/records/{id}/history?limit=<n>` -- the most recent versions of one record, newest first.
 *
 * This is the safety net the architecture document asks for against a client merge bug: a bad merge
 * that overwrites a note is recoverable as long as the previous ciphertext is still here, and only
 * a device holding the account root key can read what comes back.
 */
private fun history(deps: ServerDeps, session: SessionRow, blindedId: String?, limitParam: String?): Reply {
    if (blindedId == null || !validBlindedId(blindedId, deps.config.maxBlindedIdChars)) {
        return error(BAD_REQUEST, "invalid_blinded_id", "Record id is missing or malformed.")
    }
    val limit = when (val raw = limitParam) {
        null -> deps.config.defaultHistoryLimit
        else -> raw.toIntOrNull()?.takeIf { it in 1..deps.config.maxHistoryLimit }
            ?: return error(BAD_REQUEST, "invalid_limit", "limit is out of range.")
    }

    val versions = deps.store.history(session.accountId, blindedId, limit)
    if (versions.isEmpty()) {
        return error(HttpStatusCode.NotFound, "unknown_record", "No such record on this account.")
    }
    return Reply(
        HttpStatusCode.OK,
        JSON.encodeToString(HistoryResponse(blindedId, versions.map { it.toDto() })),
    )
}
