package my.cheysoff.core_pairing

import my.cheysoff.core_pairing.protocol.AccountDeviceSession
import my.cheysoff.core_pairing.protocol.MonotonicClock
import my.cheysoff.core_pairing.protocol.OfferOutcome

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

/**
 * Scan an offer and seal it with an empty config: the account device's half in one call, which is
 * what it was before enrolment split it in two.
 *
 * Most of this suite is about the handshake — the key schedule, the seal, `sid` binding, expiry —
 * and none of that changed when the seal moved into its own step. Composing the two here keeps
 * those tests reading as one action, and leaves the tests that are actually about the split calling
 * the two methods directly, which is where a reader should look for the contract.
 *
 * @return null when the offer was rejected, so a call site reads `accept(x)!!` where it used to
 *   read `onScanned(x) as OfferOutcome.Accepted`.
 */
fun AccountDeviceSession.accept(text: String, config: String = ""): AcceptedPairing? {
    val outcome = onScanned(text) as? OfferOutcome.Accepted ?: return null
    return AcceptedPairing(sas = outcome.sas, sealCode = seal(config)!!)
}

/** What [accept] produced: the six digits, and the QR2 payload. */
class AcceptedPairing(val sas: String, val sealCode: String)
