package my.cheysoff.core_sync_net

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import my.cheysoff.core_crypto.sync.SyncProtocol
import my.cheysoff.core_sync_net.auth.DeviceLabelSealer
import my.cheysoff.core_sync_net.auth.DeviceSigner
import my.cheysoff.core_sync_net.auth.SignedMessage
import my.cheysoff.core_sync_net.http.Delayer
import my.cheysoff.core_sync_net.http.HttpMethod
import my.cheysoff.core_sync_net.http.HttpRequest
import my.cheysoff.core_sync_net.http.HttpResponse
import my.cheysoff.core_sync_net.http.HttpTransport
import my.cheysoff.core_sync_net.http.Jitter
import my.cheysoff.core_sync_net.http.KtorHttpTransport
import my.cheysoff.core_sync_net.http.RetryAfterHeader
import my.cheysoff.core_sync_net.http.RetryPolicy
import my.cheysoff.core_sync_net.http.ServerEndpoint
import my.cheysoff.core_sync_net.http.TransportLog
import my.cheysoff.core_sync_net.wire.Base64Codec
import my.cheysoff.core_sync_net.wire.SyncWire

/**
 * The sync server client: [SyncApi] over one [ServerEndpoint].
 *
 * ## What this class owns
 *
 * Five things, and nothing else:
 *
 * 1. **Signing.** Every enrolment and every session handshake is a `SHA256withECDSA` signature over
 *    [SignedMessage]'s canonical bytes, made with the device's AndroidKeyStore P-256 key through
 *    [DeviceSigner]. There is no other authentication in this protocol.
 * 2. **The device label**, sealed on the way out and opened on the way back through
 *    [DeviceLabelSealer]. Neither the account key nor a plaintext name is held here; see that
 *    interface for why the one piece of encryption in this class is behind a seam.
 * 3. **The bearer token.** Obtained by the two-round-trip challenge handshake, cached in memory for
 *    its ~24 hour life, and **never written to disk**. Persisting it would mean a bearer credential
 *    in `secret_shared_prefs`, which is a bearer credential in an Auto Backup discussion, to save
 *    one round trip and one ECDSA operation a day.
 * 4. **`429` back-off.** `Retry-After`, honoured, with jitter added on top. See
 *    [RetryPolicy][my.cheysoff.core_sync_net.http.RetryPolicy] for why the jitter is not optional.
 * 5. **Turning HTTP statuses into [SyncException]s** -- and, for the two cases that are not
 *    failures, into ordinary return values.
 *
 * ## What it does not own
 *
 * It does not persist a cursor, track which rows are dirty, decide when to sync, retry across
 * passes, or resolve a conflict. It never opens an envelope -- the label seam is the only crypto it
 * reaches, and it reaches it through an interface. Those belong to the merge engine and
 * the coordinator above it, and the boundary is deliberate: this class can be tested exhaustively
 * against a fake [HttpTransport] precisely because it holds no state that outlives a call except
 * one token.
 *
 * ## Instances are per-endpoint and per-device
 *
 * One instance talks to one server as one device. It is safe to share across coroutines -- the
 * token is guarded by a [Mutex] and nothing else is mutable -- and it is cheap to build, so a
 * caller that reconfigures the server URL should build a new one rather than mutate this one. A
 * token issued by one server is meaningless to another, and keeping the instance would carry it
 * across.
 */
class SyncHttpClient(
    private val endpoint: ServerEndpoint,
    private val transport: HttpTransport,
    private val signer: DeviceSigner,
    /**
     * Seals the device label on enrolment and opens it in a device listing.
     *
     * Deliberately not defaulted. A default would be
     * [DeviceLabelSealer.NONE][DeviceLabelSealer.Companion.NONE], and a caller who got that by
     * omission would enrol an unnamed device and never find out until somebody tried to revoke the
     * right phone from a list of identical rows.
     */
    private val labelSealer: DeviceLabelSealer,
    /**
     * The device's wall clock, in epoch milliseconds.
     *
     * Injected because a signed request carries a `ts` the server checks against its own clock
     * within five minutes, so "what time does this device think it is" is a protocol input and a
     * test has to be able to move it. It is deliberately **not** used for anything else: nothing
     * here orders records by time.
     */
    private val clock: () -> Long = ::currentTimeMillis,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val delayer: Delayer = Delayer.REAL,
    private val jitter: Jitter = Jitter.RANDOM,
    private val log: TransportLog = TransportLog.NONE,
    /**
     * The client-side cap on a response body, checked again on whatever the transport returns.
     *
     * [KtorHttpTransport] already refuses an oversized body without reading it. This second check
     * exists because [transport] is an interface: a caller that supplies its own implementation --
     * a test fake today, something else tomorrow -- must not be able to hand this client a
     * hundred-megabyte buffer just because it forgot the cap. Defence in depth, one line.
     */
    private val maxResponseBytes: Int = KtorHttpTransport.DEFAULT_MAX_RESPONSE_BYTES,
) : SyncApi {

    /**
     * The cached bearer token, or null when there is none.
     *
     * Guarded by [sessionLock] for both reads and writes. The lock also serialises the handshake
     * itself, so two coroutines that discover an expired token at the same moment perform one
     * handshake between them rather than two -- which matters because the second would burn a
     * challenge for nothing and count against the same rate-limit bucket.
     */
    private var session: SyncWire.SessionToken? = null
    private val sessionLock = Mutex()

    // ------------------------------------------------------------------------------------------
    // Unauthenticated endpoints
    // ------------------------------------------------------------------------------------------

    override suspend fun health(): ServerHealth {
        val response = send(
            HttpRequest(HttpMethod.GET, endpoint.resolve(ROUTE_HEALTH)),
            ROUTE_HEALTH,
        )
        return SyncWire.decodeHealth(requireSuccess(response))
    }

    override suspend fun claimAccount(accountId: String, deviceLabel: String): ClaimOutcome {
        requireWellFormedAccountId(accountId)
        val publicKeyB64 = Base64Codec.encodeUrl(signer.publicKeySec1())
        val ts = clock()
        val signature = Base64Codec.encodeUrl(
            signer.sign(SignedMessage.claim(accountId, publicKeyB64, ts))
        )
        val response = send(
            HttpRequest(
                HttpMethod.POST,
                endpoint.resolve(ROUTE_ACCOUNT),
                body = SyncWire.claimRequest(
                    accountId = accountId,
                    publicKeyB64 = publicKeyB64,
                    sealedLabelB64 = sealLabel(publicKeyB64, deviceLabel),
                    ts = ts,
                    signatureB64 = signature,
                ),
            ),
            ROUTE_ACCOUNT,
        )
        // `409 account_exists` is the TOFU race, not a failure: two devices paired moments apart
        // both try to claim, and the loser proceeds as a vouched enrolment. Plan §10, decision D2.
        if (response.status == HTTP_CONFLICT && errorCode(response) == CODE_ACCOUNT_EXISTS) {
            return ClaimOutcome.AlreadyClaimed
        }
        return SyncWire.decodeClaim(requireSuccess(response))
    }

    override suspend fun authorizeDevice(
        accountId: String,
        voucherDeviceId: String,
        newPublicKey: ByteArray,
        deviceLabel: String,
    ): EnrolledDevice {
        requireWellFormedAccountId(accountId)
        require(newPublicKey.size == SEC1_UNCOMPRESSED_P256_BYTES) {
            "a device public key is a 65-byte SEC1 uncompressed P-256 point"
        }
        val newPublicKeyB64 = Base64Codec.encodeUrl(newPublicKey)
        val ts = clock()
        // Signed with THIS device's key -- the voucher's. The server looks up `voucherDeviceId`,
        // checks that device is not revoked, and verifies this signature against its stored public
        // key. A revoked device therefore cannot bring a new one in behind it.
        val signature = Base64Codec.encodeUrl(
            signer.sign(SignedMessage.authorize(accountId, newPublicKeyB64, ts))
        )
        val response = send(
            HttpRequest(
                HttpMethod.POST,
                endpoint.resolve(ROUTE_AUTHORIZE),
                body = SyncWire.authorizeRequest(
                    accountId = accountId,
                    newPublicKeyB64 = newPublicKeyB64,
                    // Sealed against the JOINING device's key, because that device is the one whose
                    // row the label lands on and whose key a reader will pair it with. Sealing
                    // against this voucher's key would produce a label nothing can ever open.
                    sealedLabelB64 = sealLabel(newPublicKeyB64, deviceLabel),
                    ts = ts,
                    voucherDeviceId = voucherDeviceId,
                    signatureB64 = signature,
                ),
            ),
            ROUTE_AUTHORIZE,
        )
        return SyncWire.decodeAuthorize(requireSuccess(response))
    }

    /**
     * The sealed device label as it goes on the wire: base64url, or `""` for no label.
     *
     * `""` is what the server reads as "this device sent no name", and it is what a client that
     * cannot seal must send. **There is no plaintext branch here and there must never be one** --
     * the whole point of the seal is that the operator cannot read the name, and a fallback that
     * quietly sends it in the clear when the ARK happens to be missing would defeat that on exactly
     * the runs nobody watches.
     */
    private fun sealLabel(devicePublicKeyB64: String, label: String): String {
        if (label.isEmpty()) return ""
        val sealed = labelSealer.seal(devicePublicKeyB64, label) ?: return ""
        return Base64Codec.encodeUrl(sealed)
    }

    // ------------------------------------------------------------------------------------------
    // Authenticated endpoints
    // ------------------------------------------------------------------------------------------

    override suspend fun listDevices(credentials: DeviceCredentials): List<RemoteDevice> {
        val response = sendAuthenticated(
            credentials,
            ROUTE_DEVICES,
        ) { HttpRequest(HttpMethod.GET, endpoint.resolve(ROUTE_DEVICES)) }
        return SyncWire.decodeDevices(requireSuccess(response), labelSealer::open)
    }


    override suspend fun revokeDevice(credentials: DeviceCredentials, deviceId: String) {
        requireSafePathSegment(deviceId, "device id")
        val response = sendAuthenticated(credentials, ROUTE_DEVICE_BY_ID) {
            HttpRequest(HttpMethod.DELETE, endpoint.resolve("/v1/devices/$deviceId"))
        }
        // The body names the device that was revoked and nothing this client needs; the status is
        // the whole answer. Still run it through requireSuccess so a 404 or a 401 throws.
        requireSuccess(response)
    }

    override suspend fun changesSince(
        credentials: DeviceCredentials,
        since: Cursor,
        limit: Int?,
    ): ChangesPage {
        val effectiveLimit = limit ?: DEFAULT_CHANGES_LIMIT
        require(effectiveLimit in 1..MAX_CHANGES_LIMIT) {
            "a change page holds between 1 and $MAX_CHANGES_LIMIT records"
        }
        val response = sendAuthenticated(credentials, ROUTE_CHANGES) {
            HttpRequest(
                HttpMethod.GET,
                endpoint.resolve(ROUTE_CHANGES, "since=${since.seq}&limit=$effectiveLimit"),
            )
        }
        // A cursor beyond the server's high-water mark means the server was restored from an older
        // backup, or this is a different server. Both need a deliberate re-baseline and neither is
        // survivable by resetting the cursor to zero -- against clean rows that is indistinguishable
        // from "the account is empty", and the next pass would be a mass delete. Plan §8, F7.
        if (response.status == HTTP_CONFLICT && errorCode(response) == CODE_CURSOR_AHEAD) {
            throw SyncException.CursorAheadOfServer(since.seq)
        }
        return SyncWire.decodeChanges(requireSuccess(response), since.seq)
    }

    override suspend fun pushRecords(
        credentials: DeviceCredentials,
        items: List<PushItem>,
    ): PushOutcome {
        require(items.isNotEmpty()) { "a push batch cannot be empty" }
        require(items.size <= MAX_BATCH_ITEMS) {
            "the sync server accepts at most $MAX_BATCH_ITEMS records in one batch"
        }
        require(items.map { it.blindedId }.toSet().size == items.size) {
            "a push batch cannot name one record twice"
        }
        items.forEach {
            requireSafePathSegment(it.blindedId, "record id")
            require(it.baseSeq >= 0) { "baseSeq is a server sequence number and cannot be negative" }
        }

        val body = SyncWire.upsertRequest(items)
        val response = sendAuthenticated(credentials, ROUTE_RECORDS) {
            HttpRequest(HttpMethod.POST, endpoint.resolve(ROUTE_RECORDS), body = body)
        }
        // A 409 here is DATA, not an error: the per-item results have the same shape as on a 200,
        // the items that did not conflict were applied, and each conflict carries the blocking
        // version inline so the caller can merge without a second round trip. Treating this status
        // as a failure would discard the results of the items that succeeded and would resend work
        // that was never in conflict. Plan §3.2, rule 3.
        if (response.status == HTTP_OK || response.status == HTTP_CONFLICT) {
            return SyncWire.decodeUpsert(response.body)
        }
        // Anything else really is a failure -- a 400 for a batch the server would not accept, a 401,
        // a 429 that outlasted the retry budget. requireSuccess throws for all of them; it can never
        // return here, and the call is written this way rather than as a bare `throw` so that adding
        // a new success status is one line and not a restructure.
        return SyncWire.decodeUpsert(requireSuccess(response))
    }

    override suspend fun history(
        credentials: DeviceCredentials,
        blindedId: String,
        limit: Int?,
    ): List<RemoteRecord> {
        requireSafePathSegment(blindedId, "record id")
        require(limit == null || limit in 1..MAX_HISTORY_LIMIT) {
            "the sync server returns between 1 and $MAX_HISTORY_LIMIT versions of a record"
        }
        val query = limit?.let { "limit=$it" }
        val response = sendAuthenticated(credentials, ROUTE_HISTORY) {
            HttpRequest(
                HttpMethod.GET,
                endpoint.resolve("/v1/records/$blindedId/history", query),
            )
        }
        return SyncWire.decodeHistory(requireSuccess(response))
    }

    // ------------------------------------------------------------------------------------------
    // The request pipeline
    // ------------------------------------------------------------------------------------------

    /**
     * Sends one request, honouring `429` with [RetryPolicy] and jitter.
     *
     * The request body is a `ByteArray` and the request is rebuilt identically on each attempt, so
     * a retry is byte-for-byte the same request -- including the same signature over the same `ts`.
     * That is correct while the wait stays inside the server's five-minute freshness window, which
     * is why [RetryPolicy.maxRetryAfterMillis] is a minute rather than an hour.
     *
     * [routeTemplate] is the route as it appears in the route table, never the real path. See
     * [TransportLog].
     */
    private suspend fun send(
        request: HttpRequest,
        routeTemplate: String,
    ): HttpResponse {
        var lastRetryAfter = retryPolicy.defaultRetryAfterMillis
        var attempt = 1
        while (true) {
            val started = clock()
            val response = guardResponseSize(transport.execute(request))
            log.request(request.method.name, routeTemplate, response.status, clock() - started)

            if (response.status != HTTP_TOO_MANY_REQUESTS) return response

            lastRetryAfter = retryAfterMillis(response)
            if (attempt >= retryPolicy.maxAttempts) throw SyncException.RateLimited(lastRetryAfter)
            // Jitter is ADDED to the server's delay, never subtracted from it: `Retry-After` is the
            // earliest the bucket will have refilled, so a shorter wait is a request guaranteed to
            // be refused. The random part is what stops a household's three devices, all woken by
            // the same alarm and all throttled in the same instant, from returning together
            // forever.
            delayer.delay(lastRetryAfter + jitter.extraMillis(lastRetryAfter))
            attempt++
        }
    }

    /**
     * Sends an authenticated request, obtaining a token first and re-handshaking **once** on a
     * `401`.
     *
     * [build] is a function rather than a request because the `Authorization` header differs
     * between the two attempts: the second carries a token the first did not have.
     *
     * The single retry is the whole policy, and it is deliberate (plan §8, F6). A `401` after a
     * fresh handshake is not a stale token -- it is an unknown device, a signature that does not
     * verify, or a device clock far enough out that every signed request looks stale to the server.
     * None of those is fixed by asking again, and a loop here would spend the rate-limit budget
     * discovering that.
     */
    private suspend fun sendAuthenticated(
        credentials: DeviceCredentials,
        routeTemplate: String,
        build: () -> HttpRequest,
    ): HttpResponse {
        val first = send(withBearer(build(), obtainToken(credentials, forceNew = false)), routeTemplate)
        if (first.status != HTTP_UNAUTHORIZED) return first

        val second = send(withBearer(build(), obtainToken(credentials, forceNew = true)), routeTemplate)
        if (second.status == HTTP_UNAUTHORIZED) {
            throw SyncException.Unauthorized(errorCode(second) ?: CODE_UNAUTHORIZED)
        }
        return second
    }

    private fun withBearer(request: HttpRequest, token: String): HttpRequest = HttpRequest(
        method = request.method,
        url = request.url,
        headers = request.headers + (HEADER_AUTHORIZATION to "Bearer $token"),
        body = request.body,
    )

    /**
     * Returns a live bearer token, running the challenge handshake when there is not one.
     *
     * @param forceNew discards whatever is cached first. Used after a `401`, where the cached token
     *   is by definition the one the server just refused.
     */
    private suspend fun obtainToken(credentials: DeviceCredentials, forceNew: Boolean): String =
        sessionLock.withLock {
            if (forceNew) session = null
            session?.takeIf { it.expiresAt - clock() > TOKEN_EXPIRY_MARGIN_MILLIS }?.let {
                return@withLock it.token
            }
            val fresh = handshake(credentials)
            session = fresh
            fresh.token
        }

    /**
     * The two-round-trip session handshake.
     *
     * `POST /v1/session/challenge` yields a single-use nonce; signing
     * `("session", accountId, deviceId, challenge)` and posting it to `POST /v1/session` yields a
     * 24-hour bearer token. It is two round trips because a challenge/response handshake needs two;
     * the design document's single `POST /v1/session` line is implemented as these.
     *
     * The challenge is consumed by the server on lookup, **before** the signature is checked, so a
     * failed attempt burns it. That costs an honest client one extra round trip on the next pass
     * and costs an online guesser the ability to try twice against one nonce -- so this does not
     * retry the handshake on a bad signature. A bad signature means the key or the message encoding
     * is wrong, and trying again with the same key and the same encoding is not a plan.
     */
    private suspend fun handshake(credentials: DeviceCredentials): SyncWire.SessionToken {
        requireWellFormedAccountId(credentials.accountId)
        val challengeResponse = send(
            HttpRequest(
                HttpMethod.POST,
                endpoint.resolve(ROUTE_SESSION_CHALLENGE),
                body = SyncWire.challengeRequest(credentials.accountId, credentials.deviceId),
            ),
            ROUTE_SESSION_CHALLENGE,
        )
        val challenge = SyncWire.decodeChallenge(requireSuccess(challengeResponse))

        val signature = Base64Codec.encodeUrl(
            signer.sign(
                SignedMessage.session(
                    credentials.accountId,
                    credentials.deviceId,
                    challenge.challenge,
                )
            )
        )
        val sessionResponse = send(
            HttpRequest(
                HttpMethod.POST,
                endpoint.resolve(ROUTE_SESSION),
                body = SyncWire.sessionRequest(
                    accountId = credentials.accountId,
                    deviceId = credentials.deviceId,
                    challenge = challenge.challenge,
                    signatureB64 = signature,
                ),
            ),
            ROUTE_SESSION,
        )
        return SyncWire.decodeSession(requireSuccess(sessionResponse))
    }

    // ------------------------------------------------------------------------------------------
    // Status handling
    // ------------------------------------------------------------------------------------------

    /**
     * Returns the response body for a `2xx`, and throws the right [SyncException] for anything else.
     *
     * The two `409`s that are not failures are intercepted by their call sites *before* this is
     * reached, because only the call site knows which `409` it is expecting.
     */
    private fun requireSuccess(response: HttpResponse): ByteArray {
        if (response.status in 200..299) return response.body

        val error = SyncWire.decodeError(response.body)
        val code = error?.code ?: CODE_UNKNOWN
        throw when (response.status) {
            HTTP_UNAUTHORIZED -> SyncException.Unauthorized(code)
            HTTP_FORBIDDEN ->
                if (code == CODE_DEVICE_REVOKED) SyncException.DeviceRevoked
                else SyncException.Server(response.status, code, error?.message.orEmpty())
            HTTP_NOT_FOUND -> SyncException.NotFound(code)
            HTTP_TOO_MANY_REQUESTS -> SyncException.RateLimited(retryAfterMillis(response))
            HTTP_PAYLOAD_TOO_LARGE -> SyncException.RequestTooLarge
            // A 3xx reaches here only because redirects are off in the transport, which is
            // deliberate: a redirect can walk a pinned client to a host the pin does not cover, and
            // no endpoint in this protocol legitimately redirects.
            in 300..399 -> SyncException.Protocol("the sync server redirected; this client does not follow redirects")
            else -> SyncException.Server(response.status, code, error?.message.orEmpty())
        }
    }

    /** The `error` code of a structured error body, or null if the body is not one. */
    private fun errorCode(response: HttpResponse): String? = SyncWire.decodeError(response.body)?.code

    private fun retryAfterMillis(response: HttpResponse): Long {
        val requested = RetryAfterHeader.parseMillis(response.header(HEADER_RETRY_AFTER))
            ?: retryPolicy.defaultRetryAfterMillis
        return requested.coerceAtMost(retryPolicy.maxRetryAfterMillis)
    }

    /** The second half of the response cap. See [maxResponseBytes]. */
    private fun guardResponseSize(response: HttpResponse): HttpResponse {
        if (response.body.size > maxResponseBytes) {
            throw SyncException.ResponseTooLarge(maxResponseBytes)
        }
        return response
    }

    // ------------------------------------------------------------------------------------------
    // Input validation
    // ------------------------------------------------------------------------------------------

    /**
     * Refuses anything that is not base64url text before it becomes part of a URL path.
     *
     * Blinded record IDs and device IDs are the only caller-supplied strings that reach a path. Both
     * are base64url by construction, so anything else is a bug or an attack -- `../` and a `?` are
     * both excluded by this character set, so a traversal attempt is refused here rather than
     * escaped into something that merely looks harmless. The server validates the same shape at its
     * end; this is the client half, and it exists so that a malformed value fails at the call site
     * that produced it.
     */
    private fun requireSafePathSegment(value: String, what: String) {
        require(value.isNotEmpty() && value.length <= MAX_PATH_SEGMENT_CHARS) {
            "$what is empty or too long"
        }
        require(
            value.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }
        ) {
            "$what is not base64url text"
        }
    }

    /**
     * `accountId` is `HKDF(ARK, "manana/sync/v1/account")` rendered as base64url, so it is always
     * exactly [SyncProtocol.ACCOUNT_ID_BYTES] bytes.
     *
     * Checked here because the alternative is a `400 invalid_account_id` from the server for a
     * value the caller could have been told about locally, and because an accountId of the wrong
     * length is a sign the caller derived it from something other than the ARK -- which would name
     * a different account and is not a mistake worth discovering over the network.
     */
    private fun requireWellFormedAccountId(accountId: String) {
        val decoded = Base64Codec.decodeUrl(accountId)
        require(decoded != null && decoded.size == SyncProtocol.ACCOUNT_ID_BYTES) {
            "accountId must be ${SyncProtocol.ACCOUNT_ID_BYTES} bytes of base64url"
        }
    }

    companion object {

        /**
         * The production constructor: a client talking to [endpoint] over [KtorHttpTransport], on
         * whichever engine this platform supplies (OkHttp on Android and the JVM).
         *
         * It exists so that the endpoint cannot be given to the client and the transport
         * separately. They must be the same one -- the transport is where the certificate pin is
         * installed and the pin is scoped to that endpoint's host, so a client whose URLs point
         * somewhere else would be making unpinned requests through a pinned transport that never
         * fires.
         */
        fun create(
            endpoint: ServerEndpoint,
            signer: DeviceSigner,
            labelSealer: DeviceLabelSealer,
            log: TransportLog = TransportLog.NONE,
        ): SyncHttpClient = SyncHttpClient(
            endpoint = endpoint,
            transport = KtorHttpTransport.create(endpoint),
            signer = signer,
            labelSealer = labelSealer,
            log = log,
        )

        // Route templates. These are what reaches a log line; a real path never does.
        private const val ROUTE_HEALTH = "/healthz"
        private const val ROUTE_ACCOUNT = "/v1/account"
        private const val ROUTE_AUTHORIZE = "/v1/devices/authorize"
        private const val ROUTE_SESSION_CHALLENGE = "/v1/session/challenge"
        private const val ROUTE_SESSION = "/v1/session"
        private const val ROUTE_DEVICES = "/v1/devices"
        private const val ROUTE_DEVICE_BY_ID = "/v1/devices/{id}"
        private const val ROUTE_CHANGES = "/v1/changes"
        private const val ROUTE_RECORDS = "/v1/records"
        private const val ROUTE_HISTORY = "/v1/records/{id}/history"

        private const val HEADER_AUTHORIZATION = "Authorization"
        private const val HEADER_RETRY_AFTER = "Retry-After"

        private const val HTTP_OK = 200
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_CONFLICT = 409
        private const val HTTP_PAYLOAD_TOO_LARGE = 413
        private const val HTTP_TOO_MANY_REQUESTS = 429

        // The subset of the server's error codes this client branches on. The rest travel through
        // SyncException.Server as opaque strings, which is enough for a caller to log and for a
        // developer to look up in server/README.md.
        private const val CODE_ACCOUNT_EXISTS = "account_exists"
        private const val CODE_CURSOR_AHEAD = "cursor_ahead_of_server"
        private const val CODE_DEVICE_REVOKED = "device_revoked"
        private const val CODE_UNAUTHORIZED = "unauthorized"
        private const val CODE_UNKNOWN = "unknown"

        /** SEC1 uncompressed P-256: `0x04 ‖ X(32) ‖ Y(32)`. */
        private const val SEC1_UNCOMPRESSED_P256_BYTES = 65

        /** `MANANA_MAX_BATCH_ITEMS`. A larger batch is a `400 batch_too_large`. */
        const val MAX_BATCH_ITEMS = 64

        /** `maxChangesLimit`. A larger `limit` is a `400 invalid_limit`. */
        const val MAX_CHANGES_LIMIT = 500

        /**
         * The page size this client asks for when the caller does not choose one, well under the
         * server's own default of 200.
         *
         * The server's default is sized for a server. This one is sized for a phone that has to
         * decrypt, merge and commit every record on the page inside a single pass, and that is
         * charged for the whole page's memory at once whether or not it finishes.
         */
        const val DEFAULT_CHANGES_LIMIT = 32

        /**
         * `maxHistoryLimit`. The server retains only `historyDepth` (10) versions per record
         * anyway, so asking for more than that returns what exists rather than an error.
         */
        const val MAX_HISTORY_LIMIT = 50

        /** `maxBlindedIdChars` and `revokeDevice`'s own bound, whichever a caller hits first. */
        private const val MAX_PATH_SEGMENT_CHARS = 64

        /**
         * How long before a token's stated expiry it is treated as already gone.
         *
         * A token that expires while the request carrying it is in flight costs a `401`, a
         * handshake and a retry -- survivable, but avoidable for one subtraction. A minute also
         * absorbs the ordinary disagreement between the device's clock and the server's, which is
         * the same disagreement the server's five-minute signature window is sized for.
         */
        private const val TOKEN_EXPIRY_MARGIN_MILLIS = 60_000L
    }
}
