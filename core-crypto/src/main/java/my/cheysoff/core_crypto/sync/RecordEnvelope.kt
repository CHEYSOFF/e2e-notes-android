package my.cheysoff.core_crypto.sync

import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The record envelope — the only form in which note data is allowed to leave the device.
 *
 * ```
 * envelope := ver(1B) ‖ nonce(12B) ‖ ciphertext ‖ tag(16B)
 * key      := HKDF(K_content, "manana/rec/v1" ‖ blindedId)          per-record
 * nonce    := 12 bytes from SecureRandom                            NOT a counter
 * AAD      := ver ‖ recType ‖ blindedId ‖ hlc                       (canonical encoding below)
 * plaintext:= RecordPadding.pad(payload)                            256-byte buckets
 * ```
 *
 * ### Why the nonce is random and must stay random
 *
 * Repeating a (key, nonce) pair under GCM is catastrophic, not merely weak: it leaks the XOR of
 * the two plaintexts and — far worse — allows recovery of the GHASH authentication subkey, after
 * which an attacker can forge arbitrary records for that key. The tempting "optimisation" is a
 * counter, since counters never repeat *within one running process*. They repeat constantly across
 * the events this app actually has: a device restored from a backup resumes at an old counter, a
 * process killed between "increment" and "persist" replays one, and two paired devices sealing the
 * same record start from the same place.
 *
 * The defence here is two-layered. Keys are **per record**, derived from the blinded ID, so each
 * key encrypts on the order of one message per record version — with 96-bit random nonces the
 * birthday bound is not remotely approached even if a record were rewritten billions of times.
 * And the nonce is drawn from [SecureRandom] on every seal, so no persisted state exists that a
 * restore or a crash could rewind. **Do not replace [SecureRandom] with a counter here.**
 *
 * ### Why `hlc` is in the AAD
 *
 * A malicious or rolled-back server can serve an *old* envelope for a record. That blob is
 * genuinely authentic, so AEAD alone cannot detect it. Binding the record's hybrid logical clock
 * into the associated data means the client — which reads the `hlc` from outside the envelope
 * before decrypting — can only open the blob if the outer `hlc` matches the one it was sealed
 * with. ⚠️ The client must **also** compare the outer `hlc` against the copy inside the decrypted
 * payload; this class authenticates the value it is handed, it cannot know whether that value is
 * the one the caller intended.
 *
 * Pure `javax.crypto` / `java.security` — no Android, no state beyond the RNG, unit-testable.
 * Never logs key material, plaintext, or the ARK-derived keys it is given.
 */
object RecordEnvelope {

    private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_ALGORITHM = "AES"
    private const val TAG_BITS = SyncProtocol.TAG_BYTES * 8

    /** Smallest structurally valid envelope: version + nonce + tag, with empty ciphertext. */
    private const val MIN_ENVELOPE_BYTES =
        1 + SyncProtocol.NONCE_BYTES + SyncProtocol.TAG_BYTES

    private val secureRandom = SecureRandom()

    /**
     * Seals [payload] into an envelope for the record ([recType], [blindedId]) at clock [hlc].
     *
     * [payload] is padded to a 256-byte bucket first, so the returned length reveals only the
     * bucket count. [kContent] is not modified and remains the caller's, as does [payload]; the
     * padded plaintext copy this function makes is zeroed before returning.
     */
    fun seal(
        kContent: ByteArray,
        recType: String,
        blindedId: String,
        hlc: String,
        payload: ByteArray,
    ): ByteArray {
        val padded = RecordPadding.pad(payload)
        val nonce = ByteArray(SyncProtocol.NONCE_BYTES).also(secureRandom::nextBytes)
        val key = perRecordKey(kContent, blindedId)
        try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(associatedData(recType, blindedId, hlc))
            // JCA returns ciphertext ‖ tag concatenated, which is exactly the envelope's tail.
            val sealed = cipher.doFinal(padded)

            val envelope = ByteArray(1 + nonce.size + sealed.size)
            envelope[0] = SyncProtocol.ENVELOPE_VERSION
            nonce.copyInto(envelope, destinationOffset = 1)
            sealed.copyInto(envelope, destinationOffset = 1 + nonce.size)
            return envelope
        } finally {
            // `padded` holds the plaintext in the clear; drop it as soon as it is sealed.
            padded.fill(0)
        }
    }

    /**
     * Opens [envelope] for the record ([recType], [blindedId]) at clock [hlc], returning the
     * unpadded payload — or null if it does not authenticate.
     *
     * Null covers every failure the same way, deliberately: a wrong key, a flipped ciphertext bit,
     * a tampered nonce, a mismatched `recType`/`blindedId`/`hlc`, a truncated blob and an
     * unsupported version are indistinguishable to a caller and should all be handled as "this
     * record cannot be trusted". Distinguishing them here would only invite a caller to treat some
     * of them as recoverable.
     */
    fun open(
        kContent: ByteArray,
        recType: String,
        blindedId: String,
        hlc: String,
        envelope: ByteArray,
    ): ByteArray? {
        if (envelope.size < MIN_ENVELOPE_BYTES) return null
        if (envelope[0] != SyncProtocol.ENVELOPE_VERSION) return null

        val nonce = envelope.copyOfRange(1, 1 + SyncProtocol.NONCE_BYTES)
        val body = envelope.copyOfRange(1 + SyncProtocol.NONCE_BYTES, envelope.size)
        val key = perRecordKey(kContent, blindedId)
        var padded: ByteArray? = null
        try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(associatedData(recType, blindedId, hlc))
            padded = cipher.doFinal(body)
            return RecordPadding.unpad(padded)
        } catch (_: AEADBadTagException) {
            // Tag mismatch: wrong key, altered ciphertext/nonce, or associated data that does not
            // match what this record was sealed with.
            return null
        } catch (_: GeneralSecurityException) {
            // Any other crypto failure — input a provider rejects before it reaches tag
            // verification, or a provider that reports a bad tag as something other than
            // AEADBadTagException. No unit test reaches this branch against SunJCE, because every
            // input this class can construct either passes the structural checks above or fails
            // as a tag mismatch; it is here so that a different provider on a real device degrades
            // to "cannot open this record" instead of throwing out of `open`. Same defensive
            // shape, and the same reasoning, as `PassphraseCipher.unwrapWithPin`.
            return null
        } finally {
            padded?.fill(0)
        }
    }

    /**
     * Derives the per-record AES key: `HKDF(K_content, "manana/rec/v1" ‖ blindedId)`.
     *
     * `K_content` is already uniformly random, so the HKDF-Extract step is not strictly buying
     * entropy concentration here; it is used anyway because it costs one HMAC and keeps this
     * derivation the same shape as every other one in the protocol.
     *
     * `internal` rather than private so the unit tests can assert on the derived bytes directly.
     * That is not gold-plating: `blindedId` feeds both this derivation *and* the associated data,
     * so from the outside, removing it from either one alone changes nothing observable — the
     * other still separates the records. Only a direct assertion can show that this half is
     * actually wired up. Nothing outside this module may call it; it returns live key material.
     */
    internal fun perRecordKeyBytes(kContent: ByteArray, blindedId: String): ByteArray {
        val info = (SyncProtocol.INFO_RECORD_KEY_PREFIX + blindedId).toByteArray(Charsets.UTF_8)
        return Hkdf.derive(
            ikm = kContent,
            salt = null,
            info = info,
            length = SyncProtocol.DERIVED_KEY_BYTES,
        )
    }

    private fun perRecordKey(kContent: ByteArray, blindedId: String): SecretKeySpec {
        val keyBytes = perRecordKeyBytes(kContent, blindedId)
        val key = SecretKeySpec(keyBytes, KEY_ALGORITHM)
        // SecretKeySpec's constructor takes its own copy, so clearing our intermediate array here
        // removes one copy of the key from the heap. It does NOT remove the SecretKeySpec's copy:
        // `getEncoded()` hands back a fresh clone rather than the internal array, and
        // `SecretKeySpec` does not implement a working `destroy()`, so the JDK offers no way to
        // wipe it. Same trade-off, and the same limitation, as `PassphraseCipher.deriveKey`.
        keyBytes.fill(0)
        return key
    }

    /**
     * Builds the associated data: `ver ‖ recType ‖ blindedId ‖ hlc`, each variable-length field
     * preceded by its 2-byte big-endian length.
     *
     * The length prefixes are a deliberate strengthening of the plain concatenation in the design
     * doc, and they cost six bytes of AAD that never travel over the wire. Without them the
     * encoding is ambiguous across any two *adjacent* variable-length fields: `recType="note"`
     * with `blindedId="AB…"` and `recType="not"` with `blindedId="eAB…"` concatenate to the same
     * bytes, so one record's envelope would authenticate under the other's labels. Length-prefixing
     * makes the encoding injective, so every distinct field triple gets distinct associated data.
     *
     * Honesty about what that buys *today*: with the current field order, both boundaries that
     * could be shifted have `blindedId` on one side of them, and `blindedId` also selects the
     * per-record key — so a shifted-boundary envelope already fails to decrypt for an unrelated
     * reason, and no seal/open test can observe the difference. The prefixes are insurance against
     * a fourth field, a reordering, or a future variant that stops deriving keys per record; the
     * property is asserted directly on this function instead.
     *
     * The leading version byte is likewise belt-and-braces: [open] rejects an unknown version
     * structurally before it ever reaches the cipher. It is in the AAD so that if a version 2 is
     * ever added and both are accepted, a v1 envelope can never be reinterpreted as v2.
     *
     * Both devices must build this identically — it is as much a protocol constant as the strings
     * in [SyncProtocol], and changing it invalidates every existing envelope. `internal` so the
     * unit tests can assert the injectivity that seal/open cannot expose.
     */
    internal fun associatedData(recType: String, blindedId: String, hlc: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(SyncProtocol.ENVELOPE_VERSION.toInt())
        writeLengthPrefixed(out, recType)
        writeLengthPrefixed(out, blindedId)
        writeLengthPrefixed(out, hlc)
        return out.toByteArray()
    }

    private fun writeLengthPrefixed(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 0xFFFF) { "associated-data field is too long to length-prefix" }
        out.write((bytes.size ushr 8) and 0xFF)
        out.write(bytes.size and 0xFF)
        out.write(bytes, 0, bytes.size)
    }
}
