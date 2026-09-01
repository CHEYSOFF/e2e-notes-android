package my.cheysoff.core_pairing

import my.cheysoff.core_pairing.protocol.AccountDeviceSession
import my.cheysoff.core_pairing.protocol.CollectResult
import my.cheysoff.core_pairing.protocol.DepositResult
import my.cheysoff.core_pairing.protocol.MonotonicClock
import my.cheysoff.core_pairing.protocol.OfferOutcome
import my.cheysoff.core_pairing.protocol.RendezvousClient
import my.cheysoff.core_pairing.protocol.RendezvousProtocol
import my.cheysoff.core_pairing.protocol.RendezvousSlot

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

/**
 * An in-memory rendezvous with the server's own rules: keyed on `(sid, slot)`, first write wins,
 * single use per slot.
 *
 * A fake of the *server's behaviour*, not of the client's — which is why the rules are reproduced
 * rather than stubbed out. [force] is the attacker's door: it puts a blob somewhere the honest
 * protocol never would, which is how the `sid` binding, the `EA` comparison and the tag check get
 * exercised.
 *
 * Shared between the two directions' suites deliberately. Both run against one fake, so a rule that
 * only one of them relies on cannot quietly stop being enforced for the other.
 */
class FakeDrop : RendezvousClient {
    private val rows = HashMap<String, String>()

    /** Every deposit that reached this drop, in order, as `(sid, slot)`. */
    val deposits = mutableListOf<Pair<String, RendezvousSlot>>()

    /** When set, every collect returns this instead of looking at the rows. */
    var answer: CollectResult? = null

    override fun deposit(sid: ByteArray, slot: RendezvousSlot, code: String): DepositResult {
        val key = key(sid, slot)
        if (rows.containsKey(key)) return DepositResult.AlreadyDeposited
        rows[key] = RendezvousProtocol.toBlob(code)
        deposits += RendezvousProtocol.encodeSid(sid) to slot
        return DepositResult.Deposited(expiresAt = 0L)
    }

    override fun collect(sid: ByteArray, slot: RendezvousSlot): CollectResult {
        answer?.let { return it }
        val blob = rows.remove(key(sid, slot)) ?: return CollectResult.Pending
        return CollectResult.Collected(RendezvousProtocol.fromBlob(blob))
    }

    /** Park a blob regardless of what is already there. Only an attacker can do this. */
    fun force(sid: ByteArray, slot: RendezvousSlot, code: String) {
        rows[key(sid, slot)] = RendezvousProtocol.toBlob(code)
    }

    private fun key(sid: ByteArray, slot: RendezvousSlot): String =
        RendezvousProtocol.encodeSid(sid) + slot.pathSuffix
}
