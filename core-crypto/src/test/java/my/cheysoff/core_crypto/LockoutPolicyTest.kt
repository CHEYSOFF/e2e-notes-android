package my.cheysoff.core_crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LockoutPolicyTest {

    private val now = 1_000_000L

    @Test
    fun `no lockout for zero fails`() {
        assertEquals(0L, LockoutPolicy.lockoutUntil(0, now))
    }

    @Test
    fun `no lockout up to and including FREE_ATTEMPTS`() {
        for (failCount in 1..LockoutPolicy.FREE_ATTEMPTS) {
            assertEquals(
                "failCount=$failCount should not lock",
                0L,
                LockoutPolicy.lockoutUntil(failCount, now),
            )
        }
    }

    @Test
    fun `boundary at FREE_ATTEMPTS is free, FREE_ATTEMPTS + 1 locks`() {
        assertEquals(0L, LockoutPolicy.lockoutUntil(LockoutPolicy.FREE_ATTEMPTS, now))
        assertEquals(
            now + LockoutPolicy.BASE_LOCK_MS,
            LockoutPolicy.lockoutUntil(LockoutPolicy.FREE_ATTEMPTS + 1, now),
        )
    }

    @Test
    fun `sixth fail locks for BASE_LOCK_MS`() {
        assertEquals(now + 30_000L, LockoutPolicy.lockoutUntil(6, now))
    }

    @Test
    fun `seventh fail locks for double BASE_LOCK_MS`() {
        assertEquals(now + 60_000L, LockoutPolicy.lockoutUntil(7, now))
    }

    @Test
    fun `eighth fail locks for quadruple BASE_LOCK_MS`() {
        assertEquals(now + 120_000L, LockoutPolicy.lockoutUntil(8, now))
    }

    @Test
    fun `lockout caps at MAX_LOCK_MS for large fail counts`() {
        assertEquals(now + LockoutPolicy.MAX_LOCK_MS, LockoutPolicy.lockoutUntil(50, now))
        assertEquals(now + LockoutPolicy.MAX_LOCK_MS, LockoutPolicy.lockoutUntil(1000, now))
    }

    /**
     * Regression: `BASE_LOCK_MS shl steps` overflowed to a negative duration around failCount 55-57
     * (disabling the lockout entirely) and wrapped back to 30s at failCount 70, because Long.shl
     * masks the shift to 6 bits instead of saturating. The cap test above missed it: failCount 50
     * and 1000 happen to land on shifts that stay positive.
     */
    @Test
    fun `shift overflow never shortens the lockout`() {
        for (failCount in (LockoutPolicy.FREE_ATTEMPTS + 1)..2000) {
            val until = LockoutPolicy.lockoutUntil(failCount, now)
            val duration = until - now
            assertTrue(
                "failCount=$failCount produced duration=$duration (must stay within 1..MAX)",
                duration in LockoutPolicy.BASE_LOCK_MS..LockoutPolicy.MAX_LOCK_MS,
            )
        }
    }

    @Test
    fun `previously overflowing fail counts still lock for the maximum`() {
        for (failCount in intArrayOf(55, 56, 57, 64, 70, 128)) {
            assertEquals(
                "failCount=$failCount",
                now + LockoutPolicy.MAX_LOCK_MS,
                LockoutPolicy.lockoutUntil(failCount, now),
            )
        }
    }

    // --- remainingMillis: which of the two persisted deadlines is believed --------------------

    /** Uptime of a device that has been running a while; also the pre-reboot uptime below. */
    private val elapsed = 3L * 24 * 60 * 60 * 1000   // 3 days

    @Test
    fun `no stored deadlines means not locked out`() {
        assertEquals(0L, LockoutPolicy.remainingMillis(0L, 0L, now, elapsed))
    }

    @Test
    fun `expired deadlines never report negative time`() {
        assertEquals(
            0L,
            LockoutPolicy.remainingMillis(now - 5_000L, elapsed - 5_000L, now, elapsed),
        )
    }

    @Test
    fun `normal case reports the agreed remaining time`() {
        // Both deadlines were written together, so they agree: 30s ago a 60s lockout started.
        val remaining = 30_000L
        assertEquals(
            remaining,
            LockoutPolicy.remainingMillis(now + remaining, elapsed + remaining, now, elapsed),
        )
    }

    @Test
    fun `the stricter deadline wins when the two disagree`() {
        assertEquals(
            120_000L,
            LockoutPolicy.remainingMillis(now + 10_000L, elapsed + 120_000L, now, elapsed),
        )
        assertEquals(
            120_000L,
            LockoutPolicy.remainingMillis(now + 120_000L, elapsed + 10_000L, now, elapsed),
        )
    }

    /**
     * The reason the monotonic deadline exists: System.currentTimeMillis() is user-settable, so
     * winding the clock forward in Settings retires the wall-clock deadline. Within a boot the
     * monotonic deadline is untouched by that and still holds the user.
     */
    @Test
    fun `clock wound forward within a boot does not retire the lockout`() {
        val lockoutMs = 120_000L
        val wallUntil = now + lockoutMs
        val elapsedUntil = elapsed + lockoutMs
        // User jumps the wall clock a year ahead; uptime is unchanged.
        val cheatedNow = now + 365L * 24 * 60 * 60 * 1000
        assertEquals(
            lockoutMs,
            LockoutPolicy.remainingMillis(wallUntil, elapsedUntil, cheatedNow, elapsed),
        )
    }

    /**
     * Regression (the serious one): elapsedRealtime resets to 0 on reboot, so a deadline stored
     * before the reboot implies a remainder equal to the device's whole previous uptime. The old
     * code clamped that *report* to MAX_LOCK_MS instead of discarding the deadline, so it kept
     * returning MAX_LOCK_MS on every call until uptime climbed back — days, on a device that had
     * been up for days — and unlockWithPin rejects the attempt before even trying the unwrap, so
     * the correct PIN could not clear it. A stale deadline must be ignored entirely.
     */
    @Test
    fun `deadline left over from before a reboot is ignored`() {
        val lockoutMs = 30_000L
        // Wrong PIN 30s before a reboot, on a device that had been up 3 days.
        val wallUntil = now - 5_000L                 // wall-clock window has since expired
        val elapsedUntil = elapsed + lockoutMs       // measured against the old, now-gone uptime
        val afterReboot = 12_000L                    // 12s of uptime since the reboot

        assertEquals(0L, LockoutPolicy.remainingMillis(wallUntil, elapsedUntil, now, afterReboot))
    }

    /** Same reboot, but the wall-clock window is genuinely still open: it alone decides. */
    @Test
    fun `after a reboot the wall-clock deadline still applies`() {
        val wallUntil = now + 20_000L
        val elapsedUntil = elapsed + 30_000L
        assertEquals(
            20_000L,
            LockoutPolicy.remainingMillis(wallUntil, elapsedUntil, now, 12_000L),
        )
    }

    /**
     * The hard guarantee: whatever junk sits in the monotonic slot, it can never hold the user for
     * more than one MAX_LOCK_MS. (Values at or below MAX_LOCK_MS after a reboot are honoured —
     * they are indistinguishable from a live deadline — but they are bounded, so no bricking.)
     */
    @Test
    fun `a stale monotonic deadline can never exceed MAX_LOCK_MS`() {
        val pastUptimes = longArrayOf(
            0L,
            LockoutPolicy.MAX_LOCK_MS,
            60L * 60 * 1000,                    // 1 hour
            3L * 24 * 60 * 60 * 1000,           // 3 days
            120L * 24 * 60 * 60 * 1000,         // 4 months
            Long.MAX_VALUE / 2,                 // absurd/corrupt
        )
        for (pastUptime in pastUptimes) {
            for (sinceBoot in longArrayOf(0L, 1_000L, 60_000L)) {
                val remaining = LockoutPolicy.remainingMillis(
                    lockoutUntilWall = 0L,      // wall clock says free
                    lockoutUntilElapsed = pastUptime + LockoutPolicy.MAX_LOCK_MS,
                    nowWall = now,
                    nowElapsed = sinceBoot,
                )
                assertTrue(
                    "pastUptime=$pastUptime sinceBoot=$sinceBoot gave remaining=$remaining",
                    remaining <= LockoutPolicy.MAX_LOCK_MS,
                )
            }
        }
    }

    /** A live deadline at the very top of the legitimate range must still be honoured in full. */
    @Test
    fun `a maximum-length live lockout is not mistaken for a stale one`() {
        for (uptime in longArrayOf(0L, 1_000L, elapsed, Long.MAX_VALUE / 2)) {
            assertEquals(
                "uptime=$uptime",
                LockoutPolicy.MAX_LOCK_MS,
                LockoutPolicy.remainingMillis(
                    lockoutUntilWall = 0L,
                    lockoutUntilElapsed = uptime + LockoutPolicy.MAX_LOCK_MS,
                    nowWall = now,
                    nowElapsed = uptime,
                ),
            )
        }
    }

    /** A cleared monotonic slot (0) is "no deadline", not a deadline at time zero. */
    @Test
    fun `zero monotonic deadline is not treated as a lockout`() {
        assertEquals(0L, LockoutPolicy.remainingMillis(0L, 0L, now, 0L))
        assertEquals(
            10_000L,
            LockoutPolicy.remainingMillis(now + 10_000L, 0L, now, elapsed),
        )
    }

    /**
     * End-to-end over the real backoff curve: whatever [lockoutUntil] writes into the two slots,
     * remainingMillis agrees with it while the clocks are consistent, and never exceeds
     * MAX_LOCK_MS once the monotonic clock has been reset by a reboot.
     */
    @Test
    fun `every lockout the policy can produce survives a reboot bounded`() {
        for (failCount in (LockoutPolicy.FREE_ATTEMPTS + 1)..100) {
            val until = LockoutPolicy.lockoutUntil(failCount, now)
            val duration = until - now
            val elapsedUntil = elapsed + duration

            assertEquals(
                "failCount=$failCount before reboot",
                duration,
                LockoutPolicy.remainingMillis(until, elapsedUntil, now, elapsed),
            )
            val afterReboot = LockoutPolicy.remainingMillis(until, elapsedUntil, now, 5_000L)
            assertTrue(
                "failCount=$failCount after reboot gave $afterReboot",
                afterReboot <= LockoutPolicy.MAX_LOCK_MS,
            )
        }
    }
}
