package my.cheysoff.core_crypto

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

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
 * Pure `javax.crypto` / `java.security` — no Android Keystore, no [android.content.Context] —
 * so it runs in plain JVM unit tests.
 *
 * Never logs secrets (passphrase, PIN, derived key bytes).
 */
object PassphraseCipher {

    const val ITERATIONS = 210_000

    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_ALGORITHM = "AES"

    private val secureRandom = SecureRandom()

    /** Wrap [passphrase] under a PBKDF2(pin)-derived AES-256-GCM key. The caller owns/zeroes [pin]. */
    fun wrapWithPin(passphrase: ByteArray, pin: CharArray): PinWrap {
        val salt = ByteArray(SALT_BYTES).also(secureRandom::nextBytes)
        val iv = ByteArray(IV_BYTES).also(secureRandom::nextBytes)

        val key = deriveKey(pin, salt, ITERATIONS)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val ciphertext = cipher.doFinal(passphrase)
        return PinWrap(salt = salt, iv = iv, ciphertext = ciphertext, iterations = ITERATIONS)
    }

    /** Unwrap; returns null when the PIN is wrong (GCM tag mismatch) or data is tampered. */
    fun unwrapWithPin(wrap: PinWrap, pin: CharArray): ByteArray? {
        val key = deriveKey(pin, wrap.salt, wrap.iterations)
        return try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, wrap.iv))
            cipher.doFinal(wrap.ciphertext)
        } catch (_: AEADBadTagException) {
            // Wrong PIN or tampered ciphertext/IV/salt — GCM tag verification failed.
            null
        } catch (_: GeneralSecurityException) {
            // Any other crypto failure (malformed input, etc.).
            null
        }
    }

    private fun deriveKey(pin: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
        val spec = PBEKeySpec(pin, salt, iterations, KEY_BITS)
        try {
            var keyBytes: ByteArray? = factory.generateSecret(spec).encoded
            val secretKey = SecretKeySpec(keyBytes, KEY_ALGORITHM)
            // SecretKeySpec copied the bytes; clear our intermediate copy.
            keyBytes?.fill(0)
            keyBytes = null
            return secretKey
        } finally {
            spec.clearPassword()
        }
    }
}
