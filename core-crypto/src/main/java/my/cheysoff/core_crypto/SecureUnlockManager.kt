package my.cheysoff.core_crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import my.cheysoff.core_crypto.sync.AccountRootKey
import my.cheysoff.core_crypto.sync.ArkCipher
import my.cheysoff.core_crypto.sync.ArkWrap
import my.cheysoff.core_crypto.sync.SyncProtocol
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.inject.Inject
import javax.inject.Singleton
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
 * [EncryptedSharedPreferences] file (`secret_shared_prefs`) the pre-secure-unlock key manager used,
 * so a pre-existing install's raw `db_passphrase` can be migrated in place ([needsMigration] /
 * [setupPin]). Holds the unlocked passphrase in memory until [lock].
 *
 * This is the only key manager: it owns the DB passphrase for every install, migrated or fresh.
 *
 * The DB passphrase is created in exactly ONE place — [setupPin] — and never regenerated
 * implicitly anywhere (implicit regeneration would silently wipe the encrypted database).
 *
 * The same discipline, for the same reason, governs the Account Root Key: it is created in exactly
 * ONE place — [ensureArk] — which refuses to create one whenever `ark_ct` is already present.
 * A second ARK does not fail loudly; it forks the account into two halves that can never read each
 * other. The ARK is stored wrapped under the DB passphrase ([ArkCipher]), so it is decrypted at
 * exactly the moment the passphrase is and both unlock paths get it without either of them
 * changing.
 *
 * The one part of this that genuinely needs a hardware Keystore — minting the master key and
 * opening [EncryptedSharedPreferences] under it — lives behind [EncryptedPrefsStore], so
 * everything below is ordinary logic over a [SharedPreferences] and is unit-tested in
 * `SecureUnlockManagerTest`. Never logs secrets.
 */
@Singleton
class SecureUnlockManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val biometricCipher: BiometricKeystoreCipher,
    private val prefsStore: EncryptedPrefsStore,
) {
    /**
     * True when the prefs file had to be discarded because its Keystore key was gone (e.g. a
     * cloud/D2D restore brings back `secret_shared_prefs` but never the non-exportable master key).
     * The wraps are then unrecoverable, so any existing `notes.db` is undecryptable and must be
     * dropped — see DataModule, which reads this before opening the database.
     */
    @Volatile
    var wasStateReset: Boolean = false
        private set

    // Lazy: opening the store touches the Keystore, and this @Singleton is injected into
    // Application, so an eager initializer would do that work on the main thread during startup.
    private val prefs: SharedPreferences by lazy { openPrefs() }

    /**
     * Counter file for [openPrefs]. Deliberately a PLAIN SharedPreferences: it exists to decide
     * whether the ENCRYPTED file can be opened at all, so it cannot itself be encrypted, and it
     * holds no secret — just how many consecutive launches have failed.
     */
    private val healthPrefs: SharedPreferences by lazy {
        context.getSharedPreferences(HEALTH_PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Open the encrypted prefs, and fall back to discarding the file only once retrying has been
     * shown not to help.
     *
     * Two failure modes have to be told apart, and the cost of confusing them is severe in both
     * directions. Letting a failure propagate crash-loops the app (the auth screen's first act is
     * to read this file) with no way out but clearing app data; treating a failure as key loss
     * deletes every wrap of the DB passphrase and, via [wasStateReset], the whole notes database.
     *
     * The retry budget therefore spans PROCESS LAUNCHES, not just milliseconds. An in-process
     * retry can only rule out a failure that clears within milliseconds, and the failure that
     * matters most does not: when the Keystore blob behind the master key survives but is no
     * longer usable (low storage during a keyset write, OEM keystore corruption, a partial
     * restore), Tink reports `KeyStoreException("the master key %s exists but is unusable")`
     * wrapping `InvalidKeyException` — and it does so only AFTER its own internal
     * wait-and-retry has given up. That error is identical 150ms later and identical next boot.
     * Matching on it by type is what the previous version of this file did via a blanket
     * `GeneralSecurityException`, which swept in genuinely transient errors and destroyed notes;
     * matching on it by message would bind us to a Tink string. Counting failed launches proves
     * the same thing without depending on either.
     *
     * So: retry briefly in-process (Keystore momentarily busy at cold start is real and cheap to
     * absorb), and otherwise reset only when the error is PROVABLY terminal on its first sighting
     * ([KeyLossPolicy.isProvableKeyLoss]) or when [OPEN_FAILURE_LAUNCHES] separate launches have
     * now failed the same way. Until that budget is spent the error is rethrown, which crashes — bad, but
     * recoverable, because the ciphertext is all still on disk and a later launch may succeed.
     *
     * The sleeps run on whichever thread first touches [prefs], possibly the main thread, but only
     * on the already-broken path and for at most [OPEN_RETRY_BASE_MS] * (2^(n-1) - 1) total.
     *
     * FOLLOW-UP: even a proven reset destroys every note silently. Asking the user first ("your
     * keys could not be recovered — reset the app?") would be strictly better, but needs a screen
     * and a nav state that do not exist yet.
     */
    private fun openPrefs(): SharedPreferences {
        var lastError: Exception? = null
        for (attempt in 0 until OPEN_ATTEMPTS) {
            if (attempt > 0) {
                try {
                    Thread.sleep(OPEN_RETRY_BASE_MS shl (attempt - 1))
                } catch (_: InterruptedException) {
                    // Restore the flag for whoever owns this thread and stop: every remaining
                    // Thread.sleep would throw immediately anyway, collapsing the backoff into a
                    // busy loop, and leaving the flag set would leak the interrupt to the next
                    // task on this (pooled) thread.
                    Thread.currentThread().interrupt()
                    break
                }
            }
            try {
                val opened = prefsStore.open()
                // A launch that got in clears the history: only CONSECUTIVE failures are evidence.
                if (healthPrefs.contains(KEY_OPEN_FAILURES)) {
                    healthPrefs.edit(commit = true) { remove(KEY_OPEN_FAILURES) }
                }
                return opened
            } catch (e: Exception) {
                lastError = e
            }
        }

        // Reached only after at least one prefsStore.open() failure, so lastError is set.
        val error = lastError!!
        // commit, not apply: the whole point is that this survives the crash on the next line.
        val failedLaunches = healthPrefs.getInt(KEY_OPEN_FAILURES, 0) + 1
        healthPrefs.edit(commit = true) { putInt(KEY_OPEN_FAILURES, failedLaunches) }

        if (!KeyLossPolicy.isProvableKeyLoss(error) && failedLaunches < OPEN_FAILURE_LAUNCHES) throw error

        prefsStore.discard()
        healthPrefs.edit(commit = true) { remove(KEY_OPEN_FAILURES) }
        wasStateReset = true
        return prefsStore.open()
    }

    /** In-memory unlocked passphrase, or null while locked. Owned/zeroed by this class. */
    private var inMem: ByteArray? = null

    /**
     * In-memory Account Root Key, or null while locked OR when this device has no ARK yet.
     *
     * Two different nulls on purpose: "locked" and "never had one" are both states in which there
     * is no ARK to hand out, and no reader of [currentArk] can do anything different about them.
     * [ensureArk] is the one place that tells them apart.
     */
    private var inMemArk: ByteArray? = null

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
            // lockoutUntil is 0 while the fail count is still inside the free allowance, and 0
            // is a sentinel, not an instant: subtracting a NEGATIVE `now` (wall clock set before
            // 1970) would otherwise turn "not locked" into a decade-long duration and persist it.
            // Clamping to MAX_LOCK_MS also enforces, at the only site that writes it, the bound
            // that LockoutPolicy.isElapsedDeadlineStale infers when reading it back.
            val duration = if (lockoutUntil == 0L) 0L
            else (lockoutUntil - now).coerceIn(0L, LockoutPolicy.MAX_LOCK_MS)
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
        // Clear the wrong-PIN window exactly as unlockWithPin does. A successful biometric unlock
        // is proof of the same thing a correct PIN is, so leaving the counters set would be wrong
        // on its own — and leaving the MONOTONIC deadline set is actively harmful. It is only
        // ignored after a reboot while uptime is low; a user who fails the PIN on a
        // long-uptime device and then unlocks by fingerprint from then on would find that stored
        // deadline stops looking stale once uptime climbed back past it, and be locked out for
        // five minutes, days later, having failed nothing.
        prefs.edit(commit = true) {
            putInt(KEY_FAIL_COUNT, 0)
            putLong(KEY_LOCKOUT_UNTIL, 0L)
            putLong(KEY_LOCKOUT_UNTIL_ELAPSED, 0L)
        }
        return true
    }

    /** Install a new in-memory passphrase, zeroing any previous one instead of leaking it to GC. */
    private fun setInMem(pp: ByteArray) {
        inMem?.fill(0)
        inMem = pp
        // The ARK rides in on the passphrase. Unwrapping it HERE, rather than in each unlock
        // method, is what makes "no change to the unlock flows" literally true: setupPin,
        // unlockWithPin and unlockWithBiometric all pass through this one line, so all three
        // produce an ARK on a device that has one and none of them had to learn about it.
        setInMemArk(unwrapStoredArk(pp))
        _unlocked.value = true
    }

    /** Install a new in-memory ARK, zeroing any previous one. Null means "this device has none". */
    private fun setInMemArk(ark: ByteArray?) {
        inMemArk?.fill(0)
        inMemArk = ark
    }

    /** A copy of the unlocked passphrase, or null if locked. Caller owns/zeroes the copy. */
    fun currentPassphrase(): ByteArray? = inMem?.copyOf()

    /**
     * A copy of the unlocked Account Root Key, or null. Caller owns/zeroes the copy.
     *
     * Mirrors [currentPassphrase], including the null — but unlike the passphrase, an ARK is not
     * something every install has. A device that has never paired and never offered to share its
     * account has none, and this returns null for it; see [ensureArk] for why that is deliberate.
     * Never creates one.
     */
    fun currentArk(): ByteArray? = inMemArk?.copyOf()

    /**
     * The Account Root Key for this device, creating one on first use.
     *
     * **This is the only call site of [AccountRootKey.generateArk] in the codebase, and it must
     * stay that way.** Every branch of the guard below matters:
     *
     *  - an ARK already in memory is returned as it is;
     *  - a stored `ark_ct` that did not unwrap makes this return null rather than mint a
     *    replacement. That case is a bug or a damaged file, and the account key is not recoverable
     *    from a new one; overwriting it would turn "sync is broken today" into "half the notes on
     *    this account are unreadable forever";
     *  - a locked device returns null, because there is nothing to wrap a new key under.
     *
     * ## Why creation is lazy rather than eager in [setupPin]
     *
     * Eager creation cannot reach the installs that matter. [setupPin] runs once per device and
     * every existing install has already run it, so an ARK minted there would reach only devices
     * set up after this change and existing devices would still need a lazy path. Having both
     * means two call sites of [AccountRootKey.generateArk] — exactly the thing that must never
     * exist. Lazy is also the honest choice: an account key is created when the user first asks to
     * share their notes with another device, not silently on everyone's next unlock whether they
     * ever sync or not.
     *
     * The price is that [currentArk] can return null and callers must handle it.
     *
     * @return a copy the caller owns and should zero, or null if this device is locked or holds an
     *   `ark_ct` that will not open.
     */
    fun ensureArk(): ByteArray? {
        inMemArk?.let { return it.copyOf() }
        val passphrase = inMem ?: return null
        // The guard the design asks for by name: generation is unreachable once ark_ct exists.
        if (prefs.contains(KEY_ARK_CT)) return null

        val ark = AccountRootKey.generateArk()
        try {
            storeArk(ark, passphrase)
            setInMemArk(ark.copyOf())
            return ark.copyOf()
        } finally {
            ark.fill(0)
        }
    }

    /**
     * Store an ARK received from another device by pairing.
     *
     * The second — and only other — way an ARK arrives on a device. It does not go through
     * [ensureArk] and never calls [AccountRootKey.generateArk]: these bytes were generated once,
     * on the first device, and this device is joining that account rather than starting one.
     *
     * It REPLACES any ARK this device already had. That is what joining an account means, and
     * there is no alternative that leaves re-pairing possible — but it is the one operation here
     * that can discard an account key, so it is reachable only from the pairing screen and only
     * after the user has compared the six-digit code on both phones.
     *
     * Caller owns [ark]; a copy is kept.
     */
    fun adoptArk(ark: ByteArray) {
        require(ark.size == SyncProtocol.ARK_BYTES) {
            "ARK must be ${SyncProtocol.ARK_BYTES} bytes, was ${ark.size}"
        }
        val passphrase = inMem ?: error("adoptArk() requires an unlocked passphrase")
        storeArk(ark, passphrase)
        setInMemArk(ark.copyOf())
    }

    /** Zero and drop the in-memory passphrase and ARK. */
    fun lock() {
        inMem?.fill(0)
        inMem = null
        setInMemArk(null)
        _unlocked.value = false
    }

    private fun storeArk(ark: ByteArray, passphrase: ByteArray) {
        val wrap = ArkCipher.wrap(ark, passphrase)
        prefs.edit(commit = true) {
            putString(KEY_ARK_IV, encode(wrap.iv))
            putString(KEY_ARK_CT, encode(wrap.ciphertext))
        }
    }

    /** The stored ARK unwrapped under [passphrase], or null if there is none or it will not open. */
    private fun unwrapStoredArk(passphrase: ByteArray): ByteArray? {
        val iv = prefs.getString(KEY_ARK_IV, null) ?: return null
        val ct = prefs.getString(KEY_ARK_CT, null) ?: return null
        return ArkCipher.unwrap(
            ArkWrap(
                iv = Base64.decode(iv, Base64.DEFAULT),
                ciphertext = Base64.decode(ct, Base64.DEFAULT),
            ),
            passphrase,
        )
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
        const val PASSPHRASE_BYTES = 32

        /** Total tries at opening the encrypted prefs before classifying the failure. */
        const val HEALTH_PREFS_NAME = "secure_unlock_health"
        const val KEY_OPEN_FAILURES = "prefs_open_failures"

        /**
         * Consecutive LAUNCHES that must fail to open the prefs before the file is discarded.
         * Every launch below this threshold is a visible crash, so the number trades user pain
         * against the risk of wiping notes over a condition that would have cleared (a full disk,
         * an OEM keystore hiccup). Erring high is the cheap direction: the notes are the thing
         * that cannot be recovered, a crash is not.
         */
        const val OPEN_FAILURE_LAUNCHES = 5

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

        /**
         * The wrapped Account Root Key. Both are new keys in an existing file and are absent on
         * every install that has never paired — which is precisely what [ensureArk] reads to
         * decide whether an ARK exists at all.
         */
        const val KEY_ARK_IV = "ark_iv"
        const val KEY_ARK_CT = "ark_ct"

        const val KEY_LEGACY_PASSPHRASE = "db_passphrase"
    }
}
