package my.cheysoff.core_sync_engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The `429` back-off rule: the herd, and the one direction the jitter is allowed to go. */
class RetryPlanTest {

    /**
     * The property the whole rule turns on. `Retry-After` is the server saying "the bucket will not
     * have refilled before this"; a wait shorter than it is not back-off, it is a second request
     * that is guaranteed to be refused.
     *
     * Checked against a **real** random source rather than a fixed one, because "never shorter" is
     * exactly the claim a fixed jitter cannot make.
     */
    @Test
    fun `the wait is never shorter than the server asked for`() {
        val random = Random(7)
        val plan = RetryPlan(RetryJitter.RANDOM)
        repeat(2_000) {
            val requested = random.nextLong(1L, RetryPlan.MAX_RETRY_AFTER_MILLIS)
            val wait = plan.retryAfterMillis(TransportFault.RATE_LIMITED, requested)
            assertTrue("waited $wait for a requested $requested", wait >= requested)
        }
    }

    /** Two devices told the same thing must not decide the same thing, or they collide again. */
    @Test
    fun `two clients told to wait the same do not wait the same`() {
        val plan = RetryPlan(RetryJitter.RANDOM)
        val waits = (1..200).map { plan.retryAfterMillis(TransportFault.RATE_LIMITED, 5_000L) }
        assertTrue("every client picked the same wait, which is the herd", waits.toSet().size > 1)
    }

    /**
     * A network failure is not a request to stay away, and a client that backed off from one would
     * stop syncing the moment a train went into a tunnel.
     */
    @Test
    fun `only a rate limit produces a wait`() {
        val plan = RetryPlan(RetryJitter { 1_000L })
        TransportFault.entries.filter { it != TransportFault.RATE_LIMITED }.forEach { fault ->
            assertEquals(fault.name, 0L, plan.retryAfterMillis(fault, 5_000L))
        }
    }

    /** "No header" must never degrade into "retry immediately". */
    @Test
    fun `a missing retry-after falls back rather than retrying immediately`() {
        val plan = RetryPlan(RetryJitter.NONE, defaultRetryAfterMillis = 5_000L)

        assertEquals(5_000L, plan.retryAfterMillis(TransportFault.RATE_LIMITED, 0L))
        assertEquals(5_000L, plan.retryAfterMillis(TransportFault.RATE_LIMITED, -1L))
    }

    /** A proxy asking for a day must not stop the app syncing for a day. */
    @Test
    fun `an absurd retry-after is capped`() {
        val plan = RetryPlan(RetryJitter.NONE, maxRetryAfterMillis = 60_000L)

        assertEquals(60_000L, plan.retryAfterMillis(TransportFault.RATE_LIMITED, 86_400_000L))
    }

    /** The spread stops growing past a minute; more randomness only makes the client look broken. */
    @Test
    fun `the spread is capped even when the base is larger`() {
        val huge = RetryJitter.RANDOM.spreadMillis(RetryJitter.SPREAD_CAP_MILLIS * 10)

        assertTrue("spread $huge exceeded the cap", huge <= RetryJitter.SPREAD_CAP_MILLIS)
    }
}
