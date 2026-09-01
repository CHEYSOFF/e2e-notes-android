package my.cheysoff.core_crypto

import my.cheysoff.core_crypto.platform.aesGcmOpen
import my.cheysoff.core_crypto.platform.aesGcmSeal
import my.cheysoff.core_crypto.platform.pbkdf2HmacSha256
import my.cheysoff.core_crypto.platform.secureRandomBytes

/**
 * Result of wrapping a passphrase under a PIN-derived key.
 *
 * Carries everything needed to later re-derive the key and decrypt, except the PIN itself.
 */
class PinWrap(
    val salt: ByteArray,
    val iv: ByteArray,
    val ciphertext: ByteArray,
    val iterations: Int,
)

/**
 * Wraps/unwraps a database passphrase under a key derived from the user's PIN.
 *
 * Common code over the primitives in `platform` — no Android Keystore, no `Context`, no JVM types
 * — so it runs in plain unit tests on every target.
 *
 * ## Cross-platform parity is deliberately NOT claimed for this class
 *
 * A [PinWrap] is written and read on one device and never travels: it is not synced, not backed up
 * to the account, and not part of the wire protocol. So unlike `RecordEnvelope`, this class does
 * not need an iPhone to derive the same key from the same PIN that a Pixel would — nothing will
 * ever ask it to.
 *
 * That matters because the character-to-byte conversion inside PBKDF2 belongs to the JCA provider
 * on the JVM and to this code on Apple, and the two are only guaranteed to agree for ASCII. Every
 * PIN this app derives from comes from a numeric keypad, so they agree in practice too; see the
 * KDoc on `pbkdf2HmacSha256`.
 *
 * Never logs secrets (passphrase, PIN, derived key bytes).
 */
object PassphraseCipher {

    /**
     * The Android app's iteration count, and the default for [wrapWithPin].
     *
     * A *default* rather than the only value, because the products this class serves have different
     * threat models. On Android the wrap sits inside `EncryptedSharedPreferences` behind a
     * non-exportable Keystore key, so the only way at the PIN is on the device through
     * `LockoutPolicy` and the iteration count is a second line of defence. On the desktop the wrap
     * is an ordinary file and the iteration count is the *only* cost an offline guess pays;
     * `PassphrasePolicy` picks a higher one and shows its arithmetic.
     *
     * Raising this constant re-wraps nothing: [PinWrap] records the count it was created with and
     * [unwrapWithPin] derives with `wrap.iterations`, so every existing wrap keeps opening at its
     * own cost. Which is also what makes the parameter safe to have.
     */
    const val ITERATIONS = 210_000

    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128


    /**
     * Wrap [passphrase] under a PBKDF2(pin)-derived AES-256-GCM key. The caller owns/zeroes [pin].
     *
     * [iterations] defaults to [ITERATIONS]; a caller whose wrap is not protected by a hardware key
     * passes a higher one. It is recorded in the returned [PinWrap] and is what [unwrapWithPin]
     * derives with, so the two never have to agree out of band.
     */
    fun wrapWithPin(
        passphrase: ByteArray,
        pin: CharArray,
        iterations: Int = ITERATIONS,
    ): PinWrap {
        require(iterations > 0) { "iterations must be positive, was $iterations" }
        val salt = secureRandomBytes(SALT_BYTES)
        val iv = secureRandomBytes(IV_BYTES)

        val key = deriveKey(pin, salt, iterations)
        try {
            val ciphertext =
                aesGcmSeal(key = key, nonce = iv, aad = null, plaintext = passphrase)
            return PinWrap(salt = salt, iv = iv, ciphertext = ciphertext, iterations = iterations)
        } finally {
            key.fill(0)
        }
    }

    /** Unwrap; returns null when the PIN is wrong (GCM tag mismatch) or data is tampered. */
    fun unwrapWithPin(wrap: PinWrap, pin: CharArray): ByteArray? {
        val key = deriveKey(pin, wrap.salt, wrap.iterations)
        return try {
            // A wrong PIN, a tampered ciphertext, IV or salt, and any other cryptographic failure
            // all arrive as null — which is the same set the two `catch` blocks here used to cover
            // and the same answer they gave.
            aesGcmOpen(key = key, nonce = wrap.iv, aad = null, sealed = wrap.ciphertext)
        } finally {
            key.fill(0)
        }
    }

    /**
     * The PIN-derived AES key, as raw bytes.
     *
     * It used to return a `SecretKeySpec`, which held a copy of the key that the JDK offers no way
     * to wipe — `destroy()` is unimplemented and `getEncoded()` hands back a clone. Raw bytes have
     * no such problem, and both callers above zero them in a `finally`. That is a small security
     * improvement the seam made possible rather than a behaviour change: the same PBKDF2 call
     * produces the same bytes.
     *
     * [pin] is not modified and remains the caller's to zero.
     */
    private fun deriveKey(pin: CharArray, salt: ByteArray, iterations: Int): ByteArray =
        pbkdf2HmacSha256(
            password = pin,
            salt = salt,
            iterations = iterations,
            keyBytes = KEY_BITS / 8,
        )
}
