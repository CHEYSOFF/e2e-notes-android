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
    // Lazy for the same reason as [prefs]: building a MasterKey touches the Keystore, and this
    // @Singleton is injected into Application, so an eager initializer would do that on the main
    // thread during startup.
    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    /**
     * True when the prefs file had to be discarded because its Keystore key was gone (e.g. a
     * cloud/D2D restore brings back `secret_shared_prefs` but never the non-exportable master key).
     * The wraps are then unrecoverable, so any existing `notes.db` is undecryptable and must be
     * dropped — see DataModule, which reads this before opening the database.
     */
    @Volatile
    var wasStateReset: Boolean = false
        private set

    // Mirror EncryptionManager.createSharedPreferences() so we share the legacy prefs file.
    private val prefs: SharedPreferences by lazy { openPrefs() }

    /**
     * Open the encrypted prefs, retrying transient failures, and fall back to discarding the file
     * ONLY when the Keystore key is provably gone.
     *
     * Two failure modes have to be told apart, and the cost of confusing them is severe in both
     * directions. Letting a failure propagate crash-loops the app (isPinSet() runs on the main
     * thread from the auth screen) with no way out but clearing app data; treating a failure as
     * key loss deletes every wrap of the DB passphrase and, via [wasStateReset], the whole notes
     * database. So: retry first — Keystore being momentarily unavailable at cold start is the
     * common case and it resolves within milliseconds — and only classify once retries are spent.
     *
     * The sleeps run on whichever thread first touches [prefs], possibly the main thread, but only
     * on the already-broken path and for at most [OPEN_RETRY_BASE_MS] * (2^(n-1) - 1) total.
     */
    private fun openPrefs(): SharedPreferences {
        var lastError: Exception? = null
        for (attempt in 0 until OPEN_ATTEMPTS) {
            if (attempt > 0) {
                try {
                    Thread.sleep(OPEN_RETRY_BASE_MS shl (attempt - 1))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            try {
                return createPrefs()
            } catch (e: Exception) {
                lastError = e
            }
        }

        val error = lastError!!
        // Not provable key loss: rethrow. That crash-loops until the underlying problem clears,
        // which is bad — but it is recoverable (the ciphertext is all still on disk), whereas the
        // reset below is not.
        if (!isKeyLoss(error)) throw error
        context.deleteSharedPreferences(PREFS_NAME)
        wasStateReset = true
        return createPrefs()
    }

    private fun createPrefs(): SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    /**
     * True only for failures that PROVE the Keystore key protecting the prefs is unrecoverable,
     * i.e. that retrying forever would never succeed. Deliberately narrower than
     * EncryptionManager.isKeyLoss, which accepts any [java.security.GeneralSecurityException]:
     * Tink and EncryptedSharedPreferences wrap transient trouble in that class too (Keystore
     * daemon momentarily unavailable, keyset read failing under low storage, the window right
     * after a lockscreen credential change), and each such throw at cold start used to destroy
     * every note. Bare GeneralSecurityException and [java.security.KeyStoreException] are
     * therefore treated as transient — retried by [openPrefs], then rethrown, never reset.
     *
     * Retained, with the reason each one is terminal:
     *  - [javax.crypto.AEADBadTagException]: the GCM tag on the stored Tink keyset does not
     *    authenticate under the key the Keystore now holds. Same bytes, same key, every retry —
     *    the outcome is deterministic. This is the signature of the case the reset path exists
     *    for: a restore brings back `secret_shared_prefs` but not the non-exportable master key,
     *    so MasterKey.Builder mints a fresh one that cannot unwrap the restored keyset.
     *  - [android.security.keystore.KeyPermanentlyInvalidatedException]: by contract the key has
     *    been invalidated for good (lockscreen removed, or biometrics re-enrolled for an
     *    auth-bound key). It is never coming back, however long we wait.
     *
     * Walks the cause chain because both arrive wrapped in a GeneralSecurityException from Tink.
     */
    private fun isKeyLoss(error: Throwable): Boolean {
        var e: Throwable? = error
        while (e != null) {
            if (e is javax.crypto.AEADBadTagException ||
                e is android.security.keystore.KeyPermanentlyInvalidatedException
            ) return true
            e = e.cause
        }
        return false
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
                putLong(KEY_LOCKOUT_UNTIL_ELAPSED, 0L)
                if (migrating) remove(KEY_LEGACY_PASSPHRASE)
            }
            // Keep an in-memory copy; zero the local working copy below.
            setInMem(passphrase.copyOf())
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
            // Also record the deadline on the monotonic clock. System.currentTimeMillis() is
            // user-settable, so a wall-clock-only deadline is skipped by moving the device clock
            // forward in Settings; SystemClock.elapsedRealtime() cannot be changed that way.
            val duration = max(0L, lockoutUntil - now)
            val elapsedUntil =
                if (duration > 0) android.os.SystemClock.elapsedRealtime() + duration else 0L
            prefs.edit(commit = true) {
                putInt(KEY_FAIL_COUNT, failCount)
                putLong(KEY_LOCKOUT_UNTIL, lockoutUntil)
                putLong(KEY_LOCKOUT_UNTIL_ELAPSED, elapsedUntil)
            }
            return UnlockResult.WrongPin(duration)
        }

        prefs.edit(commit = true) {
            putInt(KEY_FAIL_COUNT, 0)
            putLong(KEY_LOCKOUT_UNTIL, 0L)
            putLong(KEY_LOCKOUT_UNTIL_ELAPSED, 0L)
        }
        setInMem(pp)
        return UnlockResult.Success
    }

    /**
     * Milliseconds remaining on an active lockout window, or 0 if not locked out.
     *
     * Takes the STRICTER of the wall-clock and monotonic deadlines, so winding the device clock
     * forward doesn't retire the lockout early. A monotonic deadline left over from before a
     * reboot is discarded outright — see [LockoutPolicy.remainingMillis], which owns the whole
     * (unit-tested) decision; this method only reads the clocks and the store.
     */
    fun lockoutRemainingMillis(): Long = LockoutPolicy.remainingMillis(
        lockoutUntilWall = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L),
        lockoutUntilElapsed = prefs.getLong(KEY_LOCKOUT_UNTIL_ELAPSED, 0L),
        nowWall = System.currentTimeMillis(),
        nowElapsed = android.os.SystemClock.elapsedRealtime(),
    )

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
        // Missing wrap is a recoverable state (biometric was disabled/cleared), not a crash: return
        // false so the caller can fall back to the PIN. doFinal may still throw if the Keystore key
        // was invalidated between init and use — the caller catches that.
        val ct = prefs.getString(KEY_BIO_CT, null) ?: return false
        setInMem(unlockedDecryptCipher.doFinal(Base64.decode(ct, Base64.DEFAULT)))
        return true
    }

    /** Install a new in-memory passphrase, zeroing any previous one instead of leaking it to GC. */
    private fun setInMem(pp: ByteArray) {
        inMem?.fill(0)
        inMem = pp
        _unlocked.value = true
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

        /** Total tries at opening the encrypted prefs before classifying the failure. */
        const val OPEN_ATTEMPTS = 3

        /** Backoff before retry n (doubling): 50ms, then 100ms — 150ms worst case. */
        const val OPEN_RETRY_BASE_MS = 50L

        const val KEY_PIN_SALT = "pin_salt"
        const val KEY_PIN_IV = "pin_iv"
        const val KEY_PIN_CT = "pin_ct"
        const val KEY_PIN_ITERS = "pin_iters"
        const val KEY_BIO_IV = "bio_iv"
        const val KEY_BIO_CT = "bio_ct"
        const val KEY_FAIL_COUNT = "fail_count"
        const val KEY_LOCKOUT_UNTIL = "lockout_until"
        const val KEY_LOCKOUT_UNTIL_ELAPSED = "lockout_until_elapsed"

        const val KEY_LEGACY_PASSPHRASE = "db_passphrase"
    }
}
