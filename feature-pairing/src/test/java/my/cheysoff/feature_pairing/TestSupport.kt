package my.cheysoff.feature_pairing

import my.cheysoff.feature_pairing.protocol.MonotonicClock

/**
 * A [MonotonicClock] the test drives by hand.
 *
 * The pairing sessions read the clock rather than owning one precisely so expiry can be tested
 * without a two-minute `Thread.sleep`.
 */
class FakeClock(var now: Long = 0L) : MonotonicClock {
    override fun elapsedMillis(): Long = now

    fun advance(millis: Long) {
        now += millis
    }
}

/** Hex helpers, so the RFC vectors can be pasted in the form the RFC prints them. */
fun hex(s: String): ByteArray {
    val clean = s.filterNot { it.isWhitespace() }
    require(clean.length % 2 == 0)
    return ByteArray(clean.length / 2) {
        clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
