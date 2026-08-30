package my.cheysoff.core_crypto.sync

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * [ArkCipher] — the wrap that stands between the account key and the disk.
 *
 * Plain JVM: no Robolectric, no Keystore. Everything here is real AES-256-GCM over a real
 * HKDF-derived key, so a failure means the crypto is wrong rather than that a stub disagreed.
 */
class ArkCipherTest {

    private val ark = ByteArray(SyncProtocol.ARK_BYTES) { (it * 5 + 3).toByte() }
    private val passphrase = ByteArray(32) { (0x40 + it).toByte() }

    @Test
    fun `round-trips the ark under the same passphrase`() {
        val wrap = ArkCipher.wrap(ark, passphrase)
        assertArrayEquals(ark, ArkCipher.unwrap(wrap, passphrase))
    }

    @Test
    fun `leaves the caller's arrays alone`() {
        val arkCopy = ark.copyOf()
        val passphraseCopy = passphrase.copyOf()
        ArkCipher.unwrap(ArkCipher.wrap(ark, passphrase), passphrase)
        assertArrayEquals("the ark was mutated", arkCopy, ark)
        assertArrayEquals("the passphrase was mutated", passphraseCopy, passphrase)
    }

    @Test
    fun `a different passphrase does not open the wrap`() {
        val wrap = ArkCipher.wrap(ark, passphrase)
        val other = passphrase.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertNull(ArkCipher.unwrap(wrap, other))
    }

    /**
     * The reason for the 128-bit tag. A stored ARK that has been edited must not open at all —
     * returning 32 different bytes would fork the account exactly as a second [
     * AccountRootKey.generateArk] would.
     */
    @Test
    fun `a modified ciphertext does not open`() {
        val wrap = ArkCipher.wrap(ark, passphrase)
        val tampered = ArkWrap(
            iv = wrap.iv,
            ciphertext = wrap.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() },
        )
        assertNull(ArkCipher.unwrap(tampered, passphrase))
    }

    @Test
    fun `a modified iv does not open`() {
        val wrap = ArkCipher.wrap(ark, passphrase)
        val tampered = ArkWrap(
            iv = wrap.iv.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() },
            ciphertext = wrap.ciphertext,
        )
        assertNull(ArkCipher.unwrap(tampered, passphrase))
    }

    /** A corrupted prefs entry can produce an empty IV, which GCMParameterSpec rejects outright. */
    @Test
    fun `an empty iv is a null rather than a throw`() {
        assertNull(ArkCipher.unwrap(ArkWrap(ByteArray(0), ByteArray(48)), passphrase))
    }

    @Test
    fun `every wrap uses a fresh nonce`() {
        val first = ArkCipher.wrap(ark, passphrase)
        val second = ArkCipher.wrap(ark, passphrase)
        assertNotEquals(first.iv.toList(), second.iv.toList())
        assertNotEquals(first.ciphertext.toList(), second.ciphertext.toList())
    }

    @Test
    fun `the ciphertext does not contain the plaintext`() {
        val wrap = ArkCipher.wrap(ark, passphrase)
        assertEquals(SyncProtocol.ARK_BYTES + 16, wrap.ciphertext.size)
        assertFalse(wrap.ciphertext.toList().windowed(ark.size).contains(ark.toList()))
    }

    @Test
    fun `refuses an ark that is not 32 bytes`() {
        assertThrows(IllegalArgumentException::class.java) {
            ArkCipher.wrap(ByteArray(31), passphrase)
        }
    }

    /**
     * The stored format, pinned independently of [ArkCipher] itself.
     *
     * This test builds the ciphertext from the formula the design document states —
     * `K_arkwrap = HKDF(dbPassphrase, "manana/sync/v1/arkwrap")`, AES-256-GCM, 128-bit tag, no AAD
     * — and asserts [ArkCipher.unwrap] opens it. If the key derivation, the info string, the tag
     * length or the AAD ever changes, this fails; and the thing it protects is every existing
     * install's `ark_ct`, which nothing can recover once it stops opening.
     */
    @Test
    fun `the stored format is HKDF of the passphrase under the arkwrap info string`() {
        val key = Hkdf.derive(
            ikm = passphrase,
            salt = null,
            info = "manana/sync/v1/arkwrap".toByteArray(Charsets.US_ASCII),
            length = 32,
        )
        val iv = ByteArray(12) { (it + 1).toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(ark)

        assertEquals(
            "the info string in SyncProtocol is the one the format uses",
            SyncProtocol.INFO_ARK_WRAP,
            "manana/sync/v1/arkwrap",
        )
        assertArrayEquals(ark, ArkCipher.unwrap(ArkWrap(iv, ciphertext), passphrase))
    }
}
