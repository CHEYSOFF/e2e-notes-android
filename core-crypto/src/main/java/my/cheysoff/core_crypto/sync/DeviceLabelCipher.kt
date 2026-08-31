package my.cheysoff.core_crypto.sync

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Seals a device's human-readable name so the sync server stores a blob instead of "Vova's Pixel 7".
 *
 * ```
 * sealed    := ver(1B) ‖ nonce(12B) ‖ ciphertext(128B) ‖ tag(16B)     157 bytes, always
 * key       := HKDF(ARK, "manana/sync/v1/devicelabel")
 * AAD       := ver ‖ devicePublicKeyB64                               (length-prefixed)
 * plaintext := uint16be(len) ‖ utf8(label) ‖ zero filler              padded to a constant 128 B
 * ```
 *
 * ## Why the label is encrypted rather than dropped
 *
 * The server has no legitimate use for it: it never matches on the label, never orders by it and
 * never shows it to anyone but the account's own devices. Leaving it in the clear was a pure gift
 * to an operator, since a name people actually type identifies the *person* as readily as the
 * device.
 *
 * Dropping the field outright was the other option and it was rejected, because the label has one
 * job that matters and it is a security job: it is how a user picks the right row in the device
 * list when revoking a lost phone. A base64 public-key fingerprint is a bad substitute — a user who
 * cannot tell the two rows apart revokes the wrong device, and revoking the wrong device is a
 * worse outcome than the operator knowing a phone model.
 *
 * ## Why the plaintext is a constant size
 *
 * A bucket scheme would still leak the length of the name, and a device list is a handful of rows,
 * so there is no length distribution to hide behind. Every sealed label is padded to
 * [SyncProtocol.DEVICE_LABEL_PLAINTEXT_BYTES] and is therefore byte-for-byte the same size,
 * whatever the name. At one label per device that fixed cost is not worth measuring.
 *
 * ## What the associated data buys, and what it does not
 *
 * The label is bound to the device public key it was enrolled with, so an operator cannot move one
 * device's name onto another row: the tag stops verifying and [open] returns null.
 *
 * It is **not** bound to the enrolment signature. `server/README.md` specifies the signed message
 * byte-for-byte as `("claim", accountId, devicePublicKeyB64, ts)` — the label is not one of its
 * fields, and it was not one before this class existed either. So an attacker positioned between a
 * device and the server can still substitute a *different* sealed blob for a device's label. The
 * result is that [open] returns null and the UI shows an unnamed device; it cannot be made to show
 * an attacker's chosen text, because the attacker cannot seal one without the ARK. Widening the
 * signed message would be a protocol change reaching well past this file, and the outcome it would
 * buy — "unnamed" versus "unnamed, and the request rejected" — does not pay for it.
 *
 * Pure `javax.crypto` / `java.security` — no Android, no state beyond the RNG, unit-testable.
 * Never logs the ARK, the derived key, or the label.
 */
object DeviceLabelCipher {

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_ALGORITHM = "AES"
    private const val TAG_BITS = SyncProtocol.TAG_BYTES * 8
    private const val LENGTH_PREFIX_BYTES = 2

    /**
     * The longest label this can seal, in UTF-8 bytes. A caller that accepts free text from a user
     * must cap it at this before calling [seal]; truncating here would risk cutting a multi-byte
     * character in half and producing a name with a replacement glyph in it.
     */
    const val MAX_LABEL_UTF8_BYTES =
        SyncProtocol.DEVICE_LABEL_PLAINTEXT_BYTES - LENGTH_PREFIX_BYTES

    /** Exact size of every sealed label. Constant by construction — see the class KDoc. */
    const val SEALED_BYTES =
        1 + SyncProtocol.NONCE_BYTES + SyncProtocol.DEVICE_LABEL_PLAINTEXT_BYTES +
            SyncProtocol.TAG_BYTES

    private val secureRandom = SecureRandom()

    /**
     * Seals [label] for the device enrolling with [devicePublicKeyB64], under a key derived from
     * [ark].
     *
     * [devicePublicKeyB64] must be exactly the string sent in the enrolment request, since that is
     * what the opening device will have. [ark] is not modified and remains the caller's.
     *
     * Throws [IllegalArgumentException] if [label] does not fit in [MAX_LABEL_UTF8_BYTES]; that is
     * a caller bug, and silently storing a truncated name would be worse.
     */
    fun seal(ark: ByteArray, devicePublicKeyB64: String, label: String): ByteArray {
        val text = label.toByteArray(Charsets.UTF_8)
        require(text.size <= MAX_LABEL_UTF8_BYTES) {
            "device label is ${text.size} UTF-8 bytes, at most $MAX_LABEL_UTF8_BYTES fit"
        }

        val padded = ByteArray(SyncProtocol.DEVICE_LABEL_PLAINTEXT_BYTES)
        padded[0] = (text.size ushr 8).toByte()
        padded[1] = text.size.toByte()
        text.copyInto(padded, destinationOffset = LENGTH_PREFIX_BYTES)

        val key = deriveKey(ark)
        try {
            val nonce = ByteArray(SyncProtocol.NONCE_BYTES).also(secureRandom::nextBytes)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, KEY_ALGORITHM),
                GCMParameterSpec(TAG_BITS, nonce),
            )
            cipher.updateAAD(associatedData(devicePublicKeyB64))
            val sealed = cipher.doFinal(padded)

            val out = ByteArray(1 + nonce.size + sealed.size)
            out[0] = SyncProtocol.DEVICE_LABEL_VERSION
            nonce.copyInto(out, destinationOffset = 1)
            sealed.copyInto(out, destinationOffset = 1 + nonce.size)
            return out
        } finally {
            key.fill(0)
            padded.fill(0)
        }
    }

    /**
     * Opens a sealed label, or returns null if it does not authenticate under [ark] and
     * [devicePublicKeyB64].
     *
     * Null is the ordinary case a UI must handle, not an exception: a device list can contain a row
     * whose label was written by a device on a different account, or substituted in transit, or
     * simply written by a future app version. Show such a row as unnamed and let the user identify
     * it by its key fingerprint; do not hide it, because a device the user cannot see is a device
     * the user cannot revoke.
     */
    fun open(ark: ByteArray, devicePublicKeyB64: String, sealed: ByteArray): String? {
        if (sealed.size != SEALED_BYTES) return null
        if (sealed[0] != SyncProtocol.DEVICE_LABEL_VERSION) return null

        val nonce = sealed.copyOfRange(1, 1 + SyncProtocol.NONCE_BYTES)
        val body = sealed.copyOfRange(1 + SyncProtocol.NONCE_BYTES, sealed.size)
        val key = deriveKey(ark)
        var padded: ByteArray? = null
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, KEY_ALGORITHM),
                GCMParameterSpec(TAG_BITS, nonce),
            )
            cipher.updateAAD(associatedData(devicePublicKeyB64))
            padded = cipher.doFinal(body)

            val length = ((padded[0].toInt() and 0xFF) shl 8) or (padded[1].toInt() and 0xFF)
            // This block has already passed tag verification, so a length that does not fit means
            // a bug rather than an attacker — but it still must not index off the end.
            if (length > padded.size - LENGTH_PREFIX_BYTES) return null
            return String(padded, LENGTH_PREFIX_BYTES, length, Charsets.UTF_8)
        } catch (_: GeneralSecurityException) {
            // Wrong ARK, a different device's key in the associated data, or a modified blob.
            return null
        } catch (_: IllegalArgumentException) {
            // GCMParameterSpec rejects some malformed nonces outright. Same answer.
            return null
        } finally {
            key.fill(0)
            padded?.fill(0)
        }
    }

    /**
     * `ver ‖ devicePublicKeyB64`, the variable-length field preceded by its 2-byte big-endian
     * length.
     *
     * `internal` so the tests can assert the binding directly. The length prefix is consistent with
     * [RecordEnvelope.associatedData] and, as there, is insurance against a second field rather
     * than a live defence with only one.
     */
    internal fun associatedData(devicePublicKeyB64: String): ByteArray {
        val bytes = devicePublicKeyB64.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 0xFFFF) { "associated-data field is too long to length-prefix" }
        val out = ByteArray(3 + bytes.size)
        out[0] = SyncProtocol.DEVICE_LABEL_VERSION
        out[1] = (bytes.size ushr 8).toByte()
        out[2] = bytes.size.toByte()
        bytes.copyInto(out, destinationOffset = 3)
        return out
    }

    private fun deriveKey(ark: ByteArray): ByteArray {
        require(ark.size == SyncProtocol.ARK_BYTES) {
            "ARK must be ${SyncProtocol.ARK_BYTES} bytes, was ${ark.size}"
        }
        return Hkdf.derive(
            ikm = ark,
            salt = null,
            info = SyncProtocol.INFO_DEVICE_LABEL.toByteArray(Charsets.US_ASCII),
            length = SyncProtocol.DERIVED_KEY_BYTES,
        )
    }
}
