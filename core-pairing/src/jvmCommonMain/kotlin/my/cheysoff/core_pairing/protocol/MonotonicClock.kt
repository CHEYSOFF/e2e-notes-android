package my.cheysoff.core_pairing.protocol

/**
 * A millisecond counter that only ever goes forward.
 *
 * Deliberately **not** `System.currentTimeMillis()`. The wall clock is user-settable, so a
 * timeout measured against it can be shortened or extended by changing a system setting — the same
 * hazard `LockoutPolicy` already guards against elsewhere in this codebase. Production binds this
 * to `SystemClock.elapsedRealtime()`, which counts from boot, includes deep sleep, and cannot be
 * moved; tests bind a counter they advance by hand.
 *
 * The interface exists so the protocol state machines stay pure JVM classes with no Android import
 * in them, which is what makes the expiry rules unit-testable at all.
 */
fun interface MonotonicClock {
    fun elapsedMillis(): Long
}
