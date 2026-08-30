package manana.sync.server

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The canonical signed-message encoding: the thing both sides must build identically. */
class SignedMessageTest {

    /**
     * The property the length prefixes exist for. Without them, `("authorize", "AB", "C", …)` and
     * `("authorize", "A", "BC", …)` concatenate to the same bytes, and a device signing an
     * authorisation for one key would have produced a valid signature for another.
     */
    @Test
    fun shiftingAFieldBoundaryChangesTheMessage() {
        assertFalse(
            SignedMessage.authorize("AB", "C", 1).contentEquals(SignedMessage.authorize("A", "BC", 1))
        )
    }

    /** A signature made for one purpose must never verify for another. */
    @Test
    fun eachPurposeProducesADistinctMessage() {
        val claim = SignedMessage.claim("acct", "key", 7)
        val authorize = SignedMessage.authorize("acct", "key", 7)
        assertFalse(claim.contentEquals(authorize))
    }

    @Test
    fun everyFieldIsLoadBearing() {
        val base = SignedMessage.authorize("acct", "key", 7)
        assertFalse(base.contentEquals(SignedMessage.authorize("acct2", "key", 7)))
        assertFalse(base.contentEquals(SignedMessage.authorize("acct", "key2", 7)))
        assertFalse(base.contentEquals(SignedMessage.authorize("acct", "key", 8)))
    }

    @Test
    fun theEncodingIsDeterministic() {
        assertContentEquals(
            SignedMessage.session("a", "d", "c"),
            SignedMessage.session("a", "d", "c"),
        )
    }

    /**
     * The exact bytes, spelled out, so that a client implementer has something to compare against
     * and so that an accidental change to the encoding fails here rather than in production.
     *
     * `lp("manana/sync/v1/sig")` is `00 12` followed by 18 ASCII bytes; `lp("claim")` is `00 05`
     * followed by five; then the three fields in order.
     */
    @Test
    fun theEncodingIsExactlyLengthPrefixedFields() {
        val message = SignedMessage.claim("A", "B", 9)
        val expected = byteArrayOf(0, 18) + "manana/sync/v1/sig".toByteArray() +
            byteArrayOf(0, 5) + "claim".toByteArray() +
            byteArrayOf(0, 1) + "A".toByteArray() +
            byteArrayOf(0, 1) + "B".toByteArray() +
            byteArrayOf(0, 1) + "9".toByteArray()
        assertContentEquals(expected, message)
    }
}

/** P-256 decoding, validation and ECDSA verification. */
class P256VerifyTest {

    private val device = TestDevice()
    private val encoded = B64.decodeOrNull(device.publicKeyB64)!!

    @Test
    fun aRealKeyDecodes() {
        assertNotNull(P256Verify.decodePublicKey(encoded))
    }

    @Test
    fun aWrongLengthPointIsRejected() {
        assertNull(P256Verify.decodePublicKey(encoded.copyOf(64)))
        assertNull(P256Verify.decodePublicKey(encoded + byteArrayOf(0)))
        assertNull(P256Verify.decodePublicKey(ByteArray(0)))
    }

    @Test
    fun aCompressedOrUnknownTagIsRejected() {
        val compressed = encoded.copyOf()
        compressed[0] = 0x02
        assertNull(P256Verify.decodePublicKey(compressed))
    }

    /** `04 || 00…0 || 00…0` -- the "(0, 0)" spelling of the point at infinity people try. */
    @Test
    fun theZeroPointIsRejected() {
        val zero = ByteArray(65)
        zero[0] = 0x04
        assertNull(P256Verify.decodePublicKey(zero))
    }

    @Test
    fun anOffCurvePointIsRejected() {
        val offCurve = encoded.copyOf()
        offCurve[64] = (offCurve[64].toInt() xor 0x01).toByte()
        assertNull(P256Verify.decodePublicKey(offCurve))
    }

    @Test
    fun aCoordinateAtOrAboveTheFieldPrimeIsRejected() {
        val p = BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF", 16)
        val bytes = p.toByteArray().let { if (it.size == 33) it.copyOfRange(1, 33) else it }
        val bad = ByteArray(65)
        bad[0] = 0x04
        System.arraycopy(bytes, 0, bad, 1, 32)
        System.arraycopy(encoded, 33, bad, 33, 32)
        assertNull(P256Verify.decodePublicKey(bad))
    }

    @Test
    fun aValidSignatureVerifies() {
        val message = "hello".toByteArray()
        val signature = B64.decodeOrNull(device.sign(message))!!
        assertTrue(P256Verify.verify(encoded, message, signature))
    }

    @Test
    fun aSignatureOverDifferentBytesDoesNotVerify() {
        val signature = B64.decodeOrNull(device.sign("hello".toByteArray()))!!
        assertFalse(P256Verify.verify(encoded, "hellp".toByteArray(), signature))
    }

    @Test
    fun anotherKeysSignatureDoesNotVerify() {
        val message = "hello".toByteArray()
        val signature = B64.decodeOrNull(TestDevice().sign(message))!!
        assertFalse(P256Verify.verify(encoded, message, signature))
    }

    /** A signature that is not even DER must be a `false`, never an exception. */
    @Test
    fun garbageSignatureBytesReturnFalse() {
        assertFalse(P256Verify.verify(encoded, "hello".toByteArray(), byteArrayOf(1, 2, 3)))
        assertFalse(P256Verify.verify(encoded, "hello".toByteArray(), ByteArray(0)))
    }

    @Test
    fun anUnusableKeyMakesVerificationFailRatherThanThrow() {
        assertFalse(P256Verify.verify(ByteArray(10), "hello".toByteArray(), byteArrayOf(1)))
    }
}

/** Base64url, and the digest used for tokens and the replay cache. */
class B64Test {

    @Test
    fun encodeAndDecodeRoundTrip() {
        val bytes = ByteArray(16) { it.toByte() }
        assertContentEquals(bytes, B64.decodeOrNull(B64.encode(bytes)))
    }

    @Test
    fun encodingIsUnpaddedAndUrlSafe() {
        val encoded = B64.encode(ByteArray(16))
        assertEquals(22, encoded.length)
        assertFalse(encoded.contains('='))
        assertFalse(encoded.contains('+'))
        assertFalse(encoded.contains('/'))
    }

    @Test
    fun standardAlphabetCharactersAreRejected() {
        assertNull(B64.decodeOrNull("++++"))
        assertNull(B64.decodeOrNull("////"))
        assertNull(B64.decodeOrNull("not base64!"))
    }

    @Test
    fun decodeExactlyEnforcesTheLength() {
        assertNotNull(B64.decodeExactly(B64.encode(ByteArray(16)), 16))
        assertNull(B64.decodeExactly(B64.encode(ByteArray(8)), 16))
        assertNull(B64.decodeExactly("!!!", 16))
    }

    @Test
    fun sha256HexIsStableAndHex() {
        val digest = sha256Hex("abc".toByteArray())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", digest)
    }
}
