package my.cheysoff.feature_pairing

import my.cheysoff.feature_pairing.protocol.KeyDerivation
import my.cheysoff.feature_pairing.protocol.MonotonicClock
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 5869 HKDF-SHA256, for tests only.
 *
 * This is the **test fake** standing in for Phase 1's implementation of [KeyDerivation]. It lives
 * in `src/test` on purpose: shipping a second HKDF in production is exactly how two halves of one
 * protocol drift apart, so the production seam is deliberately unbound on this branch (see
 * `PairingSeamModule`). A fake is fine here because [TestHkdfVectorsTest] checks it against RFC
 * 5869's own published vectors — so the pairing tests above it are testing against a *correct*
 * HKDF, not merely a self-consistent one.
 *
 * If it ever disagrees with Phase 1's implementation, the pairing tests will keep passing and real
 * devices will fail to pair. That is the risk the seam exists to make visible, and binding the two
 * together is the one-line follow-up.
 */
object TestHkdf : KeyDerivation {

    private const val ALGORITHM = "HmacSHA256"
    private const val HASH_LEN = 32

    override fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, outLen: Int): ByteArray {
        require(outLen in 0..(255 * HASH_LEN)) { "outLen out of range" }
        val prk = extract(ikm, salt)
        return expand(prk, info, outLen)
    }

    /** RFC 5869 §2.2. An empty salt means HashLen zero bytes, not "no key". */
    private fun extract(ikm: ByteArray, salt: ByteArray): ByteArray {
        val key = if (salt.isEmpty()) ByteArray(HASH_LEN) else salt
        return hmac(key, ikm)
    }

    /** RFC 5869 §2.3. */
    private fun expand(prk: ByteArray, info: ByteArray, outLen: Int): ByteArray {
        val out = ByteArray(outLen)
        var previous = ByteArray(0)
        var written = 0
        var counter = 1
        while (written < outLen) {
            val block = hmac(prk, previous + info + byteArrayOf(counter.toByte()))
            val take = minOf(block.size, outLen - written)
            System.arraycopy(block, 0, out, written, take)
            written += take
            previous = block
            counter++
        }
        return out
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance(ALGORITHM).run {
            init(SecretKeySpec(key, ALGORITHM))
            doFinal(data)
        }
}

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
