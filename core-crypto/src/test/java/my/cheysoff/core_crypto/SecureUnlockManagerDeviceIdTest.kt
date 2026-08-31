package my.cheysoff.core_crypto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The two values [SecureUnlockManager] gained for sync: the local `device_id` and the HLC node
 * derived from it.
 *
 * They live in `secret_shared_prefs` rather than in the database for one reason, and it is the
 * reason the first test names: the database cannot be opened while the app is locked
 * (`DataModule.provideNoteDatabase` throws), and the sync engine needs a device identity before it
 * has unlocked anything. The prefs file is opened under a Keystore master key, not the PIN, so it
 * is readable at that moment.
 *
 * Kept apart from [SecureUnlockManagerArkTest] for the same reason that file is kept apart from
 * [SecureUnlockManagerTest]: those are the regression nets for behaviour that already shipped.
 */
@RunWith(RobolectricTestRunner::class)
class SecureUnlockManagerDeviceIdTest {

    private lateinit var context: Context
    private lateinit var store: FakeEncryptedPrefsStore

    private val correctPin get() = charArrayOf('1', '2', '3', '4', '5', '6')

    /** The on-disk name, duplicated on purpose so a rename in production breaks a test. */
    private val keyDeviceId = "device_id"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = FakeEncryptedPrefsStore(context)
        store.clearFile()
    }

    private fun newManager() = SecureUnlockManager(context, BiometricKeystoreCipher(), store)

    // ── device_id ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the device id is readable while locked`() {
        // The whole reason it is not a database column. Nothing has been unlocked here, and no PIN
        // has even been set up.
        val manager = newManager()
        assertFalse(manager.unlocked.value)

        assertTrue(manager.deviceId().isNotEmpty())
    }

    @Test
    fun `the device id is generated once and then never changes`() {
        val manager = newManager()
        val first = manager.deviceId()

        assertEquals(first, manager.deviceId())
        // …including across a process restart, which is what the prefs write is for. A second id
        // for one install would change the HLC node derived from it and split this device's
        // history in two.
        assertEquals(first, newManager().deviceId())
    }

    @Test
    fun `the device id survives setting up a PIN and unlocking`() {
        val manager = newManager()
        val before = manager.deviceId()

        manager.setupPin(correctPin)
        assertEquals(before, manager.deviceId())

        manager.lock()
        manager.unlockWithPin(correctPin)
        assertEquals(before, manager.deviceId())
    }

    @Test
    fun `two installs get different device ids`() {
        val first = newManager().deviceId()
        store.clearFile()
        assertNotEquals(first, newManager().deviceId())
    }

    @Test
    fun `the device id is 128 bits of lowercase hex`() {
        // Not derived from anything about the hardware — not ANDROID_ID, not the build
        // fingerprint, not the model — because it is the salt the published node is derived from
        // and it is itself sent to the server during the session handshake.
        val id = newManager().deviceId()
        assertEquals(SecureUnlockManagerDeviceIdConstants.EXPECTED_HEX_LENGTH, id.length)
        assertTrue("not lowercase hex: '$id'", id.matches(Regex("[0-9a-f]+")))
    }

    @Test
    fun `the device id is stored under its documented key`() {
        val id = newManager().deviceId()
        assertEquals(id, store.prefs().getString(keyDeviceId, null))
    }

    // ── hlcNode ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `there is no node until this device has an account key`() {
        val manager = newManager()
        assertEquals("", manager.hlcNode)

        manager.setupPin(correctPin)
        // setupPin deliberately does not mint an ARK, so there is still no account to be a
        // pseudonym for. Publishing a local fallback here is what HlcNode exists to prevent.
        assertEquals("", manager.hlcNode)
    }

    @Test
    fun `a node appears once an ARK exists, and matches the documented derivation`() {
        val manager = newManager()
        manager.setupPin(correctPin)
        val ark = manager.ensureArk()!!

        val node = manager.hlcNode

        assertEquals(HlcNode.derive(ark, manager.deviceId()), node)
        assertTrue(node.isNotEmpty())
    }

    @Test
    fun `the node is restored by an ordinary unlock, not only by creating the ARK`() {
        val manager = newManager()
        manager.setupPin(correctPin)
        manager.ensureArk()
        val node = manager.hlcNode

        manager.lock()
        assertEquals("locking must take the node with the ARK", "", manager.hlcNode)

        manager.unlockWithPin(correctPin)
        assertEquals(node, manager.hlcNode)

        // …and on a fresh process too, since the node is derived rather than stored.
        val relaunched = newManager()
        relaunched.unlockWithPin(correctPin)
        assertEquals(node, relaunched.hlcNode)
    }

    @Test
    fun `joining another account changes the node`() {
        // The per-account property, end to end: adopting a different ARK re-pseudonymises this
        // device with no rotation logic anywhere, so an operator hosting both accounts cannot see
        // that one device is behind them.
        val manager = newManager()
        manager.setupPin(correctPin)
        manager.ensureArk()
        val before = manager.hlcNode

        manager.adoptArk(ByteArray(32) { 9 })

        assertNotEquals(before, manager.hlcNode)
        assertEquals(HlcNode.derive(ByteArray(32) { 9 }, manager.deviceId()), manager.hlcNode)
    }

    @Test
    fun `the node is not the device id`() {
        val manager = newManager()
        manager.setupPin(correctPin)
        manager.ensureArk()

        assertNotEquals(manager.deviceId(), manager.hlcNode)
        assertFalse(manager.hlcNode.contains(manager.deviceId()))
    }
}

/** Kept out of the test class so the number reads as a claim about the format, not a magic literal. */
private object SecureUnlockManagerDeviceIdConstants {
    /** 16 bytes rendered as hex. */
    const val EXPECTED_HEX_LENGTH = 32
}
