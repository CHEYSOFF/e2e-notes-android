package my.cheysoff.core_crypto

/**
 * Wrong-PIN backoff. First [FREE_ATTEMPTS] fails are free; after that, lock for [BASE_LOCK_MS]
 * doubling per extra fail, capped at [MAX_LOCK_MS]. Pure + deterministic (caller passes `now`).
 */
object LockoutPolicy {
    const val FREE_ATTEMPTS = 5
    const val BASE_LOCK_MS = 30_000L
    const val MAX_LOCK_MS = 300_000L

    /** Lockout end timestamp given the new consecutive-fail count and current time; 0 = not locked. */
    fun lockoutUntil(failCount: Int, now: Long): Long {
        if (failCount <= FREE_ATTEMPTS) return 0L
        val steps = failCount - FREE_ATTEMPTS - 1            // 0 for the 6th fail
        val dur = (BASE_LOCK_MS shl steps).coerceAtMost(MAX_LOCK_MS)
        return now + dur
    }
}
