package my.cheysoff.core_domain.sync

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * The only thing that mints [Hlc] readings. One instance per process, held as a `@Singleton`.
 *
 * ## The one guarantee
 *
 * **Every clock this generator returns is strictly greater than every clock it has already
 * returned or been shown.** Nothing else in the sync design works without it: a clock that goes
 * backwards makes the user's newest edit lose to their own older one, and — because the loser is
 * silently discarded on the next pull — that is invisible data loss rather than a visible fault.
 *
 * ## Why that needs more than `System.currentTimeMillis()`
 *
 * The wall clock is user-settable, and this codebase already treats that as a fact of life rather
 * than an edge case: `LockoutPolicy.remainingMillis` keeps a monotonic deadline alongside the wall
 * one precisely so winding the clock forward cannot retire a lockout, and `TrashPolicy.isExpired`
 * resolves every ambiguous stamp as "not expired" so a moved clock cannot destroy a note. The same
 * rigour is applied here, in the same one-directional way:
 *
 *  - the wall clock moving **forward** is taken at face value ([next] adopts it);
 *  - the wall clock moving **backwards** — a manual change, an NTP correction, a timezone-database
 *    update, a dual-boot, or simply a device whose clock was never set — is absorbed by keeping
 *    the last physical value and advancing [Hlc.counter] instead. The clock still increases; it
 *    just stops tracking real time until the wall clock catches up.
 *
 * That is the textbook HLC send rule, and it is why the counter exists at all.
 *
 * ## Process restarts
 *
 * In-memory state is not enough on its own: a process that restarts after the clock has moved back
 * would start again from the new, lower wall time. The durable high-water mark is the database —
 * every row carries the clock it was last written at — so the owner of this generator seeds it
 * with the maximum stored clock before the first write of a session. [observe] is that entry
 * point; it is also how a clock arriving from another device is folded in, which is the same
 * operation for the same reason.
 *
 * ## Purity and threading
 *
 * No Android, no clock of its own — [next] takes `wallMs` from the caller — and no coroutines, so
 * the whole thing is unit-testable on the JVM, which is where `HlcGeneratorTest` proves the
 * monotonicity claim above under a deliberately hostile clock. The mutable pair is guarded by a
 * lock because Room's write coroutines are not confined to one thread; the critical
 * section is a handful of arithmetic operations, so contention is not a consideration.
 *
 * @param node supplies the node pseudonym for each minted clock. A function rather than a value
 *   because the node is derived from the account key, which arrives at unlock and changes when the
 *   device joins an account — see `HlcNode`. A node change is safe at any moment: `(ms, counter)`
 *   is already strictly increasing on its own, so the node never decides the ordering of two
 *   clocks from this generator.
 */
class HlcGenerator(private val node: () -> String) {

    // atomicfu's lock rather than `kotlin.synchronized`, which exists only on the JVM. On the JVM
    // and Android this compiles to the same intrinsic monitor it always did; it is a portability
    // change, not a concurrency one.
    private val lock = SynchronizedObject()

    /** Highest physical component issued or observed so far. */
    private var lastMs: Long = 0L

    /** Highest counter issued or observed at [lastMs]. */
    private var lastCounter: Int = 0

    /**
     * Mints the next clock, at or after [wallMs].
     *
     * `wallMs` greater than everything seen so far resets the counter to 0 and adopts it. Anything
     * else — equal, or *earlier* than the last clock — keeps the last physical value and increments
     * the counter, so the result is still strictly greater than the previous one.
     *
     * A negative [wallMs] (a clock set before 1970, which is reachable in Settings) is floored at
     * the last value by the same rule, so [Hlc]'s non-negative invariant holds without a special
     * case.
     */
    fun next(wallMs: Long): Hlc = synchronized(lock) {
        if (wallMs > lastMs) {
            lastMs = wallMs
            lastCounter = 0
        } else {
            // Counter exhaustion is unreachable in practice — it needs 2^31 writes inside one
            // millisecond — but the alternative to handling it is an overflow to a NEGATIVE
            // counter, which would fail Hlc's own invariant and, worse, make the clock go
            // backwards. Carrying into the next millisecond keeps the guarantee intact.
            if (lastCounter == Int.MAX_VALUE) {
                lastMs += 1
                lastCounter = 0
            } else {
                lastCounter += 1
            }
        }
        Hlc(ms = lastMs, counter = lastCounter, node = node())
    }

    /**
     * Folds an already-existing clock into this generator's state, so the next [next] is strictly
     * greater than it.
     *
     * Two callers, one meaning: the maximum clock stored in the local database (the durable
     * high-water mark that survives a restart) and the clock on a record received from another
     * device. In both cases the generator has been *shown* a clock it did not mint, and every
     * clock it mints afterwards has to beat it or the local write would appear older than the
     * thing it was written in response to.
     *
     * [Hlc.node] is deliberately ignored: it breaks ties between two clocks, but it says nothing
     * about how far this generator's own counter must advance.
     */
    fun observe(seen: Hlc) = synchronized(lock) {
        if (seen.ms > lastMs) {
            lastMs = seen.ms
            lastCounter = seen.counter
        } else if (seen.ms == lastMs && seen.counter > lastCounter) {
            lastCounter = seen.counter
        }
    }

    /**
     * The highest clock issued or observed, with the current node — i.e. what [next] would have
     * to beat. Exposed for tests and diagnostics; production code mints with [next].
     */
    fun peek(): Hlc = synchronized(lock) { Hlc(lastMs, lastCounter, node()) }
}
