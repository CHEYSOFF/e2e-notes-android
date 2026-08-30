package manana.sync.server

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Either "you may proceed" or "come back in [retryAfterSeconds]". */
sealed interface RateDecision {
    data object Allowed : RateDecision
    class Throttled(val retryAfterSeconds: Long) : RateDecision
}

/**
 * A token bucket per key, in memory.
 *
 * One bucket holds [burst] tokens and refills at [permitsPerMinute] per minute. A request takes one
 * token; with none left it is refused with the number of whole seconds until the next token, which
 * the caller returns as `Retry-After` on a `429`.
 *
 * ### Why `Retry-After` matters more here than on a public API
 *
 * This server is one person's VPS and its clients are two or three devices that all wake up when
 * their owner picks up a phone. Without a retry hint they back off on schedules of their own
 * choosing, which tend to be the same schedule, and a herd forms against a machine with no capacity
 * to spare. The architecture document asks for the header and asks clients to honour it with
 * jitter; this is the half that lives on the server.
 *
 * State is in memory and is lost on restart. That is the correct trade for a limiter whose purpose
 * is smoothing an honest client and blunting a brute-force loop: a restart hands an attacker one
 * fresh bucket, which is worth far less than a table this server would otherwise have to write on
 * every single request.
 */
class RateLimiter(
    private val permitsPerMinute: Int,
    private val burst: Int,
    private val clock: Clock,
) {
    private class Bucket(var tokens: Double, var lastRefillMillis: Long)

    private val lock = ReentrantLock()
    private val buckets = HashMap<String, Bucket>()

    private val refillPerMillis: Double = permitsPerMinute.toDouble() / 60_000.0

    fun check(key: String): RateDecision = lock.withLock {
        val now = clock.nowMillis()
        val bucket = buckets.getOrPut(key) { Bucket(burst.toDouble(), now) }

        val elapsed = (now - bucket.lastRefillMillis).coerceAtLeast(0)
        bucket.tokens = (bucket.tokens + elapsed * refillPerMillis).coerceAtMost(burst.toDouble())
        bucket.lastRefillMillis = now

        if (bucket.tokens >= 1.0) {
            bucket.tokens -= 1.0
            RateDecision.Allowed
        } else {
            val millisToOneToken = ((1.0 - bucket.tokens) / refillPerMillis).toLong()
            // Never advertise 0: a client that reads "retry after 0 seconds" retries instantly and
            // is refused again, which is the herd this exists to prevent.
            RateDecision.Throttled(maxOf(1L, (millisToOneToken + 999) / 1000))
        }
    }

    /**
     * Forgets buckets that have been full for a while, so the map does not grow without bound on a
     * server that sees many distinct client addresses. Called opportunistically from [check]'s
     * caller side is unnecessary -- a bucket is at most a few dozen bytes and the key set here is
     * one account plus a handful of addresses -- so this exists for the operator who points a wider
     * audience at it, and for tests.
     */
    fun evictIdle(idleMillis: Long) = lock.withLock {
        val cutoff = clock.nowMillis() - idleMillis
        buckets.entries.removeIf { it.value.lastRefillMillis < cutoff && it.value.tokens >= burst }
    }
}
