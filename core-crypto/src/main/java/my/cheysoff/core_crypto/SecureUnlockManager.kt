package my.cheysoff.core_crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Result of a PIN unlock attempt.
 */
sealed interface UnlockResult {
    data object Success : UnlockResult

    /** Wrong PIN. [lockoutMillis] > 0 if this fail tripped a lockout window. */
    data class WrongPin(val lockoutMillis: Long) : UnlockResult

    /** Attempt rejected because a lockout window is still active. */
    data class LockedOut(val remainingMillis: Long) : UnlockResult
}

/**
 * Wires the PIN-wrap ([PassphraseCipher]) and biometric Keystore ([BiometricKeystoreCipher])
 * primitives into a single secure-unlock manager.
 *
 * Stores the PIN-wrapped DB passphrase (and optional biometric-wrapped copy) in the SAME
 * [EncryptedSharedPreferences] file the legacy [EncryptionManager] uses, so it can migrate the
 * legacy raw `db_passphrase`. Holds the unlocked passphrase in memory until [lock].
 *
 * ADDITIVE: this class has no consumers yet; the cutover from [EncryptionManager] happens later.
 *
 * The DB passphrase is created in exactly ONE place — [setupPin] — and never regenerated
 * implicitly anywhere (implicit regeneration would silently wipe the encrypted database).
 *
 * [EncryptedSharedPreferences]/Keystore are device-only, so this class is not unit-tested; it is
 * verified by compile + review + on-device phase. Never logs secrets.
 */
@Singleton
class SecureUnlockManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val biometricCipher: BiometricKeystoreCipher,
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    // Mirror EncryptionManager.createSharedPreferences() so we share the legacy prefs file.
    private val prefs: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** In-memory unlocked passphrase, or null while locked. Owned/zeroed by this class. */
    private var inMem: ByteArray? = null

    private val _unlocked = MutableStateFlow(false)

    /** True while a passphrase is held in memory (post-unlock); flips to false on [lock]. The nav
     *  layer observes this to gate the UI back to the auth screen when the app re-locks. */
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    /** True once a PIN has been set up (a PIN-wrapped passphrase exists). */
    fun isPinSet(): Boolean = prefs.contains(KEY_PIN_CT)

    /** True when a legacy raw passphrase is present and no PIN has been set up yet. */
    fun needsMigration(): Boolean = prefs.contains(KEY_LEGACY_PASSPHRASE) && !isPinSet()

    /**
     * Set up (or migrate to) a PIN. On a fresh install this GENERATES a new 32-byte passphrase;
     * on migration it REUSES the legacy passphrase. Wraps it under the PIN, persists the wrap,
     * sets the in-memory passphrase, resets the lockout state, and (on migration) deletes the
     * legacy key. Caller owns/zeroes [pin].
     *
     * This is the ONLY place a passphrase is created.
     */
    fun setupPin(pin: CharArray) {
        val migrating = needsMigration()
        val passphrase: ByteArray = if (migrating) {
            val legacy = prefs.getString(KEY_LEGACY_PASSPHRASE, null)
                ?: error("needsMigration() true but legacy passphrase missing")
            Base64.decode(legacy, Base64.DEFAULT)
        } else {
            ByteArray(PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }
        }

        try {
            val wrap = PassphraseCipher.wrapWithPin(passphrase, pin)
            prefs.edit(commit = true) {
                putString(KEY_PIN_SALT, encode(wrap.salt))
                putString(KEY_PIN_IV, encode(wrap.iv))
                putString(KEY_PIN_CT, encode(wrap.ciphertext))
                putInt(KEY_PIN_ITERS, wrap.iterations)
                putInt(KEY_FAIL_COUNT, 0)
                putLong(KEY_LOCKOUT_UNTIL, 0L)
                if (migrating) remove(KEY_LEGACY_PASSPHRASE)
            }
            // Keep an in-memory copy; zero the local working copy below.
            inMem = passphrase.copyOf()
            _unlocked.value = true
        } finally {
            passphrase.fill(0)
        }
    }

    /** Attempt to unlock with [pin]. Caller owns/zeroes [pin]. */
    fun unlockWithPin(pin: CharArray): UnlockResult {
        val remaining = lockoutRemainingMillis()
        if (remaining > 0) return UnlockResult.LockedOut(remaining)

        val wrap = loadPinWrap() ?: return UnlockResult.WrongPin(0L)
        val pp = PassphraseCipher.unwrapWithPin(wrap, pin)

        if (pp == null) {
            val failCount = prefs.getInt(KEY_FAIL_COUNT, 0) + 1
            val now = System.currentTimeMillis()
            val lockoutUntil = LockoutPolicy.lockoutUntil(failCount, now)
            prefs.edit(commit = true) {
                putInt(KEY_FAIL_COUNT, failCount)
                putLong(KEY_LOCKOUT_UNTIL, lockoutUntil)
            }
            return UnlockResult.WrongPin(max(0L, lockoutUntil - now))
        }

        prefs.edit(commit = true) {
            putInt(KEY_FAIL_COUNT, 0)
            putLong(KEY_LOCKOUT_UNTIL, 0L)
        }
        inMem = pp
        _unlocked.value = true
        return UnlockResult.Success
    }

    /** Milliseconds remaining on an active lockout window, or 0 if not locked out. */
    fun lockoutRemainingMillis(): Long =
        max(0L, prefs.getLong(KEY_LOCKOUT_UNTIL, 0L) - System.currentTimeMillis())

    /** True once a biometric-wrapped passphrase exists. */
    fun isBiometricEnabled(): Boolean = prefs.contains(KEY_BIO_CT)

    /** Initialized ENCRYPT-mode cipher to put in a CryptoObject when enabling biometric. */
    fun biometricEncryptCipher(): Cipher = biometricCipher.createEncryptCipher()

    /**
     * Initialized DECRYPT-mode cipher (for the stored bio IV) to put in a CryptoObject when
     * unlocking with biometric. May throw KeyPermanentlyInvalidatedException (re-enrollment) —
     * the caller should then [disableBiometric] and fall back to PIN.
     */
    fun biometricDecryptCipher(): Cipher {
        val iv = prefs.getString(KEY_BIO_IV, null)
            ?: error("biometricDecryptCipher() called but no biometric wrap stored")
        return biometricCipher.createDecryptCipher(Base64.decode(iv, Base64.DEFAULT))
    }

    /**
     * Enable biometric unlock using the biometric-prompt-unlocked ENCRYPT cipher. Requires the
     * passphrase to be currently unlocked. Stores the biometric IV and ciphertext.
     */
    fun enableBiometric(unlockedEncryptCipher: Cipher) {
        val passphrase = inMem ?: error("enableBiometric() requires an unlocked passphrase")
        val ct = unlockedEncryptCipher.doFinal(passphrase)
        prefs.edit(commit = true) {
            putString(KEY_BIO_IV, encode(unlockedEncryptCipher.iv))
            putString(KEY_BIO_CT, encode(ct))
        }
    }

    /** Disable biometric unlock: clear the biometric wrap and delete the Keystore key. */
    fun disableBiometric() {
        prefs.edit(commit = true) {
            remove(KEY_BIO_IV)
            remove(KEY_BIO_CT)
        }
        biometricCipher.deleteKey()
    }

    /**
     * Unlock with the biometric-prompt-unlocked DECRYPT cipher: decrypts the stored biometric
     * ciphertext into the in-memory passphrase. Returns true on success.
     */
    fun unlockWithBiometric(unlockedDecryptCipher: Cipher): Boolean {
        val ct = prefs.getString(KEY_BIO_CT, null)
            ?: error("unlockWithBiometric() called but no biometric wrap stored")
        val pp = unlockedDecryptCipher.doFinal(Base64.decode(ct, Base64.DEFAULT))
        inMem = pp
        _unlocked.value = true
        return true
    }

    /** A copy of the unlocked passphrase, or null if locked. Caller owns/zeroes the copy. */
    fun currentPassphrase(): ByteArray? = inMem?.copyOf()

    /** Zero and drop the in-memory passphrase. */
    fun lock() {
        inMem?.fill(0)
        inMem = null
        _unlocked.value = false
    }

    private fun loadPinWrap(): PinWrap? {
        val salt = prefs.getString(KEY_PIN_SALT, null) ?: return null
        val iv = prefs.getString(KEY_PIN_IV, null) ?: return null
        val ct = prefs.getString(KEY_PIN_CT, null) ?: return null
        val iters = prefs.getInt(KEY_PIN_ITERS, 0)
        if (iters <= 0) return null
        return PinWrap(
            salt = Base64.decode(salt, Base64.DEFAULT),
            iv = Base64.decode(iv, Base64.DEFAULT),
            ciphertext = Base64.decode(ct, Base64.DEFAULT),
            iterations = iters,
        )
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.DEFAULT)

    private companion object {
        const val PREFS_NAME = "secret_shared_prefs"
        const val PASSPHRASE_BYTES = 32

        const val KEY_PIN_SALT = "pin_salt"
        const val KEY_PIN_IV = "pin_iv"
        const val KEY_PIN_CT = "pin_ct"
        const val KEY_PIN_ITERS = "pin_iters"
        const val KEY_BIO_IV = "bio_iv"
        const val KEY_BIO_CT = "bio_ct"
        const val KEY_FAIL_COUNT = "fail_count"
        const val KEY_LOCKOUT_UNTIL = "lockout_until"

        const val KEY_LEGACY_PASSPHRASE = "db_passphrase"
    }
}
