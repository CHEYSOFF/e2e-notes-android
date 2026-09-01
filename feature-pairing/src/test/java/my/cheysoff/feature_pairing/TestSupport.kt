package my.cheysoff.feature_pairing

import my.cheysoff.core_pairing.protocol.MonotonicClock

/**
 * A [MonotonicClock] the test drives by hand.
 *
 * The pairing sessions read the clock rather than owning one precisely so expiry can be tested
 * without a two-minute `Thread.sleep`.
 *
 * A near-copy of the one in `:core-pairing`'s own suite, and deliberately not shared: a test
 * fixture published from one module to another is a `testFixtures` variant to configure and an
 * extra publication edge for four lines that never change. The two are independent by design --
 * neither is a protocol primitive, so the "two implementations drift" argument that governs
 * `KeyDerivation` does not apply.
 */
class FakeClock(var now: Long = 0L) : MonotonicClock {
    override fun elapsedMillis(): Long = now

    fun advance(millis: Long) {
        now += millis
    }
}
