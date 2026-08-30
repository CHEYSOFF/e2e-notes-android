package my.cheysoff.core_crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import my.cheysoff.core_crypto.sync.ArkCipher
import my.cheysoff.core_crypto.sync.ArkWrap
import my.cheysoff.core_crypto.sync.SyncProtocol
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

/**
 * The Account Root Key half of [SecureUnlockManager].
 *
 * ## What is actually being defended here
 *
 * One thing, above everything else: **an ARK is generated once per account and never again.** A
 * second generation does not throw, does not log and does not corrupt anything visibly — it
 * returns another perfectly good 32-byte key, and from that moment the account is two accounts
 * whose records cannot be read across. There is no repair afterwards, because neither half's
 * plaintext is recoverable from the other's key.
 *
 * So the tests below are mostly about the *absence* of a second call: after a relaunch, after a
 * biometric unlock instead of a PIN one, and — the sharpest case — when a stored `ark_ct` is
 * present but will not open, which is the state in which "just make a new one" looks most
 * tempting and is most destructive.
 *
 * Kept apart from [SecureUnlockManagerTest] rather than appended to it because that suite is the
 * regression net for existing installs (PIN wrap, legacy migration, lockout, biometric) and must
 * keep passing unchanged; this one is new behaviour on top of it.
 */
@RunWith(RobolectricTestRunner::class)
class SecureUnlockManagerArkTest {

    private lateinit var context: Context
    private lateinit var store: FakeEncryptedPrefsStore

    private val correctPin get() = charArrayOf('1', '2', '3', '4', '5', '6')

    // The on-disk names, duplicated from the private companion on purpose: they are the format of
    // an existing install, so a rename in production must break a test rather than follow along.
    private val keyArkIv = "ark_iv"
    private val keyArkCt = "ark_ct"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = FakeEncryptedPrefsStore(context)
        store.clearFile()
    }

    private fun newManager() = SecureUnlockManager(context, BiometricKeystoreCipher(), store)

    private fun prefs(): SharedPreferences = store.prefs()

    // ---------------------------------------------------------------------------------------
    // Lazy creation
    // ---------------------------------------------------------------------------------------

    /**
     * The eager-vs-lazy decision, stated as a test.
     *
     * Setting up a PIN must not mint an account key. Most installs never sync, and an ARK created
     * on everyone's behalf is key material at rest that the user never asked for and cannot
     * decline.
     */
    @Test
    fun `setupPin does not create an ark`() {
        val manager = newManager()
        manager.setupPin(correctPin)

        assertNull(manager.currentArk())
        assertFalse(prefs().contains(keyArkCt))
        assertFalse(prefs().contains(keyArkIv))
    }

    @Test
    fun `ensureArk creates one, stores it wrapped, and hands it back`() {
        val manager = newManager()
        manager.setupPin(correctPin)

        val ark = manager.ensureArk()!!
        assertEquals(SyncProtocol.ARK_BYTES, ark.size)
        assertFalse("a real key, not a zero-filled array", ark.all { it == 0.toByte() })
        assertArrayEquals(ark, manager.currentArk())

        // Stored, and stored WRAPPED: the raw key must not be sitting in the prefs value.
        assertTrue(prefs().contains(keyArkCt))
        assertTrue(prefs().contains(keyArkIv))
        val stored = Base64.decode(prefs().getString(keyArkCt, null)!!, Base64.DEFAULT)
        assertFalse(stored.toList().windowed(ark.size).contains(ark.toList()))
    }

    /**
     * The wrap really is under `HKDF(dbPassphrase, ".../arkwrap")`, not under anything else.
     *
     * Asserted by opening the stored ciphertext with the passphrase the manager holds. This is the
     * property the whole design rests on: the ARK becomes readable at the same instant the
     * database does, through whichever unlock path the user took.
     */
    @Test
    fun `the stored ark opens under the database passphrase`() {
        val manager = newManager()
        manager.setupPin(correctPin)
        val ark = manager.ensureArk()!!

        val wrap = ArkWrap(
            iv = Base64.decode(prefs().getString(keyArkIv, null)!!, Base64.DEFAULT),
            ciphertext = Base64.decode(prefs().getString(keyArkCt, null)!!, Base64.DEFAULT),
        )
        assertArrayEquals(ark, ArkCipher.unwrap(wrap, manager.currentPassphrase()!!))
    }

    // ---------------------------------------------------------------------------------------
    // Generated exactly once — the whole reason this class owns the ARK
    // ---------------------------------------------------------------------------------------

    @Test
    fun `ensureArk is idempotent within a session`() {
        val manager = newManager()
        manager.setupPin(correctPin)

        assertArrayEquals(manager.ensureArk(), manager.ensureArk())
    }

    @Test
    fun `a relaunch reuses the stored ark instead of generating another`() {
        val first = newManager()
        first.setupPin(correctPin)
        val original = first.ensureArk()!!
        val storedCiphertext = prefs().getString(keyArkCt, null)

        // A second manager over the same store is a second app launch.
        val relaunched = newManager()
        assertEquals(UnlockResult.Success, relaunched.unlockWithPin(correctPin))

        assertArrayEquals("the unlock alone must produce it", original, relaunched.currentArk())
        assertArrayEquals(original, relaunched.ensureArk())
        assertEquals("and nothing was rewritten", storedCiphertext, prefs().getString(keyArkCt, null))
    }

    /**
     * **The test the design asks for by name: generation is unreachable once `ark_ct` exists.**
     *
     * Driven through the worst version of that state — a stored wrap that does not open, because
     * that is the only way `ark_ct` can be present while the in-memory ARK is null, and therefore
     * the only place a "no ARK? make one" shortcut could hide. The correct answer is null: the
     * account key is gone and a new one would not bring it back, it would fork the account and
     * make the loss permanent on both sides.
     */
    @Test
    fun `ensureArk refuses to generate while an unopenable ark_ct is stored`() {
        val manager = newManager()
        manager.setupPin(correctPin)

        // An ARK wrapped under some other passphrase: right shape, right keys, will not open.
        val foreign = ArkCipher.wrap(ByteArray(SyncProtocol.ARK_BYTES) { 0x11 }, ByteArray(32) { 0x22 })
        prefs().edit()
            .putString(keyArkIv, Base64.encodeToString(foreign.iv, Base64.DEFAULT))
            .putString(keyArkCt, Base64.encodeToString(foreign.ciphertext, Base64.DEFAULT))
            .commit()

        val relaunched = newManager()
        assertEquals(UnlockResult.Success, relaunched.unlockWithPin(correctPin))

        assertNull("nothing to hand out", relaunched.currentArk())
        assertNull("and nothing may be minted over it", relaunched.ensureArk())
        assertArrayEquals(
            "the stored ciphertext is untouched",
            foreign.ciphertext,
            Base64.decode(prefs().getString(keyArkCt, null)!!, Base64.DEFAULT),
        )
    }

    @Test
    fun `ensureArk returns null on a locked device`() {
        newManager().setupPin(correctPin)
        val locked = newManager()

        assertNull(locked.ensureArk())
        assertFalse("and did not write a half-made account", prefs().contains(keyArkCt))
    }

    // ---------------------------------------------------------------------------------------
    // Both unlock paths, unchanged
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a biometric unlock produces the ark exactly as a PIN unlock does`() {
        val manager = newManager()
        manager.setupPin(correctPin)
        val original = manager.ensureArk()!!
        val (encrypt, decrypt) = aesGcmCipherPair()
        manager.enableBiometric(encrypt)

        val relaunched = newManager()
        assertTrue(relaunched.unlockWithBiometric(decrypt))

        assertArrayEquals(original, relaunched.currentArk())
    }

    // ---------------------------------------------------------------------------------------
    // Adoption — the other, and only other, way an ARK arrives
    // ---------------------------------------------------------------------------------------

    @Test
    fun `adoptArk stores a paired device's key and survives a relaunch`() {
        val manager = newManager()
        manager.setupPin(correctPin)

        val fromOtherDevice = ByteArray(SyncProtocol.ARK_BYTES) { (0x70 + it).toByte() }
        manager.adoptArk(fromOtherDevice)
        assertArrayEquals(fromOtherDevice, manager.currentArk())

        val relaunched = newManager()
        assertEquals(UnlockResult.Success, relaunched.unlockWithPin(correctPin))
        assertArrayEquals(fromOtherDevice, relaunched.currentArk())
        // And having adopted, this device never mints: ensureArk sees the adopted key first.
        assertArrayEquals(fromOtherDevice, relaunched.ensureArk())
    }

    @Test
    fun `adoptArk keeps the caller's array rather than aliasing it`() {
        val manager = newManager()
        manager.setupPin(correctPin)
        val received = ByteArray(SyncProtocol.ARK_BYTES) { (0x70 + it).toByte() }
        val expected = received.copyOf()

        manager.adoptArk(received)
        // The pairing screen zeroes what it was handed once it is stored. If the manager kept the
        // same array, that zeroing would blank the live account key.
        received.fill(0)

        assertArrayEquals(expected, manager.currentArk())
    }

    @Test
    fun `adoptArk refuses a locked device and a wrong-sized key`() {
        newManager().setupPin(correctPin)
        val unlocked = newManager().also { it.unlockWithPin(correctPin) }

        assertThrows(IllegalArgumentException::class.java) { unlocked.adoptArk(ByteArray(31)) }
        assertThrows(IllegalStateException::class.java) {
            newManager().adoptArk(ByteArray(SyncProtocol.ARK_BYTES))
        }
        assertFalse(prefs().contains(keyArkCt))
    }

    // ---------------------------------------------------------------------------------------
    // In-memory lifetime
    // ---------------------------------------------------------------------------------------

    /** See the equivalent helper in [SecureUnlockManagerTest] for why reflection is the only way. */
    private fun inMemArkArray(manager: SecureUnlockManager): ByteArray? {
        val field = SecureUnlockManager::class.java.getDeclaredField("inMemArk")
        field.isAccessible = true
        return field.get(manager) as ByteArray?
    }

    @Test
    fun `lock zeroes the in-memory ark rather than just dropping the reference`() {
        val manager = newManager()
        manager.setupPin(correctPin)
        manager.ensureArk()
        val live = inMemArkArray(manager)!!
        assertFalse("precondition: the key is really there", live.all { it == 0.toByte() })

        manager.lock()

        assertTrue("every byte wiped", live.all { it == 0.toByte() })
        assertNull("and the reference dropped", inMemArkArray(manager))
        assertNull(manager.currentArk())
    }

    @Test
    fun `currentArk hands out a copy, so a caller cannot corrupt the live key`() {
        val manager = newManager()
        manager.setupPin(correctPin)
        val original = manager.ensureArk()!!

        manager.currentArk()!!.fill(0)

        assertArrayEquals(original, manager.currentArk())
        assertNotNull(inMemArkArray(manager))
    }

    @Test
    fun `unlocking again replaces the in-memory ark and wipes the old copy`() {
        val manager = newManager()
        manager.setupPin(correctPin)
        manager.ensureArk()
        val first = inMemArkArray(manager)!!

        assertEquals(UnlockResult.Success, manager.unlockWithPin(correctPin))

        val second = inMemArkArray(manager)!!
        assertTrue("a genuinely new array was installed", first !== second)
        assertTrue("the replaced array was wiped", first.all { it == 0.toByte() })
        assertFalse("the live one was not", second.all { it == 0.toByte() })
    }

    private fun aesGcmCipherPair(): Pair<Cipher, Cipher> {
        val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val encrypt = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val decrypt = Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypt.iv)) }
        return encrypt to decrypt
    }
}
