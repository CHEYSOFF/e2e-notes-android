package my.cheysoff.core_crypto.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sealing a device's name.
 *
 * Two properties carry the privacy claim, and they fail in different directions:
 *
 *  - **The name is not in the blob**, and neither is its length. A round-trip test cannot see
 *    either failure — a cipher that prepended the name in the clear would round-trip perfectly —
 *    so the blob is searched for the plaintext directly, and names of wildly different lengths are
 *    checked to produce blobs of identical size.
 *  - **The blob is bound to the device it names.** An operator who swaps two rows of the `devices`
 *    table must not end up with two correctly-named devices in the wrong order, and the only thing
 *    stopping that is the public key in the associated data.
 */
class DeviceLabelCipherTest {

    private val ark = ByteArray(32) { it.toByte() }
    private val otherArk = ByteArray(32) { (it + 11).toByte() }

    private val publicKeyB64 = "BAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    private val otherPublicKeyB64 = "BCECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"

    private val label = "Vova's Pixel 7"

    private fun seal(
        label: String = this.label,
        publicKeyB64: String = this.publicKeyB64,
        ark: ByteArray = this.ark,
    ) = DeviceLabelCipher.seal(ark, publicKeyB64, label)

    private fun open(
        sealed: ByteArray,
        publicKeyB64: String = this.publicKeyB64,
        ark: ByteArray = this.ark,
    ) = DeviceLabelCipher.open(ark, publicKeyB64, sealed)

    // ---------------------------------------------------------------------------------------
    // Round trip
    // ---------------------------------------------------------------------------------------

    @Test
    fun `seal then open returns the original label`() {
        assertEquals(label, open(seal()))
    }

    @Test
    fun `an empty label round-trips`() {
        assertEquals("", open(seal(label = "")))
    }

    @Test
    fun `a label of non-ASCII characters round-trips`() {
        val cyrillic = "Вовин Pixel 7"

        assertEquals(cyrillic, open(seal(label = cyrillic)))
    }

    @Test
    fun `an emoji label round-trips`() {
        // Four bytes per code point in UTF-8, and a surrogate pair in the Kotlin String. A scheme
        // that counted characters rather than bytes would silently mangle this.
        val emoji = "📱 kitchen tablet"

        assertEquals(emoji, open(seal(label = emoji)))
    }

    @Test
    fun `the longest label that fits round-trips`() {
        val longest = "L".repeat(DeviceLabelCipher.MAX_LABEL_UTF8_BYTES)

        assertEquals(longest, open(seal(label = longest)))
    }

    @Test
    fun `a second device deriving from the same ARK opens the first device's label`() {
        // The property that makes the device list work at all: the paired device holds the ARK and
        // nothing else, and that is enough.
        assertEquals(label, DeviceLabelCipher.open(ark.copyOf(), publicKeyB64, seal()))
    }

    // ---------------------------------------------------------------------------------------
    // What the blob does not say
    // ---------------------------------------------------------------------------------------

    @Test
    fun `the sealed blob does not contain the label`() {
        val marker = "SECRETPHONE"

        val sealed = seal(label = marker)

        assertTrue(!String(sealed, Charsets.ISO_8859_1).contains(marker))
    }

    @Test
    fun `every sealed label is the same length whatever the name`() {
        // The reason the plaintext is padded to a constant rather than to a bucket: a device list
        // is a handful of rows, so there is no distribution of lengths to hide inside.
        val sizes = listOf(
            "",
            "a",
            "Vova's Pixel 7",
            "L".repeat(DeviceLabelCipher.MAX_LABEL_UTF8_BYTES),
        ).map { seal(label = it).size }.toSet()

        assertEquals(setOf(DeviceLabelCipher.SEALED_BYTES), sizes)
    }

    @Test
    fun `two seals of the same label produce different blobs`() {
        // A deterministic seal would let an operator tell that two devices share a name, and — far
        // worse under GCM — would mean a repeated nonce.
        assertNotEquals(seal().toHex(), seal().toHex())
    }

    @Test
    fun `sealing a label too long to fit is refused rather than truncated`() {
        val tooLong = "L".repeat(DeviceLabelCipher.MAX_LABEL_UTF8_BYTES + 1)

        val thrown = runCatching { seal(label = tooLong) }.exceptionOrNull()

        assertTrue("expected IllegalArgumentException, got $thrown", thrown is IllegalArgumentException)
    }

    @Test
    fun `a multi-byte label is measured in bytes and not in characters`() {
        // 63 three-byte characters is 189 UTF-8 bytes: comfortably under the character count a
        // naive check would apply, and comfortably over what fits.
        val tooLong = "字".repeat(63)

        assertTrue(tooLong.length <= DeviceLabelCipher.MAX_LABEL_UTF8_BYTES)
        assertTrue(runCatching { seal(label = tooLong) }.isFailure)
    }

    // ---------------------------------------------------------------------------------------
    // What the blob is bound to
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a label sealed for one device does not open for another`() {
        // The row-swap defence: an operator who moves this blob onto another device's row gets an
        // unnamed device, not a mislabelled one.
        assertNull(open(seal(), publicKeyB64 = otherPublicKeyB64))
    }

    @Test
    fun `a label does not open under a different ARK`() {
        assertNull(open(seal(), ark = otherArk))
    }

    @Test
    fun `a flipped ciphertext bit fails to open`() {
        val sealed = seal()
        sealed[20] = (sealed[20].toInt() xor 0x01).toByte()

        assertNull(open(sealed))
    }

    @Test
    fun `a flipped tag bit fails to open`() {
        val sealed = seal()
        val last = sealed.size - 1
        sealed[last] = (sealed[last].toInt() xor 0x01).toByte()

        assertNull(open(sealed))
    }

    @Test
    fun `a flipped nonce bit fails to open`() {
        val sealed = seal()
        sealed[1] = (sealed[1].toInt() xor 0x01).toByte()

        assertNull(open(sealed))
    }

    @Test
    fun `an unknown version byte is rejected`() {
        val sealed = seal()
        sealed[0] = 2

        assertNull(open(sealed))
    }

    @Test
    fun `a blob of the wrong length is rejected`() {
        assertNull(open(ByteArray(0)))
        assertNull(open(seal().copyOf(DeviceLabelCipher.SEALED_BYTES - 1)))
        assertNull(open(seal().copyOf(DeviceLabelCipher.SEALED_BYTES + 1)))
    }

    // ---------------------------------------------------------------------------------------
    // Associated data, asserted directly
    //
    // seal/open cannot show that the version byte is in the associated data, because `open`
    // already rejects an unknown version structurally. Asserting the bytes is the only way.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `associated data is exactly the version byte and the length-prefixed public key`() {
        assertEquals(
            "01" + "0003" + "414243",
            DeviceLabelCipher.associatedData("ABC").toHex(),
        )
    }

    @Test
    fun `associated data changes with the public key`() {
        assertNotEquals(
            DeviceLabelCipher.associatedData(publicKeyB64).toHex(),
            DeviceLabelCipher.associatedData(otherPublicKeyB64).toHex(),
        )
    }

    // ---------------------------------------------------------------------------------------
    // Keys
    // ---------------------------------------------------------------------------------------

    @Test
    fun `the label key is domain-separated from the record keys`() {
        // If `INFO_DEVICE_LABEL` ever collided with another info string, the same AES key would be
        // used for two different purposes under two different threat models. Deriving both here
        // and comparing is the cheapest way to hold the separation.
        val labelKey = Hkdf.derive(
            ikm = ark,
            salt = null,
            info = SyncProtocol.INFO_DEVICE_LABEL.toByteArray(Charsets.US_ASCII),
            length = SyncProtocol.DERIVED_KEY_BYTES,
        ).toHex()
        val contentKey = AccountRootKey.derive(ark).kContent.toHex()

        assertNotEquals(labelKey, contentKey)
    }

    @Test
    fun `an ARK of the wrong size is refused`() {
        val thrown = runCatching { seal(ark = ByteArray(16)) }.exceptionOrNull()

        assertTrue("expected IllegalArgumentException, got $thrown", thrown is IllegalArgumentException)
    }
}
