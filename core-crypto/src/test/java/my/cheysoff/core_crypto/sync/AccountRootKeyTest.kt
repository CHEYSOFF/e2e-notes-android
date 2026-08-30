package my.cheysoff.core_crypto.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ARK key hierarchy: generation, and the three derivations that hang off it.
 *
 * The derivations are checked for *determinism* and *separation* rather than against fixed
 * expected bytes. Pinning literal expected outputs here would be a change-detector: it would fail
 * loudly on any edit to [SyncProtocol]'s info strings, which is arguably useful, but it would also
 * be indistinguishable from a real HKDF regression — and HKDF itself is already pinned to the RFC
 * vectors in `HkdfTest`, which is the assertion that actually has authority.
 */
class AccountRootKeyTest {

    /** A fixed ARK, so every assertion below is reproducible. Never a real key. */
    private fun testArk(seed: Int = 0): ByteArray =
        ByteArray(SyncProtocol.ARK_BYTES) { (it + seed).toByte() }

    // ---------------------------------------------------------------------------------------
    // Generation
    // ---------------------------------------------------------------------------------------

    @Test
    fun `generateArk returns 32 bytes`() {
        assertEquals(SyncProtocol.ARK_BYTES, AccountRootKey.generateArk().size)
    }

    @Test
    fun `generateArk returns different bytes every call`() {
        // This is the property that makes a second call catastrophic, asserted here so the danger
        // documented on `generateArk` is visible as behaviour rather than only as a comment: two
        // calls really do produce two unrelated accounts.
        val first = AccountRootKey.generateArk()
        val second = AccountRootKey.generateArk()

        assertFalse(first.contentEquals(second))
    }

    @Test
    fun `two generated ARKs derive completely unrelated account identities`() {
        val a = AccountRootKey.derive(AccountRootKey.generateArk())
        val b = AccountRootKey.derive(AccountRootKey.generateArk())

        assertFalse(a.accountId.contentEquals(b.accountId))
        assertFalse(a.kContent.contentEquals(b.kContent))
        assertFalse(a.kId.contentEquals(b.kId))
    }

    // ---------------------------------------------------------------------------------------
    // Derivation
    // ---------------------------------------------------------------------------------------

    @Test
    fun `derive is deterministic for the same ARK`() {
        // The property that lets a paired device reach the same keys from the ARK alone.
        val first = AccountRootKey.derive(testArk())
        val second = AccountRootKey.derive(testArk())

        assertEquals(first.kContent.toHex(), second.kContent.toHex())
        assertEquals(first.kId.toHex(), second.kId.toHex())
        assertEquals(first.accountId.toHex(), second.accountId.toHex())
    }

    @Test
    fun `derive returns the documented lengths`() {
        val keys = AccountRootKey.derive(testArk())

        assertEquals(SyncProtocol.DERIVED_KEY_BYTES, keys.kContent.size)
        assertEquals(SyncProtocol.DERIVED_KEY_BYTES, keys.kId.size)
        assertEquals(SyncProtocol.ACCOUNT_ID_BYTES, keys.accountId.size)
    }

    @Test
    fun `the three derived values are different from each other`() {
        // Guards the single most damaging way this could be miswired: reusing one info string for
        // two derivations, which would make the server-visible accountId a prefix of a live secret
        // key. Compared over the shortest common length so the 16-byte accountId is included.
        val keys = AccountRootKey.derive(testArk())
        val prefix = SyncProtocol.ACCOUNT_ID_BYTES

        assertNotEquals(keys.kContent.copyOf(prefix).toHex(), keys.kId.copyOf(prefix).toHex())
        assertNotEquals(keys.kContent.copyOf(prefix).toHex(), keys.accountId.toHex())
        assertNotEquals(keys.kId.copyOf(prefix).toHex(), keys.accountId.toHex())
    }

    @Test
    fun `a one-bit change in the ARK changes all three derived values`() {
        val keys = AccountRootKey.derive(testArk())
        val flipped = testArk().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        val other = AccountRootKey.derive(flipped)

        assertNotEquals(keys.kContent.toHex(), other.kContent.toHex())
        assertNotEquals(keys.kId.toHex(), other.kId.toHex())
        assertNotEquals(keys.accountId.toHex(), other.accountId.toHex())
    }

    @Test
    fun `derive does not modify the ARK it was given`() {
        val ark = testArk()
        val before = ark.toHex()

        AccountRootKey.derive(ark)

        assertEquals(before, ark.toHex())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `derive rejects a short ARK`() {
        AccountRootKey.derive(ByteArray(SyncProtocol.ARK_BYTES - 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `derive rejects a long ARK`() {
        AccountRootKey.derive(ByteArray(SyncProtocol.ARK_BYTES + 1))
    }

    // ---------------------------------------------------------------------------------------
    // Hygiene
    // ---------------------------------------------------------------------------------------

    @Test
    fun `destroy zeroes the secret keys and leaves the public accountId intact`() {
        val keys = AccountRootKey.derive(testArk())
        val accountIdBefore = keys.accountId.toHex()

        keys.destroy()

        assertTrue(keys.kContent.all { it == 0.toByte() })
        assertTrue(keys.kId.all { it == 0.toByte() })
        assertEquals(accountIdBefore, keys.accountId.toHex())
    }
}
