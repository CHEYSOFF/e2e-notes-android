package my.cheysoff.core_crypto.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Base64url encoding, checked against the RFC 4648 §10 test vectors with the padding removed.
 *
 * Those seven vectors cover all three group alignments (0, 1 and 2 trailing bytes), which is where
 * a hand-rolled encoder goes wrong. The URL-safe alphabet is checked separately, because the
 * standard vectors never produce a `+` or a `/` and so cannot tell the two alphabets apart.
 */
class Base64UrlTest {

    private fun encodeAscii(text: String) = Base64Url.encode(text.toByteArray(Charsets.US_ASCII))

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
}
