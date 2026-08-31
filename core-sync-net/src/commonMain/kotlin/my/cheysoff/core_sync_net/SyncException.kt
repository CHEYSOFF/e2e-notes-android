package my.cheysoff.core_sync_net

/**
 * Every way a call on [SyncApi] can fail.
 *
 * ## What is deliberately not in here
 *
 * `409 account_exists` and a per-item push `409` are branches of the protocol, not failures, and
 * they are modelled as return values ([ClaimOutcome.AlreadyClaimed], [PushResult.Conflict]). Making
 * them exceptions would push callers into `try`/`catch` control flow for the two outcomes they are
 * most likely to see.
 *
 * ## What the messages may contain
 *
 * Nothing secret, ever. No bearer token, no signature, no account ID, no device ID, no blinded
 * record ID and no envelope byte appears in any message here, because these strings end up in
 * crash reports, `adb logcat` and bug reports pasted into chat windows. The server takes the same
 * position about its own log lines (`server/README.md`, "What the log file contains"), and this is
 * the client half of it. `SyncExceptionSecrecyTest` holds the line.
 *
 * The safe message text the *server* sends in its `{"error","message"}` body is passed through
 * verbatim in [Server.serverMessage], because it is written to be safe -- "The signature did not
 * verify", not "the signature 3045… did not verify".
 */
sealed class SyncException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /**
     * The request never got an answer: DNS, connect, TLS handshake, read timeout, a dropped
     * connection mid-body.
     *
     * Retryable by the caller on a later pass, and deliberately **not** retried here. A transport
     * failure repeated immediately is a transport failure repeated immediately; the sync engine
     * runs again in a minute anyway.
     */
    class Network(message: String, cause: Throwable?) : SyncException(message, cause)

    /**
     * The server's certificate did not match the pin the pairing QR carried.
     *
     * A separate type from [Network] on purpose. This is the one connection failure that must never
     * be retried, never be downgraded and never be presented to the user as "check your
     * connection": it means something is terminating TLS that is not the server the user paired
     * with.
     */
    class PinMismatch(message: String, cause: Throwable?) : SyncException(message, cause)

    /**
     * The response was not something this client understands: not JSON, the wrong shape, a missing
     * field, a base64 field that will not decode, or an invariant this client checks that the
     * response violated.
     *
     * This is what a client and server that disagree about the wire format look like from here,
     * which is why `SyncServerContractTest` exists: it drives the real server so that a
     * disagreement is found by a test rather than by a user.
     */
    class Protocol(message: String, cause: Throwable? = null) : SyncException(message, cause)

    /**
     * A structured `{"error":"<code>","message":"<safe text>"}` the client has no more specific
     * type for -- `malformed_request`, `invalid_envelope`, `batch_too_large` and the rest.
     *
     * Almost all of these are caller errors: a batch that is too big, an `hlc` longer than 128
     * characters, an envelope over 256 KiB. [code] is the server's stable machine-readable string.
     */
    class Server(
        val status: Int,
        val code: String,
        val serverMessage: String,
    ) : SyncException("sync server refused the request: $code (HTTP $status)")

    /**
     * `401`, after the client had already discarded its token, re-run the session handshake and
     * retried the call once.
     *
     * A second `401` is not a stale token. It means the device is unknown to this server, its
     * signature does not verify, or its clock is far enough out that the server calls every signed
     * request stale -- none of which another retry fixes.
     */
    class Unauthorized(val code: String) : SyncException("sync server rejected this device's session")

    /** `403 device_revoked`: this device has been revoked. Nothing it does will work again. */
    object DeviceRevoked : SyncException("this device has been revoked on the account")

    /**
     * `429`, after the client exhausted its retry budget honouring `Retry-After`.
     *
     * [retryAfterMillis] is the last delay the server asked for, **without** the jitter this client
     * adds on top -- a caller scheduling its next pass should add its own spread rather than
     * inheriting one that was already used.
     */
    class RateLimited(val retryAfterMillis: Long) :
        SyncException("sync server is rate limiting this client")

    /**
     * `409 cursor_ahead_of_server` from `GET /v1/changes`.
     *
     * **Halt the engine.** The client's cursor is beyond anything this server has, which means
     * either the server was restored from an older backup or the client is pointed at a different
     * server. Both need a deliberate re-baseline. Resetting the cursor to zero instead is the
     * documented catastrophe (plan §8, F7): against rows that are not dirty it is indistinguishable
     * from "the account is empty", and the next pass is a mass delete.
     */
    class CursorAheadOfServer(val requested: Long) :
        SyncException("the sync cursor is ahead of this server; a re-baseline is required")

    /** `404`. The device, or the record, is not on this account. */
    class NotFound(val code: String) : SyncException("sync server has no such object: $code")

    /**
     * `413`: the request body exceeded the server's cap (4 MiB by default).
     *
     * A caller error, and a recoverable one -- split the batch.
     */
    object RequestTooLarge : SyncException("the request body is larger than the server accepts")

    /**
     * The **response** was larger than this client is willing to hold in memory.
     *
     * The server bounds what it accepts; nothing bounds what a server -- or something impersonating
     * one -- may send back. Without this cap a single response can be an out-of-memory kill, and an
     * out-of-memory kill in the middle of a sync pass is the crash case the plan's §3.3 has to
     * reason about. The limit is enforced twice: while reading the body, so the bytes are never
     * fully buffered, and again on the buffer that comes back, so a substituted transport cannot
     * bypass it.
     */
    class ResponseTooLarge(val limitBytes: Int) :
        SyncException("the sync server's response exceeded $limitBytes bytes")
}
