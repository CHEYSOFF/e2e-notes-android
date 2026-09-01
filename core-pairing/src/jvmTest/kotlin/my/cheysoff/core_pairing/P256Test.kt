package my.cheysoff.core_pairing

import my.cheysoff.core_pairing.protocol.InvalidPeerKeyException
import my.cheysoff.core_pairing.protocol.P256
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey

/**
 * The point-validation tests.
 *
 * These are the most important tests in the module. P-256 does not validate points for free the way
 * X25519 does, so an attacker-supplied off-curve point handed to `KeyAgreement` leaks the private
 * scalar modulo a small order — a handful of exchanges and the ephemeral key is recovered. Nothing
 * in this codebase relies on the JCA provider catching that; [P256.decodePublicKey] checks it
 * itself, and these tests are what say so.
 *
 * ## Mutation evidence
 *
 * Deleting the `if (!isOnCurve(x, y))` block from `P256.decodePublicKey` and running this class
 * fails [rejectsOffCurvePoint], [rejectsPointWithBothCoordinatesZero] and
 * [rejectsPointOnADifferentCurve], plus `PairingSessionTest`'s two invalid-point tests — five
 * failures in all. Measured here on JDK 17 (SunEC) on 2026-08-31, and the failure message is the
 * part worth reading:
 *
 * ```
 *     java.lang.AssertionError: expected InvalidPeerKeyException but nothing was thrown
 * ```
 *
 * **Nothing was thrown.** `KeyFactory.generatePublic(ECPublicKeySpec(...))` accepted an off-curve
 * point without complaint and handed back a usable `ECPublicKey`. So the check in
 * [P256.decodePublicKey] is not belt-and-braces over a provider that would have caught it anyway —
 * on this platform it is the only thing standing between a hostile QR code and `KeyAgreement`.
 */
class P256Test {

    // -- domain parameters --------------------------------------------------------------------

    /**
     * The hard-coded curve constants are what the point validation is measured against, so a typo
     * in any of them would be silent and catastrophic. This asks the *platform* for a secp256r1
     * key and compares every parameter it reports against the constants in [P256].
     */
    @Test
    fun platformAgreesWithHardCodedCurve() {
        val platform = (P256.generateEphemeralKeyPair().public as ECPublicKey).params
        val curve = platform.curve

        assertEquals(P256.FIELD_PRIME, (curve.field as java.security.spec.ECFieldFp).p)
        assertEquals(P256.CURVE_A, curve.a)
        assertEquals(P256.CURVE_B, curve.b)
        assertEquals(P256.GENERATOR_X, platform.generator.affineX)
        assertEquals(P256.GENERATOR_Y, platform.generator.affineY)
        assertEquals(P256.CURVE_ORDER, platform.order)
        assertEquals(P256.CURVE_COFACTOR, platform.cofactor)
    }

    /**
     * The cofactor being 1 is what makes the omitted `nQ = O` step sound: with h = 1 the curve
     * group has prime order n, so any on-curve point is already in the order-n subgroup. If this
     * ever changes, [P256]'s validation is no longer full validation.
     */
    @Test
    fun cofactorIsOneSoPartialValidationIsFullValidation() {
        assertEquals(1, P256.CURVE_COFACTOR)
        assertTrue("curve order must be prime for the cofactor argument", P256.CURVE_ORDER.isProbablePrime(64))
    }

    /** The generator must satisfy the very equation the validator applies. A sanity floor. */
    @Test
    fun generatorIsOnTheCurve() {
        assertTrue(isOnCurve(P256.GENERATOR_X, P256.GENERATOR_Y))
    }

    // -- encoding round trip ------------------------------------------------------------------

    @Test
    fun encodesAndDecodesItsOwnKeys() {
        repeat(20) {
            val pair = P256.generateEphemeralKeyPair()
            val encoded = P256.encodePublicKey(pair.public as ECPublicKey)

            assertEquals(65, encoded.size)
            assertEquals(0x04.toByte(), encoded[0])

            val decoded = P256.decodePublicKey(encoded)
            assertEquals((pair.public as ECPublicKey).w.affineX, decoded.w.affineX)
            assertEquals((pair.public as ECPublicKey).w.affineY, decoded.w.affineY)
            // Canonical: re-encoding must reproduce the same bytes, or two devices could feed
            // different `info` into the same key schedule.
            assertArrayEqualsBytes(encoded, P256.encodePublicKey(decoded))
        }
    }

    // -- rejections ---------------------------------------------------------------------------

    /**
     * The headline case: a point whose coordinates are in range and whose encoding is perfect, but
     * which does not satisfy the curve equation.
     */
    @Test
    fun rejectsOffCurvePoint() {
        val valid = P256.encodePublicKey(P256.generateEphemeralKeyPair().public as ECPublicKey)
        val x = BigInteger(1, valid.copyOfRange(1, 33))
        val y = BigInteger(1, valid.copyOfRange(33, 65))
        // y + 1 is not a square root of x^3 + ax + b unless y = -y-1 mod p, which cannot happen
        // for a valid point (it would need 2y = -1, i.e. y = (p-1)/2 * ... ) -- asserted, not assumed.
        val bumped = y.add(BigInteger.ONE).mod(P256.FIELD_PRIME)
        assertFalse("test point must genuinely be off the curve", isOnCurve(x, bumped))

        assertThrowsInvalidPeerKey { P256.decodePublicKey(point(x, bumped)) }
    }

    /**
     * `04 || 00…0 || 00…0` — the "(0, 0)" spelling of the point at infinity people actually try.
     *
     * It is rejected by the curve equation rather than by a special case, because `b != 0` means
     * `0 != b (mod p)`.
     */
    @Test
    fun rejectsPointWithBothCoordinatesZero() {
        assertThrowsInvalidPeerKey { P256.decodePublicKey(point(BigInteger.ZERO, BigInteger.ZERO)) }
    }

    /**
     * A point taken from a *different* curve `y² = x³ + ax + b'`, which is the actual shape of an
     * invalid-curve attack: the attacker picks a curve with small subgroups and sends a
     * small-order point on it.
     */
    @Test
    fun rejectsPointOnADifferentCurve() {
        // Choose x = 2 and y = 3, i.e. b' = y^2 - x^3 - a*x. Some curve contains this point; P-256
        // does not, which is exactly the property under test.
        val x = BigInteger.valueOf(2)
        val y = BigInteger.valueOf(3)
        assertFalse(isOnCurve(x, y))
        assertThrowsInvalidPeerKey { P256.decodePublicKey(point(x, y)) }
    }

    /** SEC1 encodes the point at infinity as the single byte 0x00, which is not 65 bytes long. */
    @Test
    fun rejectsSec1PointAtInfinityEncoding() {
        assertThrowsInvalidPeerKey { P256.decodePublicKey(byteArrayOf(0x00)) }
    }

    @Test
    fun rejectsWrongLengths() {
        val valid = P256.encodePublicKey(P256.generateEphemeralKeyPair().public as ECPublicKey)
        assertThrowsInvalidPeerKey { P256.decodePublicKey(ByteArray(0)) }
        assertThrowsInvalidPeerKey { P256.decodePublicKey(valid.copyOfRange(0, 64)) }
        assertThrowsInvalidPeerKey { P256.decodePublicKey(valid + byteArrayOf(0)) }
    }

    /** Compressed points (0x02 / 0x03) and the hybrid tags are not this wire format. */
    @Test
    fun rejectsNonUncompressedTags() {
        val valid = P256.encodePublicKey(P256.generateEphemeralKeyPair().public as ECPublicKey)
        for (tag in listOf(0x00, 0x01, 0x02, 0x03, 0x05, 0x06, 0x07, 0xFF)) {
            val tampered = valid.copyOf().also { it[0] = tag.toByte() }
            assertThrowsInvalidPeerKey("tag $tag must be rejected") {
                P256.decodePublicKey(tampered)
            }
        }
    }

    /**
     * A coordinate `>= p` is outside the field and is also a non-canonical spelling of the value it
     * reduces to. Both are reasons to refuse rather than reduce.
     */
    @Test
    fun rejectsCoordinatesNotBelowTheFieldPrime() {
        val valid = P256.encodePublicKey(P256.generateEphemeralKeyPair().public as ECPublicKey)
        val x = BigInteger(1, valid.copyOfRange(1, 33))
        val y = BigInteger(1, valid.copyOfRange(33, 65))

        assertThrowsInvalidPeerKey { P256.decodePublicKey(point(P256.FIELD_PRIME, y)) }
        assertThrowsInvalidPeerKey { P256.decodePublicKey(point(x, P256.FIELD_PRIME)) }
        // The largest value a 32-byte field can hold, which is far above p.
        val allOnes = BigInteger(1, ByteArray(32) { 0xFF.toByte() })
        assertThrowsInvalidPeerKey { P256.decodePublicKey(point(allOnes, allOnes)) }
    }

    // -- ECDH ---------------------------------------------------------------------------------

    @Test
    fun twoPartiesAgreeOnTheSameSecret() {
        val a = P256.generateEphemeralKeyPair()
        val b = P256.generateEphemeralKeyPair()

        val fromA = P256.sharedSecret(
            a.private as ECPrivateKey,
            P256.decodePublicKey(P256.encodePublicKey(b.public as ECPublicKey)),
        )
        val fromB = P256.sharedSecret(
            b.private as ECPrivateKey,
            P256.decodePublicKey(P256.encodePublicKey(a.public as ECPublicKey)),
        )

        assertEquals(32, fromA.size)
        assertArrayEqualsBytes(fromA, fromB)
    }

    @Test
    fun differentPeersProduceDifferentSecrets() {
        val a = P256.generateEphemeralKeyPair()
        val b = P256.generateEphemeralKeyPair()
        val c = P256.generateEphemeralKeyPair()

        val withB = P256.sharedSecret(a.private as ECPrivateKey, b.public as ECPublicKey)
        val withC = P256.sharedSecret(a.private as ECPrivateKey, c.public as ECPublicKey)
        assertNotEquals(withB.toHex(), withC.toHex())
    }

    // -- helpers ------------------------------------------------------------------------------

    private fun point(x: BigInteger, y: BigInteger): ByteArray {
        val out = ByteArray(65)
        out[0] = 0x04
        pad32(x).copyInto(out, 1)
        pad32(y).copyInto(out, 33)
        return out
    }

    private fun pad32(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        val magnitude = if (raw.size > 32) raw.copyOfRange(raw.size - 32, raw.size) else raw
        val out = ByteArray(32)
        magnitude.copyInto(out, 32 - magnitude.size)
        return out
    }

    /** The curve equation, written out independently of [P256] so the test does not test itself. */
    private fun isOnCurve(x: BigInteger, y: BigInteger): Boolean {
        val p = P256.FIELD_PRIME
        val lhs = y.multiply(y).mod(p)
        val rhs = x.multiply(x).multiply(x).add(P256.CURVE_A.multiply(x)).add(P256.CURVE_B).mod(p)
        return lhs == rhs
    }
}

/** JUnit 4 has no `assertThrows` with a reified type; this keeps the tests readable. */
internal inline fun assertThrowsInvalidPeerKey(message: String = "", block: () -> Unit) {
    try {
        block()
    } catch (expected: InvalidPeerKeyException) {
        return
    }
    throw AssertionError(
        "expected InvalidPeerKeyException but nothing was thrown" +
            if (message.isEmpty()) "" else " ($message)"
    )
}

internal fun assertArrayEqualsBytes(expected: ByteArray, actual: ByteArray) {
    org.junit.Assert.assertEquals(expected.toHex(), actual.toHex())
}
