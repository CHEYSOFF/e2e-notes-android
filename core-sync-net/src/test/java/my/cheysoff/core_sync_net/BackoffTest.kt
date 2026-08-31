package my.cheysoff.core_sync_net

import my.cheysoff.core_sync_net.http.Jitter
import my.cheysoff.core_sync_net.http.RetryAfterHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `Retry-After` parsing and the jitter that goes on top of it. */
class BackoffTest {

    @Test
    fun `delta-seconds is read as milliseconds`() {
        assertEquals(3_000L, RetryAfterHeader.parseMillis("3"))
        assertEquals(0L, RetryAfterHeader.parseMillis("0"))
        assertEquals(3_000L, RetryAfterHeader.parseMillis("  3  "))
    }

    /**
     * The HTTP-date form is deliberately not implemented. Reading it needs the two clocks to agree,
     * and this project treats a user-settable device clock as hostile everywhere else it matters.
     * Falling back to the caller's default is a longer wait, not a shorter one, so the failure mode
     * is politeness rather than a hammered server.
     */
    @Test
    fun `an http-date or any other unparseable value falls back to the caller's default`() {
        assertNull(RetryAfterHeader.parseMillis("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertNull(RetryAfterHeader.parseMillis(null))
        assertNull(RetryAfterHeader.parseMillis(""))
        assertNull(RetryAfterHeader.parseMillis("3.5"))
        assertNull(RetryAfterHeader.parseMillis("-1"))
    }

    @Test
    fun `an overflowing delta does not wrap into a negative delay`() {
        assertEquals(Long.MAX_VALUE, RetryAfterHeader.parseMillis(Long.MAX_VALUE.toString()))
    }

    /**
     * The two properties the whole thing rests on: the extra delay is never negative -- which would
     * make the client return *before* the server allowed -- and it is bounded, so a server asking
     * for an hour cannot produce a wait of up to two.
     */
    @Test
    fun `random jitter is never negative and never exceeds the cap`() {
        for (base in listOf(0L, 1L, 1_000L, 60_000L, 3_600_000L, Long.MAX_VALUE)) {
            repeat(200) {
                val extra = Jitter.RANDOM.extraMillis(base)
                assertTrue("negative jitter at base=$base", extra >= 0)
                assertTrue("unbounded jitter at base=$base", extra <= Jitter.JITTER_CAP_MILLIS)
                assertTrue("jitter exceeded the base at base=$base", extra <= maxOf(base, 0))
            }
        }
    }

    /**
     * The whole point of the jitter is that two devices told to wait the same amount do not come
     * back at the same moment. A source that returned a constant would satisfy every bound above
     * and none of the purpose.
     */
    @Test
    fun `random jitter actually varies`() {
        val observed = (1..500).map { Jitter.RANDOM.extraMillis(10_000L) }.toSet()
        assertTrue("jitter must not be constant", observed.size > 100)
    }
}
