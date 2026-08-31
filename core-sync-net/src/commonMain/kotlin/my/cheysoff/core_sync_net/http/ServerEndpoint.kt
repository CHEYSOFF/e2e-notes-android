package my.cheysoff.core_sync_net.http

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

        val separator = trimmed.indexOf(SCHEME_SEPARATOR)
        require(separator > 0) { "the sync server URL must be http or https" }
        val scheme = trimmed.substring(0, separator).lowercase()
        require(scheme == "https" || scheme == "http") {
            "the sync server URL must be http or https"
        }

        val afterScheme = trimmed.substring(separator + SCHEME_SEPARATOR.length)
        // A URL with a query or a fragment would silently break every path this client appends to
        // it -- `https://host/?x=1` + `/v1/changes` is not a URL anyone meant to type.
        require(!afterScheme.contains('?') && !afterScheme.contains('#')) {
            "the sync server URL must not carry a query string or a fragment"
        }
        val authority = afterScheme.substringBefore('/')
        // Rejected rather than stripped. A `user:password@` in a sync server URL is a credential
        // stored wherever the URL is stored, and this protocol authenticates with a device key --
        // there is nothing for it to mean here, so accepting it would only hide a mistake.
        require(!authority.contains('@')) {
            "the sync server URL must not carry a username or password"
        }
        val parsedHost = hostOf(authority)
            ?: throw IllegalArgumentException("the sync server URL has no usable host")

        this.baseUrl = trimmed
        this.host = parsedHost
        this.isSecure = scheme == "https"

        require(isSecure || isLoopback(parsedHost)) {
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

        private const val SCHEME_SEPARATOR = "://"

        /**
         * The host part of an authority, without its port, or null if there is not one.
         *
         * ## Why this is parsed by hand
         *
         * It used to be `java.net.URI`, which is JVM-only and therefore not available to a module
         * that has to compile for Apple as well. The two portable alternatives were both worse: a
         * URL type from an HTTP library would make this validation depend on that library's
         * leniency (Ktor's `Url`, for one, invents a scheme for a bare hostname rather than
         * refusing it), and there is nothing in the Kotlin standard library.
         *
         * What is accepted is deliberately narrow, and matches what `java.net.URI` accepted for
         * this project's purposes: a bracketed IPv6 literal, or a run of ASCII letters, digits,
         * `-` and `.`. `URI` rejected a raw space, a non-ASCII character and an underscore in a
         * host too -- the first two as a syntax error, the last by parsing the authority as
         * registry-based and returning a null host, which this class already treated as "no host".
         *
         * The host is returned **with** its brackets for IPv6, which is also what `URI.getHost`
         * did, and is why [isLoopback] tests both spellings of `::1`.
         */
        private fun hostOf(authority: String): String? {
            if (authority.startsWith("[")) {
                val close = authority.indexOf(']')
                if (close < 0) return null
                val literal = authority.substring(0, close + 1)
                // Anything after the bracket must be an explicit port and nothing else.
                if (!isEmptyOrPort(authority.substring(close + 1))) return null
                return if (literal.length > 2) literal else null
            }
            val colon = authority.indexOf(':')
            val host = if (colon < 0) authority else authority.substring(0, colon)
            if (colon >= 0 && !isEmptyOrPort(authority.substring(colon))) return null
            if (host.isEmpty()) return null
            val wellFormed = host.all {
                it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '.'
            }
            return if (wellFormed) host else null
        }

        /** `""` or `":"` followed by at least one digit and nothing else. */
        private fun isEmptyOrPort(tail: String): Boolean = when {
            tail.isEmpty() -> true
            tail[0] != ':' -> false
            else -> tail.length > 1 && tail.drop(1).all { it in '0'..'9' }
        }
    }
}
