package my.cheysoff.core_sync_net.http

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import my.cheysoff.core_sync_net.SyncException
import kotlin.coroutines.cancellation.CancellationException
import io.ktor.http.HttpMethod as KtorHttpMethod

/**
 * The one place in this application that opens a socket.
 *
 * ## One client, a different engine underneath
 *
 * This file is the whole client: it builds the request, bounds the response and maps a transport
 * failure onto a [SyncException]. It names no engine. The engine -- what actually resolves DNS,
 * completes a TLS handshake and enforces the certificate pin -- arrives from [createSyncEngine],
 * which is `expect`/`actual`: OkHttp on Android and the JVM, and Darwin on an Apple target when one
 * is added.
 *
 * That split is the point of using Ktor here at all. The alternative was a second, hand-written
 * HTTP client for Apple, and this repository has already shipped one bug from two implementations
 * whose only job was to agree with each other (the two HKDFs). A second client that had to agree
 * with this one about redirects, retries, response bounds and which failure is a pin mismatch would
 * be exactly that shape again.
 *
 * ## Android's network stack did not change
 *
 * The JVM/Android actual hands Ktor a fully built [okhttp3.OkHttpClient] through the OkHttp engine's
 * `preconfigured` hook, configured with the same calls as before Ktor was introduced. So the TLS
 * behaviour, the connection pool, the certificate pinning and the timeouts on Android are OkHttp's,
 * unchanged; Ktor sits above the socket, not on it. See `SyncEngine.jvmCommon.kt`.
 *
 * ## What is switched off, and why
 *
 * - **Redirects**, at both layers. `followRedirects = false` here disables Ktor's own redirect
 *   plugin, and the OkHttp actual sets `followRedirects`/`followSslRedirects` false as well. A
 *   pinned client that follows a redirect can be walked to a host the pin does not cover, and the
 *   sync protocol has no endpoint that legitimately redirects. A `3xx` therefore arrives at the
 *   client as a `3xx`, which it treats as a protocol error.
 * - **`expectSuccess`.** Off, so a `4xx` or `5xx` is an ordinary return rather than a throw. That is
 *   [HttpTransport]'s contract: deciding what a `409` means is the client's job, and a `409` on
 *   push is data.
 * - **Retry on connection failure**, in the engine. Retrying is a policy this module makes
 *   deliberately and visibly, in `Backoff`, driven by the server's `Retry-After`. Two independent
 *   retry layers produce a delay schedule nobody can predict, which is how a back-off that was meant
 *   to protect a small VPS turns into the thing that overwhelms it.
 * - **Cookies, cache, content negotiation, logging.** None is installed. A cached
 *   `GET /v1/changes` would return a stale page and advance a cursor past records that were never
 *   seen; a logging plugin would put URLs and bearer tokens somewhere [TransportLog] deliberately
 *   cannot.
 */
class KtorHttpTransport private constructor(
    private val client: HttpClient,
    private val maxResponseBytes: Int,
) : HttpTransport {

    override suspend fun execute(request: HttpRequest): HttpResponse {
        try {
            return client.prepareRequest {
                method = request.method.toKtor()
                url(request.url)
                for ((name, value) in request.headers) header(name, value)
                // A body on a GET is not expressible in this protocol, so the only question is
                // whether a POST with nothing to say still sends the empty object. It does, and with
                // the JSON content type, because that is what the pre-Ktor transport sent.
                if (request.method == HttpMethod.POST || request.body != null) {
                    contentType(JSON_CONTENT_TYPE)
                    setBody(request.body ?: EMPTY_BODY)
                }
            }.execute { response ->
                // Read at most one byte more than the cap. `readRemaining` stops at the limit, so an
                // oversized body is detected without the rest of it ever being read -- the
                // client-side mirror of the server's own `readBounded`, which is written with the
                // same two calls.
                val bytes = response.bodyAsChannel()
                    .readRemaining(maxResponseBytes.toLong() + 1)
                    .readByteArray()
                if (bytes.size > maxResponseBytes) {
                    throw SyncException.ResponseTooLarge(maxResponseBytes)
                }
                HttpResponse(
                    status = response.status.value,
                    headers = response.headers.entries()
                        .associate { (name, values) -> name to values.firstOrNull().orEmpty() },
                    body = bytes,
                )
            }
        } catch (e: SyncException) {
            // The size cap above throws one of these from inside the response block. It is this
            // module's own verdict and must not be re-read as a connection failure.
            throw e
        } catch (e: CancellationException) {
            // A cancelled sync pass is not a network error and must stay cancelled: swallowing this
            // into SyncException.Network would make a caller retry work the caller has abandoned.
            throw e
        } catch (e: Throwable) {
            throw classifyTransportFailure(e)
        }
    }

    private fun HttpMethod.toKtor(): KtorHttpMethod = when (this) {
        HttpMethod.GET -> KtorHttpMethod.Get
        HttpMethod.POST -> KtorHttpMethod.Post
        HttpMethod.DELETE -> KtorHttpMethod.Delete
    }

    companion object {

        /**
         * Spelled out rather than built from [ContentType.Application.Json] plus a charset, so that
         * the header is the same string the pre-Ktor transport sent. The server reads the body as
         * bytes and never looks at this header, so the value is not load-bearing -- but "the request
         * is byte-identical to the one that already works" is worth more here than tidiness.
         */
        private val JSON_CONTENT_TYPE = ContentType.parse("application/json; charset=utf-8")

        private val EMPTY_BODY = ByteArray(0)

        /**
         * The largest response body this client will hold in memory.
         *
         * Sized from the protocol rather than picked: the biggest legitimate response is a full
         * page of `GET /v1/changes`, which is `limit` records of at most `MANANA_MAX_ENVELOPE_BYTES`
         * (256 KiB) each, plus base64 expansion. The client asks for a page of
         * `SyncHttpClient.DEFAULT_CHANGES_LIMIT` (32) records, so the worst legitimate case is on
         * the order of 32 × 256 KiB × 4/3 ≈ 11 MiB. 16 MiB leaves headroom for JSON overhead
         * without being an amount of memory an Android process can absorb by accident.
         *
         * Note this is a cap on **one response**, not on a sync pass. Pulling a large account still
         * works; it takes more pages.
         */
        const val DEFAULT_MAX_RESPONSE_BYTES = 16 * 1024 * 1024

        /**
         * Builds a transport for one [endpoint], enforcing its certificate pin if it has one.
         *
         * The pin is scoped to the endpoint's host by [createSyncEngine], which is what makes it
         * meaningful: a pin that applied to every host would be enforced against whichever host a
         * redirect reached, and redirects are off for the same reason.
         */
        fun create(
            endpoint: ServerEndpoint,
            maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
        ): KtorHttpTransport {
            val client = HttpClient(createSyncEngine(endpoint)) {
                expectSuccess = false
                followRedirects = false
            }
            return KtorHttpTransport(client, maxResponseBytes)
        }
    }
}
