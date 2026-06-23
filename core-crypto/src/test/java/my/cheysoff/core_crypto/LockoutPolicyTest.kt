package my.cheysoff.core_crypto

import org.junit.Assert.assertEquals
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
}
