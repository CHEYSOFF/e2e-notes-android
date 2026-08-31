package my.cheysoff.core_sync_net

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import my.cheysoff.core_sync_net.http.HttpMethod
import my.cheysoff.core_sync_net.http.HttpRequest
import my.cheysoff.core_sync_net.http.KtorHttpTransport
import my.cheysoff.core_sync_net.http.ServerEndpoint
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * [KtorHttpTransport] against a real socket, without the sync server.
 *
 * ## Why this exists, given `SyncServerContractTest`
 *
 * The contract test is the one that catches a disagreement with the server, and it stays the
 * authority on that. But it is **opt-in**: a plain `./gradlew test` skips it, and before this file
 * existed that meant the everyday suite exercised `KtorHttpTransport` with nothing at all. Every
 * other test in this module talks to `FakeHttpTransport`, which is the seam *above* this class, so a
 * transport that dropped the `Authorization` header, ignored the response status or truncated a body
 * would leave the hermetic suite entirely green.
 *
 * That was measured rather than assumed. Breaking this class in seven different ways -- dropping
 * request headers, dropping the request body, capping the response at 64 bytes, reporting every
 * status as 200, discarding response headers, sending `DELETE` as `POST`, following redirects --
 * failed only contract-test methods, or nothing at all. Those are the seven properties asserted
 * below.
 *
 * ## Why the JDK's own HTTP server
 *
 * `com.sun.net.httpserver.HttpServer` ships with the JDK, so this adds no dependency and starts in
 * milliseconds. It is deliberately not the sync server: what is under test here is the layer that
 * turns an [HttpRequest] into bytes on a socket and a socket back into an
 * [HttpResponse][my.cheysoff.core_sync_net.http.HttpResponse], and pointing it at the real server
 * would only re-test what the contract test already covers.
 *
 * **This is still not a test of TLS.** The certificate pin, and therefore
 * [SyncException.PinMismatch], needs a TLS server with a certificate this test controls; that gap is
 * real and `ServerEndpointTest` says so too.
 */
class KtorHttpTransportTest {

    private lateinit var server: HttpServer
    private lateinit var endpoint: ServerEndpoint

    /** What the last request carried, captured by [handle]. */
    private var seenMethod: String? = null
    private var seenPath: String? = null
    private var seenQuery: String? = null
    private var seenAuthorization: String? = null
    private var seenBody: ByteArray = ByteArray(0)

    /** What the next response should be. */
    private var replyStatus = 200
    private var replyBody: ByteArray = "{}".toByteArray()
    private var replyHeaders: Map<String, String> = emptyMap()

    @Before
    fun startServer() {
        // Port 0 asks the OS for a free one, and the server binds it before this method returns --
        // so unlike picking a port and hoping, there is no window for something else to take it.
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/", ::handle)
        server.start()
        endpoint = ServerEndpoint("http://127.0.0.1:${server.address.port}")
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    private fun handle(exchange: HttpExchange) {
        seenMethod = exchange.requestMethod
        seenPath = exchange.requestURI.path
        seenQuery = exchange.requestURI.query
        seenAuthorization = exchange.requestHeaders.getFirst("Authorization")
        seenBody = exchange.requestBody.readBytes()

        for ((name, value) in replyHeaders) exchange.responseHeaders.add(name, value)
        exchange.sendResponseHeaders(replyStatus, replyBody.size.toLong())
        exchange.responseBody.write(replyBody)
        exchange.close()
    }

    private fun transport(maxResponseBytes: Int = KtorHttpTransport.DEFAULT_MAX_RESPONSE_BYTES) =
        KtorHttpTransport.create(endpoint, maxResponseBytes)

    // ------------------------------------------------------------------------------------------
    // The request
    // ------------------------------------------------------------------------------------------

    /**
     * The `Authorization` header is the whole of this protocol's authentication on an authenticated
     * call, and it is set by `SyncHttpClient` and carried by this class. A transport that built the
     * request without the caller's headers would leave every fake-transport test green and every
     * real request a `401`.
     */
    @Test
    fun `a POST carries its method, path, query, headers and body to the server`() = runBlocking {
        transport().execute(
            HttpRequest(
                method = HttpMethod.POST,
                url = endpoint.resolve("/v1/records", "since=4&limit=32"),
                headers = mapOf("Authorization" to "Bearer test-token"),
                body = """{"items":[]}""".toByteArray(),
            )
        )

        assertEquals("POST", seenMethod)
        assertEquals("/v1/records", seenPath)
        assertEquals("since=4&limit=32", seenQuery)
        assertEquals("Bearer test-token", seenAuthorization)
        assertEquals("""{"items":[]}""", seenBody.decodeToString())
    }

    /**
     * `DELETE` is one endpoint -- `DELETE /v1/devices/{id}` -- and it is revocation, so a verb sent
     * as anything else is a device that cannot be signed out.
     */
    @Test
    fun `each verb reaches the server as itself`() = runBlocking {
        transport().execute(HttpRequest(HttpMethod.GET, endpoint.resolve("/healthz")))
        assertEquals("GET", seenMethod)

        transport().execute(HttpRequest(HttpMethod.DELETE, endpoint.resolve("/v1/devices/abc")))
        assertEquals("DELETE", seenMethod)
        assertEquals("/v1/devices/abc", seenPath)
    }

    // ------------------------------------------------------------------------------------------
    // The response
    // ------------------------------------------------------------------------------------------

    /**
     * A `4xx` or `5xx` is a **return**, not a throw -- [HttpTransport][my.cheysoff.core_sync_net.http.HttpTransport]
     * says so, because deciding what a `409` means is the client's job and a `409` on push is data.
     * A transport that reported every response as `200` would turn a conflict into an accepted write.
     */
    @Test
    fun `the server's status and body come back verbatim`() = runBlocking {
        replyStatus = 409
        replyBody = """{"error":"account_exists"}""".toByteArray()

        val response = transport().execute(HttpRequest(HttpMethod.GET, endpoint.resolve("/healthz")))

        assertEquals(409, response.status)
        assertEquals("""{"error":"account_exists"}""", response.body.decodeToString())
    }

    /**
     * `Retry-After` is the only response header this client reads, and reading it is what stops a
     * `429` from becoming a five-second guess. It is looked up case-insensitively, so this replies
     * in a case the client does not ask for.
     */
    @Test
    fun `response headers survive, and are found whatever their case`() = runBlocking {
        replyHeaders = mapOf("retry-after" to "7")

        val response = transport().execute(HttpRequest(HttpMethod.GET, endpoint.resolve("/healthz")))

        assertEquals("7", response.header("Retry-After"))
        assertNull(response.header("X-Absent"))
    }

    /** A body of exactly the cap is legitimate and must not be refused. */
    @Test
    fun `a response at the cap is returned whole`() = runBlocking {
        replyBody = ByteArray(64) { (it * 7).toByte() }

        val response = transport(maxResponseBytes = 64)
            .execute(HttpRequest(HttpMethod.GET, endpoint.resolve("/healthz")))

        assertArrayEquals(replyBody, response.body)
    }

    /**
     * One byte over is refused. Without this cap a single response is an out-of-memory kill, and the
     * failure has to be a refusal rather than a silent truncation -- a truncated change page would
     * be JSON this client could not parse, if it were lucky, and a short page it believed, if it
     * were not.
     */
    @Test
    fun `a response over the cap is refused rather than truncated`() = runBlocking {
        replyBody = ByteArray(65)

        try {
            transport(maxResponseBytes = 64)
                .execute(HttpRequest(HttpMethod.GET, endpoint.resolve("/healthz")))
            fail("a body over the cap must be refused")
        } catch (e: SyncException.ResponseTooLarge) {
            assertEquals(64, e.limitBytes)
        }
    }

    /**
     * A redirect arrives as a `3xx`, it is not followed.
     *
     * This is the property the certificate pin rests on: a client that follows a redirect can be
     * walked to a host the pin does not cover.
     *
     * Redirects are switched off in two places, and this test only catches one of them. Turning
     * Ktor's `followRedirects` back on fails this test; turning the OkHttp engine's
     * `followRedirects(false)` back on does not, because Ktor's own switch stops it first. That is
     * what the engine-level call is: defence in depth against a Ktor default changing, and nothing a
     * test can observe while the Ktor switch is doing its job.
     */
    @Test
    fun `a redirect is returned as a redirect and not followed`() = runBlocking {
        replyStatus = 302
        replyBody = ByteArray(0)
        replyHeaders = mapOf("Location" to "http://127.0.0.1:${server.address.port}/elsewhere")

        val response = transport().execute(HttpRequest(HttpMethod.GET, endpoint.resolve("/healthz")))

        assertEquals(302, response.status)
        assertEquals("the redirect must not have been followed", "/healthz", seenPath)
    }

    // ------------------------------------------------------------------------------------------
    // Failure
    // ------------------------------------------------------------------------------------------

    /**
     * A server that is not there is [SyncException.Network] and not some engine-specific exception
     * escaping the module. That mapping is per-platform (`classifyTransportFailure`), so it is worth
     * one test that it is wired up at all.
     */
    @Test
    fun `an unreachable server is a Network failure`() = runBlocking {
        // Stopped first, so the port is known to be free and known to refuse.
        val port = server.address.port
        server.stop(0)

        try {
            KtorHttpTransport.create(ServerEndpoint("http://127.0.0.1:$port"))
                .execute(HttpRequest(HttpMethod.GET, "http://127.0.0.1:$port/healthz"))
            fail("a refused connection must be a SyncException")
        } catch (e: SyncException) {
            org.junit.Assert.assertTrue("expected Network, got $e", e is SyncException.Network)
        }
        // @After stops it again; HttpServer.stop is idempotent.
        Unit
    }
}
