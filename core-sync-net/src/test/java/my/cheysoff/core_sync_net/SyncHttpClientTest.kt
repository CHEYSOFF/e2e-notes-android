package my.cheysoff.core_sync_net

import kotlinx.coroutines.test.runTest
import my.cheysoff.core_sync_net.http.RetryPolicy
import my.cheysoff.core_sync_net.http.ServerEndpoint
import my.cheysoff.core_sync_net.http.TransportLog
import my.cheysoff.core_sync_net.wire.Base64Codec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [SyncHttpClient] against a scripted transport.
 *
 * These tests cover the decisions, not the sockets: which bytes get signed, when a token is
 * re-fetched, how long a `429` is honoured for, and which HTTP statuses are data rather than
 * failures. The complementary test is `SyncServerContractTest`, which runs the real server -- a
 * fake transport can only ever agree with whatever this client believes, and the two together are
 * what make a client/server disagreement fail a test.
 */
class SyncHttpClientTest {

    private val endpoint = ServerEndpoint("http://127.0.0.1:8080")
    private val signer = TestDeviceSigner()
    private val transport = FakeHttpTransport()
    private val delayer = RecordingDelayer()

    /** 16 bytes of base64url, which is the only shape the protocol gives an account ID. */
    private val accountId = Base64Codec.encodeUrl(ByteArray(16) { it.toByte() })
    private val credentials = DeviceCredentials(accountId, "device-1")

    private fun client(
        retryPolicy: RetryPolicy = RetryPolicy(),
        maxResponseBytes: Int = 1024 * 1024,
        log: TransportLog = TransportLog.NONE,
    ) = SyncHttpClient(
        endpoint = endpoint,
        transport = transport,
        signer = signer,
        clock = { FIXED_NOW },
        retryPolicy = retryPolicy,
        delayer = delayer,
        jitter = FixedJitter(JITTER_MILLIS),
        log = log,
        maxResponseBytes = maxResponseBytes,
    )

    /** Queues the two responses of a successful session handshake. */
    private fun enqueueHandshake(token: String = "tok-abc") {
        transport.enqueue(200, """{"challenge":"chal-1","expiresAt":${FIXED_NOW + 120_000}}""")
        transport.enqueue(200, """{"token":"$token","expiresAt":${FIXED_NOW + 86_400_000}}""")
    }

    // ------------------------------------------------------------------------------------------
    // Enrolment
    // ------------------------------------------------------------------------------------------

    @Test
    fun `a claim sends a signature over the canonical message and returns the server device id`() =
        runTest {
            transport.enqueue(201, """{"accountId":"$accountId","deviceId":"dev-9","createdAt":7}""")

            val outcome = client().claimAccount(accountId, "Pixel 7")

            val claimed = outcome as ClaimOutcome.Claimed
            assertEquals("dev-9", claimed.deviceId)
            assertEquals(7L, claimed.createdAt)

            val body = transport.bodies().single()
            assertTrue("the claim must carry a signature", body.contains("\"signature\""))
            assertEquals("the claim must be signed exactly once", 1, signer.signCount)
        }

    /**
     * The mutation guard for the signature. Breaking the client so that it signs nothing -- or
     * signs the wrong bytes -- has to fail a test with a name, and this is that test: it rebuilds
     * the canonical message from the values in the body and verifies the transmitted signature
     * against the device's own public key.
     */
    @Test
    fun `the transmitted claim signature verifies over the canonical signed message`() = runTest {
        transport.enqueue(201, """{"accountId":"$accountId","deviceId":"dev-9","createdAt":7}""")

        client().claimAccount(accountId, "Pixel 7")

        val body = transport.bodies().single()
        val publicKeyB64 = body.jsonString("devicePublicKey")
        val signatureB64 = body.jsonString("signature")
        val ts = body.jsonNumber("ts")

        val message = my.cheysoff.core_sync_net.auth.SignedMessage.claim(accountId, publicKeyB64, ts)
        assertTrue(
            "the signature in the request body must verify over the canonical claim message",
            verifyP256(signer.publicKeySec1(), message, Base64Codec.decodeUrl(signatureB64)!!),
        )
        assertArrayEquals(
            "the key that was signed must be the key that was sent",
            signer.publicKeySec1(),
            Base64Codec.decodeUrl(publicKeyB64),
        )
    }

    @Test
    fun `a 409 account_exists is a normal outcome and not a failure`() = runTest {
        transport.enqueue(409, """{"error":"account_exists","message":"That account is already claimed."}""")

        assertTrue(client().claimAccount(accountId, "Pixel 7") is ClaimOutcome.AlreadyClaimed)
    }

    @Test
    fun `authorize signs over the joining device's key, not its own`() = runTest {
        val joining = TestDeviceSigner()
        transport.enqueue(201, """{"deviceId":"dev-2","createdAt":11}""")

        val enrolled = client().authorizeDevice(
            accountId = accountId,
            voucherDeviceId = "dev-1",
            newPublicKey = joining.publicKeySec1(),
            deviceLabel = "Tablet",
        )

        assertEquals("dev-2", enrolled.deviceId)
        val body = transport.bodies().single()
        assertArrayEquals(
            "the vouched-for key must be the joining device's",
            joining.publicKeySec1(),
            Base64Codec.decodeUrl(body.jsonString("newPublicKey")),
        )
        val message = my.cheysoff.core_sync_net.auth.SignedMessage.authorize(
            accountId,
            body.jsonString("newPublicKey"),
            body.jsonNumber("ts"),
        )
        assertTrue(
            "the vouching signature must verify under the VOUCHER's key",
            verifyP256(signer.publicKeySec1(), message, Base64Codec.decodeUrl(body.jsonString("signature"))!!),
        )
    }

    // ------------------------------------------------------------------------------------------
    // Sessions
    // ------------------------------------------------------------------------------------------

    @Test
    fun `the first authenticated call runs the handshake and sends the token as a bearer`() =
        runTest {
            enqueueHandshake(token = "tok-abc")
            transport.enqueue(200, """{"devices":[]}""")

            client().listDevices(credentials)

            assertEquals(3, transport.requests.size)
            assertTrue(transport.requests[0].url.endsWith("/v1/session/challenge"))
            assertTrue(transport.requests[1].url.endsWith("/v1/session"))
            assertEquals("Bearer tok-abc", transport.requests[2].headers["Authorization"])
        }

    /**
     * The signature that gates every authenticated call, checked the same way the claim's is.
     *
     * Added after a mutation run found the hole: deleting the signature from the session request
     * failed only `SyncServerContractTest`, because nothing here looked at what the handshake
     * actually sent. That made the contract test load-bearing for a property that a fake transport
     * can perfectly well check, and a property whose only guard needs a server to run is a property
     * nobody checks on a laptop with no JDK 17.
     */
    @Test
    fun `the session request carries a signature over the canonical session message`() = runTest {
        enqueueHandshake()
        transport.enqueue(200, """{"devices":[]}""")

        client().listDevices(credentials)

        val sessionBody = transport.bodies()[1]
        val message = my.cheysoff.core_sync_net.auth.SignedMessage.session(
            accountId,
            credentials.deviceId,
            sessionBody.jsonString("challenge"),
        )
        assertEquals("chal-1", sessionBody.jsonString("challenge"))
        assertTrue(
            "the session signature must verify over (session, accountId, deviceId, challenge)",
            verifyP256(
                signer.publicKeySec1(),
                message,
                Base64Codec.decodeUrl(sessionBody.jsonString("signature"))!!,
            ),
        )
    }

    @Test
    fun `a cached token is reused instead of re-running the handshake`() = runTest {
        enqueueHandshake()
        transport.enqueue(200, """{"devices":[]}""")
        transport.enqueue(200, """{"devices":[]}""")

        val client = client()
        client.listDevices(credentials)
        client.listDevices(credentials)

        assertEquals("one handshake, two calls", 4, transport.requests.size)
        assertEquals("the challenge is signed once per handshake", 1, signer.signCount)
    }

    /**
     * F6 in the plan's failure table: a `401` mid-pass discards the token, re-handshakes once and
     * retries the call once.
     */
    @Test
    fun `an expired token is discarded and the call is retried after a fresh handshake`() = runTest {
        enqueueHandshake(token = "stale")
        transport.enqueue(401, """{"error":"unauthorized","message":"A bearer token is required."}""")
        enqueueHandshake(token = "fresh")
        transport.enqueue(200, """{"devices":[]}""")

        client().listDevices(credentials)

        val authorised = transport.requests.filter { it.headers.containsKey("Authorization") }
        assertEquals(listOf("Bearer stale", "Bearer fresh"), authorised.map { it.headers["Authorization"] })
        assertEquals("each handshake signs its own challenge", 2, signer.signCount)
    }

    @Test
    fun `a second 401 after a fresh handshake is reported and not retried again`() = runTest {
        enqueueHandshake(token = "one")
        transport.enqueue(401, """{"error":"unauthorized","message":"nope"}""")
        enqueueHandshake(token = "two")
        transport.enqueue(401, """{"error":"unauthorized","message":"nope"}""")

        try {
            client().listDevices(credentials)
            fail("a second 401 must not be retried")
        } catch (e: SyncException.Unauthorized) {
            assertEquals("unauthorized", e.code)
        }
    }

    @Test
    fun `a revoked device is reported as revoked rather than as a generic server error`() = runTest {
        enqueueHandshake()
        transport.enqueue(403, """{"error":"device_revoked","message":"That device is revoked."}""")

        try {
            client().listDevices(credentials)
            fail("expected DeviceRevoked")
        } catch (e: SyncException) {
            assertTrue(e is SyncException.DeviceRevoked)
        }
    }

    // ------------------------------------------------------------------------------------------
    // Pull
    // ------------------------------------------------------------------------------------------

    @Test
    fun `a pull decodes records and takes the cursor from the response`() = runTest {
        enqueueHandshake()
        transport.enqueue(
            200,
            """{"records":[${record("aa", seq = 4, envelope = "AQID")},""" +
                """${record("bb", seq = 9, envelope = "BAUG")}],"nextCursor":9,"hasMore":false}""",
        )

        val page = client().changesSince(credentials, Cursor.START)

        assertEquals(2, page.records.size)
        assertEquals(9L, page.nextCursor.seq)
        assertFalse(page.hasMore)
        assertArrayEquals(byteArrayOf(1, 2, 3), page.records[0].envelope)
        assertArrayEquals(byteArrayOf(4, 5, 6), page.records[1].envelope)
        assertTrue(transport.requests.last().url.contains("since=0"))
    }

    @Test
    fun `an empty page leaves the cursor exactly where it was`() = runTest {
        enqueueHandshake()
        transport.enqueue(200, """{"records":[],"nextCursor":42,"hasMore":false}""")

        val page = client().changesSince(credentials, Cursor.ofSeq(42))

        assertEquals(42L, page.nextCursor.seq)
        assertTrue(page.records.isEmpty())
    }

    /**
     * **The cursor is the server's monotonic `seq` and never a timestamp.**
     *
     * This is the guard for that. `receivedAt` is present in every record precisely because a UI
     * may want it, which is what makes taking the cursor from it a plausible mistake -- here the
     * two fields disagree, so a client that read the wrong one produces a `nextCursor` that does
     * not match the page and the page is refused rather than stored.
     */
    @Test
    fun `a next cursor that does not match the page's largest seq is refused`() = runTest {
        enqueueHandshake()
        // seq 4 and 9; receivedAt 1700000000000 and 1700000000001. A cursor built from the
        // timestamps cannot equal the cursor built from the sequence numbers.
        transport.enqueue(
            200,
            """{"records":[${record("aa", seq = 4)},${record("bb", seq = 9)}],""" +
                """"nextCursor":1700000000001,"hasMore":false}""",
        )

        try {
            client().changesSince(credentials, Cursor.START)
            fail("a mismatched cursor must be refused")
        } catch (e: SyncException.Protocol) {
            assertTrue(e.message!!.contains("next cursor"))
        }
    }

    @Test
    fun `a change page that is not ordered by seq is refused`() = runTest {
        enqueueHandshake()
        transport.enqueue(
            200,
            """{"records":[${record("aa", seq = 9)},${record("bb", seq = 4)}],""" +
                """"nextCursor":4,"hasMore":false}""",
        )

        try {
            client().changesSince(credentials, Cursor.START)
            fail("an out-of-order page must be refused")
        } catch (e: SyncException.Protocol) {
            assertTrue(e.message!!.contains("ordered by seq"))
        }
    }

    /** F7: a rolled-back server must halt the engine, never silently reset the cursor. */
    @Test
    fun `a cursor ahead of the server halts rather than resetting`() = runTest {
        enqueueHandshake()
        transport.enqueue(
            409,
            """{"error":"cursor_ahead_of_server","message":"Re-baseline before syncing again."}""",
        )

        try {
            client().changesSince(credentials, Cursor.ofSeq(500))
            fail("expected CursorAheadOfServer")
        } catch (e: SyncException.CursorAheadOfServer) {
            assertEquals(500L, e.requested)
        }
    }

    @Test
    fun `a cursor cannot be built from a negative number`() {
        try {
            Cursor.ofSeq(-1)
            fail("a cursor is a sequence number")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    // ------------------------------------------------------------------------------------------
    // Push
    // ------------------------------------------------------------------------------------------

    @Test
    fun `a clean push reports every item as accepted`() = runTest {
        enqueueHandshake()
        transport.enqueue(
            200,
            """{"results":[{"blindedId":"aa","status":"ok","seq":12}],"accountSeq":12}""",
        )

        val outcome = client().pushRecords(credentials, listOf(pushItem("aa", baseSeq = 0)))

        assertFalse(outcome.hasConflicts)
        assertEquals(12L, (outcome.results.single() as PushResult.Accepted).seq)
        assertEquals(12L, outcome.accountSeq)
    }

    /**
     * A `409` on push is **data**. The plan's §3.2 rule 3 is explicit: the conflicting version comes
     * back inline so it can be merged in the same code path as a pulled record, and the items that
     * did not conflict *were applied*. Treating this status as a failure throws away both facts.
     *
     * This is the mutation guard for it: break the client so that a `409` raises, and this test --
     * by name -- fails.
     */
    @Test
    fun `a 409 push returns per-item results with the conflicting envelope inline`() = runTest {
        enqueueHandshake()
        transport.enqueue(
            409,
            """{"results":[{"blindedId":"aa","status":"ok","seq":13},""" +
                """{"blindedId":"bb","status":"conflict","current":${record("bb", seq = 8, envelope = "BwgJ")}}],""" +
                """"accountSeq":13}""",
        )

        val outcome = client().pushRecords(
            credentials,
            listOf(pushItem("aa", baseSeq = 0), pushItem("bb", baseSeq = 3)),
        )

        assertTrue(outcome.hasConflicts)
        val accepted = outcome.results[0] as PushResult.Accepted
        assertEquals(13L, accepted.seq)
        val conflict = outcome.results[1] as PushResult.Conflict
        val blocking = conflict.current ?: error("the blocking version must arrive inline")
        assertEquals(8L, blocking.seq)
        assertArrayEquals(byteArrayOf(7, 8, 9), blocking.envelope)
    }

    @Test
    fun `a conflict without an inline version is still reported as a conflict`() = runTest {
        enqueueHandshake()
        transport.enqueue(
            409,
            """{"results":[{"blindedId":"aa","status":"conflict"}],"accountSeq":3}""",
        )

        val conflict = client()
            .pushRecords(credentials, listOf(pushItem("aa", baseSeq = 1)))
            .results.single() as PushResult.Conflict
        assertNull(conflict.current)
    }

    @Test
    fun `an unknown per-item status is a protocol error and never an implied success`() = runTest {
        enqueueHandshake()
        transport.enqueue(
            200,
            """{"results":[{"blindedId":"aa","status":"maybe"}],"accountSeq":1}""",
        )

        try {
            client().pushRecords(credentials, listOf(pushItem("aa", baseSeq = 0)))
            fail("an unknown status must not be read as success")
        } catch (e: SyncException.Protocol) {
            assertTrue(e.message!!.contains("unknown push status"))
        }
    }

    @Test
    fun `the envelope is base64url on the wire and byte-identical on the way back`() = runTest {
        enqueueHandshake()
        val envelope = ByteArray(200) { (it * 7).toByte() }
        transport.enqueue(200, """{"results":[{"blindedId":"aa","status":"ok","seq":1}],"accountSeq":1}""")

        client().pushRecords(
            credentials,
            listOf(PushItem("aa", "note", "1-0-x", 0, envelope)),
        )

        val sent = transport.bodies().last().jsonString("envelope")
        assertArrayEquals(envelope, Base64Codec.decodeUrl(sent))
    }

    @Test
    fun `a batch that names one record twice is refused before it is sent`() = runTest {
        try {
            client().pushRecords(
                credentials,
                listOf(pushItem("aa", baseSeq = 0), pushItem("aa", baseSeq = 1)),
            )
            fail("a duplicate record in one batch must be refused locally")
        } catch (_: IllegalArgumentException) {
            assertTrue("nothing should have been sent", transport.requests.isEmpty())
        }
    }

    @Test
    fun `a blinded id that is not base64url never reaches a URL`() = runTest {
        try {
            client().history(credentials, "../../etc/passwd")
            fail("a path traversal attempt must be refused locally")
        } catch (_: IllegalArgumentException) {
            assertTrue("nothing should have been sent", transport.requests.isEmpty())
        }
    }

    // ------------------------------------------------------------------------------------------
    // Rate limiting
    // ------------------------------------------------------------------------------------------

    /**
     * The server asks for a delay; the client waits at least that long, plus jitter, and then
     * retries. `server/README.md` asks for the jitter by name: without it a household's devices,
     * all throttled in the same instant, come back in the same instant forever.
     */
    @Test
    fun `a 429 is retried after Retry-After plus jitter`() = runTest {
        enqueueHandshake()
        transport.enqueue(429, """{"error":"rate_limited","message":"slow down"}""", mapOf("Retry-After" to "3"))
        transport.enqueue(200, """{"devices":[]}""")

        client().listDevices(credentials)

        assertEquals(listOf(3_000L + JITTER_MILLIS), delayer.waits)
    }

    @Test
    fun `the jitter is added to the server's delay and never subtracted from it`() = runTest {
        enqueueHandshake()
        transport.enqueue(429, """{"error":"rate_limited","message":"slow down"}""", mapOf("Retry-After" to "3"))
        transport.enqueue(200, """{"devices":[]}""")

        client().listDevices(credentials)

        assertTrue(
            "waiting less than Retry-After is a request that is guaranteed to be refused",
            delayer.waits.single() >= 3_000L,
        )
    }

    @Test
    fun `Retry-After is read case-insensitively because proxies rewrite header case`() = runTest {
        enqueueHandshake()
        transport.enqueue(429, """{"error":"rate_limited","message":""}""", mapOf("retry-after" to "7"))
        transport.enqueue(200, """{"devices":[]}""")

        client().listDevices(credentials)

        assertEquals(listOf(7_000L + JITTER_MILLIS), delayer.waits)
    }

    @Test
    fun `a 429 without a usable Retry-After falls back to the policy default`() = runTest {
        enqueueHandshake()
        transport.enqueue(429, """{"error":"rate_limited","message":""}""")
        transport.enqueue(200, """{"devices":[]}""")

        client(RetryPolicy(defaultRetryAfterMillis = 4_000)).listDevices(credentials)

        assertEquals(listOf(4_000L + JITTER_MILLIS), delayer.waits)
    }

    @Test
    fun `an absurd Retry-After is clamped rather than parking the pass`() = runTest {
        enqueueHandshake()
        transport.enqueue(429, """{"error":"rate_limited","message":""}""", mapOf("Retry-After" to "86400"))
        transport.enqueue(200, """{"devices":[]}""")

        client(RetryPolicy(maxRetryAfterMillis = 30_000)).listDevices(credentials)

        assertEquals(listOf(30_000L + JITTER_MILLIS), delayer.waits)
    }

    @Test
    fun `a rate limit that survives the retry budget is reported to the caller`() = runTest {
        enqueueHandshake()
        transport.enqueue(429, """{"error":"rate_limited","message":""}""", mapOf("Retry-After" to "2"))
        transport.enqueue(429, """{"error":"rate_limited","message":""}""", mapOf("Retry-After" to "2"))

        try {
            client(RetryPolicy(maxAttempts = 2)).listDevices(credentials)
            fail("expected RateLimited")
        } catch (e: SyncException.RateLimited) {
            assertEquals("the reported delay excludes this client's own jitter", 2_000L, e.retryAfterMillis)
        }
        assertEquals("two attempts means one wait", 1, delayer.waits.size)
    }

    // ------------------------------------------------------------------------------------------
    // Malformed and oversized responses
    // ------------------------------------------------------------------------------------------

    @Test
    fun `a response that is not JSON is a protocol error`() = runTest {
        enqueueHandshake()
        transport.enqueue(200, "<html>502 Bad Gateway</html>")

        try {
            client().listDevices(credentials)
            fail("expected a protocol error")
        } catch (e: SyncException.Protocol) {
            assertTrue(e.message!!.contains("not valid JSON"))
        }
    }

    @Test
    fun `a response missing a field this client needs names the field and nothing else`() = runTest {
        enqueueHandshake()
        transport.enqueue(200, """{"devices":[{"deviceId":"d","label":"l","createdAt":1,"self":true}]}""")

        try {
            client().listDevices(credentials)
            fail("expected a protocol error")
        } catch (e: SyncException.Protocol) {
            assertTrue(e.message!!.contains("publicKey"))
        }
    }

    @Test
    fun `a base64 field that will not decode is a protocol error`() = runTest {
        enqueueHandshake()
        transport.enqueue(
            200,
            """{"records":[{"blindedId":"aa","recType":"note","hlc":"1-0-x","seq":1,""" +
                """"envelope":"not base64!!","receivedAt":2}],"nextCursor":1,"hasMore":false}""",
        )

        try {
            client().changesSince(credentials, Cursor.START)
            fail("expected a protocol error")
        } catch (e: SyncException.Protocol) {
            assertTrue(e.message!!.contains("envelope"))
        }
    }

    @Test
    fun `a response larger than the cap is refused rather than buffered`() = runTest {
        enqueueHandshake()
        transport.enqueue(200, "x".repeat(5_000))

        try {
            client(maxResponseBytes = 4_096).listDevices(credentials)
            fail("expected ResponseTooLarge")
        } catch (e: SyncException.ResponseTooLarge) {
            assertEquals(4_096, e.limitBytes)
        }
    }

    @Test
    fun `a redirect is a protocol error because this client does not follow one`() = runTest {
        enqueueHandshake()
        transport.enqueue(302, "", mapOf("Location" to "https://elsewhere.example/v1/devices"))

        try {
            client().listDevices(credentials)
            fail("expected a protocol error")
        } catch (e: SyncException.Protocol) {
            assertTrue(e.message!!.contains("redirect"))
        }
    }

    @Test
    fun `a structured server error keeps the server's code and safe message`() = runTest {
        enqueueHandshake()
        transport.enqueue(400, """{"error":"batch_too_large","message":"Too many items in one batch."}""")

        try {
            client().pushRecords(credentials, listOf(pushItem("aa", baseSeq = 0)))
            fail("expected a server error")
        } catch (e: SyncException.Server) {
            assertEquals("batch_too_large", e.code)
            assertEquals(400, e.status)
            assertEquals("Too many items in one batch.", e.serverMessage)
        }
    }

    // ------------------------------------------------------------------------------------------
    // Logging
    // ------------------------------------------------------------------------------------------

    /**
     * The client half of the server's `LoggingTest`. The interface has nowhere to put a URL, so
     * this asserts what actually reaches a log line rather than trusting that it will stay that way.
     */
    @Test
    fun `log lines name route templates and never a token, an account id or a record id`() =
        runTest {
            val lines = mutableListOf<String>()
            val log = TransportLog { method, route, status, _ -> lines += "$method $route $status" }
            enqueueHandshake(token = "tok-secret")
            transport.enqueue(200, """{"versions":[]}""")

            client(log = log).history(credentials, "blindedRecordId")

            assertEquals(
                listOf(
                    "POST /v1/session/challenge 200",
                    "POST /v1/session 200",
                    "GET /v1/records/{id}/history 200",
                ),
                lines,
            )
            for (line in lines) {
                assertFalse(line.contains("tok-secret"))
                assertFalse(line.contains(accountId))
                assertFalse(line.contains("blindedRecordId"))
                assertFalse(line.contains("device-1"))
            }
        }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private fun record(
        blindedId: String,
        seq: Long,
        envelope: String = "AQID",
        receivedAt: Long = 1_700_000_000_000L + seq,
    ) = """{"blindedId":"$blindedId","recType":"note","hlc":"1-0-node","seq":$seq,""" +
        """"envelope":"$envelope","receivedAt":$receivedAt}"""

    private fun pushItem(blindedId: String, baseSeq: Long) =
        PushItem(blindedId, "note", "1-0-node", baseSeq, byteArrayOf(1, 2, 3))

    /** Pulls a string field out of a request body, without depending on the wire codec. */
    private fun String.jsonString(field: String): String =
        Regex("\"$field\":\"([^\"]*)\"").find(this)!!.groupValues[1]

    private fun String.jsonNumber(field: String): Long =
        Regex("\"$field\":(-?\\d+)").find(this)!!.groupValues[1].toLong()

    private fun verifyP256(publicKeySec1: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        val x = java.math.BigInteger(1, publicKeySec1.copyOfRange(1, 33))
        val y = java.math.BigInteger(1, publicKeySec1.copyOfRange(33, 65))
        val parameters = java.security.AlgorithmParameters.getInstance("EC").apply {
            init(java.security.spec.ECGenParameterSpec("secp256r1"))
        }
        val spec = parameters.getParameterSpec(java.security.spec.ECParameterSpec::class.java)
        val key = java.security.KeyFactory.getInstance("EC").generatePublic(
            java.security.spec.ECPublicKeySpec(java.security.spec.ECPoint(x, y), spec)
        )
        return java.security.Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(message)
            verify(signature)
        }
    }

    private companion object {
        const val FIXED_NOW = 1_700_000_000_000L
        const val JITTER_MILLIS = 250L
    }
}

