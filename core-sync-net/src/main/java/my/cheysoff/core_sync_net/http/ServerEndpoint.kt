package my.cheysoff.core_sync_net.http

import java.net.URI

/**
 * Where the sync server is, and what certificate it is allowed to present.
 *
 * The pairing QR already carries both values -- `ServerHint(url, spkiPinSha256)` in
 * `feature-pairing/.../protocol/PairingWire.kt` -- so a second device learns them from the first
 * and this type is the shape they arrive in.
 *
 * ## The scheme rule
 *
 * `https` is required, with exactly one exception: a loopback host may use `http`. That is not a
 * convenience for tests, it is the honest reading of the server's own deployment model. The server
 * speaks plain HTTP and must sit behind a TLS-terminating proxy (`server/README.md`); it binds
 * `127.0.0.1` by default so that exposing it directly has to be deliberate. So plain HTTP to
 * loopback is the one case where no traffic leaves the machine and there is nothing for TLS to
 * protect, and every other case is a bearer token in the clear.
 *
 * A [spkiPinSha256] on a non-`https` endpoint is rejected rather than ignored, because a pin that
 * is silently not enforced is worse than no pin: it is a security control the user believes they
 * have.
 */
class ServerEndpoint(
    baseUrl: String,
    /**
     * SHA-256 of the server's DER `SubjectPublicKeyInfo`, exactly 32 bytes, or null for "trust the
     * platform's CA set".
     *
     * This is the same 32 bytes `ServerHint.spkiPinSha256` carries, in the same form.
     */
    val spkiPinSha256: ByteArray? = null,
) {

    /** [baseUrl] with any trailing slash removed, so path concatenation cannot double it. */
    val baseUrl: String

    /** Host only, no port -- what a certificate pin is scoped to. */
    val host: String

    /** True when the connection is TLS and a pin can therefore be enforced. */
    val isSecure: Boolean

    init {
        require(spkiPinSha256 == null || spkiPinSha256.size == SPKI_PIN_SIZE_BYTES) {
            "an SPKI pin is a 32-byte SHA-256 digest"
        }

        val trimmed = baseUrl.trim().trimEnd('/')
        require(trimmed.isNotEmpty()) { "the sync server URL is empty" }

        val uri = try {
            URI(trimmed)
        } catch (e: Exception) {
            throw IllegalArgumentException("the sync server URL is not a valid URL", e)
        }
        val scheme = uri.scheme?.lowercase()
        val uriHost = uri.host
        require(scheme == "https" || scheme == "http") {
            "the sync server URL must be http or https"
        }
        require(!uriHost.isNullOrEmpty()) { "the sync server URL has no host" }
        // A URL with a query or a fragment would silently break every path this client appends to
        // it -- `https://host/?x=1` + `/v1/changes` is not a URL anyone meant to type.
        require(uri.query == null && uri.fragment == null) {
            "the sync server URL must not carry a query string or a fragment"
        }

        this.baseUrl = trimmed
        this.host = uriHost
        this.isSecure = scheme == "https"

        require(isSecure || isLoopback(uriHost)) {
            "plain http is only allowed to a loopback address; this server needs https"
        }
        require(spkiPinSha256 == null || isSecure) {
            "a certificate pin cannot be enforced over plain http"
        }
    }

    /**
     * Appends an already-encoded absolute [path] (leading `/`) and an optional query string.
     *
     * There is no encoding here on purpose. Every value this client ever puts in a URL is either a
     * decimal integer or a base64url string whose character set the caller has already checked --
     * see `SyncHttpClient.requireSafePathSegment`. An escaping helper at this layer would suggest
     * unchecked values are acceptable here, and they are not: the one place a caller-supplied string
     * reaches a path is the blinded record ID, and a path-traversal attempt in it should be refused
     * outright rather than encoded into something harmless-looking.
     */
    fun resolve(path: String, query: String? = null): String {
        require(path.startsWith("/")) { "path must be absolute" }
        return if (query.isNullOrEmpty()) baseUrl + path else "$baseUrl$path?$query"
    }

    private fun isLoopback(host: String): Boolean =
        host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"

    companion object {
        /** A SHA-256 digest. Matches `ServerHint.SPKI_PIN_SIZE_BYTES`. */
        const val SPKI_PIN_SIZE_BYTES = 32
    }
}
