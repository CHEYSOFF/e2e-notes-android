package my.cheysoff.core_domain.model

/**
 * How long a soft-deleted note or folder survives in Trash before it is purged for good.
 *
 * Pure and deterministic: every function takes `now` from the caller, so the expiry rule can be
 * unit-tested without a clock. That matters more than usual here, because the only thing this
 * object decides is whether a row is destroyed irrecoverably.
 *
 * The wall clock is user-settable, and this codebase already defends against that elsewhere (see
 * LockoutPolicy.remainingMillis). The defence taken here is deliberately one-directional: every
 * ambiguous case resolves to "NOT expired". A row that outstays its 30 days because the clock
 * moved is a row the user can still restore; a row purged early is gone.
 */
object TrashPolicy {

    /** Retention window, in milliseconds: 30 days. */
    const val RETENTION_MILLIS: Long = 30L * 24 * 60 * 60 * 1000

    /** The same window in whole days, for UI copy. */
    const val RETENTION_DAYS: Int = 30

    private const val DAY_MILLIS: Long = 24L * 60 * 60 * 1000

    /**
     * True when a row deleted at [deletedAt] has outlived [RETENTION_MILLIS] as of [now].
     *
     * Returns false — i.e. "keep it" — for every case where the answer is not clearly yes:
     *
     * - [deletedAt] is null. Every soft-delete path in this app stamps the time, but a row that
     *   somehow lacks a stamp has no measurable age, and inventing one would purge it.
     * - [deletedAt] is 0 or negative. 0 is what an `INTEGER NOT NULL DEFAULT 0` column holds, so it
     *   reads as "unset" rather than as midnight 1970 — which would be instantly expired.
     * - [deletedAt] is in the future relative to [now]. Either the clock moved backwards since the
     *   delete, or it was wound forward when the delete happened. Both mean the elapsed time is
     *   unknown, not zero.
     */
    fun isExpired(deletedAt: Long?, now: Long): Boolean {
        if (deletedAt == null || deletedAt <= 0L) return false
        if (deletedAt > now) return false
        return now - deletedAt >= RETENTION_MILLIS
    }

    /**
     * The newest [deletedAt] that is already expired at [now] — a row is expired exactly when
     * `deletedAt <= purgeThreshold(now)`, which is the form the purge SQL uses.
     *
     * This is the same test as [isExpired] rearranged (`now - deletedAt >= RETENTION_MILLIS`), so
     * the two agree on every stamp that reaches the query. The query keeps the remaining guards of
     * its own (`deletedAt IS NOT NULL AND deletedAt > 0`); a future stamp needs no guard there
     * because `deletedAt > now > purgeThreshold(now)` already excludes it.
     */
    fun purgeThreshold(now: Long): Long = now - RETENTION_MILLIS

    /**
     * Whole days left before a row deleted at [deletedAt] expires, rounded UP so a row with any
     * time at all remaining reads as at least "1 day left".
     *
     * Returns null when the age is unknown for the reasons [isExpired] lists (no stamp, or an
     * unset one), [RETENTION_DAYS] for a future stamp (the row's clock is ahead of ours, so it has
     * at least the full window left), and 0 once the row is expired — at that point it is awaiting
     * the next purge pass rather than already gone.
     */
    fun daysRemaining(deletedAt: Long?, now: Long): Int? {
        if (deletedAt == null || deletedAt <= 0L) return null
        if (deletedAt > now) return RETENTION_DAYS
        val remaining = RETENTION_MILLIS - (now - deletedAt)
        if (remaining <= 0L) return 0
        // Ceiling division. `remaining` is in (0, RETENTION_MILLIS] here, so the +DAY_MILLIS cannot
        // overflow and the result always fits an Int.
        return ((remaining + DAY_MILLIS - 1) / DAY_MILLIS).toInt()
    }
}
