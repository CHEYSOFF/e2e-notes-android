package my.cheysoff.feature_pairing

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RFC 5869 Appendix A, the SHA-256 test cases.
 *
 * These check the **test fake**, not production code — but they are the reason the rest of the
 * pairing tests mean anything. A self-consistent-but-wrong KDF would let every session test pass
 * while real devices, running Phase 1's HKDF, failed to derive the same key. Pinning the fake to
 * the published vectors makes "the fake is a real HKDF" a fact rather than an assumption.
 */
class TestHkdfVectorsTest {

    /** RFC 5869 A.1: basic case with salt and info. */
    @Test
    fun rfc5869TestCase1() {
        val okm = TestHkdf.derive(
            ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"),
            salt = hex("000102030405060708090a0b0c"),
            info = hex("f0f1f2f3f4f5f6f7f8f9"),
            outLen = 42,
        )
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a" +
                "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865",
            okm.toHex(),
        )
    }

    /** RFC 5869 A.2: longer inputs and output, which exercises more than one expand block. */
    @Test
    fun rfc5869TestCase2() {
        val okm = TestHkdf.derive(
            ikm = hex(
                "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f" +
                    "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f" +
                    "404142434445464748494a4b4c4d4e4f"
            ),
            salt = hex(
                "606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f" +
                    "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f" +
                    "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf"
            ),
            info = hex(
                "b0b1b2b3b4b5b6b7b8b9babbbcbdbebfc0c1c2c3c4c5c6c7c8c9cacbcccdcecf" +
                    "d0d1d2d3d4d5d6d7d8d9dadbdcdddedfe0e1e2e3e4e5e6e7e8e9eaebecedeeef" +
                    "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff"
            ),
            outLen = 82,
        )
        assertEquals(
            "b11e398dc80327a1c8e7f78c596a4934" +
                "4f012eda2d4efad8a050cc4c19afa97c" +
                "59045a99cac7827271cb41c65e590e09" +
                "da3275600c2f09b8367793a9aca3db71" +
                "cc30c58179ec3e87c14c01d5c1f3434" +
                "f1d87",
            okm.toHex(),
        )
    }

    /**
     * RFC 5869 A.3: zero-length salt and info.
     *
     * Not a shape this module ever produces — pairing always passes a 16-byte `sid` as the salt —
     * but it is the case where two HKDF implementations most often disagree ("empty salt" must
     * mean HashLen zero bytes, not "skip the extract step"), so it is the one worth pinning.
     */
    @Test
    fun rfc5869TestCase3() {
        val okm = TestHkdf.derive(
            ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"),
            salt = ByteArray(0),
            info = ByteArray(0),
            outLen = 42,
        )
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31" +
                "b8a11f5c5ee1879ec3454e5f3c738d2d" +
                "9d201395faa4b61a96c8",
            okm.toHex(),
        )
    }
}
