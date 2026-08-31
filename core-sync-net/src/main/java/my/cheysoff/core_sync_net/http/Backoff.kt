package my.cheysoff.core_sync_net.http

import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * How long to wait after a `429`, and why it is never exactly what the server asked for.
 *
 * The server answers an exhausted rate-limit bucket with `429` and a `Retry-After` in whole
 * seconds, never zero. Its README asks for one specific thing in return: *"Honour it **with
 * jitter**: this is one person's VPS and their devices all wake up together, so identical back-off
 * schedules form a herd."*
 *
 * That is the entire design constraint. Three devices in one household unlock at breakfast, all
 * sync, all get throttled at the same instant, and all are told to come back in five seconds. If
 * each waits exactly five seconds they collide again five seconds later, and again after that --
 * a self-sustaining thundering herd against a machine that is, at most, a small VPS the user pays
 * for themselves.
 *
 * ## The rule
 *
 * ```
 * wait = retryAfter + random(0, min(retryAfter, JITTER_CAP))
 * ```
 *
 * The jitter is **added**, never subtracted. `Retry-After` is the server saying "the bucket will
 * not have refilled before this"; waiting less than it is not back-off, it is a second request that
 * is guaranteed to be refused. This is not the "full jitter" of the usual exponential-back-off
 * write-ups, which picks uniformly in `[0, base]` and can therefore return sooner than the server
 * allowed -- correct when the delay is the client's own invention, wrong when it is the server's
 * instruction.
 *
 * The spread is capped so that a server asking for an hour does not produce a wait of up to two
 * hours; past a minute of separation the herd is broken and more randomness only makes the client
 * look broken.
 */
class RetryPolicy(
    /**
     * How many times one request is *sent*, in total, including the first.
     *
     * Three means at most two waits. A rate limit that survives two honoured `Retry-After` delays
     * is not a burst, and continuing to hold a sync pass open for it is worse than ending the pass
     * and letting the next one try -- the caller gets [SyncException.RateLimited][my.cheysoff.core_sync_net.SyncException.RateLimited]
     * with the delay the server last asked for.
     */
    val maxAttempts: Int = 3,

    /**
     * Used when a `429` arrives with no `Retry-After`, or one this client cannot parse.
     *
     * This server always sends a parseable one, but a proxy in front of it may not, and "no header"
     * must not degrade into "retry immediately".
     */
    val defaultRetryAfterMillis: Long = 5_000L,

    /**
     * A ceiling on how long one call will sit waiting inside a single request.
     *
     * A server -- or a proxy -- that asks for a `Retry-After` of a day must not park a sync pass
     * for a day. Past this the client stops waiting and reports the rate limit to the caller, which
     * can schedule its next pass however it likes.
     */
    val maxRetryAfterMillis: Long = 60_000L,
) {
    init {
        require(maxAttempts >= 1) { "a request is sent at least once" }
        require(defaultRetryAfterMillis >= 0) { "a delay cannot be negative" }
        require(maxRetryAfterMillis >= 0) { "a delay cannot be negative" }
    }
}

/**
 * Suspends for a given number of milliseconds.
 *
 * Injected rather than called directly so that a test of the back-off *policy* does not have to
 * spend real seconds proving it. `kotlinx-coroutines-test` can skip a `delay`, but only inside its
 * own scheduler; a fake here also lets a test assert **how long** the client decided to wait, which
 * is the property that actually matters.
 */
fun interface Delayer {
    suspend fun delay(millis: Long)

    companion object {
        /** The production delayer. */
        val REAL: Delayer = Delayer { millis -> delay(millis) }
    }
}

/**
 * Chooses the extra, random part of a back-off wait.
 *
 * A `fun interface` rather than a call to [Random] inside the client, because "the wait is random"
 * and "the wait is at least `Retry-After`" are two different claims and only the second one can be
 * tested against a real random source.
 */
fun interface Jitter {

    /**
     * Returns the extra delay to add on top of [baseMillis]. Must be `>= 0`.
     */
    fun extraMillis(baseMillis: Long): Long

    companion object {

        /**
         * Past a minute of spread the herd is already broken; see [RetryPolicy]'s KDoc.
         */
        const val JITTER_CAP_MILLIS = 60_000L

        /** Uniform in `[0, min(baseMillis, JITTER_CAP_MILLIS)]`. */
        val RANDOM: Jitter = Jitter { baseMillis ->
            val window = minOf(baseMillis, JITTER_CAP_MILLIS)
            if (window <= 0) 0L else Random.nextLong(window + 1)
        }
    }
}

/**
 * Parses the `Retry-After` header this server sends.
 *
 * Only the delta-seconds form is understood. RFC 9110 also allows an HTTP-date, and this client
 * deliberately does not implement it: reading it correctly needs the server's clock, comparing it
 * to the device's clock needs the two to agree, and this project already treats a user-settable
 * device clock as hostile (`LockoutPolicy`, `TrashPolicy`). An unparseable header falls back to
 * [RetryPolicy.defaultRetryAfterMillis], which is a longer wait than any HTTP-date this server
 * would send anyway -- so the failure mode is "waits a bit too long", not "hammers the server".
 */
internal object RetryAfterHeader {

    /**
     * @return the requested delay in milliseconds, or null when the header is absent, not an
     *   integer, or negative.
     */
    fun parseMillis(value: String?): Long? {
        val seconds = value?.trim()?.toLongOrNull() ?: return null
        if (seconds < 0) return null
        // A server sending an absurd number of seconds must not overflow the millisecond
        // conversion into a negative delay; the caller clamps to `maxRetryAfterMillis` afterwards,
        // but only if the arithmetic that got here stayed sane.
        if (seconds > Long.MAX_VALUE / 1000) return Long.MAX_VALUE
        return seconds * 1000
    }
}
