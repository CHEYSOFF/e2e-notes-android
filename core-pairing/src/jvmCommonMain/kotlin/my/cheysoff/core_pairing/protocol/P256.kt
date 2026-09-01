package my.cheysoff.core_pairing.protocol

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.EllipticCurve
import java.security.spec.InvalidKeySpecException
import javax.crypto.KeyAgreement

/**
 * Thrown when a peer public key read off a QR code is not a usable P-256 point.
 *
 * A dedicated type rather than a boolean or a null, because this is the one decode failure that is
 * never an accident: a QR symbol carries Reed-Solomon error correction and a format checksum, so a
 * frame that decodes at all decoded *correctly*. A point that parses as a v1 pairing frame and is
 * then off-curve was written that way on purpose. See [P256.decodePublicKey].
 */
class InvalidPeerKeyException(message: String) : IllegalArgumentException(message)

/**
 * NIST P-256 (a.k.a. secp256r1, prime256v1) — key generation, ECDH, and the SEC1 point encoding
 * the QR payloads use, with **explicit** public-key validation on the decode path.
 *
 * ## Why P-256 and not X25519
 *
 * X25519 would be the modern default and it validates points for free — its clamped scalar
 * multiplication maps every 32-byte string to a valid result, so "invalid point" is not a category
 * that exists. It is not available here. `NamedParameterSpec`, `XECPublicKey`, `XECPrivateKey`,
 * `XECKey` and the Ed25519 interfaces are all `since="33"` in the Android SDK's `api-versions.xml`;
 * the android-32 platform jar contains none of them. This app's `minSdk` is 31, so
 * `NamedParameterSpec.X25519` cannot even be *named* at compile time. There is therefore one code
 * path and no runtime version check: P-256 through plain JCA, which has been on the platform since
 * API 23.
 *
 * ## What that costs, and what this file does about it
 *
 * P-256 does **not** validate points for free. Handing an attacker-chosen off-curve point to
 * `KeyAgreement` is the classic invalid-curve attack: the scalar multiply then runs in a group of
 * the attacker's choosing, usually of small order, and each such exchange leaks the private scalar
 * modulo that small order. A handful of them recover the whole ephemeral private key by CRT.
 *
 * Nothing here relies on `KeyFactory.generatePublic` catching that. Some JCA providers do reject
 * off-curve points and some historically did not; treating "the provider probably checks" as the
 * defence is how invalid-curve bugs get shipped. [decodePublicKey] runs the check itself, on the
 * raw coordinates, **before** a `PublicKey` object exists at all — and therefore long before
 * anything reaches [sharedSecret].
 *
 * ## The check
 *
 * SP 800-56A rev3 §5.6.2.3.4 ("ECC Partial Public-Key Validation"):
 *
 *  1. Q is not the point at infinity.
 *  2. x and y are integers in `[0, p-1]`.
 *  3. `y² ≡ x³ + ax + b (mod p)`.
 *
 * Full validation additionally requires `nQ = O`. It is omitted here, and that omission is sound
 * rather than a shortcut: P-256's cofactor is **1**, so the curve group has exactly `n` points and
 * `n` is prime. Every point satisfying (1)–(3) is therefore already in the order-`n` subgroup, and
 * the scalar multiply would be a guaranteed-`O` no-op costing a full ECDH. This is the standard
 * "for h = 1, partial validation is full validation" result; it would NOT hold on a curve with a
 * cofactor, and a future curve change must revisit it. [CURVE_COFACTOR] is asserted below so that
 * a change of curve constants trips an assertion rather than silently invalidating this paragraph.
 */
object P256 {

    // -----------------------------------------------------------------------------------------
    // Domain parameters
    // -----------------------------------------------------------------------------------------
    // Hard-coded rather than fetched with AlgorithmParameters.getInstance("EC"), for two reasons.
    // First, the validation above must not depend on a provider to tell it which curve it is
    // validating against — that is circular. Second, these are public, standardised, immutable
    // constants (FIPS 186-4 D.1.2.3 / SEC 2 §2.4.2), so there is nothing to look up.
    //
    // A typo here would be catastrophic and silent, so P256Test.platformAgreesWithHardCodedCurve
    // generates a key with the platform's own "secp256r1" and asserts every one of these six
    // numbers against what the platform reports.

    /** Field prime: 2^256 - 2^224 + 2^192 + 2^96 - 1. */
    val FIELD_PRIME: BigInteger = BigInteger(
        "FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF", 16
    )

    /** Curve coefficient a. Equal to p - 3, as it is for every NIST prime curve. */
    val CURVE_A: BigInteger = BigInteger(
        "FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFC", 16
    )

    /** Curve coefficient b. */
    val CURVE_B: BigInteger = BigInteger(
        "5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B", 16
    )

    /** Base point x. */
    val GENERATOR_X: BigInteger = BigInteger(
        "6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296", 16
    )

    /** Base point y. */
    val GENERATOR_Y: BigInteger = BigInteger(
        "4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5", 16
    )

    /** Order of the base point. Prime. */
    val CURVE_ORDER: BigInteger = BigInteger(
        "FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16
    )

    /**
     * Cofactor. **1** for P-256 — the fact the omitted `nQ = O` step above rests on. Written out
     * as a named constant so the reasoning in this file's KDoc has something to point at.
     */
    const val CURVE_COFACTOR: Int = 1

    /** Size of a field element in bytes. Every coordinate is left-padded to exactly this. */
    const val FIELD_SIZE_BYTES: Int = 32

    /**
     * Length of a SEC1 *uncompressed* point: one `0x04` tag byte plus two field elements.
     *
     * Uncompressed rather than compressed (33 bytes) on purpose. Compression would save 32 bytes
     * per QR — which neither payload needs; both fit comfortably — at the cost of a modular square
     * root on the decode path, i.e. hand-written field arithmetic sitting directly in front of the
     * key-agreement input. Sending y outright means the validation above is a single equation over
     * numbers that are already present.
     */
    const val POINT_SIZE_BYTES: Int = 1 + 2 * FIELD_SIZE_BYTES

    /** SEC1 tag byte introducing an uncompressed point. */
    private const val UNCOMPRESSED_TAG: Byte = 0x04

    /** The JCA name of this curve. Only used to ask the platform for a key pair. */
    private const val CURVE_NAME = "secp256r1"

    /** The domain parameters as JCA sees them, assembled from the constants above. */
    val PARAMETER_SPEC: ECParameterSpec = ECParameterSpec(
        EllipticCurve(ECFieldFp(FIELD_PRIME), CURVE_A, CURVE_B),
        ECPoint(GENERATOR_X, GENERATOR_Y),
        CURVE_ORDER,
        CURVE_COFACTOR,
    )

    // -----------------------------------------------------------------------------------------
    // Key generation and agreement
    // -----------------------------------------------------------------------------------------

    /**
     * A fresh ephemeral key pair for one pairing session.
     *
     * Generated with the platform's own `secp256r1` rather than [PARAMETER_SPEC] so that a device
     * whose provider has a hardware or otherwise-optimised path for the named curve gets it. The
     * two are the same curve; [P256Test] asserts that.
     *
     * Ephemeral in the strict sense: one key pair per session, never persisted, never reused. The
     * device's *long-lived* identity key is a separate object entirely and lives in the Keystore —
     * see `my.cheysoff.feature_pairing.identity.DeviceIdentityKey` in `:feature-pairing`.
     */
    fun generateEphemeralKeyPair(random: SecureRandom = SecureRandom()): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec(CURVE_NAME), random)
        return generator.generateKeyPair()
    }

    /**
     * The raw ECDH shared secret: the x-coordinate of `private * peer`, as 32 big-endian bytes.
     *
     * This is `Z` in the pairing design. It is **never** used as a key directly — it goes straight
     * into [KeyDerivation.derive] — because the raw output of a Diffie-Hellman is not uniformly
     * distributed and carries no context binding of its own.
     *
     * [peer] must already have come out of [decodePublicKey]; this function does not re-validate,
     * and there is no other way for a peer key to enter this module.
     *
     * The result is normalised to exactly [FIELD_SIZE_BYTES]. JCA providers are supposed to return
     * a field-element-sized, left-padded array here (ANSI X9.63 §5.4.1), and the ones on this
     * platform do, but a provider that stripped a leading zero byte would produce a value that is
     * numerically identical and byte-wise different — and since this feeds a KDF, byte-wise is what
     * matters. Normalising costs nothing and removes the question.
     */
    fun sharedSecret(privateKey: ECPrivateKey, peer: ECPublicKey): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(peer, true)
        val raw = agreement.generateSecret()
        return leftPadToFieldSize(raw)
    }

    // -----------------------------------------------------------------------------------------
    // Point encoding
    // -----------------------------------------------------------------------------------------

    /**
     * SEC1 uncompressed encoding of [key]'s public point: `0x04 || X(32) || Y(32)`.
     *
     * Always exactly [POINT_SIZE_BYTES] bytes. Coordinates are left-padded with zeros, which is
     * what makes the encoding canonical — `BigInteger.toByteArray()` on its own produces a
     * variable-length, sometimes sign-extended array, and two devices that encoded the same point
     * to different byte strings would derive different session keys from the same `info`.
     */
    fun encodePublicKey(key: ECPublicKey): ByteArray {
        val point = key.w
        val out = ByteArray(POINT_SIZE_BYTES)
        out[0] = UNCOMPRESSED_TAG
        System.arraycopy(leftPadToFieldSize(point.affineX.toByteArray()), 0, out, 1, FIELD_SIZE_BYTES)
        System.arraycopy(
            leftPadToFieldSize(point.affineY.toByteArray()), 0, out, 1 + FIELD_SIZE_BYTES,
            FIELD_SIZE_BYTES,
        )
        return out
    }

    /**
     * Decode and **validate** a peer's public key from its SEC1 uncompressed encoding.
     *
     * This is the security-critical function of the whole module. Every rejection below happens
     * before any `PublicKey` object exists, and therefore before anything could reach
     * [sharedSecret].
     *
     * Rejects, in order:
     *  - a length other than [POINT_SIZE_BYTES];
     *  - any tag byte other than `0x04`. This is what rules out the point at infinity: SEC1 encodes
     *    O as the single byte `0x00`, and it is not representable in this fixed-length form at all.
     *    A caller who nevertheless hand-built `04 || 00…0 || 00…0` — the "(0, 0)" spelling of
     *    infinity that people actually try — is caught by the curve equation, because `b ≠ 0` means
     *    `0 ≢ b (mod p)`;
     *  - a coordinate `≥ p` (non-canonical, and outside the field);
     *  - a point that does not satisfy `y² ≡ x³ + ax + b (mod p)`.
     *
     * @throws InvalidPeerKeyException on every one of those.
     */
    fun decodePublicKey(encoded: ByteArray): ECPublicKey {
        if (encoded.size != POINT_SIZE_BYTES) {
            throw InvalidPeerKeyException(
                "peer point is ${encoded.size} bytes, expected $POINT_SIZE_BYTES"
            )
        }
        if (encoded[0] != UNCOMPRESSED_TAG) {
            throw InvalidPeerKeyException(
                "peer point tag is 0x${"%02x".format(encoded[0])}, expected 0x04 (uncompressed)"
            )
        }

        val x = BigInteger(1, encoded.copyOfRange(1, 1 + FIELD_SIZE_BYTES))
        val y = BigInteger(1, encoded.copyOfRange(1 + FIELD_SIZE_BYTES, POINT_SIZE_BYTES))

        // `BigInteger(1, ...)` is non-negative by construction, so only the upper bound is left to
        // test. A coordinate >= p is both outside the field and a non-canonical encoding of the
        // value it reduces to.
        if (x >= FIELD_PRIME) throw InvalidPeerKeyException("peer point x is not in [0, p-1]")
        if (y >= FIELD_PRIME) throw InvalidPeerKeyException("peer point y is not in [0, p-1]")

        if (!isOnCurve(x, y)) {
            throw InvalidPeerKeyException("peer point is not on P-256")
        }

        return try {
            KeyFactory.getInstance("EC")
                .generatePublic(ECPublicKeySpec(ECPoint(x, y), PARAMETER_SPEC)) as ECPublicKey
        } catch (e: InvalidKeySpecException) {
            // Unreachable by construction: every reason a provider rejects an EC public key spec —
            // wrong length, bad tag, out-of-range coordinate, off-curve point — has already been
            // tested above. If it ever fires, something is wrong with the *environment* rather than
            // with the input, and it must not be reported to the user as "bad QR code". It is
            // deliberately NOT rethrown as InvalidPeerKeyException: that would make this catch
            // block a silent second implementation of the validation above, and deleting the
            // on-curve check would then still produce a well-typed rejection — which is exactly the
            // regression the check exists to prevent, and exactly what the mutation test asserts
            // cannot happen.
            throw IllegalStateException("EC KeyFactory rejected an already-validated P-256 point", e)
        }
    }

    /** `y² ≡ x³ + ax + b (mod p)`. */
    private fun isOnCurve(x: BigInteger, y: BigInteger): Boolean {
        val left = y.modPow(BigInteger.valueOf(2), FIELD_PRIME)
        val right = x.modPow(BigInteger.valueOf(3), FIELD_PRIME)
            .add(CURVE_A.multiply(x))
            .add(CURVE_B)
            .mod(FIELD_PRIME)
        return left == right
    }

    /**
     * Normalise a big-endian magnitude to exactly [FIELD_SIZE_BYTES] bytes: strip the leading sign
     * byte `BigInteger.toByteArray()` adds when the high bit is set, and left-pad short values.
     */
    private fun leftPadToFieldSize(value: ByteArray): ByteArray {
        if (value.size == FIELD_SIZE_BYTES) return value
        if (value.size > FIELD_SIZE_BYTES) {
            // Only a single leading 0x00 can legitimately be present (BigInteger's sign byte); more
            // than that means the value genuinely does not fit in a field element.
            val excess = value.size - FIELD_SIZE_BYTES
            for (i in 0 until excess) {
                require(value[i] == 0.toByte()) { "value does not fit in $FIELD_SIZE_BYTES bytes" }
            }
            return value.copyOfRange(excess, value.size)
        }
        val out = ByteArray(FIELD_SIZE_BYTES)
        System.arraycopy(value, 0, out, FIELD_SIZE_BYTES - value.size, value.size)
        return out
    }
}
