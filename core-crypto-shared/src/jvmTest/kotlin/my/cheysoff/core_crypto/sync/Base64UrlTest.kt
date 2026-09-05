package my.cheysoff.core_crypto.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Base64url encoding and decoding, checked against the RFC 4648 §10 test vectors with the padding
 * removed.
 *
 * Those seven vectors cover all three group alignments (0, 1 and 2 trailing bytes), which is where
 * a hand-rolled encoder goes wrong. The URL-safe alphabet is checked separately, because the
 * standard vectors never produce a `+` or a `/` and so cannot tell the two alphabets apart.
 */
class Base64UrlTest {

    private fun encodeAscii(text: String) = Base64Url.encode(text.toByteArray(Charsets.US_ASCII))

    private fun decodeAscii(text: String) =
        Base64Url.decode(text)?.toString(Charsets.US_ASCII)

    @Test
    fun `RFC 4648 vectors encode without padding`() {
        assertEquals("", encodeAscii(""))
        assertEquals("Zg", encodeAscii("f"))
        assertEquals("Zm8", encodeAscii("fo"))
        assertEquals("Zm9v", encodeAscii("foo"))
        assertEquals("Zm9vYg", encodeAscii("foob"))
        assertEquals("Zm9vYmE", encodeAscii("fooba"))
        assertEquals("Zm9vYmFy", encodeAscii("foobar"))
    }

    @Test
    fun `the URL-safe alphabet is used for the last two symbols`() {
        // Standard base64 would render these six bits as `+` and `/`, both of which need escaping
        // in a URL path. 0xfb 0xff 0xfe splits into the 6-bit values 62, 63, 63, 62.
        assertEquals("-__-", Base64Url.encode(hex("fbfffe")))
    }

    @Test
    fun `every one of the 64 alphabet symbols is emitted in the right position`() {
        // A single mistyped or transposed character in the ALPHABET constant would corrupt exactly
        // one 6-bit value, which the vectors above would very likely miss. This walks all 64.
        //
        // For a one-byte input the first output character is alphabet[byte ushr 2], because the
        // leading 6 bits of the 24-bit group are the top 6 bits of that byte. So encoding
        // `(v shl 2)` for v in 0..63 reads the alphabet entry for v directly.
        val expected = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val actual = (0..63).map { v ->
            Base64Url.encode(byteArrayOf((v shl 2).toByte()))[0]
        }.joinToString("")

        assertEquals(expected, actual)
    }

    @Test
    fun `a multi-group input round-trips against a known base64 value`() {
        // 48 bytes is an exact multiple of three, so this exercises the loop with no partial final
        // group — the case the seven RFC vectors only reach with "foobar".
        assertEquals(
            "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8gISIjJCUmJygpKissLS4v",
            Base64Url.encode(ByteArray(48) { it.toByte() }),
        )
    }

    @Test
    fun `a 16-byte input encodes to 22 characters`() {
        assertEquals(22, Base64Url.encode(ByteArray(16)).length)
    }

    // -- decode ----------------------------------------------------------------------------------

    @Test
    fun `RFC 4648 vectors decode back to their bytes`() {
        assertEquals("", decodeAscii(""))
        assertEquals("f", decodeAscii("Zg"))
        assertEquals("fo", decodeAscii("Zm8"))
        assertEquals("foo", decodeAscii("Zm9v"))
        assertEquals("foob", decodeAscii("Zm9vYg"))
        assertEquals("fooba", decodeAscii("Zm9vYmE"))
        assertEquals("foobar", decodeAscii("Zm9vYmFy"))
    }

    @Test
    fun `every byte value survives a round trip`() {
        // 0x00 and 0xFF included, and every alignment: 256 is not a multiple of three, so the
        // slices below end on all three group boundaries.
        val all = ByteArray(256) { it.toByte() }
        for (length in 0..all.size) {
            val slice = all.copyOfRange(0, length)
            val decoded = Base64Url.decode(Base64Url.encode(slice))
            assertArrayEquals("length $length", slice, decoded)
        }
    }

    @Test
    fun `the URL-safe symbols decode to their standard values`() {
        assertArrayEquals(hex("fbfffe"), Base64Url.decode("-__-"))
    }

    @Test
    fun `a character outside the alphabet refuses the input`() {
        // `+` and `/` are the standard alphabet's last two symbols and are NOT this one's; `=` is
        // padding this encoder never emits. All three mean the string came from somewhere else.
        assertNull(Base64Url.decode("Zm9v+g"))
        assertNull(Base64Url.decode("Zm9v/g"))
        assertNull(Base64Url.decode("Zm9vYg=="))
        assertNull(Base64Url.decode("not base64"))
    }

    @Test
    fun `a length of four n plus one refuses the input`() {
        // No number of bytes encodes to a trailing group of one character, so a string of this
        // length is malformed however friendly it looks.
        assertNull(Base64Url.decode("Z"))
        assertNull(Base64Url.decode("Zm9vZ"))
    }
}
