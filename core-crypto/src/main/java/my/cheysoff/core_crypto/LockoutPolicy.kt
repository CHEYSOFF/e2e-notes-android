package my.cheysoff.core_crypto

/**
 * Wrong-PIN backoff. First [FREE_ATTEMPTS] fails are free; after that, lock for [BASE_LOCK_MS]
 * doubling per extra fail, capped at [MAX_LOCK_MS]. Pure + deterministic (caller passes `now`).
 */
object LockoutPolicy {
    const val FREE_ATTEMPTS = 5
    const val BASE_LOCK_MS = 30_000L
    const val MAX_LOCK_MS = 300_000L

    /** Shift count at which BASE_LOCK_MS is already past MAX_LOCK_MS — beyond this, saturate. */
    private const val MAX_SHIFT = 16

    /** Lockout end timestamp given the new consecutive-fail count and current time; 0 = not locked. */
    fun lockoutUntil(failCount: Int, now: Long): Long {
        if (failCount <= FREE_ATTEMPTS) return 0L
        val steps = failCount - FREE_ATTEMPTS - 1            // 0 for the 6th fail
        // Saturate BEFORE shifting: Long.shl masks the shift to 6 bits and does not saturate, so
        // `BASE_LOCK_MS shl 49` overflows to a NEGATIVE value (coerceAtMost then keeps it), which
        // would put the deadline in the past and disable the lockout entirely; by `shl 64` the
        // shift wraps to 0 and the backoff resets to 30s. Both are brute-force windows.
        val dur = if (steps >= MAX_SHIFT) MAX_LOCK_MS
        else (BASE_LOCK_MS shl steps).coerceAtMost(MAX_LOCK_MS)
        return now + dur
    }
}
