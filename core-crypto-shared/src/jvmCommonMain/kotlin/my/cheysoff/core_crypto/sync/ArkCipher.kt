package my.cheysoff.core_crypto.sync

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** An ARK wrapped for storage: the GCM output and the nonce it was produced under. */
class ArkWrap(val iv: ByteArray, val ciphertext: ByteArray)

/**
 * Wraps and unwraps the Account Root Key under the device's database passphrase.
 *
 * ```
 *   K_arkwrap = HKDF(ikm = dbPassphrase, salt = none, info = "manana/sync/v1/arkwrap", 32)
 *   ark_ct    = AES-256-GCM(key = K_arkwrap, iv = 12 random bytes, plaintext = ARK)
 * ```
 *
 * ## Why the database passphrase and not a second Keystore key
 *
 * Because it makes the ARK available at exactly the moment the database is, through both unlock
 * paths, with no change to either. A parallel biometric wrap would mean a second thing to enable,
 * a second thing to invalidate on fingerprint re-enrolment, and a second migration for existing
 * installs. The passphrase is already the one secret both `unlockWithPin` and
 * `unlockWithBiometric` produce, so deriving from it inherits all of that for free — see
 * `docs/design/e2e-sync-architecture.md` §"Key hierarchy".
 *
 * It also means the ARK is exactly as strong as the database already is, and no stronger: whoever
 * can open `notes.db` can open this. That is the intended bound, not an oversight — the ARK
 * protects the *synced copy* of the same notes.
 *
 * ## Why HKDF rather than using the passphrase directly
 *
 * Domain separation. The passphrase is SQLCipher's file key; using it unchanged as an AES-GCM key
 * would put the same 32 bytes into two different algorithms with two different threat models. The
 * `info` string is what keeps `K_arkwrap` a one-way function of it, so a weakness in either use
 * cannot be carried into the other.
 *
 * ## No AAD
 *
 * There is exactly one thing ever sealed under `K_arkwrap`, stored at one fixed pair of keys in
 * one file. There is no second ciphertext to confuse this one with, so an AAD would bind it to
 * nothing it is not already bound to. The 128-bit GCM tag still detects any modification.
 *
 * Pure `javax.crypto` / `java.security` — no Android types — so it is unit-tested in `src/test`
 * exactly like [my.cheysoff.core_crypto.PassphraseCipher]. Never logs key material.
 */
object ArkCipher {

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_ALGORITHM = "AES"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BYTES = 32

    private val secureRandom = SecureRandom()

    /**
     * Wrap [ark] under a key derived from [passphrase].
     *
     * Both arguments stay the caller's; neither is modified. The derived key is zeroed here.
     */
    fun wrap(ark: ByteArray, passphrase: ByteArray): ArkWrap {
        require(ark.size == SyncProtocol.ARK_BYTES) {
            "ARK must be ${SyncProtocol.ARK_BYTES} bytes, was ${ark.size}"
        }
        val key = deriveWrapKey(passphrase)
        try {
            val iv = ByteArray(IV_BYTES).also(secureRandom::nextBytes)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, KEY_ALGORITHM),
                GCMParameterSpec(TAG_BITS, iv),
            )
            return ArkWrap(iv = iv, ciphertext = cipher.doFinal(ark))
        } finally {
            key.fill(0)
        }
    }

    /**
     * Unwrap, or return null if GCM rejects the ciphertext.
     *
     * Null rather than an exception because the one caller — `SecureUnlockManager` — must treat a
     * failure as "this device has no usable ARK right now" and must **not** react by generating a
     * new one. A stored `ark_ct` that will not open is a bug or a damaged file, and minting a
     * replacement would fork the account rather than repair it.
     */
    fun unwrap(wrap: ArkWrap, passphrase: ByteArray): ByteArray? {
        val key = deriveWrapKey(passphrase)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, KEY_ALGORITHM),
                GCMParameterSpec(TAG_BITS, wrap.iv),
            )
            cipher.doFinal(wrap.ciphertext)
        } catch (_: GeneralSecurityException) {
            // Wrong passphrase, truncated ciphertext, modified IV — all the same answer here.
            null
        } catch (_: IllegalArgumentException) {
            // GCMParameterSpec rejects an empty IV outright, which a corrupted prefs entry can
            // produce. Same answer: there is no ARK to be had.
            null
        } finally {
            key.fill(0)
        }
    }

    private fun deriveWrapKey(passphrase: ByteArray): ByteArray = Hkdf.derive(
        ikm = passphrase,
        salt = null,
        info = SyncProtocol.INFO_ARK_WRAP.toByteArray(Charsets.US_ASCII),
        length = KEY_BYTES,
    )
}
