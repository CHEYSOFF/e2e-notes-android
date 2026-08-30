package my.cheysoff.core_domain

import my.cheysoff.core_domain.model.TrashPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The purge decision, tested without a clock — which is the whole reason [TrashPolicy] takes `now`
 * as a parameter. Everything this object decides is irreversible, so the cases that matter most
 * here are the ones where it must refuse to decide.
 */
class TrashPolicyTest {

    private val day = 24L * 60 * 60 * 1000

    /** An arbitrary "now" well clear of the epoch, so subtracting 30 days stays positive. */
    private val now = 1_700_000_000_000L

    // --- the ordinary case ---

    @Test
    fun `a row deleted just now is not expired`() {
        assertFalse(TrashPolicy.isExpired(now, now))
    }

    @Test
    fun `a row one day short of the window is not expired`() {
        assertFalse(TrashPolicy.isExpired(now - 29 * day, now))
    }

    @Test
    fun `a row exactly at the window is expired`() {
        assertTrue(TrashPolicy.isExpired(now - TrashPolicy.RETENTION_MILLIS, now))
    }

    @Test
    fun `a row one millisecond short of the window is not expired`() {
        assertFalse(TrashPolicy.isExpired(now - TrashPolicy.RETENTION_MILLIS + 1, now))
    }

    @Test
    fun `a row well past the window is expired`() {
        assertTrue(TrashPolicy.isExpired(now - 365 * day, now))
    }

    // --- the cases where the age is unknown: all of them must keep the row ---

    @Test
    fun `a row with no stamp is never expired`() {
        assertFalse(TrashPolicy.isExpired(null, now))
    }

    @Test
    fun `a row stamped zero is never expired`() {
        // 0 is the "unset" sentinel this schema uses, not midnight 1970. Reading it as a real
        // instant would make every such row instantly expired.
        assertFalse(TrashPolicy.isExpired(0L, now))
    }

    @Test
    fun `a row with a negative stamp is never expired`() {
        assertFalse(TrashPolicy.isExpired(-1L, now))
    }

    @Test
    fun `a stamp in the future is never expired`() {
        // The clock moved backwards since the delete (or forwards when it happened). Elapsed time
        // is unknown, not negative-therefore-huge.
        assertFalse(TrashPolicy.isExpired(now + day, now))
    }

    @Test
    fun `a stamp far in the future is never expired`() {
        assertFalse(TrashPolicy.isExpired(now + 365 * day, now))
    }

    // --- purgeThreshold must agree with isExpired, since the SQL uses it instead ---

    @Test
    fun `purgeThreshold selects exactly the stamps isExpired calls expired`() {
        val threshold = TrashPolicy.purgeThreshold(now)
        val candidates = listOf(
            now,
            now - day,
            now - 29 * day,
            now - TrashPolicy.RETENTION_MILLIS + 1,
            now - TrashPolicy.RETENTION_MILLIS,
            now - TrashPolicy.RETENTION_MILLIS - 1,
            now - 365 * day,
            now + day,
            1L,
        )
        candidates.forEach { deletedAt ->
            // The SQL adds `deletedAt > 0` on top of this comparison, so restrict the equivalence
            // to positive stamps — which is exactly what the query lets through.
            val bySql = deletedAt > 0 && deletedAt <= threshold
            assertEquals("stamp $deletedAt", TrashPolicy.isExpired(deletedAt, now), bySql)
        }
    }

    // --- daysRemaining, which only drives UI copy ---

    @Test
    fun `a freshly deleted row has the full window left`() {
        assertEquals(TrashPolicy.RETENTION_DAYS, TrashPolicy.daysRemaining(now, now))
    }

    @Test
    fun `days remaining rounds up so any time left reads as at least one day`() {
        // One millisecond short of expiry.
        assertEquals(1, TrashPolicy.daysRemaining(now - TrashPolicy.RETENTION_MILLIS + 1, now))
    }

    @Test
    fun `a row deleted a day ago has twenty nine days left`() {
        assertEquals(29, TrashPolicy.daysRemaining(now - day, now))
    }

    @Test
    fun `an expired row has zero days left rather than a negative count`() {
        assertEquals(0, TrashPolicy.daysRemaining(now - TrashPolicy.RETENTION_MILLIS, now))
        assertEquals(0, TrashPolicy.daysRemaining(now - 365 * day, now))
    }

    @Test
    fun `an unknown stamp has no day count at all`() {
        assertNull(TrashPolicy.daysRemaining(null, now))
        assertNull(TrashPolicy.daysRemaining(0L, now))
    }

    @Test
    fun `a future stamp reports the full window rather than a count above it`() {
        assertEquals(TrashPolicy.RETENTION_DAYS, TrashPolicy.daysRemaining(now + 365 * day, now))
    }

    @Test
    fun `days remaining is zero exactly when the row is expired`() {
        var t = now
        while (t > now - 31 * day) {
            val expired = TrashPolicy.isExpired(t, now)
            val left = TrashPolicy.daysRemaining(t, now)
            assertEquals("stamp $t", expired, left == 0)
            t -= 6 * 60 * 60 * 1000 // step back six hours at a time
        }
    }
}
