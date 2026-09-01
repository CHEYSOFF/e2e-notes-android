package my.cheysoff.core_crypto.platform

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Android and the JVM: the JCA, exactly as this module used it before the seam existed.
 *
 * Every call below is the same provider call, with the same transformation string, the same tag
 * length and the same argument order that `RecordEnvelope`, `ArkCipher`, `DeviceLabelCipher`,
 * `Hkdf`, `BlindedRecordId` and `PassphraseCipher` each used to make for themselves. That is
 * deliberate and is the property that makes this refactor safe to ship: an envelope sealed by a
 * released build opens under this file, because this file performs the identical operations.
 * `AesGcmKnownAnswerTest` and the protocol vectors are what hold it there.
 *
 * The provider is not named. It is SunJCE in a unit test and Conscrypt on a device, they are
 * expected to agree, and pinning one would break the other -- which is precisely why
 * `AesGcmKnownAnswerTest` checks published vectors rather than self-consistency.
 */

/**
 * One [SecureRandom] for the process rather than one per call site.
 *
 * `SecureRandom()` seeds itself from the OS on construction, which is cheap but not free, and the
 * three classes that used to each hold their own instance now share this. It is thread-safe;
 * `nextBytes` synchronises internally.
 */
private val secureRandom = SecureRandom()

internal actual fun secureRandomBytes(size: Int): ByteArray {
    // `SecureRandom.nextBytes` accepts a zero-length array and silently does nothing, which would
    // hand a caller an "all-zero nonce" that is indistinguishable from a legitimate one. Every
    // caller here passes a protocol constant, so a zero can only be a bug, and both actuals reject
    // it so that the bug surfaces on whichever platform it is written on.
    require(size > 0) { "cannot draw $size random bytes" }
    return ByteArray(size).also(secureRandom::nextBytes)
}

internal actual fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
    val mac = Mac.getInstance(HMAC_ALGORITHM)
    mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
    return mac.doFinal(message)
}

/**
 * The password is handed to [PBEKeySpec] as characters and the provider does its own conversion --
 * see the `expect`'s KDoc for why that must not be moved into common code.
 *
 * [PBEKeySpec.clearPassword] zeroes the spec's internal copy; the caller's array stays the
 * caller's. The intermediate `encoded` array is zeroed for the same reason `PassphraseCipher` used
 * to zero it: it is live key material and there is no reason to leave a copy on the heap.
 */
internal actual fun pbkdf2HmacSha256(
    password: CharArray,
    salt: ByteArray,
    iterations: Int,
    keyBytes: Int,
): ByteArray {
    val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
    val spec = PBEKeySpec(password, salt, iterations, keyBytes * 8)
    try {
        return factory.generateSecret(spec).encoded
    } finally {
        spec.clearPassword()
    }
}

internal actual fun aesGcmSeal(
    key: ByteArray,
    nonce: ByteArray,
    aad: ByteArray?,
    plaintext: ByteArray,
): ByteArray {
    val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
    val secretKey = SecretKeySpec(key, KEY_ALGORITHM)
    cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, nonce))
    // Skipped entirely when there is no associated data, rather than passed as an empty array.
    // The two are equivalent to `Cipher`, and this keeps the call identical to the one the
    // pre-seam code made in the no-AAD cases (`ArkCipher`).
    if (aad != null && aad.isNotEmpty()) cipher.updateAAD(aad)
    // JCA returns ciphertext ‖ tag concatenated, which is exactly the layout the seam promises.
    return cipher.doFinal(plaintext)
}

internal actual fun aesGcmOpen(
    key: ByteArray,
    nonce: ByteArray,
    aad: ByteArray?,
    sealed: ByteArray,
): ByteArray? {
    // A blob too short to hold a tag cannot be authenticated, and the JCA does not answer that
    // politely: SunJCE throws `ProviderException` wrapping a `ShortBufferException`, and
    // `ProviderException` is a RuntimeException, so it goes straight past every `catch` below.
    // `RecordEnvelope` never reaches this -- it length-checks first -- but `ArkCipher` and
    // `PassphraseCipher` hand over whatever was stored, so a truncated prefs entry used to throw
    // out of `unwrap`. Checking here fixes that and makes both actuals answer identically.
    if (sealed.size < TAG_BITS / 8) return null
    return try {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        val secretKey = SecretKeySpec(key, KEY_ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, nonce))
        if (aad != null && aad.isNotEmpty()) cipher.updateAAD(aad)
        cipher.doFinal(sealed)
    } catch (_: AEADBadTagException) {
        // Tag mismatch: wrong key, altered ciphertext/nonce, or associated data that does not
        // match what this was sealed with.
        null
    } catch (_: GeneralSecurityException) {
        // Any other crypto failure -- input a provider rejects before tag verification, or a
        // provider that reports a bad tag as something other than AEADBadTagException. No unit
        // test reaches this against SunJCE; it is here so that a different provider on a real
        // device degrades to "cannot open" instead of throwing out of the caller.
        null
    } catch (_: IllegalArgumentException) {
        // GCMParameterSpec rejects a zero-length nonce outright, which a corrupted stored blob can
        // produce. Same answer: there is nothing to open.
        null
    }
}

private const val HMAC_ALGORITHM = "HmacSHA256"
private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
private const val KEY_ALGORITHM = "AES"

/** 128 bits -- the full GCM tag, never truncated. Matches `SyncProtocol.TAG_BYTES * 8`. */
private const val TAG_BITS = 128
