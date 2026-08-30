package my.cheysoff.feature_pairing

import my.cheysoff.feature_pairing.protocol.HkdfKeyDerivation
import my.cheysoff.feature_pairing.protocol.KeyDerivation
import my.cheysoff.feature_pairing.protocol.PairingProtocol
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The seam between this module and the sync key hierarchy, pinned to literal bytes.
 *
 * ## Why this file exists in this form
 *
 * Until this change there were two HKDF-SHA256 implementations: `core-crypto`'s [
 * my.cheysoff.core_crypto.sync.Hkdf], and a `TestHkdf` fake in this module's `src/test` that the
 * pairing tests bound instead. Each was checked against RFC 5869 on its own, so **both suites
 * passed and would have gone on passing if the two had disagreed** — the failure would only have
 * appeared as two real phones unable to pair. A differential test was written first and run: the
 * two produced byte-identical output on RFC 5869 A.1–A.3 and on both shapes pairing actually uses.
 * The fake was then deleted, and the bytes it agreed on are frozen below.
 *
 * Frozen vectors are strictly stronger than the differential test they replace. Comparing two
 * implementations only proves they agree with *each other*; these constants are the protocol, so a
 * change to `Hkdf`, to [HkdfKeyDerivation], to [PairingProtocol.DOMAIN] or to
 * [PairingProtocol.SAS_INFO] fails here by name rather than in the field.
 *
 * Everything is exercised through [KeyDerivation] — the interface the sessions call — rather than
 * through `Hkdf` directly, so the adapter is covered too.
 */
class HkdfSeamTest {

    private val seam: KeyDerivation = HkdfKeyDerivation

    // -- RFC 5869 Appendix A, the SHA-256 cases -----------------------------------------------
    //
    // Duplicated from `core-crypto`'s own HkdfTest on purpose. That suite proves `Hkdf` is
    // correct; these three prove that the object *pairing reaches through the seam* is the same
    // correct thing, which is a different claim and the one that was untested before.

    /** RFC 5869 A.1: basic case with salt and info. */
    @Test
    fun rfc5869TestCase1() {
        val okm = seam.derive(
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
        val okm = seam.derive(
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
        val okm = seam.derive(
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

    // -- The two shapes pairing actually uses --------------------------------------------------

    /** A fixed stand-in for the ECDH output; the pattern is arbitrary, the length is not. */
    private val z = ByteArray(32) { (it * 7 + 1).toByte() }

    private val sid = ByteArray(PairingProtocol.SID_SIZE_BYTES) { (0xA0 + it).toByte() }

    /** A SEC1 uncompressed point is 0x04 followed by 64 coordinate bytes. */
    private val encodedEa = ByteArray(65) { if (it == 0) 0x04 else (0x10 + it).toByte() }
    private val encodedEb = ByteArray(65) { if (it == 0) 0x04 else (0x90 - it).toByte() }

    /**
     * `Ks = HKDF(ikm = Z, salt = sid, info = "manana/pair/v1" ‖ EA ‖ EB, 32)`.
     *
     * The single most load-bearing derivation in the protocol: this is the AES key the ARK is
     * sealed under. `info` is assembled by [PairingProtocol.sessionKeyInfo] rather than spelled
     * out here, so a change to the domain string or to the EA-before-EB ordering lands on this
     * assertion.
     */
    @Test
    fun sessionKeyShapeIsPinned() {
        val ks = seam.derive(
            ikm = z,
            salt = sid,
            info = PairingProtocol.sessionKeyInfo(encodedEa, encodedEb),
            outLen = PairingProtocol.SESSION_KEY_SIZE_BYTES,
        )
        assertEquals(
            "3745cd760669f2171f323678515f5696c7db4cd125003ae40eca6479343b92a2",
            ks.toHex(),
        )
    }

    /** `sas = HKDF(ikm = ARK, salt = sid, info = "manana/pair/v1/confirm", 8)`, before reduction. */
    @Test
    fun sasShapeIsPinned() {
        val ark = ByteArray(32) { (0x5A + it).toByte() }
        val bytes = seam.derive(
            ikm = ark,
            salt = sid,
            info = PairingProtocol.SAS_INFO.toByteArray(Charsets.US_ASCII),
            outLen = 8,
        )
        assertEquals("54d8c5f9403b021f", bytes.toHex())
    }

    /**
     * `info` is not a bag of bytes: swapping EA and EB must change the key.
     *
     * Both devices order the points by role rather than by who scanned first, so if one side ever
     * assembled `info` the other way round the two would derive different `Ks` and every pairing
     * would end in a GCM tag failure. Cheap to state, and it fails loudly if `sessionKeyInfo` is
     * ever "tidied" into something order-independent.
     */
    @Test
    fun sessionKeyDependsOnPointOrder() {
        val forward = seam.derive(
            ikm = z,
            salt = sid,
            info = PairingProtocol.sessionKeyInfo(encodedEa, encodedEb),
            outLen = PairingProtocol.SESSION_KEY_SIZE_BYTES,
        )
        val swapped = seam.derive(
            ikm = z,
            salt = sid,
            info = PairingProtocol.sessionKeyInfo(encodedEb, encodedEa),
            outLen = PairingProtocol.SESSION_KEY_SIZE_BYTES,
        )
        org.junit.Assert.assertNotEquals(forward.toHex(), swapped.toHex())
    }
}
