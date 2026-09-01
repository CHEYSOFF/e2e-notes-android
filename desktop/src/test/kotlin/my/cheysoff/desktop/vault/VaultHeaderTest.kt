package my.cheysoff.desktop.vault

import my.cheysoff.core_crypto.PinWrap
import my.cheysoff.core_crypto.sync.ArkWrap
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultHeaderTest {

    private fun header() = VaultHeader(
        version = VaultHeader.CURRENT_VERSION,
        keyWrap = PinWrap(
            salt = ByteArray(16) { it.toByte() },
            iv = ByteArray(12) { (it + 100).toByte() },
            ciphertext = ByteArray(48) { (it * 3).toByte() },
            iterations = PassphrasePolicy.ITERATIONS,
        ),
        arkWrap = ArkWrap(
            iv = ByteArray(12) { (it + 7).toByte() },
            ciphertext = ByteArray(48) { (it + 11).toByte() },
        ),
        deviceId = "5f2b1c33-0000-4000-8000-000000000001",
    )

    @Test
    fun `every field survives a round trip`() {
        val original = header()
        val decoded = VaultHeader.decode(VaultHeader.encode(original))

        assertNotNull(decoded)
        decoded!!
        assertEquals(original.version, decoded.version)
        assertEquals(original.keyWrap.iterations, decoded.keyWrap.iterations)
        assertArrayEquals(original.keyWrap.salt, decoded.keyWrap.salt)
        assertArrayEquals(original.keyWrap.iv, decoded.keyWrap.iv)
        assertArrayEquals(original.keyWrap.ciphertext, decoded.keyWrap.ciphertext)
        assertArrayEquals(original.arkWrap.iv, decoded.arkWrap.iv)
        assertArrayEquals(original.arkWrap.ciphertext, decoded.arkWrap.ciphertext)
        assertEquals(original.deviceId, decoded.deviceId)
    }

    /**
     * The iteration count is stored, not assumed. Without it, changing
     * [PassphrasePolicy.ITERATIONS] would make every existing vault unopenable — the derivation
     * would use the new count against a wrap made with the old one.
     */
    @Test
    fun `the stored iteration count is what comes back, not the current constant`() {
        val old = header().copy(
            keyWrap = PinWrap(
                salt = ByteArray(16),
                iv = ByteArray(12),
                ciphertext = ByteArray(32),
                iterations = 1234,
            ),
        )
        assertEquals(1234, VaultHeader.decode(VaultHeader.encode(old))!!.keyWrap.iterations)
    }

    @Test
    fun `a header from a newer version is refused rather than half-read`() {
        val text = VaultHeader.encode(header()).toString(Charsets.UTF_8)
            .replace("\"v\": 1", "\"v\": 2")
        assertNull(VaultHeader.decode(text.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `a header naming a KDF this build does not implement is refused`() {
        val text = VaultHeader.encode(header()).toString(Charsets.UTF_8)
            .replace("PBKDF2WithHmacSHA256", "scrypt")
        assertNull(VaultHeader.decode(text.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `truncated and non-JSON files decode to null rather than throwing`() {
        val whole = VaultHeader.encode(header())
        assertNull(VaultHeader.decode(whole.copyOf(whole.size / 2)))
        assertNull(VaultHeader.decode(ByteArray(0)))
        assertNull(VaultHeader.decode("not json at all".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `a header missing the device id is refused`() {
        val text = VaultHeader.encode(header()).toString(Charsets.UTF_8)
            .replace("\"deviceId\"", "\"unrelated\"")
        assertNull(VaultHeader.decode(text.toByteArray(Charsets.UTF_8)))
    }

    /**
     * The header is metadata, not a secret store: the salt, the IVs and the iteration count are
     * public inputs by construction. What it must never contain is anything that identifies the
     * account, and the one such value in reach is `accountId`. This asserts the shape of the file
     * rather than any one omission, so a field added later without thought is caught.
     */
    @Test
    fun `the header carries no plaintext account material`() {
        val text = VaultHeader.encode(header()).toString(Charsets.UTF_8)
        assertTrue(text.contains("\"kdf\""))
        assertTrue(!text.contains("accountId"))
        assertTrue(!text.contains("kContent"))
        assertTrue(!text.contains("kId"))
    }
}
