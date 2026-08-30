package my.cheysoff.core_crypto.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * HKDF-SHA256 against the **published RFC 5869 Appendix A test vectors**.
 *
 * This is the point of the whole file. A self-consistency test ("expand twice, get the same
 * bytes") passes against an HKDF that swaps the HMAC key and message, starts its counter at 0,
 * feeds the running output back in instead of the previous block, or omits `info` — all of which
 * produce 32 uniformly random-looking bytes that no assertion about randomness can distinguish
 * from correct output. Only the RFC's own PRK and OKM values catch those.
 *
 * RFC 5869 has seven vectors. A.1–A.3 are the SHA-256 ones and all three are here; A.4–A.7 are
 * SHA-1, which this implementation deliberately does not support.
 *
 *  - **A.1** basic: 22-byte IKM, 13-byte salt, 10-byte info, L=42 (two expand blocks).
 *  - **A.2** long inputs: 80-byte IKM/salt/info, L=82 (three expand blocks, partial last block) —
 *    the one that pins the counter and the `T(i-1)` chaining.
 *  - **A.3** zero-length salt and info, L=42 — the one that pins the "empty salt becomes HashLen
 *    zero bytes" rule and the "info may be empty" path.
 */
class HkdfTest {

    // ---------------------------------------------------------------------------------------
    // RFC 5869 A.1 — Basic test case with SHA-256
    // ---------------------------------------------------------------------------------------

    private val a1Ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
    private val a1Salt = hex("000102030405060708090a0b0c")
    private val a1Info = hex("f0f1f2f3f4f5f6f7f8f9")
    private val a1Prk =
        "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5"
    private val a1Okm =
        "3cb25f25faacd57a90434f64d0362f2a" +
            "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
            "34007208d5b887185865"

    @Test
    fun `RFC 5869 A1 extract produces the published PRK`() {
        assertEquals(a1Prk, Hkdf.extract(a1Salt, a1Ikm).toHex())
    }

    @Test
    fun `RFC 5869 A1 expand produces the published OKM`() {
        assertEquals(a1Okm, Hkdf.expand(hex(a1Prk), a1Info, 42).toHex())
    }

    @Test
    fun `RFC 5869 A1 full derive produces the published OKM`() {
        assertEquals(a1Okm, Hkdf.derive(a1Ikm, a1Salt, a1Info, 42).toHex())
    }

    // ---------------------------------------------------------------------------------------
    // RFC 5869 A.2 — Test with SHA-256 and longer inputs/outputs
    // ---------------------------------------------------------------------------------------

    /** 0x00..0x4f — 80 bytes. */
    private val a2Ikm = ByteArray(80) { it.toByte() }

    /** 0x60..0xaf — 80 bytes. */
    private val a2Salt = ByteArray(80) { (0x60 + it).toByte() }

    /** 0xb0..0xff — 80 bytes. */
    private val a2Info = ByteArray(80) { (0xb0 + it).toByte() }
    private val a2Prk =
        "06a6b88c5853361a06104c9ceb35b45cef760014904671014a193f40c15fc244"
    private val a2Okm =
        "b11e398dc80327a1c8e7f78c596a4934" +
            "4f012eda2d4efad8a050cc4c19afa97c" +
            "59045a99cac7827271cb41c65e590e09" +
            "da3275600c2f09b8367793a9aca3db71" +
            "cc30c58179ec3e87c14c01d5c1f3434f" +
            "1d87"

    @Test
    fun `RFC 5869 A2 extract produces the published PRK`() {
        assertEquals(a2Prk, Hkdf.extract(a2Salt, a2Ikm).toHex())
    }

    @Test
    fun `RFC 5869 A2 expand produces the published OKM across three blocks`() {
        assertEquals(a2Okm, Hkdf.expand(hex(a2Prk), a2Info, 82).toHex())
    }

    @Test
    fun `RFC 5869 A2 full derive produces the published OKM`() {
        assertEquals(a2Okm, Hkdf.derive(a2Ikm, a2Salt, a2Info, 82).toHex())
    }

    // ---------------------------------------------------------------------------------------
    // RFC 5869 A.3 — Test with SHA-256 and zero-length salt/info
    // ---------------------------------------------------------------------------------------

    private val a3Ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
    private val a3Prk =
        "19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04"
    private val a3Okm =
        "8da4e775a563c18f715f802a063c5a31" +
            "b8a11f5c5ee1879ec3454e5f3c738d2d" +
            "9d201395faa4b61a96c8"

    @Test
    fun `RFC 5869 A3 extract with an empty salt produces the published PRK`() {
        assertEquals(a3Prk, Hkdf.extract(ByteArray(0), a3Ikm).toHex())
    }

    @Test
    fun `RFC 5869 A3 expand with empty info produces the published OKM`() {
        assertEquals(a3Okm, Hkdf.expand(hex(a3Prk), ByteArray(0), 42).toHex())
    }

    @Test
    fun `RFC 5869 A3 full derive produces the published OKM`() {
        assertEquals(a3Okm, Hkdf.derive(a3Ikm, ByteArray(0), ByteArray(0), 42).toHex())
    }

    // ---------------------------------------------------------------------------------------
    // Properties the RFC vectors do not pin down on their own
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a null salt is treated exactly like an empty salt`() {
        // RFC 5869 §2.2: "if not provided, it is set to a string of HashLen zeros". The A.3 vector
        // exercises the empty-array spelling; this pins the null spelling to the same result.
        assertArrayEquals(Hkdf.extract(null, a3Ikm), Hkdf.extract(ByteArray(0), a3Ikm))
        assertEquals(a3Prk, Hkdf.extract(null, a3Ikm).toHex())
    }

    @Test
    fun `an explicit all-zero salt of HashLen bytes matches the empty salt`() {
        assertEquals(a3Prk, Hkdf.extract(ByteArray(Hkdf.HASH_LEN), a3Ikm).toHex())
    }

    @Test
    fun `changing only info changes the output`() {
        // The failure mode this catches is an expand that never feeds `info` into the HMAC: every
        // derivation in this protocol is distinguished ONLY by its info string, so an ignored
        // `info` would silently make K_content, K_id and accountId the same 32 bytes.
        val first = Hkdf.derive(a1Ikm, a1Salt, "one".toByteArray(), 32).toHex()
        val second = Hkdf.derive(a1Ikm, a1Salt, "two".toByteArray(), 32).toHex()

        assertNotEquals(first, second)
    }

    @Test
    fun `output is a prefix-consistent stream so a shorter length is a prefix of a longer one`() {
        // A consequence of the T(1)‖T(2)‖… construction, and a cheap check that the truncation of
        // the final partial block happens at the end rather than per block.
        val long = Hkdf.derive(a1Ikm, a1Salt, a1Info, 82).toHex()
        val short = Hkdf.derive(a1Ikm, a1Salt, a1Info, 42).toHex()

        assertEquals(short, long.substring(0, 84))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `expand rejects a zero length`() {
        Hkdf.expand(hex(a1Prk), a1Info, 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `expand rejects a length above the RFC 5869 maximum`() {
        // The one-byte counter cannot address more than 255 blocks; asking for more would silently
        // wrap the counter and repeat output blocks.
        Hkdf.expand(hex(a1Prk), a1Info, Hkdf.MAX_OUTPUT_BYTES + 1)
    }

    @Test
    fun `expand accepts exactly the RFC 5869 maximum length`() {
        assertEquals(Hkdf.MAX_OUTPUT_BYTES, Hkdf.expand(hex(a1Prk), a1Info, Hkdf.MAX_OUTPUT_BYTES).size)
    }
}
