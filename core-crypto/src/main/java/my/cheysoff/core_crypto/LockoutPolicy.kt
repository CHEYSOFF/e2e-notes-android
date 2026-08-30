package my.cheysoff.core_crypto

import kotlin.math.max

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

    /**
     * Milliseconds still to wait on an active lockout window, or 0 if free to attempt.
     *
     * Two deadlines are persisted for the same window: [lockoutUntilWall] on the user-settable
     * wall clock (`System.currentTimeMillis`) and [lockoutUntilElapsed] on the monotonic clock
     * (`SystemClock.elapsedRealtime`). The STRICTER of the two wins, so winding the device clock
     * forward in Settings does not retire the lockout early.
     *
     * The catch: elapsedRealtime restarts at 0 on every reboot, so a deadline written before a
     * reboot is measured against a clock that no longer exists. Such a deadline is IGNORED here
     * (see [isElapsedDeadlineStale]) rather than clamped, leaving the wall-clock deadline to
     * decide on its own — exactly the behaviour that existed before the monotonic deadline was
     * added, and the only direction that cannot strand the user out of their notes.
     *
     * @param lockoutUntilWall stored wall-clock deadline (0 = none)
     * @param lockoutUntilElapsed stored monotonic deadline (0 = none)
     * @param nowWall current `System.currentTimeMillis()`
     * @param nowElapsed current `SystemClock.elapsedRealtime()`
     */
    fun remainingMillis(
        lockoutUntilWall: Long,
        lockoutUntilElapsed: Long,
        nowWall: Long,
        nowElapsed: Long,
    ): Long {
        // 0 is the "no deadline" sentinel in BOTH slots and must be treated as such, not as an
        // instant in 1970: with the wall clock set before the epoch, `0 - nowWall` is a positive
        // remainder of decades, so a fresh install that has never failed a PIN would report itself
        // locked out forever. The elapsed slot below has always had this guard; the wall slot did
        // not, which left one unbounded lockout in a function whose whole thesis is that there
        // are none.
        val byWall = if (lockoutUntilWall == 0L) 0L else lockoutUntilWall - nowWall
        val byElapsed = when {
            lockoutUntilElapsed == 0L -> 0L
            isElapsedDeadlineStale(lockoutUntilElapsed, nowElapsed) -> 0L
            else -> lockoutUntilElapsed - nowElapsed
        }
        return max(0L, max(byWall, byElapsed))
    }

    /**
     * True when [lockoutUntilElapsed] cannot have been written during the CURRENT boot session and
     * must therefore be discarded.
     *
     * The deadline is always stored as `elapsedRealtime() + duration` with `duration` at most
     * [MAX_LOCK_MS], and elapsedRealtime only moves forward within a boot, so anything written
     * this boot satisfies `deadline - nowElapsed <= MAX_LOCK_MS`. A larger remainder is proof the
     * value was measured against a pre-reboot uptime that has since reset to ~0.
     *
     * Discarding rather than clamping is the whole point. Clamping only the *reported* remainder
     * (the original bug) left the stale deadline in place, so every call kept reporting
     * MAX_LOCK_MS until uptime climbed back to the stored value: a phone up for three days before
     * the reboot stayed locked for three days, and because the caller rejects the attempt before
     * trying the unwrap, not even the correct PIN could clear it — the only escape was Clear App
     * Data, which destroys every wrap and therefore every note. The worst case now is a single
     * MAX_LOCK_MS wait, in the one situation where the pre-reboot uptime was itself under
     * MAX_LOCK_MS and the stale value is genuinely indistinguishable from a live one.
     */
    private fun isElapsedDeadlineStale(lockoutUntilElapsed: Long, nowElapsed: Long): Boolean =
        lockoutUntilElapsed - nowElapsed > MAX_LOCK_MS
}
