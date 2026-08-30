package my.cheysoff.core_crypto

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock

/**
 * The PIN, migration, lockout, biometric and in-memory-passphrase behaviour of
 * [SecureUnlockManager].
 *
 * ## Why this can be a JVM test at all
 *
 * Everything here used to be untestable for one reason: [SecureUnlockManager.openPrefs] built its
 * own `EncryptedSharedPreferences` from an Android Keystore `MasterKey`, and Robolectric has no
 * `AndroidKeyStore` provider. Pulling that single mechanism out behind [EncryptedPrefsStore] left
 * the rest — which is all ordinary branching over a `SharedPreferences` — reachable from here.
 * See [FakeEncryptedPrefsStore] for exactly what is and is not faked.
 *
 * The PIN wrap itself is NOT faked: every `setupPin`/`unlockWithPin` below runs real
 * PBKDF2-HMAC-SHA256 at [PassphraseCipher.ITERATIONS] and real AES-256-GCM. A wrong PIN here fails
 * because the GCM tag genuinely does not verify, not because a stub said so.
 *
 * ## What is deliberately not covered
 *
 * [SecureUnlockManager.biometricEncryptCipher] and the success path of
 * [SecureUnlockManager.biometricDecryptCipher] both delegate straight into
 * [BiometricKeystoreCipher], which needs the real `AndroidKeyStore`. The methods that merely
 * *carry* a caller-supplied `Cipher` — `enableBiometric` and `unlockWithBiometric` — are fully
 * covered, using a plain JCE AES-GCM key, because neither cares where its cipher came from.
 */
@RunWith(RobolectricTestRunner::class)
class SecureUnlockManagerTest {

    private lateinit var context: Context
    private lateinit var store: FakeEncryptedPrefsStore

    /** The PIN used everywhere a *correct* PIN is meant. */
    private val correctPin get() = charArrayOf('1', '2', '3', '4', '5', '6')

    /** Same length, different digits — so a failure can only be the GCM tag, never the shape. */
    private val wrongPin get() = charArrayOf('6', '5', '4', '3', '2', '1')

    // Key names copied from SecureUnlockManager's private companion. They are duplicated here on
    // purpose: these strings are the on-disk format of an existing install, so a rename in
    // production is a migration hazard, and a test that renamed itself in lockstep would hide it.
    private val keyPinSalt = "pin_salt"
    private val keyPinIv = "pin_iv"
    private val keyPinCt = "pin_ct"
    private val keyPinIters = "pin_iters"
    private val keyBioIv = "bio_iv"
    private val keyBioCt = "bio_ct"
    private val keyFailCount = "fail_count"
    private val keyLockoutUntil = "lockout_until"
    private val keyLockoutUntilElapsed = "lockout_until_elapsed"
    private val keyLegacyPassphrase = "db_passphrase"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = FakeEncryptedPrefsStore(context)
        // Start every test from a genuinely fresh install rather than relying on how much of the
        // app's data directory Robolectric happens to rebuild between test methods.
        store.clearFile()
    }

    /**
     * A manager over the shared [store]. Building a second one models a second app launch: it gets
     * a fresh in-memory state but the same persisted prefs.
     */
    private fun newManager() = SecureUnlockManager(context, BiometricKeystoreCipher(), store)

    private fun prefs(): SharedPreferences = store.prefs()

    // ---------------------------------------------------------------------------------------
    // setupPin — the only place a DB passphrase is ever created
    // ---------------------------------------------------------------------------------------

    @Test
    fun `setupPin on a fresh install creates a 32-byte passphrase and stores a PIN wrap`() {
        val manager = newManager()
        assertFalse("nothing is set up yet", manager.isPinSet())

        manager.setupPin(correctPin)

        val passphrase = manager.currentPassphrase()
        assertNotNull(passphrase)
        assertEquals("SQLCipher is keyed with 32 bytes", 32, passphrase!!.size)
        // A generator that silently produced zeros would still be 32 bytes long.
        assertFalse("the passphrase must not be all zeros", passphrase.all { it == 0.toByte() })

        assertTrue(manager.isPinSet())
        assertTrue(manager.unlocked.value)
        // All four wrap components must be present, or loadPinWrap() returns null on the next
        // launch and every correct PIN is reported as wrong.
        assertTrue(prefs().contains(keyPinSalt))
        assertTrue(prefs().contains(keyPinIv))
        assertTrue(prefs().contains(keyPinCt))
        assertEquals(PassphraseCipher.ITERATIONS, prefs().getInt(keyPinIters, 0))
    }

    @Test
    fun `setupPin stores a wrap that unwraps back to the very same passphrase`() {
        // This is the single most important assertion in this file. The passphrase in memory right
        // after setup is what SQLCipher creates the database with; the passphrase recovered from
        // the wrap on the next launch is what SQLCipher is asked to open it with. If those two ever
        // differ, every note is unreadable, and nothing else in the app would notice.
        val manager = newManager()
        manager.setupPin(correctPin)
        val created = manager.currentPassphrase()!!

        // A second manager over the same prefs is a second app launch: nothing in memory carries
        // over, so the bytes below can only have come out of the stored wrap.
        val relaunched = newManager()
        assertEquals(UnlockResult.Success, relaunched.unlockWithPin(correctPin))

        assertArrayEquals(created, relaunched.currentPassphrase())
    }

    @Test
    fun `setupPin generates a different passphrase for each fresh install`() {
        // Guards the opposite failure from the one above: a constant "passphrase" would satisfy
        // every round-trip assertion in this file while giving every install the same DB key.
        val first = newManager().also { it.setupPin(correctPin) }.currentPassphrase()!!

        prefs().edit().clear().commit()
        store = FakeEncryptedPrefsStore(context)
        val second = newManager().also { it.setupPin(correctPin) }.currentPassphrase()!!

        assertFalse("two fresh installs must not share a DB key", first.contentEquals(second))
    }

    @Test
    fun `no operation other than setupPin ever changes the passphrase`() {
        // "Created in exactly ONE place and never regenerated implicitly" is a claim about every
        // other entry point, so this walks all of them and re-checks the bytes each time.
        val manager = newManager()
        manager.setupPin(correctPin)
        val original = manager.currentPassphrase()!!

        assertEquals(UnlockResult.WrongPin(0L), manager.unlockWithPin(wrongPin))
        assertArrayEquals(original, manager.currentPassphrase())

        assertEquals(0L, manager.lockoutRemainingMillis())
        assertArrayEquals(original, manager.currentPassphrase())

        val (encryptCipher, _) = aesGcmCipherPair()
        manager.enableBiometric(encryptCipher)
        assertArrayEquals(original, manager.currentPassphrase())

        manager.lock()
        assertEquals(UnlockResult.Success, manager.unlockWithPin(correctPin))
        assertArrayEquals(original, manager.currentPassphrase())

        // And a relaunch, which is the case that actually matters on a user's device.
        assertEquals(UnlockResult.Success, newManager().unlockWithPin(correctPin))
    }

    @Test
    fun `currentPassphrase hands out a copy, so a caller cannot corrupt the live key`() {
        val manager = newManager()
        manager.setupPin(correctPin)
        val original = manager.currentPassphrase()!!

        // DataModule zeroes the copy it is given after passing it to SQLCipher. If that zeroing
        // reached the manager's own array, the next database open in the same session would key
        // with 32 zero bytes.
        manager.currentPassphrase()!!.fill(0)

        assertArrayEquals(original, manager.currentPassphrase())
    }

    // ---------------------------------------------------------------------------------------
    // Legacy-passphrase migration
    // ---------------------------------------------------------------------------------------

    /** Seeds the raw `db_passphrase` a pre-secure-unlock install left behind. */
    private fun seedLegacyPassphrase(): ByteArray {
        val legacy = ByteArray(32) { (it + 1).toByte() }
        prefs().edit()
            .putString(keyLegacyPassphrase, Base64.encodeToString(legacy, Base64.DEFAULT))
            .commit()
        return legacy
    }

    @Test
    fun `needsMigration is true only while a legacy passphrase exists and no PIN is set`() {
        val manager = newManager()
        assertFalse("a fresh install has nothing to migrate", manager.needsMigration())

        seedLegacyPassphrase()
        assertTrue(newManager().needsMigration())

        newManager().setupPin(correctPin)
        assertFalse("once a PIN exists there is nothing left to migrate", newManager().needsMigration())
    }

    @Test
    fun `setupPin on migration REUSES the legacy passphrase instead of generating one`() {
        // The whole reason setupPin has a migration branch. Generating here would hand SQLCipher a
        // key that has nothing to do with the notes.db already sitting on disk, and every note
        // written before the app was updated would be gone.
        val legacy = seedLegacyPassphrase()

        val manager = newManager()
        assertTrue(manager.needsMigration())
        manager.setupPin(correctPin)

        assertArrayEquals(legacy, manager.currentPassphrase())
    }

    @Test
    fun `migration removes the raw legacy passphrase and leaves a working PIN wrap`() {
        val legacy = seedLegacyPassphrase()
        newManager().setupPin(correctPin)

        // Same commit: the wrap is readable and the plaintext copy is gone, together. Whether the
        // two writes were literally one commit is not observable from outside SharedPreferences;
        // what is observable — and what would break if the removal were dropped or deferred — is
        // that no plaintext key survives a completed setup.
        assertFalse("the raw passphrase must not survive", prefs().contains(keyLegacyPassphrase))
        assertTrue(prefs().contains(keyPinCt))

        val relaunched = newManager()
        assertFalse(relaunched.needsMigration())
        assertEquals(UnlockResult.Success, relaunched.unlockWithPin(correctPin))
        assertArrayEquals(legacy, relaunched.currentPassphrase())
    }

    @Test
    fun `migration resets the lockout state left behind by the legacy install`() {
        seedLegacyPassphrase()
        prefs().edit()
            .putInt(keyFailCount, 4)
            .putLong(keyLockoutUntil, System.currentTimeMillis() + 60_000)
            .putLong(keyLockoutUntilElapsed, SystemClock.elapsedRealtime() + 60_000)
            .commit()

        newManager().setupPin(correctPin)

        assertEquals(0, prefs().getInt(keyFailCount, -1))
        assertEquals(0L, prefs().getLong(keyLockoutUntil, -1L))
        assertEquals(0L, prefs().getLong(keyLockoutUntilElapsed, -1L))
        assertEquals(0L, newManager().lockoutRemainingMillis())
    }

    // ---------------------------------------------------------------------------------------
    // unlockWithPin
    // ---------------------------------------------------------------------------------------

    @Test
    fun `unlockWithPin with no wrap stored reports a wrong PIN rather than crashing`() {
        // Reachable if the prefs file was reset under the app (see openPrefs). The auth screen can
        // recover from WrongPin; it cannot recover from an exception.
        assertEquals(UnlockResult.WrongPin(0L), newManager().unlockWithPin(correctPin))
    }

    @Test
    fun `unlockWithPin rejects a wrap whose iteration count is missing`() {
        // iterations feeds PBKDF2 directly, so a 0 there would derive a different (and much
        // cheaper) key than the one the ciphertext was made with. loadPinWrap refuses it instead.
        newManager().setupPin(correctPin)
        prefs().edit().putInt(keyPinIters, 0).commit()

        assertEquals(UnlockResult.WrongPin(0L), newManager().unlockWithPin(correctPin))
    }

    @Test
    fun `a correct PIN unlocks and a wrong PIN does not`() {
        newManager().setupPin(correctPin)

        val manager = newManager()
        assertFalse(manager.unlocked.value)
        assertEquals(UnlockResult.WrongPin(0L), manager.unlockWithPin(wrongPin))
        assertNull("a failed attempt must not leave a key in memory", manager.currentPassphrase())
        assertFalse(manager.unlocked.value)

        assertEquals(UnlockResult.Success, manager.unlockWithPin(correctPin))
        assertNotNull(manager.currentPassphrase())
        assertTrue(manager.unlocked.value)
    }

    @Test
    fun `the fail count is persisted across launches and the first five fails are free`() {
        newManager().setupPin(correctPin)

        // Each attempt is made by a NEW manager, so the count can only be coming off disk.
        for (attempt in 1..LockoutPolicy.FREE_ATTEMPTS) {
            assertEquals(
                "fail $attempt is inside the free allowance",
                UnlockResult.WrongPin(0L),
                newManager().unlockWithPin(wrongPin),
            )
            assertEquals(attempt, prefs().getInt(keyFailCount, -1))
            assertEquals(0L, prefs().getLong(keyLockoutUntil, -1L))
            assertEquals(0L, prefs().getLong(keyLockoutUntilElapsed, -1L))
        }
    }

    @Test
    fun `the sixth consecutive fail opens a lockout window on both clocks`() {
        newManager().setupPin(correctPin)
        repeat(LockoutPolicy.FREE_ATTEMPTS) { newManager().unlockWithPin(wrongPin) }

        val wallBefore = System.currentTimeMillis()
        val elapsedBefore = SystemClock.elapsedRealtime()
        val result = newManager().unlockWithPin(wrongPin)

        val wrong = result as UnlockResult.WrongPin
        assertEquals("the first paid fail costs BASE_LOCK_MS", LockoutPolicy.BASE_LOCK_MS, wrong.lockoutMillis)
        assertEquals(LockoutPolicy.FREE_ATTEMPTS + 1, prefs().getInt(keyFailCount, -1))

        // Both deadlines, not just the wall one: a wall-clock-only lockout is skipped by moving the
        // device clock forward in Settings, which is the attack the elapsed slot exists to stop.
        val wall = prefs().getLong(keyLockoutUntil, 0L)
        val elapsed = prefs().getLong(keyLockoutUntilElapsed, 0L)
        assertTrue("wall deadline is ~30s out", wall >= wallBefore + LockoutPolicy.BASE_LOCK_MS)
        assertTrue(
            "elapsed deadline is ~30s out",
            elapsed >= elapsedBefore + LockoutPolicy.BASE_LOCK_MS,
        )
        assertTrue(
            "and neither is further out than the cap",
            wall - wallBefore <= LockoutPolicy.MAX_LOCK_MS &&
                elapsed - elapsedBefore <= LockoutPolicy.MAX_LOCK_MS,
        )
    }

    @Test
    fun `during a lockout even the correct PIN is refused, and refusing does not spend an attempt`() {
        newManager().setupPin(correctPin)
        repeat(LockoutPolicy.FREE_ATTEMPTS + 1) { newManager().unlockWithPin(wrongPin) }
        val failCountAfterLockout = prefs().getInt(keyFailCount, -1)

        val result = newManager().unlockWithPin(correctPin)

        val locked = result as UnlockResult.LockedOut
        assertTrue("the caller is told how long to wait", locked.remainingMillis > 0)
        assertTrue(locked.remainingMillis <= LockoutPolicy.BASE_LOCK_MS)
        // The attempt was rejected before the unwrap, so it must not have counted against the user
        // — otherwise repeatedly tapping "unlock" would extend their own lockout.
        assertEquals(failCountAfterLockout, prefs().getInt(keyFailCount, -1))
    }

    @Test
    fun `once the window passes, the correct PIN succeeds and clears BOTH deadline slots`() {
        newManager().setupPin(correctPin)
        repeat(LockoutPolicy.FREE_ATTEMPTS + 1) { newManager().unlockWithPin(wrongPin) }
        assertTrue(newManager().lockoutRemainingMillis() > 0)

        // Simulating 30 seconds passing needs both clocks moved, and Robolectric only virtualizes
        // one of them: ShadowSystemClock drives SystemClock.elapsedRealtime(), while
        // System.currentTimeMillis() is the JVM's real clock and cannot be advanced (verified at
        // API 35 — advanceBy moved elapsedRealtime by exactly 60000ms and the wall clock by 1ms).
        // So the elapsed deadline is passed for real, and the wall deadline is rewritten to the
        // value it would already hold 31 seconds from now. lockoutRemainingMillis takes the
        // STRICTER of the two, so both have to be past for this to unlock at all.
        ShadowSystemClock.advanceBy(Duration.ofMillis(LockoutPolicy.BASE_LOCK_MS + 1_000))
        prefs().edit().putLong(keyLockoutUntil, System.currentTimeMillis() - 1_000).commit()
        assertEquals(0L, newManager().lockoutRemainingMillis())

        assertEquals(UnlockResult.Success, newManager().unlockWithPin(correctPin))

        assertEquals(0, prefs().getInt(keyFailCount, -1))
        assertEquals("wall deadline cleared", 0L, prefs().getLong(keyLockoutUntil, -1L))
        assertEquals("elapsed deadline cleared", 0L, prefs().getLong(keyLockoutUntilElapsed, -1L))
        assertEquals(0L, newManager().lockoutRemainingMillis())
    }

    @Test
    fun `a correct PIN inside the free allowance also resets the fail count`() {
        newManager().setupPin(correctPin)
        repeat(3) { newManager().unlockWithPin(wrongPin) }
        assertEquals(3, prefs().getInt(keyFailCount, -1))

        assertEquals(UnlockResult.Success, newManager().unlockWithPin(correctPin))

        // Without this, three fails today plus three fails next week would lock the user out.
        assertEquals(0, prefs().getInt(keyFailCount, -1))
    }

    // ---------------------------------------------------------------------------------------
    // Biometric
    // ---------------------------------------------------------------------------------------

    /**
     * An ENCRYPT/DECRYPT pair over one plain AES-256-GCM key.
     *
     * SecureUnlockManager only ever calls `doFinal` on the cipher it is handed and reads its `iv`,
     * so a JCE key stands in exactly for the Keystore-backed, biometric-gated one that
     * [BiometricKeystoreCipher] mints on a device. The DECRYPT half is deliberately built from the
     * ENCRYPT half's IV, which is the same handshake the production code performs through
     * `bio_iv`.
     */
    private fun aesGcmCipherPair(): Pair<Cipher, Cipher> {
        val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val encrypt = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val decrypt = Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypt.iv)) }
        return encrypt to decrypt
    }

    @Test
    fun `enableBiometric requires an unlocked passphrase`() {
        newManager().setupPin(correctPin)
        val locked = newManager()

        assertThrows(IllegalStateException::class.java) {
            locked.enableBiometric(aesGcmCipherPair().first)
        }
        assertFalse(locked.isBiometricEnabled())
    }

    @Test
    fun `enableBiometric then unlockWithBiometric round-trips the same passphrase`() {
        val manager = newManager()
        manager.setupPin(correctPin)
        val original = manager.currentPassphrase()!!

        val (encrypt, decrypt) = aesGcmCipherPair()
        manager.enableBiometric(encrypt)
        assertTrue(manager.isBiometricEnabled())
        assertTrue(prefs().contains(keyBioIv))
        assertTrue(prefs().contains(keyBioCt))

        val relaunched = newManager()
        assertTrue(relaunched.unlockWithBiometric(decrypt))
        assertArrayEquals(original, relaunched.currentPassphrase())
        assertTrue(relaunched.unlocked.value)
    }

    @Test
    fun `unlockWithBiometric clears the lockout window`() {
        // Regression guard. A successful fingerprint proves what a correct PIN proves, so it has to
        // clear the same counters — and the ELAPSED deadline especially. That deadline is only
        // ignored while it looks stale (more than MAX_LOCK_MS ahead of current uptime). Left
        // behind on a device that was up for days, it stops looking stale once uptime climbs back
        // past it, and the user is locked out for five minutes days later having failed nothing.
        val manager = newManager()
        manager.setupPin(correctPin)
        val (encrypt, decrypt) = aesGcmCipherPair()
        manager.enableBiometric(encrypt)
        manager.lock()

        repeat(LockoutPolicy.FREE_ATTEMPTS + 1) { newManager().unlockWithPin(wrongPin) }
        assertTrue("precondition: a lockout is open", newManager().lockoutRemainingMillis() > 0)

        assertTrue(newManager().unlockWithBiometric(decrypt))

        assertEquals(0, prefs().getInt(keyFailCount, -1))
        assertEquals(0L, prefs().getLong(keyLockoutUntil, -1L))
        assertEquals(0L, prefs().getLong(keyLockoutUntilElapsed, -1L))
        assertEquals(0L, newManager().lockoutRemainingMillis())
    }

    @Test
    fun `unlockWithBiometric returns false when no biometric wrap is stored`() {
        // Recoverable state — biometric was disabled or the prefs were reset — so the caller must
        // get a false it can fall back to PIN on, not an exception.
        newManager().setupPin(correctPin)
        val manager = newManager()

        assertFalse(manager.unlockWithBiometric(aesGcmCipherPair().second))
        assertNull(manager.currentPassphrase())
    }

    @Test
    fun `disableBiometric clears the stored biometric wrap`() {
        val manager = newManager()
        manager.setupPin(correctPin)
        manager.enableBiometric(aesGcmCipherPair().first)
        assertTrue(manager.isBiometricEnabled())

        // disableBiometric clears the prefs and THEN deletes the Keystore key. Only the second half
        // is out of reach here (Robolectric has no AndroidKeyStore, so deleteKey throws), and it is
        // swallowed so the first half can be asserted. If the prefs removals were dropped, this
        // test fails; the runCatching cannot mask that.
        runCatching { manager.disableBiometric() }

        assertFalse(manager.isBiometricEnabled())
        assertFalse(prefs().contains(keyBioIv))
        assertFalse(prefs().contains(keyBioCt))
    }

    @Test
    fun `biometricDecryptCipher refuses to run without a stored IV`() {
        newManager().setupPin(correctPin)
        assertThrows(IllegalStateException::class.java) { newManager().biometricDecryptCipher() }
    }

    // ---------------------------------------------------------------------------------------
    // In-memory passphrase lifetime
    // ---------------------------------------------------------------------------------------

    /**
     * The live `inMem` array, by reflection.
     *
     * Zeroing is the whole point of [SecureUnlockManager.lock], and it is invisible through the
     * public API: `currentPassphrase()` returns a copy, so it cannot show whether the original was
     * wiped or merely dropped for the garbage collector to leave on the heap. Reading the field is
     * the only way to tell those two apart, and telling them apart is the requirement.
     */
    private fun inMemArray(manager: SecureUnlockManager): ByteArray? {
        val field = SecureUnlockManager::class.java.getDeclaredField("inMem")
        field.isAccessible = true
        return field.get(manager) as ByteArray?
    }

    @Test
    fun `lock zeroes the in-memory passphrase rather than just dropping the reference`() {
        val manager = newManager()
        manager.setupPin(correctPin)
        val live = inMemArray(manager)!!
        assertFalse("precondition: the key is really there", live.all { it == 0.toByte() })

        manager.lock()

        assertTrue("every byte wiped", live.all { it == 0.toByte() })
        assertNull("and the reference dropped", inMemArray(manager))
        assertNull(manager.currentPassphrase())
        assertFalse(manager.unlocked.value)
    }

    @Test
    fun `installing a new passphrase zeroes the one it replaces`() {
        // setInMem's other caller. Unlocking twice in one session (PIN, then biometric, or a
        // re-unlock after a background lock) must not leave the previous copy readable on the heap.
        val manager = newManager()
        manager.setupPin(correctPin)
        val first = inMemArray(manager)!!

        assertEquals(UnlockResult.Success, manager.unlockWithPin(correctPin))

        val second = inMemArray(manager)!!
        assertTrue("a genuinely new array was installed", first !== second)
        assertTrue("the replaced array was wiped", first.all { it == 0.toByte() })
        assertFalse("the live one was not", second.all { it == 0.toByte() })
    }

    @Test
    fun `lock is idempotent and safe before any unlock`() {
        val manager = newManager()
        manager.lock()
        manager.lock()
        assertNull(manager.currentPassphrase())
        assertFalse(manager.unlocked.value)
    }
}
