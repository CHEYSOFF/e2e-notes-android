package my.cheysoff.feature_pairing

import my.cheysoff.feature_pairing.protocol.AccountBundle
import my.cheysoff.feature_pairing.protocol.PairingCodec
import my.cheysoff.feature_pairing.protocol.PairingProtocol
import my.cheysoff.feature_pairing.protocol.PairingSeal
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * AES-256-GCM around the account bundle.
 *
 * ## Mutation evidence
 *
 * Changing `PairingProtocol.sealAad` to drop `sid` — i.e. `fun sealAad(sid: ByteArray) =
 * DOMAIN_BYTES` — makes [aadMustCarrySid] fail: the seal then opens under a *different* session id,
 * which is exactly the cross-session replay the AAD binding exists to stop. It also makes
 * `PairingSessionTest.qr2FromAnotherSessionIsNotAcceptedEvenIfSidCheckWereRemoved` fail, which is
 * the same property tested one layer up with the `sid` comparison deliberately bypassed.
 */
class PairingSealTest {

    private val key = ByteArray(32) { (it * 3 + 1).toByte() }
    private val nonce = ByteArray(12) { it.toByte() }
    private val sid = ByteArray(16) { (it + 100).toByte() }
    private val bundle = AccountBundle(
        ark = ByteArray(32) { (it * 11).toByte() },
        accountId = "abc123",
        config = "{}",
    )

    @Test
    fun sealsAndOpens() {
        val sealed = PairingSeal.seal(key, nonce, sid, bundle)
        val opened = PairingSeal.open(key, nonce, sid, sealed)
        assertNotNull(opened)
        val decoded = PairingCodec.decodeBundle(opened!!)
        assertArrayEquals(bundle.ark, decoded.ark)
        assertEquals(bundle.accountId, decoded.accountId)
        assertEquals(bundle.config, decoded.config)
    }

    /** The ciphertext plus the 128-bit tag, and no plaintext visible in it. */
    @Test
    fun sealIsCiphertextPlusATag() {
        val plaintext = PairingCodec.encodeBundle(bundle)
        val sealed = PairingSeal.seal(key, nonce, sid, bundle)
        assertEquals(plaintext.size + PairingProtocol.GCM_TAG_SIZE_BITS / 8, sealed.size)
        // A stream cipher's output must not equal its input anywhere near the whole length; this is
        // a cheap "did anyone accidentally wire up a null cipher" check.
        assertEquals(false, sealed.copyOfRange(0, plaintext.size).contentEquals(plaintext))
    }

    /**
     * The AAD binding. A seal made for one session must not open under another, even though the key
     * and the nonce are identical.
     *
     * This is the *second* of the two independent `sid` bindings — the first is `sid` as the HKDF
     * salt, which is tested one layer up. Either alone would stop a cross-session replay; having
     * both means a mistake in one is not exploitable.
     */
    @Test
    fun aadMustCarrySid() {
        val sealed = PairingSeal.seal(key, nonce, sid, bundle)
        val otherSid = ByteArray(16) { (it + 200).toByte() }
        assertNull(PairingSeal.open(key, nonce, otherSid, sealed))
    }

    /** One bit of the session id is enough. */
    @Test
    fun aadRejectsASingleFlippedSidBit() {
        val sealed = PairingSeal.seal(key, nonce, sid, bundle)
        val nudged = sid.copyOf().also { it[7] = (it[7].toInt() xor 0x01).toByte() }
        assertNull(PairingSeal.open(key, nonce, nudged, sealed))
    }

    @Test
    fun rejectsTheWrongKey() {
        val sealed = PairingSeal.seal(key, nonce, sid, bundle)
        val otherKey = key.copyOf().also { it[0] = (it[0].toInt() xor 0x80).toByte() }
        assertNull(PairingSeal.open(otherKey, nonce, sid, sealed))
    }

    @Test
    fun rejectsTheWrongNonce() {
        val sealed = PairingSeal.seal(key, nonce, sid, bundle)
        val otherNonce = nonce.copyOf().also { it[11] = (it[11].toInt() xor 0x01).toByte() }
        assertNull(PairingSeal.open(key, otherNonce, sid, sealed))
    }

    /** Every single-byte modification of the ciphertext and of the tag must be caught. */
    @Test
    fun rejectsAnyTamperedByte() {
        val sealed = PairingSeal.seal(key, nonce, sid, bundle)
        for (index in sealed.indices) {
            val tampered = sealed.copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }
            assertNull("byte $index was not caught", PairingSeal.open(key, nonce, sid, tampered))
        }
    }

    /** Truncation is a tag failure, not an exception. */
    @Test
    fun rejectsTruncatedInput() {
        val sealed = PairingSeal.seal(key, nonce, sid, bundle)
        assertNull(PairingSeal.open(key, nonce, sid, sealed.copyOfRange(0, sealed.size - 1)))
        assertNull(PairingSeal.open(key, nonce, sid, ByteArray(0)))
        assertNull(PairingSeal.open(key, nonce, sid, ByteArray(8)))
    }

    /** Wrong-shaped arguments return null rather than throwing at a stranger's input. */
    @Test
    fun rejectsWronglySizedParameters() {
        val sealed = PairingSeal.seal(key, nonce, sid, bundle)
        assertNull(PairingSeal.open(ByteArray(16), nonce, sid, sealed))
        assertNull(PairingSeal.open(key, ByteArray(16), sid, sealed))
        assertNull(PairingSeal.open(key, nonce, ByteArray(8), sealed))
    }
}
