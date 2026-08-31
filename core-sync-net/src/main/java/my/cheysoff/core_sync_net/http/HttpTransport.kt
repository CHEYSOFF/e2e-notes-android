package my.cheysoff.core_sync_net.http

/**
 * The seam between "what the sync protocol says" and "how bytes reach a socket".
 *
 * Everything above this interface -- signing, the session handshake, `429` back-off, `409`
 * handling, JSON, cursor invariants -- is plain JVM code with no Android in it and no network in
 * it, so all of it is unit-testable against a fake that returns canned responses. Everything below
 * it is one OkHttp call. That split is the reason this module has real tests for the parts that are
 * easy to get wrong and no tests at all for the part that is not.
 *
 * It is deliberately tiny. There is no interceptor chain, no retry, no header manipulation and no
 * concept of a base URL here: those are protocol decisions and they belong to the client, where
 * they are visible and testable, not to a transport that a reader would have to open to discover
 * them.
 */
fun interface HttpTransport {

    /**
     * Performs one request and returns the whole response.
     *
     * Implementations must not retry, must not follow a redirect that changes the host, and must
     * bound the response body -- see [SyncException.ResponseTooLarge][my.cheysoff.core_sync_net.SyncException.ResponseTooLarge].
     *
     * A failure to reach the server is a
     * [SyncException.Network][my.cheysoff.core_sync_net.SyncException.Network]; a certificate-pin
     * failure is a [SyncException.PinMismatch][my.cheysoff.core_sync_net.SyncException.PinMismatch].
     * An HTTP status the server chose -- including `4xx` and `5xx` -- is a normal return, not a
     * throw: deciding what a `409` means is the client's job.
     */
    suspend fun execute(request: HttpRequest): HttpResponse
}

/** The three verbs the sync protocol uses. */
enum class HttpMethod { GET, POST, DELETE }

/**
 * One outbound request.
 *
 * [headers] carries `Authorization` when the call is authenticated. It is a plain map because
 * nothing here needs multi-valued headers, and because a map is trivially assertable in a test that
 * checks a signature or a token was actually sent.
 */
class HttpRequest(
    val method: HttpMethod,
    /** Absolute URL, already resolved and query-encoded by the caller. */
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    /** UTF-8 JSON for `POST`, null for `GET` and `DELETE`. */
    val body: ByteArray? = null,
)

/**
 * One inbound response.
 *
 * [body] is the complete response body, already bounded by the transport. It is a `ByteArray`
 * rather than a stream so that the client can read it more than once -- for example, to try the
 * success shape and then fall back to the error shape -- without a second network read being
 * possible.
 */
class HttpResponse(
    val status: Int,
    private val headers: Map<String, String>,
    val body: ByteArray,
) {
    /**
     * Header lookup, case-insensitively.
     *
     * RFC 9110 makes field names case-insensitive and real proxies do change their case. A
     * case-sensitive lookup for `Retry-After` against a proxy that wrote `retry-after` is a rate
     * limit silently ignored, which is precisely the herd this client is written to avoid.
     */
    fun header(name: String): String? {
        val wanted = name.lowercase()
        for ((key, value) in headers) if (key.lowercase() == wanted) return value
        return null
    }
}
