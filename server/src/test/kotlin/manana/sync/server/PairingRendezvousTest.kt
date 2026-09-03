package manana.sync.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The pairing rendezvous: `/v1/pair/{sid}` and `/v1/pair/{sid}/reply`.
 *
 * Five rules carry the security of these endpoints, and each has a test named after it here so
 * that breaking one produces a sentence rather than a count:
 *
 *  1. **TTL** -- a deposit is not collectable after `pairingTtlMillis`.
 *  2. **Single use** -- the first collect takes it and the second finds nothing.
 *  3. **First write wins** -- a second deposit under a live `(sid, slot)` cannot displace the first.
 *  4. **Size cap** -- an oversized blob is refused rather than stored.
 *  5. **Capacity** -- the table cannot grow past `maxLivePairings`, whoever is writing.
 *
 * There is also a sixth property that is not a rule so much as the point of the whole thing: the
 * server returns the blob **byte for byte** and never looks inside it. `opaqueBytesSurviveExactly`
 * is that test.
 *
 * ## The two slots
 *
 * Since the account device can be the one showing the code, a pairing has two exchanges: the
 * joining device's key material goes to `/reply` and the sealed bundle comes back on the bare path.
 * Every rule above holds **per slot**, except the capacity cap, which counts pairings -- the
 * `slotsAreIndependent*` tests and `theGlobalCapCountsPairingsNotSlots` are where that distinction
 * is pinned.
 */
class PairingRendezvousTest {

    // -----------------------------------------------------------------------------------------
    // The happy path
    // -----------------------------------------------------------------------------------------

    @Test
    fun depositThenCollectReturnsTheBlob() = serverTest { harness ->
        val sid = randomSid()
        val blob = randomBlob(512)

        val deposit = client.postJson("/v1/pair/$sid", depositBody(blob))
        assertEquals(201, deposit.status.value)
        assertEquals(
            harness.clock.now + harness.config.pairingTtlMillis,
            deposit.decode<PairingDepositResponse>().expiresAt,
        )

        val collect = client.get("/v1/pair/$sid")
        assertEquals(200, collect.status.value)
        assertEquals(blob, collect.decode<PairingCollectResponse>().sealed)
    }

    /**
     * The server is a courier, not a reader.
     *
     * The blob here is base64url of bytes chosen to look like nothing -- it is not a pairing frame
     * and would not decode as one -- and it comes back identical. That is the property the client
     * relies on: a single altered character fails the GCM tag on the far side, so "carried
     * unchanged" is not a nicety, it is the contract.
     */
    @Test
    fun opaqueBytesSurviveExactly() = serverTest {
        val sid = randomSid()
        val blob = B64.encode(ByteArray(300) { (it * 7 + 13).toByte() })

        assertEquals(201, client.postJson("/v1/pair/$sid", depositBody(blob)).status.value)
        val collected = client.get("/v1/pair/$sid").decode<PairingCollectResponse>().sealed
        assertEquals(blob, collected)
        assertTrue(B64.decodeOrNull(collected)!!.contentEquals(B64.decodeOrNull(blob)!!))
    }

    // -----------------------------------------------------------------------------------------
    // Rule 1: TTL
    // -----------------------------------------------------------------------------------------

    /**
     * A blob is gone once its lease is over.
     *
     * Driven by moving the clock rather than by sleeping, which is why `pairingTtlMillis` is
     * configurable at all. The boundary is exclusive: at exactly `expiresAt` the row is already
     * past, matching every other expiry in this store (`expires_at > now`).
     */
    @Test
    fun aDepositIsNotCollectableAfterItsTtl() = serverTest(
        testConfig(pairingTtlMillis = 60_000)
    ) { harness ->
        val sid = randomSid()
        assertEquals(201, client.postJson("/v1/pair/$sid", depositBody(randomBlob(64))).status.value)

        harness.clock.now += 59_999
        assertEquals(200, client.get("/v1/pair/$sid").status.value)

        val second = randomSid()
        assertEquals(201, client.postJson("/v1/pair/$second", depositBody(randomBlob(64))).status.value)
        harness.clock.now += 60_000
        val collect = client.get("/v1/pair/$second")
        assertEquals(404, collect.status.value)
        assertEquals("no_pairing", collect.errorCode())
    }

    /** An expired row is swept by the next deposit rather than left to accumulate. */
    @Test
    fun expiredDepositsAreSweptByALaterDeposit() = serverTest(
        testConfig(pairingTtlMillis = 60_000)
    ) { harness ->
        repeat(3) { assertEquals(201, client.postJson("/v1/pair/${randomSid()}", depositBody(randomBlob(64))).status.value) }
        assertEquals(3L, harness.store.livePairingCount())

        harness.clock.now += 60_001
        assertEquals(0L, harness.store.livePairingCount())

        client.postJson("/v1/pair/${randomSid()}", depositBody(randomBlob(64)))
        assertEquals(1L, harness.store.livePairingCount())
        // The sweep is what this asserts: without it the table would still hold the three dead rows
        // alongside the live one, and `maxLivePairings` would be reached by history rather than by
        // load.
        assertEquals(1, harness.store.pairingRowCount())
    }

    // -----------------------------------------------------------------------------------------
    // Rule 2: single use
    // -----------------------------------------------------------------------------------------

    /**
     * The first collect takes it; every later one is a `404`.
     *
     * This is what closes the window in which the server is holding anything at all. It costs a
     * retry -- a collect whose response is lost has still consumed the blob -- and that is the
     * trade the store's KDoc names.
     */
    @Test
    fun aDepositIsCollectableExactlyOnce() = serverTest {
        val sid = randomSid()
        val blob = randomBlob(128)
        client.postJson("/v1/pair/$sid", depositBody(blob))

        assertEquals(blob, client.get("/v1/pair/$sid").decode<PairingCollectResponse>().sealed)

        val second = client.get("/v1/pair/$sid")
        assertEquals(404, second.status.value)
        assertEquals("no_pairing", second.errorCode())
    }

    /**
     * Unknown, expired and already-collected are indistinguishable.
     *
     * Same status, same code, same message. A poller does the same thing for all three -- keep
     * waiting until its own clock says stop -- so telling them apart would buy nothing and would
     * confirm to a stranger that a `sid` had ever existed.
     */
    @Test
    fun unknownAndCollectedLookIdentical() = serverTest {
        val used = randomSid()
        client.postJson("/v1/pair/$used", depositBody(randomBlob(64)))
        client.get("/v1/pair/$used")

        val afterCollect = client.get("/v1/pair/$used")
        val neverExisted = client.get("/v1/pair/${randomSid()}")

        assertEquals(afterCollect.status, neverExisted.status)
        assertEquals(afterCollect.bodyAsText(), neverExisted.bodyAsText())
    }

    // -----------------------------------------------------------------------------------------
    // Rule 3: first write wins
    // -----------------------------------------------------------------------------------------

    /**
     * A second deposit cannot displace the first.
     *
     * The attack this closes: someone who knows a live `sid` overwrites the real bundle with a
     * decoy. The waiting device would then collect something that fails its GCM tag, and be told --
     * accurately, and alarmingly -- that the bundle was meant for a different device or was
     * modified. Last-write-wins would put that message within a stranger's reach.
     */
    @Test
    fun aSecondDepositCannotDisplaceTheFirst() = serverTest {
        val sid = randomSid()
        val genuine = randomBlob(128)
        val decoy = randomBlob(128)
        assertNotEquals(genuine, decoy)

        assertEquals(201, client.postJson("/v1/pair/$sid", depositBody(genuine)).status.value)

        val second = client.postJson("/v1/pair/$sid", depositBody(decoy))
        assertEquals(409, second.status.value)
        assertEquals("pairing_exists", second.errorCode())

        assertEquals(genuine, client.get("/v1/pair/$sid").decode<PairingCollectResponse>().sealed)
    }

    /** Once collected, the slot is free again -- the conflict tracks the row, not the `sid`. */
    @Test
    fun aSidIsDepositableAgainOnceItsBlobHasBeenCollected() = serverTest {
        val sid = randomSid()
        client.postJson("/v1/pair/$sid", depositBody(randomBlob(64)))
        client.get("/v1/pair/$sid")

        val again = randomBlob(64)
        assertEquals(201, client.postJson("/v1/pair/$sid", depositBody(again)).status.value)
        assertEquals(again, client.get("/v1/pair/$sid").decode<PairingCollectResponse>().sealed)
    }

    // -----------------------------------------------------------------------------------------
    // Rule 4: size cap
    // -----------------------------------------------------------------------------------------

    @Test
    fun anOversizedBlobIsRefusedAndNotStored() = serverTest(
        testConfig(maxPairingBlobBytes = 256)
    ) { harness ->
        val sid = randomSid()
        val response = client.postJson("/v1/pair/$sid", depositBody(randomBlob(257)))
        assertEquals(413, response.status.value)
        assertEquals("sealed_too_large", response.errorCode())

        assertEquals(0L, harness.store.livePairingCount())
        assertEquals(404, client.get("/v1/pair/$sid").status.value)
    }

    @Test
    fun aBlobOfExactlyTheCapIsAccepted() = serverTest(testConfig(maxPairingBlobBytes = 256)) {
        val sid = randomSid()
        assertEquals(201, client.postJson("/v1/pair/$sid", depositBody(randomBlob(256))).status.value)
    }

    /** An empty blob is nothing to carry, and storing one is a row an attacker gets for free. */
    @Test
    fun anEmptyBlobIsRefused() = serverTest {
        val response = client.postJson("/v1/pair/${randomSid()}", depositBody(""))
        assertEquals(413, response.status.value)
    }

    @Test
    fun aBlobThatIsNotBase64IsRefused() = serverTest {
        val response = client.postJson("/v1/pair/${randomSid()}", """{"sealed":"not base64!!"}""")
        assertEquals(400, response.status.value)
        assertEquals("invalid_sealed", response.errorCode())
    }

    // -----------------------------------------------------------------------------------------
    // Rule 5: capacity
    // -----------------------------------------------------------------------------------------

    /**
     * The global cap, which is the only bound that survives an attacker with many addresses.
     *
     * A per-IP bucket is per-IP. This is the number that keeps the table -- and therefore the disk
     * on a machine that also holds the only copy of someone's notes -- bounded whoever is writing.
     */
    @Test
    fun theTableCannotGrowPastTheGlobalCap() = serverTest(
        testConfig(maxLivePairings = 3)
    ) { harness ->
        repeat(3) {
            assertEquals(201, client.postJson("/v1/pair/${randomSid()}", depositBody(randomBlob(32))).status.value)
        }

        val refused = client.postJson("/v1/pair/${randomSid()}", depositBody(randomBlob(32)))
        assertEquals(503, refused.status.value)
        assertEquals("pairing_capacity", refused.errorCode())
        assertEquals(3L, harness.store.livePairingCount())
    }

    /** Expiry frees capacity: the cap counts live rows, not rows ever written. */
    @Test
    fun capacityIsFreedWhenDepositsExpire() = serverTest(
        testConfig(maxLivePairings = 2, pairingTtlMillis = 60_000)
    ) { harness ->
        repeat(2) { client.postJson("/v1/pair/${randomSid()}", depositBody(randomBlob(32))) }
        assertEquals(503, client.postJson("/v1/pair/${randomSid()}", depositBody(randomBlob(32))).status.value)

        harness.clock.now += 60_001
        assertEquals(201, client.postJson("/v1/pair/${randomSid()}", depositBody(randomBlob(32))).status.value)
    }

    // -----------------------------------------------------------------------------------------
    // Rate limiting
    // -----------------------------------------------------------------------------------------

    /**
     * Deposits draw on their own, much tighter bucket.
     *
     * An honest pairing deposits once. This is the only unauthenticated request that makes this
     * server *store* something, so it is the tap a storage-exhaustion attempt has to come through.
     */
    @Test
    fun depositsAreRateLimitedSeparatelyAndTightly() = serverTest(
        testConfig(pairingDepositPerMinute = 2, pairingDepositBurst = 2)
    ) {
        repeat(2) {
            assertEquals(201, client.postJson("/v1/pair/${randomSid()}", depositBody(randomBlob(32))).status.value)
        }
        val throttled = client.postJson("/v1/pair/${randomSid()}", depositBody(randomBlob(32)))
        assertEquals(429, throttled.status.value)
        assertEquals("rate_limited", throttled.errorCode())
    }

    /**
     * Collecting does not spend a deposit permit.
     *
     * Polling is dozens of requests per pairing; sharing a bucket sized for one deposit would make
     * the normal case throttle itself.
     */
    @Test
    fun collectingDoesNotSpendADepositPermit() = serverTest(
        testConfig(pairingDepositPerMinute = 1, pairingDepositBurst = 1)
    ) {
        val sid = randomSid()
        assertEquals(201, client.postJson("/v1/pair/$sid", depositBody(randomBlob(32))).status.value)
        repeat(20) { assertEquals(404, client.get("/v1/pair/${randomSid()}").status.value) }
        assertEquals(200, client.get("/v1/pair/$sid").status.value)
    }

    /** A malformed `sid` is refused before the bucket is charged, so it cannot exhaust it. */
    @Test
    fun aMalformedSidDoesNotSpendADepositPermit() = serverTest(
        testConfig(pairingDepositPerMinute = 1, pairingDepositBurst = 1)
    ) {
        repeat(5) {
            assertEquals(400, client.postJson("/v1/pair/short", depositBody(randomBlob(32))).status.value)
        }
        assertEquals(201, client.postJson("/v1/pair/${randomSid()}", depositBody(randomBlob(32))).status.value)
    }

    // -----------------------------------------------------------------------------------------
    // `sid` shape
    // -----------------------------------------------------------------------------------------

    /**
     * A `sid` is pinned to 16 bytes where a blinded record ID deliberately is not.
     *
     * A short `sid` would be a namespace small enough to sweep -- park a row under every one of
     * them and every pairing that follows collides with a stranger's deposit.
     */
    @Test
    fun onlyASixteenByteSidIsAccepted() = serverTest {
        // An empty segment is absent from this list because `/v1/pair/` matches no route at all and
        // is answered by Ktor with a 404 before any of this server's code runs. That is the right
        // answer and it is not this validator's doing, so asserting it here would be asserting the
        // framework.
        for (bad in listOf("short", B64.encode(ByteArray(15)), B64.encode(ByteArray(17)), "!!!!")) {
            val deposit = client.postJson("/v1/pair/$bad", depositBody(randomBlob(32)))
            assertEquals(400, deposit.status.value, "deposit accepted sid '$bad'")
            assertEquals("invalid_sid", deposit.errorCode())

            val collect = client.get("/v1/pair/$bad")
            assertEquals(400, collect.status.value, "collect accepted sid '$bad'")
        }
    }

    /** Two pairings in flight do not see each other. */
    @Test
    fun concurrentPairingsAreIndependent() = serverTest {
        val first = randomSid()
        val second = randomSid()
        val firstBlob = randomBlob(64)
        val secondBlob = randomBlob(64)

        client.postJson("/v1/pair/$first", depositBody(firstBlob))
        client.postJson("/v1/pair/$second", depositBody(secondBlob))

        assertEquals(secondBlob, client.get("/v1/pair/$second").decode<PairingCollectResponse>().sealed)
        assertEquals(firstBlob, client.get("/v1/pair/$first").decode<PairingCollectResponse>().sealed)
    }

    // -----------------------------------------------------------------------------------------
    // Logging
    // -----------------------------------------------------------------------------------------

    /**
     * No `sid` ever reaches a log line.
     *
     * The same rule `LoggingTest` enforces for blinded record IDs, and for a related reason: a log
     * of `sid`s is a log of who paired with whom and when, which is one of the few things the
     * operator is not otherwise handed. The route template is what gets logged.
     */
    @Test
    fun noSidReachesALogLine() = serverTest { harness ->
        val sid = randomSid()
        client.postJson("/v1/pair/$sid", depositBody(randomBlob(64)))
        client.get("/v1/pair/$sid")

        val lines = harness.logLines.joinToString("\n")
        assertTrue(lines.contains("POST /v1/pair/{sid}"), "the route template should be logged: $lines")
        assertTrue(lines.contains("GET /v1/pair/{sid}"), "the route template should be logged: $lines")
        assertTrue(!lines.contains(sid), "a sid reached a log line: $lines")
    }

    // -----------------------------------------------------------------------------------------
    // The reply slot
    // -----------------------------------------------------------------------------------------

    @Test
    fun theReplySlotDepositsAndCollectsLikeTheBundleSlot() = serverTest { harness ->
        val sid = randomSid()
        val blob = randomBlob(133)

        val deposit = client.postJson("/v1/pair/$sid/reply", depositBody(blob))
        assertEquals(201, deposit.status.value)
        assertEquals(
            harness.clock.now + harness.config.pairingTtlMillis,
            deposit.decode<PairingDepositResponse>().expiresAt,
        )

        val collect = client.get("/v1/pair/$sid/reply")
        assertEquals(200, collect.status.value)
        assertEquals(blob, collect.decode<PairingCollectResponse>().sealed)
    }

    /**
     * The two slots are separate resources under one name.
     *
     * The invite direction writes both under one `sid` -- the phone's reply first, the desktop's
     * bundle second -- so a deposit into either must not be visible from, or blocked by, the other.
     */
    @Test
    fun slotsAreIndependentDrops() = serverTest {
        val sid = randomSid()
        val reply = randomBlob(133)
        val bundle = randomBlob(512)

        assertEquals(201, client.postJson("/v1/pair/$sid/reply", depositBody(reply)).status.value)
        assertEquals(201, client.postJson("/v1/pair/$sid", depositBody(bundle)).status.value)

        assertEquals(reply, client.get("/v1/pair/$sid/reply").decode<PairingCollectResponse>().sealed)
        assertEquals(bundle, client.get("/v1/pair/$sid").decode<PairingCollectResponse>().sealed)
    }

    /** Collecting one slot must not consume the other. A pairing needs both legs to complete. */
    @Test
    fun slotsAreIndependentlySingleUse() = serverTest {
        val sid = randomSid()
        client.postJson("/v1/pair/$sid/reply", depositBody(randomBlob(133)))
        client.postJson("/v1/pair/$sid", depositBody(randomBlob(64)))

        assertEquals(200, client.get("/v1/pair/$sid/reply").status.value)
        assertEquals(404, client.get("/v1/pair/$sid/reply").status.value)
        // The bundle is untouched by the reply's collect.
        assertEquals(200, client.get("/v1/pair/$sid").status.value)
        assertEquals(404, client.get("/v1/pair/$sid").status.value)
    }

    /**
     * First write wins per slot -- which is what stops an attacker who guessed a `sid` from
     * replacing the phone's ephemeral key with their own after the honest one has landed.
     */
    @Test
    fun aSecondReplyCannotDisplaceTheFirst() = serverTest {
        val sid = randomSid()
        val honest = randomBlob(133)

        assertEquals(201, client.postJson("/v1/pair/$sid/reply", depositBody(honest)).status.value)
        val second = client.postJson("/v1/pair/$sid/reply", depositBody(randomBlob(133)))
        assertEquals(409, second.status.value)
        assertEquals("pairing_exists", second.errorCode())

        assertEquals(honest, client.get("/v1/pair/$sid/reply").decode<PairingCollectResponse>().sealed)
    }

    @Test
    fun aReplyIsNotCollectableAfterItsTtl() = serverTest(
        testConfig(pairingTtlMillis = 120_000)
    ) { harness ->
        val sid = randomSid()
        client.postJson("/v1/pair/$sid/reply", depositBody(randomBlob(133)))

        harness.clock.now += 119_999
        assertEquals(200, client.get("/v1/pair/$sid/reply").status.value)

        val next = randomSid()
        client.postJson("/v1/pair/$next/reply", depositBody(randomBlob(133)))
        harness.clock.now += 120_001
        assertEquals(404, client.get("/v1/pair/$next/reply").status.value)
    }

    @Test
    fun anOversizedReplyIsRefusedAndNotStored() = serverTest(
        testConfig(maxPairingBlobBytes = 256)
    ) { harness ->
        val sid = randomSid()
        val refused = client.postJson("/v1/pair/$sid/reply", depositBody(randomBlob(257)))
        assertEquals(413, refused.status.value)
        assertEquals(404, client.get("/v1/pair/$sid/reply").status.value)
        assertEquals(0L, harness.store.pairingRowCount())
    }

    @Test
    fun onlyASixteenByteSidIsAcceptedOnTheReplySlot() = serverTest {
        assertEquals(400, client.postJson("/v1/pair/short/reply", depositBody(randomBlob(32))).status.value)
        assertEquals(400, client.get("/v1/pair/short/reply").status.value)
    }

    /**
     * The cap bounds pairings, not rows.
     *
     * Its name, its error message and the disk arithmetic in `ServerConfig` all say "pairings in
     * progress". Counting slots instead would have silently halved it the day the second slot
     * landed, which is exactly the kind of change nobody notices until a limit is hit.
     */
    @Test
    fun theGlobalCapCountsPairingsNotSlots() = serverTest(
        testConfig(maxLivePairings = 2)
    ) { harness ->
        val first = randomSid()
        val second = randomSid()
        client.postJson("/v1/pair/$first/reply", depositBody(randomBlob(133)))
        client.postJson("/v1/pair/$first", depositBody(randomBlob(64)))
        client.postJson("/v1/pair/$second/reply", depositBody(randomBlob(133)))

        // Four rows would be over a cap of two; two pairings is not.
        assertEquals(3L, harness.store.pairingRowCount())
        assertEquals(2L, harness.store.livePairingCount())
        assertEquals(201, client.postJson("/v1/pair/$second", depositBody(randomBlob(64))).status.value)

        assertEquals(
            503,
            client.postJson("/v1/pair/${randomSid()}", depositBody(randomBlob(64))).status.value,
        )
    }

    /** Both slots draw on one deposit bucket: a second route must not be a second allowance. */
    @Test
    fun theReplySlotSharesTheDepositRateLimit() = serverTest(
        testConfig(pairingDepositPerMinute = 2, pairingDepositBurst = 2)
    ) {
        assertEquals(201, client.postJson("/v1/pair/${randomSid()}", depositBody(randomBlob(32))).status.value)
        assertEquals(201, client.postJson("/v1/pair/${randomSid()}/reply", depositBody(randomBlob(133))).status.value)

        val throttled = client.postJson("/v1/pair/${randomSid()}/reply", depositBody(randomBlob(133)))
        assertEquals(429, throttled.status.value)
        assertEquals("rate_limited", throttled.errorCode())
    }

    @Test
    fun noSidReachesALogLineFromTheReplySlot() = serverTest { harness ->
        val sid = randomSid()
        client.postJson("/v1/pair/$sid/reply", depositBody(randomBlob(133)))
        client.get("/v1/pair/$sid/reply")

        val lines = harness.logLines.joinToString("\n")
        assertTrue(lines.contains("POST /v1/pair/{sid}/reply"), "the route template should be logged: $lines")
        assertTrue(lines.contains("GET /v1/pair/{sid}/reply"), "the route template should be logged: $lines")
        assertTrue(!lines.contains(sid), "a sid reached a log line: $lines")
    }

    // -----------------------------------------------------------------------------------------

    private fun randomSid(): String {
        val bytes = ByteArray(16)
        RANDOM.nextBytes(bytes)
        return B64.encode(bytes)
    }

    /** A blob of exactly [bytes] decoded bytes, so a size cap can be tested at its boundary. */
    private fun randomBlob(bytes: Int): String {
        val raw = ByteArray(bytes)
        RANDOM.nextBytes(raw)
        return B64.encode(raw)
    }

    private fun depositBody(blob: String) =
        JSON_LENIENT.encodeToString(PairingDepositRequest.serializer(), PairingDepositRequest(blob))

    private companion object {
        val RANDOM = SecureRandom()
    }
}
