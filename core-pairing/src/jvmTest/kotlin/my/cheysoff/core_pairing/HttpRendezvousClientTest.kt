package my.cheysoff.core_pairing

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import my.cheysoff.core_pairing.protocol.CollectResult
import my.cheysoff.core_pairing.protocol.DepositResult
import my.cheysoff.core_pairing.protocol.HttpRendezvousClient
import my.cheysoff.core_pairing.protocol.PairingProtocol
import my.cheysoff.core_pairing.protocol.RendezvousProtocol
import my.cheysoff.core_pairing.protocol.RendezvousSlot
import my.cheysoff.core_pairing.protocol.RendezvousUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.Base64

/**
 * [HttpRendezvousClient] against a real socket.
 *
 * The server here is the JDK's own `com.sun.net.httpserver`, not a mock of `HttpURLConnection`: the
 * things worth checking on this class are which URL it builds, which method it uses, what it does
 * with each status code and how it behaves when a body is hostile — and every one of those is a
 * property of bytes on a socket rather than of a mock's call log. The real server's own contract is
 * covered by `server/`'s suite and, at the end, by an actual pairing between the two.
 */
class HttpRendezvousClientTest {

    private val sid = ByteArray(PairingProtocol.SID_SIZE_BYTES) { (it + 1).toByte() }
    private val sidPath = RendezvousProtocol.encodeSid(sid)

    // -----------------------------------------------------------------------------------------
    // Deposit
    // -----------------------------------------------------------------------------------------

    @Test
    fun aDepositPostsTheBlobToThePathForItsSid() = withServer { exchange ->
        assertEquals("POST", exchange.requestMethod)
        assertEquals("${RendezvousProtocol.PATH_PREFIX}$sidPath", exchange.requestURI.path)
        val body = exchange.requestBody.readBytes().decodeToString()
        // The prefix is dropped on the wire: over HTTP the URL already says what these bytes are,
        // and a pure base64url field is the only thing the server checks it for.
        assertTrue(body.contains(""""${RendezvousProtocol.FIELD_SEALED}""""))
        assertTrue(!body.contains(PairingProtocol.QR_PREFIX))
        respond(exchange, 201, """{"expiresAt":1234}""")
    }.let { client ->
        val result = client.deposit(sid, RendezvousSlot.BUNDLE, sealCode("hello-world-payload"))
        assertTrue(result is DepositResult.Deposited)
        assertEquals(1234L, (result as DepositResult.Deposited).expiresAt)
    }

    @Test
    fun aConflictIsReportedAsAlreadyDeposited() = withServer { exchange ->
        respond(exchange, 409, """{"error":"pairing_exists","message":"already left"}""")
    }.let { client ->
        assertTrue(client.deposit(sid, RendezvousSlot.BUNDLE, sealCode("x")) is DepositResult.AlreadyDeposited)
    }

    /** The server writes its `message` for a person, so it is preferred over the status code. */
    @Test
    fun aRefusalCarriesTheServersOwnExplanation() = withServer { exchange ->
        respond(exchange, 503, """{"error":"pairing_capacity","message":"Too many pairings."}""")
    }.let { client ->
        val result = client.deposit(sid, RendezvousSlot.BUNDLE, sealCode("x"))
        assertEquals("Too many pairings.", (result as DepositResult.Refused).detail)
    }

    @Test
    fun aDepositToANonexistentServerIsUnreachableRatherThanThrown() {
        val client = HttpRendezvousClient(
            // Port 1 on loopback: reserved, and nothing in this environment binds it.
            RendezvousUrl.parse("http://127.0.0.1:1")!!,
            timeoutMillis = 500,
        )
        assertTrue(client.deposit(sid, RendezvousSlot.BUNDLE, sealCode("x")) is DepositResult.Unreachable)
    }

    // -----------------------------------------------------------------------------------------
    // Collect
    // -----------------------------------------------------------------------------------------

    @Test
    fun aCollectPutsThePrefixBackSoTheSessionSeesAQrPayload() = withServer { exchange ->
        assertEquals("GET", exchange.requestMethod)
        respond(exchange, 200, """{"sealed":"${blob("round-trip")}"}""")
    }.let { client ->
        val result = client.collect(sid, RendezvousSlot.BUNDLE)
        assertEquals(sealCode("round-trip"), (result as CollectResult.Collected).sealCode)
    }

    @Test
    fun aMissingBlobIsPendingRatherThanAFailure() = withServer { exchange ->
        respond(exchange, 404, """{"error":"no_pairing","message":"Nothing is waiting."}""")
    }.let { client ->
        assertTrue(client.collect(sid, RendezvousSlot.BUNDLE) is CollectResult.Pending)
    }

    /**
     * A 429 keeps the poller on its schedule.
     *
     * Reported as unreachable rather than unusable on purpose: backing off is the loop's job, and
     * aborting a pairing because a server briefly said "slow down" would be the wrong failure.
     */
    @Test
    fun rateLimitingIsRetriableRatherThanTerminal() = withServer { exchange ->
        respond(exchange, 429, """{"error":"rate_limited","message":"slow down"}""")
    }.let { client ->
        assertTrue(client.collect(sid, RendezvousSlot.BUNDLE) is CollectResult.Unreachable)
    }

    /**
     * A body that is not a plausible blob is terminal, and is refused **before** the prefix goes
     * back on it.
     *
     * A gigantic string is the case that matters: without the length check this would be handed to
     * a base64 decoder and then to a frame parser, on a machine that is polling in a loop.
     */
    @Test
    fun anImplausibleBodyIsTerminalRatherThanParsed() {
        for (body in listOf(
            """{"sealed":"not base64!!"}""",
            """{"sealed":""}""",
            """{"nothing":"here"}""",
            "not json at all",
            """{"sealed":"${"A".repeat(400_000)}"}""",
        )) {
            val client = withServer { exchange -> respond(exchange, 200, body) }
            val result = client.collect(sid, RendezvousSlot.BUNDLE)
            assertTrue("expected Unusable for $body, got $result", result is CollectResult.Unusable)
        }
    }

    /**
     * A response larger than the client's own cap is truncated rather than read whole.
     *
     * The host is named in a QR code, so an unbounded read of an unbounded body is how a poll loop
     * becomes an out-of-memory. Truncation shows up as a parse failure, which is the honest outcome.
     */
    @Test
    fun anEnormousResponseIsBoundedAndReportedAsUnusable() = withServer { exchange ->
        respond(exchange, 200, """{"sealed":"""" + "A".repeat(2_000_000) + """"}""")
    }.let { client ->
        assertTrue(client.collect(sid, RendezvousSlot.BUNDLE) is CollectResult.Unusable)
    }

    /**
     * A redirect is not followed.
     *
     * The only thing a redirect on this route can do is move a sealed account bundle to a host the
     * user never saw and never approved.
     */
    @Test
    fun aRedirectIsNotFollowed() {
        var redirected = false
        val client = withServer { exchange ->
            if (exchange.requestURI.path.startsWith(RendezvousProtocol.PATH_PREFIX)) {
                exchange.responseHeaders.add("Location", "/elsewhere")
                respond(exchange, 302, "")
            } else {
                redirected = true
                respond(exchange, 200, """{"sealed":"${blob("stolen")}"}""")
            }
        }
        assertTrue(client.collect(sid, RendezvousSlot.BUNDLE) is CollectResult.Unusable)
        assertTrue("the client followed a redirect", !redirected)
    }

    // -----------------------------------------------------------------------------------------

    /**
     * Starts a one-request-per-call HTTP server on a free loopback port and returns a client
     * pointed at it.
     *
     * The server is left running for the life of the test JVM rather than shut down: each call
     * takes a fresh ephemeral port, the handler is a closure over the assertions that call wants,
     * and a stop() that races the client's own connection teardown is a flaky test for no benefit.
     */
    // -----------------------------------------------------------------------------------------
    // The two slots
    // -----------------------------------------------------------------------------------------

    /**
     * The reply slot is a **different URL**, and this is the test that says so.
     *
     * Nothing else pins it: an in-memory drop keyed on the slot behaves correctly whatever the
     * suffix is, and both ends of a pairing would still agree with each other if the two slots
     * collapsed onto one path. What would break is the pairing itself -- the account device's
     * bundle would land where the phone's reply already is and the deposit would come back as a
     * conflict -- and it would break only against a real server, which is exactly the kind of
     * failure that is expensive to find later.
     */
    @Test
    fun theReplySlotIsASeparateResource() = withServer { exchange ->
        assertEquals("POST", exchange.requestMethod)
        assertEquals("${RendezvousProtocol.PATH_PREFIX}$sidPath/reply", exchange.requestURI.path)
        respond(exchange, 201, """{"expiresAt":1}""")
    }.let { client ->
        assertTrue(
            client.deposit(sid, RendezvousSlot.REPLY, sealCode("eb-and-db")) is DepositResult.Deposited
        )
    }

    @Test
    fun collectingTheReplySlotAsksTheSamePath() = withServer { exchange ->
        assertEquals("GET", exchange.requestMethod)
        assertEquals("${RendezvousProtocol.PATH_PREFIX}$sidPath/reply", exchange.requestURI.path)
        respond(exchange, 200, """{"sealed":"${blob("answer")}"}""")
    }.let { client ->
        val result = client.collect(sid, RendezvousSlot.REPLY)
        assertEquals(sealCode("answer"), (result as CollectResult.Collected).sealCode)
    }

    /**
     * The bundle slot keeps the bare path it has always had.
     *
     * Stated as its own assertion rather than left implicit in the deposit tests above, because the
     * scanned direction's requests must be byte-identical to what they were: a server that already
     * speaks this protocol has to keep working against a client that has learned a second slot.
     */
    @Test
    fun theTwoSlotsDoNotShareAPath() {
        assertEquals("", RendezvousSlot.BUNDLE.pathSuffix)
        assertEquals("/reply", RendezvousSlot.REPLY.pathSuffix)
        assertTrue(RendezvousSlot.BUNDLE.pathSuffix != RendezvousSlot.REPLY.pathSuffix)
    }

    /**
     * A reply body is bounded far more tightly than a bundle body.
     *
     * The reply slot is the one an unauthenticated stranger writes to on the account device's
     * behalf, so its cap is the one worth being exact about; a bundle-sized body arriving there is
     * refused before anything downstream allocates it.
     */
    @Test
    fun anOversizedReplyIsRefusedByTheClient() = withServer { exchange ->
        respond(exchange, 200, """{"sealed":"${blob("x".repeat(4096))}"}""")
    }.let { client ->
        val result = client.collect(sid, RendezvousSlot.REPLY)
        assertTrue("a bundle-sized body is not a reply", result is CollectResult.Unusable)
    }

    private fun withServer(handler: (HttpExchange) -> Unit): HttpRendezvousClient {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                handler(exchange)
            } catch (t: Throwable) {
                // An assertion failure inside the handler runs on the server's thread, where JUnit
                // would never see it. Answering 599 turns it into a client-side failure the test
                // does see -- as an Unusable with an unmistakable status in it.
                runCatching { respond(exchange, 599, """{"message":"handler threw: $t"}""") }
            } finally {
                exchange.close()
            }
        }
        server.executor = null
        server.start()
        return HttpRendezvousClient(
            RendezvousUrl.parse("http://127.0.0.1:${server.address.port}")!!,
            timeoutMillis = 5_000,
        )
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
        exchange.responseBody.flush()
    }

    private fun blob(text: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())

    private fun sealCode(text: String): String = PairingProtocol.QR_PREFIX + blob(text)
}
