package my.cheysoff.core_sync_engine

import kotlin.random.Random

/**
 * How long the caller should wait after a pass that could not finish, and why it is never exactly
 * what the server asked for.
 *
 * ## The herd
 *
 * The server answers an exhausted rate-limit bucket with `429` and a `Retry-After` in whole
 * seconds. Its README asks for one thing in return: honour it **with jitter**, because this is one
 * person's VPS and their devices all wake up together. Three devices unlock at breakfast, all sync,
 * all get throttled in the same instant, and all are told to come back in five seconds. Each
 * waiting exactly five seconds means they collide again five seconds later, and again after that.
 *
 * ## Why this is not a second copy of `:core-sync-net`'s back-off
 *
 * That one spreads the retries of **one request inside one call**; this one spreads the **next
 * pass**. They are different scopes and both are needed: without the first, a `429` fails a request
 * that a two-second wait would have satisfied; without the second, the three devices that just
 * exhausted their in-request budget line up and do it again as a group. The rule is the same shape
 * on purpose — add, never subtract — so that neither can wait *less* than the server allowed.
 *
 * ## The rule
 *
 * ```
 * wait = clamp(retryAfter) + jitter(clamp(retryAfter))
 * ```
 *
 * The jitter is **added**. `Retry-After` is the server saying "the bucket will not have refilled
 * before this"; waiting less than it is not back-off, it is a second request guaranteed to be
 * refused. This is deliberately not the "full jitter" of the usual exponential-back-off write-ups,
 * which picks uniformly in `[0, base]` and can therefore return sooner than the server allowed —
 * correct when the delay is the client's own invention, wrong when it is the server's instruction.
 */
class RetryPlan(
    private val jitter: RetryJitter,
    /**
     * Used when a `429` arrives with no delay attached, or one that did not parse.
     *
     * "No header" must never degrade into "retry immediately": that is the one behaviour guaranteed
     * to make a rate limit worse.
     */
    private val defaultRetryAfterMillis: Long = DEFAULT_RETRY_AFTER_MILLIS,
    /**
     * A ceiling on the wait the engine will report.
     *
     * A server — or a proxy in front of one — that asks for a day must not stop the app syncing for
     * a day. Past this the engine reports its own ceiling and lets the next pass find out whether
     * the limit is still in force.
     */
    private val maxRetryAfterMillis: Long = MAX_RETRY_AFTER_MILLIS,
) {

    init {
        require(defaultRetryAfterMillis >= 0) { "a delay cannot be negative" }
        require(maxRetryAfterMillis >= 0) { "a delay cannot be negative" }
    }

    /**
     * The wait to report for a pass stopped by [fault].
     *
     * Only [TransportFault.RATE_LIMITED] produces a non-zero answer. Everything else returns `0`,
     * meaning "no opinion": a network failure is not a request to stay away, and a client that
     * backed off from one would stop syncing the moment a train went into a tunnel.
     *
     * @param serverRequestedMillis the delay the server asked for, without jitter. Zero or negative
     *   is read as "none given" and falls back to [defaultRetryAfterMillis].
     */
    fun retryAfterMillis(fault: TransportFault, serverRequestedMillis: Long): Long {
        if (fault != TransportFault.RATE_LIMITED) return 0L
        val requested =
            if (serverRequestedMillis > 0L) serverRequestedMillis else defaultRetryAfterMillis
        val base = if (requested > maxRetryAfterMillis) maxRetryAfterMillis else requested
        val extra = jitter.spreadMillis(base)
        require(extra >= 0L) { "jitter must not shorten a server-instructed delay" }
        return base + extra
    }

    companion object {

        /** This server always sends a parseable `Retry-After`; a proxy in front of it may not. */
        const val DEFAULT_RETRY_AFTER_MILLIS = 5_000L

        /** One minute. Past this a sync pass is not being delayed, it is being cancelled. */
        const val MAX_RETRY_AFTER_MILLIS = 60_000L
    }
}

/**
 * Chooses the extra, random part of a back-off wait.
 *
 * A seam rather than a call to `Random` inside [RetryPlan], because "the wait is random" and "the
 * wait is never shorter than the server asked for" are two claims and only the second one can be
 * tested against a real random source. It is also what keeps a failing convergence seed replayable.
 */
fun interface RetryJitter {

    /** The extra delay to add on top of [baseMillis]. Must not be negative. */
    fun spreadMillis(baseMillis: Long): Long

    companion object {

        /**
         * Past a minute of separation the herd is already broken and more randomness only makes the
         * client look broken, so the spread is capped even when the base is larger.
         */
        const val SPREAD_CAP_MILLIS = 60_000L

        /** Uniform in `[0, min(baseMillis, SPREAD_CAP_MILLIS)]`. The production choice. */
        val RANDOM: RetryJitter = RetryJitter { baseMillis ->
            val window = if (baseMillis < SPREAD_CAP_MILLIS) baseMillis else SPREAD_CAP_MILLIS
            if (window <= 0L) 0L else Random.nextLong(window + 1)
        }

        /** No spread at all. For tests, and for nothing else. */
        val NONE: RetryJitter = RetryJitter { 0L }
    }
}
