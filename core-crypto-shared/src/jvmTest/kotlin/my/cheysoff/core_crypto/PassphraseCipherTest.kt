package my.cheysoff.core_crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PassphraseCipherTest {

    private fun samplePassphrase(): ByteArray =
        ByteArray(32) { it.toByte() }

    @Test
    fun `wrap then unwrap with the same PIN returns the original passphrase`() {
        val passphrase = samplePassphrase()
        val wrap = PassphraseCipher.wrapWithPin(passphrase, "482917".toCharArray())

        val unwrapped = PassphraseCipher.unwrapWithPin(wrap, "482917".toCharArray())

        assertArrayEquals(passphrase, unwrapped)
    }

    @Test
    fun `wrong PIN returns null`() {
        val passphrase = samplePassphrase()
        val wrap = PassphraseCipher.wrapWithPin(passphrase, "482917".toCharArray())

        val unwrapped = PassphraseCipher.unwrapWithPin(wrap, "000000".toCharArray())

        assertNull(unwrapped)
    }

    @Test
    fun `tampered ciphertext returns null`() {
        val passphrase = samplePassphrase()
        val wrap = PassphraseCipher.wrapWithPin(passphrase, "482917".toCharArray())

        wrap.ciphertext[0] = (wrap.ciphertext[0].toInt() xor 0x01).toByte()

        val unwrapped = PassphraseCipher.unwrapWithPin(wrap, "482917".toCharArray())

        assertNull(unwrapped)
    }

    @Test
    fun `salt and iv differ across wraps`() {
        val passphrase = samplePassphrase()
        val first = PassphraseCipher.wrapWithPin(passphrase, "482917".toCharArray())
        val second = PassphraseCipher.wrapWithPin(passphrase, "482917".toCharArray())

        assertFalse(first.salt.contentEquals(second.salt))
        assertFalse(first.iv.contentEquals(second.iv))
    }

    @Test
    fun `iterations recorded on the wrap`() {
        val wrap = PassphraseCipher.wrapWithPin(samplePassphrase(), "482917".toCharArray())

        assertEquals(PassphraseCipher.ITERATIONS, wrap.iterations)
    }
}
