package manana.sync.server

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.EllipticCurve

/**
 * The canonical byte encoding of everything a device signs.
 *
 * ```
 * message := lp("manana/sync/v1/sig") ‖ lp(purpose) ‖ lp(field_1) ‖ … ‖ lp(field_n)
 * lp(s)   := uint16be(len(utf8(s))) ‖ utf8(s)
 * ```
 *
 * **This is a protocol constant. The client must build these bytes identically or every signature
 * it makes is rejected**, and there is no negotiation step -- the same rule, and the same reason,
 * as `core-crypto/.../sync/SyncProtocol.kt`. It is written down in `server/README.md` and in
 * `docs/design/e2e-sync-phase3-plan.md` so the client has a specification to implement against
 * rather than this file to read.
 *
 * Every field is length-prefixed for the same reason `RecordEnvelope.associatedData` prefixes its
 * fields: plain concatenation is ambiguous across adjacent variable-length fields, so an
 * `authorize` for account `AB` with key `C…` and one for account `A` with key `BC…` would produce
 * the same bytes, and a signature over one would verify as the other. Length prefixes make the
 * encoding injective.
 *
 * The domain string is itself prefixed so that no purpose can ever be confused with a longer
 * domain, and the purpose is a separate field so a `session` signature can never be replayed as an
 * `authorize`.
 */
object SignedMessage {

    private const val DOMAIN = "manana/sync/v1/sig"

    const val PURPOSE_CLAIM = "claim"
    const val PURPOSE_AUTHORIZE = "authorize"
    const val PURPOSE_SESSION = "session"

    /** `("claim", accountId, devicePublicKey, ts)` -- self-signed by the very first device. */
    fun claim(accountId: String, publicKeyB64: String, ts: Long): ByteArray =
        encode(PURPOSE_CLAIM, accountId, publicKeyB64, ts.toString())

    /** `("authorize", accountId, newPubKey, ts)` -- signed by an already-enrolled device. */
    fun authorize(accountId: String, newPublicKeyB64: String, ts: Long): ByteArray =
        encode(PURPOSE_AUTHORIZE, accountId, newPublicKeyB64, ts.toString())

    /** `("session", accountId, deviceId, challenge)` -- signed to redeem a server challenge. */
    fun session(accountId: String, deviceId: String, challenge: String): ByteArray =
        encode(PURPOSE_SESSION, accountId, deviceId, challenge)

    internal fun encode(purpose: String, vararg fields: String): ByteArray {
        val out = ByteArrayOutputStream()
        writeLengthPrefixed(out, DOMAIN)
        writeLengthPrefixed(out, purpose)
        for (field in fields) writeLengthPrefixed(out, field)
        return out.toByteArray()
    }

    private fun writeLengthPrefixed(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 0xFFFF) { "signed-message field is too long to length-prefix" }
        out.write((bytes.size ushr 8) and 0xFF)
        out.write(bytes.size and 0xFF)
        out.write(bytes, 0, bytes.size)
    }
}

/**
 * NIST P-256 public-key decoding, with explicit point validation, and ECDSA verification.
 *
 * This is the server-side half of `feature-pairing/.../protocol/P256.kt`; the two must agree on
 * the encoding (SEC1 uncompressed, `0x04 ‖ X(32) ‖ Y(32)`) and on the signature algorithm
 * (`SHA256withECDSA`, so DER-encoded signatures). It is a separate implementation rather than a
 * shared module because this build is standalone and must not depend on an Android library.
 *
 * ### Why the server validates points even though it never performs a key agreement
 *
 * The invalid-curve attack the client's `P256` guards against does not apply here: the server only
 * ever *verifies* with these keys, never multiplies a secret scalar by them, so there is no
 * private value to leak. The validation is still done, for a different reason -- a stored key that
 * is not a P-256 point is a key no device can ever sign for, so accepting one enrols a device slot
 * that can never authenticate and can never be distinguished from a legitimate one. Rejecting it
 * at the door keeps `devices` a table of keys that are all, by construction, usable.
 */
object P256Verify {

    private val FIELD_PRIME = BigInteger(
        "FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF", 16
    )
    private val CURVE_A = BigInteger(
        "FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFC", 16
    )
    private val CURVE_B = BigInteger(
        "5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B", 16
    )
    private val GENERATOR_X = BigInteger(
        "6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296", 16
    )
    private val GENERATOR_Y = BigInteger(
        "4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5", 16
    )
    private val CURVE_ORDER = BigInteger(
        "FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16
    )

    private const val FIELD_SIZE_BYTES = 32
    private const val UNCOMPRESSED_TAG: Byte = 0x04
    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

    /** Length of a SEC1 uncompressed P-256 point: tag byte plus two 32-byte coordinates. */
    const val POINT_SIZE_BYTES: Int = 1 + 2 * FIELD_SIZE_BYTES

    private val parameterSpec = ECParameterSpec(
        EllipticCurve(ECFieldFp(FIELD_PRIME), CURVE_A, CURVE_B),
        ECPoint(GENERATOR_X, GENERATOR_Y),
        CURVE_ORDER,
        1,
    )

    /**
     * Decodes and validates a SEC1 uncompressed P-256 point. Returns null -- never throws -- if the
     * length, the tag byte, a coordinate range or the curve equation `y² ≡ x³ + ax + b (mod p)`
     * does not hold.
     *
     * P-256's cofactor is 1 and its order is prime, so any point satisfying those checks is already
     * in the full group; the `nQ = O` step of full public-key validation is redundant here.
     */
    fun decodePublicKey(encoded: ByteArray): ECPublicKey? {
        if (encoded.size != POINT_SIZE_BYTES) return null
        if (encoded[0] != UNCOMPRESSED_TAG) return null

        val x = BigInteger(1, encoded.copyOfRange(1, 1 + FIELD_SIZE_BYTES))
        val y = BigInteger(1, encoded.copyOfRange(1 + FIELD_SIZE_BYTES, POINT_SIZE_BYTES))
        if (x >= FIELD_PRIME || y >= FIELD_PRIME) return null
        if (!isOnCurve(x, y)) return null

        return try {
            KeyFactory.getInstance("EC")
                .generatePublic(ECPublicKeySpec(ECPoint(x, y), parameterSpec)) as ECPublicKey
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    /**
     * Verifies a DER-encoded `SHA256withECDSA` signature over [message] under [publicKeyEncoded].
     *
     * False covers every failure identically -- an unusable key, a malformed DER signature, and a
     * well-formed signature that simply does not verify -- because a caller has nothing useful to
     * do with the distinction and telling them apart would only invite one of them to be treated as
     * recoverable.
     */
    fun verify(publicKeyEncoded: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        val key = decodePublicKey(publicKeyEncoded) ?: return false
        return try {
            Signature.getInstance(SIGNATURE_ALGORITHM).run {
                initVerify(key)
                update(message)
                verify(signature)
            }
        } catch (_: GeneralSecurityException) {
            false
        }
    }

    private fun isOnCurve(x: BigInteger, y: BigInteger): Boolean {
        val left = y.modPow(BigInteger.TWO, FIELD_PRIME)
        val right = x.modPow(BigInteger.valueOf(3), FIELD_PRIME)
            .add(CURVE_A.multiply(x))
            .add(CURVE_B)
            .mod(FIELD_PRIME)
        return left == right
    }
}
