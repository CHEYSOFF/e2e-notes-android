package my.cheysoff.feature_pairing

import my.cheysoff.core_pairing.protocol.AccountDeviceSession
import my.cheysoff.core_pairing.protocol.MonotonicClock
import my.cheysoff.core_pairing.protocol.OfferOutcome
import my.cheysoff.core_pairing.protocol.P256
import java.security.interfaces.ECPublicKey

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

/**
 * Scan an offer and seal it with an empty config: the other phone's half in one call.
 *
 * The tests in this file that use it are about *this* ViewModel as the new device, so the other
 * side is a prop. The tests about the enrolment split drive `onScanned` and `seal` separately.
 */
fun AccountDeviceSession.accept(text: String, config: String = ""): AcceptedPairing? {
    val outcome = onScanned(text) as? OfferOutcome.Accepted ?: return null
    return AcceptedPairing(sas = outcome.sas, sealCode = seal(config)!!)
}

/** What [accept] produced: the six digits, and the QR2 payload. */
class AcceptedPairing(val sas: String, val sealCode: String)

/**
 * A real P-256 point, as a computer's QR1 carries its long-lived device key.
 *
 * Generated rather than 65 arbitrary bytes: the account session validates it against the curve
 * before it will vouch for it, so a fake would be refused and the test would fail for the wrong
 * reason.
 */
fun aDeviceKey(): ByteArray =
    P256.encodePublicKey(P256.generateKeyPair().public as ECPublicKey)
