package my.cheysoff.core_crypto.platform

import my.cheysoff.core_crypto.sync.hex
import my.cheysoff.core_crypto.sync.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * [GaloisCounterMode] against the published vectors, and against the JCA.
 *
 * ## Why this suite is the reason the Apple crypto is trustworthy
 *
 * The Apple AEAD is not CommonCrypto's GCM, because CommonCrypto's Kotlin/Native bindings do not
 * expose one — see [GaloisCounterMode]'s KDoc. It is [GaloisCounterMode] over CommonCrypto's AES.
 * Which means the interesting half of it — GHASH, the counter arithmetic, the tag mask, the length
 * encoding — is `commonMain` code, and `commonMain` code runs here.
 *
 * So the risk is not "an unverified GCM ships to an iPhone". It is "an unverified *AES-ECB call*
 * ships to an iPhone", with the composition around it checked two ways on a machine that has a
 * reference implementation:
 *
 *  - **Published vectors.** McGrew & Viega test cases 15 and 16 — the AES-256 cases NIST SP 800-38D
 *    is specified from, and the same two `AesGcmKnownAnswerTest` already pins the JCA against.
 *  - **A differential test against the JCA**, over hundreds of random keys, nonces, associated data
 *    and plaintexts, including every length that is awkward: empty, one byte, one byte short of a
 *    block, exactly a block, and several blocks plus a remainder. Fixed vectors cannot cover the
 *    padding and length-field edges; this does.
 *
 * The block cipher below is the JCA's AES in ECB mode, which is the same primitive CommonCrypto
 * will supply on the other side. Nothing else about the platform is simulated and nothing needs to
 * be: [GaloisCounterMode] takes exactly one thing from its platform.
 */
class GaloisCounterModeTest {

    /**
     * AES-ECB over a whole number of blocks, from the JCA — the stand-in for the one CommonCrypto
     * call the Apple actual makes.
     *
     * `NoPadding` matters: with PKCS#7 the JCA would append a whole extra block of padding and the
     * keystream would be the right bytes followed by sixteen wrong ones, which every test here
     * would catch but which is worth naming since the Apple side has the same choice to get right
     * (`kCCOptionECBMode` with no padding option).
     */
    private fun jcaBlocks(key: ByteArray) = GaloisCounterMode.BlockCipher { input ->
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        cipher.doFinal(input)
    }

    private fun jcaGcm(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray) =
        aesGcmSeal(key, nonce, aad, plaintext)

    // -------------------------------------------------------------------------------------
    // Published vectors
    // -------------------------------------------------------------------------------------

    private val vectorKey =
        hex("feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308")
    private val vectorNonce = hex("cafebabefacedbaddecaf888")

    @Test
    fun `GCM test case 15 - 64 bytes of plaintext, no associated data`() {
        val plaintext = hex(
            "d9313225f88406e5a55909c5aff5269a" +
                "86a7a9531534f7da2e4c303d8a318a72" +
                "1c3c0c95956809532fcf0e2449a6b525" +
                "b16aedf5aa0de657ba637b391aafd255"
        )
        assertEquals(
            "522dc1f099567d07f47f37a32a84427d" +
                "643a8cdcbfe5c0c97598a2bd2555d1aa" +
                "8cb08e48590dbb3da7b08b1056828838" +
                "c5f61e6393ba7a0abcc9f662898015ad" +
                "b094dac5d93471bdec1a502270e3cc6c",
            GaloisCounterMode.seal(
                jcaBlocks(vectorKey), vectorNonce, ByteArray(0), plaintext,
            ).toHex(),
        )
    }

    @Test
    fun `GCM test case 16 - 60 bytes of plaintext, 20 bytes of associated data`() {
        // The one that exercises everything at once: an associated-data block, a ciphertext whose
        // last block is short, and therefore both zero-pads and both length fields.
        val plaintext = hex(
            "d9313225f88406e5a55909c5aff5269a" +
                "86a7a9531534f7da2e4c303d8a318a72" +
                "1c3c0c95956809532fcf0e2449a6b525" +
                "b16aedf5aa0de657ba637b39"
        )
        val aad = hex("feedfacedeadbeeffeedfacedeadbeefabaddad2")
        assertEquals(
            "522dc1f099567d07f47f37a32a84427d" +
                "643a8cdcbfe5c0c97598a2bd2555d1aa" +
                "8cb08e48590dbb3da7b08b1056828838" +
                "c5f61e6393ba7a0abcc9f662" +
                "76fc6ece0f4e1768cddf8853bb2d551b",
            GaloisCounterMode.seal(jcaBlocks(vectorKey), vectorNonce, aad, plaintext).toHex(),
        )
    }

    @Test
    fun `the empty case - no plaintext and no associated data is a bare tag`() {
        // AES-256, all-zero key and nonce: the tag is GHASH of the two zero length fields, masked
        // by E_K(J0). It exercises the paths every other vector skips, and a `J0` off by one shows
        // up here immediately.
        val sealed = GaloisCounterMode.seal(
            jcaBlocks(ByteArray(32)), ByteArray(12), ByteArray(0), ByteArray(0),
        )
        assertEquals(16, sealed.size)
        assertEquals(jcaGcm(ByteArray(32), ByteArray(12), ByteArray(0), ByteArray(0)).toHex(), sealed.toHex())
    }

    // -------------------------------------------------------------------------------------
    // Differential against the JCA
    // -------------------------------------------------------------------------------------

    @Test
    fun `it agrees with the JCA on every awkward length`() {
        val random = SecureRandom()
        val key = ByteArray(32).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)

        // Empty, one byte, one short of a block, exactly a block, one past, two blocks, an odd
        // multi-block length, and one padding bucket -- the real record size.
        val lengths = listOf(0, 1, 15, 16, 17, 31, 32, 33, 64, 100, 4096)
        for (plaintextLength in lengths) {
            for (aadLength in listOf(0, 1, 15, 16, 20, 33)) {
                val plaintext = ByteArray(plaintextLength).also(random::nextBytes)
                val aad = ByteArray(aadLength).also(random::nextBytes)
                assertArrayEquals(
                    "plaintext=$plaintextLength aad=$aadLength",
                    jcaGcm(key, nonce, aad, plaintext),
                    GaloisCounterMode.seal(jcaBlocks(key), nonce, aad, plaintext),
                )
            }
        }
    }

    @Test
    fun `it agrees with the JCA on two hundred random inputs`() {
        val random = SecureRandom()
        repeat(200) {
            val key = ByteArray(32).also(random::nextBytes)
            val nonce = ByteArray(12).also(random::nextBytes)
            val aad = ByteArray(random.nextInt(48)).also(random::nextBytes)
            val plaintext = ByteArray(random.nextInt(300)).also(random::nextBytes)
            assertArrayEquals(
                jcaGcm(key, nonce, aad, plaintext),
                GaloisCounterMode.seal(jcaBlocks(key), nonce, aad, plaintext),
            )
        }
    }

    @Test
    fun `it opens what the JCA sealed`() {
        // The direction that matters for interop: an envelope written by an Android phone, read on
        // an iPhone. Nothing else in the test suite crosses the two implementations this way.
        val random = SecureRandom()
        repeat(50) {
            val key = ByteArray(32).also(random::nextBytes)
            val nonce = ByteArray(12).also(random::nextBytes)
            val aad = ByteArray(random.nextInt(48)).also(random::nextBytes)
            val plaintext = ByteArray(random.nextInt(300)).also(random::nextBytes)
            val sealed = jcaGcm(key, nonce, aad, plaintext)
            assertArrayEquals(
                plaintext,
                GaloisCounterMode.open(jcaBlocks(key), nonce, aad, sealed),
            )
        }
    }

    @Test
    fun `the JCA opens what it sealed`() {
        // And the other direction: an iPhone writes, an Android phone reads.
        val random = SecureRandom()
        repeat(50) {
            val key = ByteArray(32).also(random::nextBytes)
            val nonce = ByteArray(12).also(random::nextBytes)
            val aad = ByteArray(random.nextInt(48)).also(random::nextBytes)
            val plaintext = ByteArray(random.nextInt(300)).also(random::nextBytes)
            val sealed = GaloisCounterMode.seal(jcaBlocks(key), nonce, aad, plaintext)
            assertArrayEquals(plaintext, aesGcmOpen(key, nonce, aad, sealed))
        }
    }

    // -------------------------------------------------------------------------------------
    // Refusals
    // -------------------------------------------------------------------------------------

    @Test
    fun `a flipped ciphertext bit is refused`() {
        val sealed = GaloisCounterMode.seal(
            jcaBlocks(vectorKey), vectorNonce, ByteArray(0), "a body".encodeToByteArray(),
        )
        sealed[0] = (sealed[0].toInt() xor 1).toByte()
        assertNull(GaloisCounterMode.open(jcaBlocks(vectorKey), vectorNonce, ByteArray(0), sealed))
    }

    @Test
    fun `a flipped tag bit is refused`() {
        val sealed = GaloisCounterMode.seal(
            jcaBlocks(vectorKey), vectorNonce, ByteArray(0), "a body".encodeToByteArray(),
        )
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 1).toByte()
        assertNull(GaloisCounterMode.open(jcaBlocks(vectorKey), vectorNonce, ByteArray(0), sealed))
    }

    @Test
    fun `changed associated data is refused`() {
        val sealed = GaloisCounterMode.seal(
            jcaBlocks(vectorKey), vectorNonce, "one".encodeToByteArray(), "x".encodeToByteArray(),
        )
        assertNull(
            GaloisCounterMode.open(
                jcaBlocks(vectorKey), vectorNonce, "two".encodeToByteArray(), sealed,
            )
        )
    }

    @Test
    fun `a blob shorter than a tag is refused rather than throwing`() {
        assertNull(GaloisCounterMode.open(jcaBlocks(vectorKey), vectorNonce, ByteArray(0), ByteArray(4)))
    }

    @Test
    fun `a nonce that is not 96 bits is rejected outright`() {
        // Not silently accepted with a GHASH-derived J0, which is what a fuller implementation
        // would do -- this one does not implement that path, and quietly treating a 64-bit nonce as
        // if it were 96 would produce a self-consistent cipher that no other GCM can read.
        val error = runCatching {
            GaloisCounterMode.seal(jcaBlocks(vectorKey), ByteArray(8), ByteArray(0), ByteArray(0))
        }.exceptionOrNull()
        assertEquals(IllegalArgumentException::class.java, error?.javaClass)
    }
}
