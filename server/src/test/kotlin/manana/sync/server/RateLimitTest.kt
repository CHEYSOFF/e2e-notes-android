package manana.sync.server

import io.ktor.http.HttpHeaders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Rate limiting: `429` with a `Retry-After` a client can actually honour. */
class RateLimitTest {

    @Test
    fun exhaustingTheBudgetReturns429WithRetryAfter() =
        serverTest(testConfig(rateLimitPerMinute = 60, rateLimitBurst = 3)) { harness ->
            repeat(3) { assertEquals(200, client.getAuth("/healthz", null).status.value) }

            val throttled = client.getAuth("/healthz", null)
            assertEquals(429, throttled.status.value)
            assertEquals("rate_limited", throttled.errorCode())

            val retryAfter = assertNotNull(throttled.headers[HttpHeaders.RetryAfter])
            val seconds = assertNotNull(retryAfter.toLongOrNull())
            assertTrue(seconds >= 1, "Retry-After must never be 0: $retryAfter")
        }

    @Test
    fun theBudgetRefillsAsTimePasses() =
        serverTest(testConfig(rateLimitPerMinute = 60, rateLimitBurst = 2)) { harness ->
            repeat(2) { client.getAuth("/healthz", null) }
            assertEquals(429, client.getAuth("/healthz", null).status.value)

            // 60 permits a minute is one a second.
            harness.clock.now += 1_000
            assertEquals(200, client.getAuth("/healthz", null).status.value)
            assertEquals(429, client.getAuth("/healthz", null).status.value)
        }

    /** A throttled request must not have been executed -- no account is created by a `429`. */
    @Test
    fun aThrottledRequestDoesNoWork() =
        serverTest(testConfig(rateLimitPerMinute = 60, rateLimitBurst = 1)) { harness ->
            assertEquals(200, client.getAuth("/healthz", null).status.value)

            val accountId = randomAccountId()
            val device = TestDevice()
            val ts = harness.clock.now
            val body = JSON_LENIENT.encodeToString(
                ClaimRequest(
                    accountId, device.publicKeyB64, "d", ts,
                    device.sign(SignedMessage.claim(accountId, device.publicKeyB64, ts)),
                )
            )
            assertEquals(429, client.postJson("/v1/account", body).status.value)
            assertTrue(!harness.store.accountExists(accountId))
        }

    // -----------------------------------------------------------------------------------------
    // The limiter itself, without HTTP in the way.
    // -----------------------------------------------------------------------------------------

    @Test
    fun bucketsAreIndependentPerKey() {
        val clock = MutableClock()
        val limiter = RateLimiter(permitsPerMinute = 60, burst = 1, clock = clock)
        assertTrue(limiter.check("a") is RateDecision.Allowed)
        assertTrue(limiter.check("a") is RateDecision.Throttled)
        assertTrue(limiter.check("b") is RateDecision.Allowed)
    }

    @Test
    fun retryAfterCountsUpTowardsTheNextPermit() {
        val clock = MutableClock()
        // Six permits a minute is one every ten seconds.
        val limiter = RateLimiter(permitsPerMinute = 6, burst = 1, clock = clock)
        assertTrue(limiter.check("k") is RateDecision.Allowed)

        val immediately = limiter.check("k") as RateDecision.Throttled
        assertEquals(10L, immediately.retryAfterSeconds)

        clock.now += 9_000
        val nearlyThere = limiter.check("k") as RateDecision.Throttled
        assertEquals(1L, nearlyThere.retryAfterSeconds)

        clock.now += 1_000
        assertTrue(limiter.check("k") is RateDecision.Allowed)
    }

    @Test
    fun aBucketNeverRefillsPastItsBurst() {
        val clock = MutableClock()
        val limiter = RateLimiter(permitsPerMinute = 600, burst = 2, clock = clock)
        clock.now += 10 * 60 * 1000 // ten minutes of idleness
        assertTrue(limiter.check("k") is RateDecision.Allowed)
        assertTrue(limiter.check("k") is RateDecision.Allowed)
        assertTrue(limiter.check("k") is RateDecision.Throttled)
    }
}
