package my.cheysoff.core_crypto.platform

import my.cheysoff.core_crypto.sync.hex
import my.cheysoff.core_crypto.sync.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Known-answer tests for the four platform primitives, against **published** vectors.
 *
 * ## Why this file is the one that matters most
 *
 * The other vector suite in this package's sibling, `ProtocolVectorsTest`, checks the protocol
 * classes against values generated from this project's own JVM implementation. That is exactly the
 * right test for *parity* — it answers "does an iPhone compute what a Pixel computes" — but it
 * cannot answer "are both of them right", because the same code wrote the question and the answer.
 *
 * This file closes that hole. Every vector below comes from outside the project:
 *
 *  - HMAC-SHA256 from **RFC 4231** §4.2 and §4.3.
 *  - PBKDF2-HMAC-SHA256 from the SHA-256 vectors published alongside **RFC 6070**, and reproduced
 *    in **RFC 7914** §11 (the scrypt RFC uses them to specify its own PBKDF2 step).
 *  - AES-256-GCM from **McGrew & Viega**, *The Galois/Counter Mode of Operation*, test cases 15
 *    and 16 — the paper NIST SP 800-38D specifies GCM from, and the same two vectors
 *    `AesGcmKnownAnswerTest` already pins on the JVM.
 *
 * So the pair of suites together says something neither says alone: the primitives are correct
 * against the standards, and the protocol built on them is identical across platforms.
 *
 * ## What a failure here means
 *
 * On the JVM: a JCA provider that does not implement what it says it does, which is why
 * `AesGcmKnownAnswerTest` was written in the first place — SunJCE in a unit test and Conscrypt on a
 * device are different code.
 *
 * On an Apple target: the actual in `PlatformCrypto.apple.kt` is wrong, and the failing test names
 * which primitive. That is the entire purpose of this file existing in `commonTest` rather than
 * `jvmTest` — see `docs/BUILDING-IOS.md`, which asks for it to be the first thing run on a Mac.
 */
class PlatformCryptoKnownAnswerTest {

    // ---------------------------------------------------------------------------------------
    // HMAC-SHA256 — RFC 4231
    // ---------------------------------------------------------------------------------------

    @Test
    fun `RFC 4231 case 2 - a short ASCII key`() {
        // The famous one: key "Jefe", data "what do ya want for nothing?".
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            hmacSha256(
                key = "Jefe".encodeToByteArray(),
                message = "what do ya want for nothing?".encodeToByteArray(),
            ).toHex(),
        )
    }

    @Test
    fun `RFC 4231 case 1 - a 20-byte key`() {
        assertEquals(
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            hmacSha256(
                key = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"),
                message = "Hi There".encodeToByteArray(),
            ).toHex(),
        )
    }

    @Test
    fun `RFC 4231 case 6 - a key longer than the SHA-256 block, which must be hashed first`() {
        // 131 bytes of 0xaa, i.e. longer than SHA-256's 64-byte block. RFC 2104 says such a key is
        // replaced by its own hash. An implementation that truncated or padded instead would still
        // produce 32 plausible bytes, and `Hkdf` would still round-trip against itself — this is
        // the case that catches it.
        assertEquals(
            "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54",
            hmacSha256(
                key = ByteArray(131) { 0xaa.toByte() },
                message = "Test Using Larger Than Block-Size Key - Hash Key First".encodeToByteArray(),
            ).toHex(),
        )
    }

    @Test
    fun `a 32-byte zero key is the one HKDF-Extract falls back to, and it is not special`() {
        // Not an RFC 4231 case; it is here because it is the key `Hkdf.extract` substitutes when
        // there is no salt, which is EVERY derivation in this protocol -- the whole key hierarchy
        // runs through it. RFC 5869 case A.3 in `HkdfTest` pins the answer; this pins that the
        // primitive underneath does not treat the input as "no key".
        //
        // A zero-LENGTH key is deliberately not tested. CommonCrypto accepts one and the JCA
        // refuses it outright (`SecretKeySpec` throws on an empty key), so the two platforms
        // genuinely differ -- and it does not matter, because `Hkdf.extract` is the only caller
        // that could produce one and it substitutes these 32 zero bytes instead. The seam's KDoc
        // says the key must be non-empty for exactly this reason.
        val tag = hmacSha256(key = ByteArray(32), message = "anything".encodeToByteArray())
        assertEquals(32, tag.size)
        assertTrue(tag.any { it != 0.toByte() }, "an HMAC tag of all zeros is not a real answer")
    }

    // ---------------------------------------------------------------------------------------
    // PBKDF2-HMAC-SHA256 — RFC 6070's inputs, SHA-256 outputs as published in RFC 7914 §11
    // ---------------------------------------------------------------------------------------

    @Test
    fun `PBKDF2-HMAC-SHA256 with one iteration`() {
        assertEquals(
            "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            pbkdf2HmacSha256(
                password = "password".toCharArray(),
                salt = "salt".encodeToByteArray(),
                iterations = 1,
                keyBytes = 32,
            ).toHex(),
        )
    }

    @Test
    fun `PBKDF2-HMAC-SHA256 with two iterations`() {
        // One iteration alone would not catch an implementation that ignored the counter, because
        // with c=1 the output is a single HMAC. Two does.
        assertEquals(
            "ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43",
            pbkdf2HmacSha256(
                password = "password".toCharArray(),
                salt = "salt".encodeToByteArray(),
                iterations = 2,
                keyBytes = 32,
            ).toHex(),
        )
    }

    @Test
    fun `PBKDF2-HMAC-SHA256 with 4096 iterations`() {
        // The one that costs real time, and the one closest in shape to what `PassphraseCipher`
        // actually does at 210,000 rounds. An implementation that silently capped the iteration
        // count — some do — fails here and passes the two above.
        assertEquals(
            "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a",
            pbkdf2HmacSha256(
                password = "password".toCharArray(),
                salt = "salt".encodeToByteArray(),
                iterations = 4096,
                keyBytes = 32,
            ).toHex(),
        )
    }

    @Test
    fun `PBKDF2-HMAC-SHA256 with an output longer than one hash block`() {
        // 40 bytes from a 32-byte PRF, so the implementation has to run a second block and
        // truncate. `PassphraseCipher` only ever asks for 32, but a platform whose PBKDF2 got the
        // multi-block case wrong would be a trap waiting for the first key that needed more.
        assertEquals(
            "348c89dbcbd32b2f32d814b8116e84cf2b17347ebc1800181c4e2a1fb8dd53e1c635518c7dac47e9",
            pbkdf2HmacSha256(
                password = "passwordPASSWORDpassword".toCharArray(),
                salt = "saltSALTsaltSALTsaltSALTsaltSALTsalt".encodeToByteArray(),
                iterations = 4096,
                keyBytes = 40,
            ).toHex(),
        )
    }

    // ---------------------------------------------------------------------------------------
    // AES-256-GCM — McGrew & Viega test cases 15 and 16
    // ---------------------------------------------------------------------------------------

    private val gcmKey = hex("feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308")
    private val gcmNonce = hex("cafebabefacedbaddecaf888")

    @Test
    fun `GCM test case 15 - 64 bytes of plaintext, no associated data`() {
        val plaintext = hex(
            "d9313225f88406e5a55909c5aff5269a" +
                "86a7a9531534f7da2e4c303d8a318a72" +
                "1c3c0c95956809532fcf0e2449a6b525" +
                "b16aedf5aa0de657ba637b391aafd255"
        )
        // ciphertext ‖ tag, concatenated — the layout the seam promises and the record envelope
        // stores. An actual whose platform hands the tag back separately must join them in this
        // order, and this is the assertion that says so.
        assertEquals(
            "522dc1f099567d07f47f37a32a84427d" +
                "643a8cdcbfe5c0c97598a2bd2555d1aa" +
                "8cb08e48590dbb3da7b08b1056828838" +
                "c5f61e6393ba7a0abcc9f662898015ad" +
                "b094dac5d93471bdec1a502270e3cc6c",
            aesGcmSeal(gcmKey, gcmNonce, aad = null, plaintext = plaintext).toHex(),
        )
    }

    @Test
    fun `GCM test case 16 - 60 bytes of plaintext, 20 bytes of associated data`() {
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
            aesGcmSeal(gcmKey, gcmNonce, aad = aad, plaintext = plaintext).toHex(),
        )
    }

    @Test
    fun `null and empty associated data are the same thing`() {
        // `Cipher` treats a skipped `updateAAD` and an empty one identically, and every ARK wrap
        // and PIN wrap already on a device was made through the skipped path. An Apple actual that
        // hashed a zero-length AAD differently would open none of them.
        val plaintext = "the same either way".encodeToByteArray()
        assertContentEquals(
            aesGcmSeal(gcmKey, gcmNonce, aad = null, plaintext = plaintext),
            aesGcmSeal(gcmKey, gcmNonce, aad = ByteArray(0), plaintext = plaintext),
        )
    }

    @Test
    fun `case 16 decrypts back, and its ciphertext agrees with case 15 on the bytes they share`() {
        val plaintext = hex(
            "d9313225f88406e5a55909c5aff5269a" +
                "86a7a9531534f7da2e4c303d8a318a72" +
                "1c3c0c95956809532fcf0e2449a6b525" +
                "b16aedf5aa0de657ba637b39"
        )
        val aad = hex("feedfacedeadbeeffeedfacedeadbeefabaddad2")
        val sealed = aesGcmSeal(gcmKey, gcmNonce, aad, plaintext)

        assertContentEquals(plaintext, aesGcmOpen(gcmKey, gcmNonce, aad, sealed))

        // The defining property of associated data: authenticated, never encrypted. Cases 15 and
        // 16 differ only in their AAD, and their ciphertexts agree byte for byte over the 60 bytes
        // of plaintext they have in common while their tags are completely different.
        val withoutAad = aesGcmSeal(gcmKey, gcmNonce, aad = null, plaintext = plaintext).toHex()
        val ciphertextHexLength = plaintext.size * 2
        assertEquals(
            withoutAad.substring(0, ciphertextHexLength),
            sealed.toHex().substring(0, ciphertextHexLength),
        )
        assertNotEquals(
            withoutAad.substring(ciphertextHexLength),
            sealed.toHex().substring(ciphertextHexLength),
        )
    }

    // ---------------------------------------------------------------------------------------
    // The tag must be verified. This is the section to read before touching an Apple actual.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a flipped ciphertext bit is refused`() {
        val plaintext = "a note body".encodeToByteArray()
        val sealed = aesGcmSeal(gcmKey, gcmNonce, aad = null, plaintext = plaintext)
        val tampered = sealed.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }

        // If this returns a value rather than null, the platform actual is decrypting without
        // verifying the tag. AES-GCM has then been reduced to AES-CTR and every integrity
        // guarantee in the protocol is gone — a server operator could flip any bit of any note and
        // no device would notice. CommonCrypto's deprecated `CCCryptorGCM` behaves exactly that
        // way on decrypt; see `PlatformCrypto.apple.kt`.
        assertNull(aesGcmOpen(gcmKey, gcmNonce, aad = null, sealed = tampered))
    }

    @Test
    fun `a flipped tag bit is refused`() {
        val sealed = aesGcmSeal(gcmKey, gcmNonce, aad = null, plaintext = "x".encodeToByteArray())
        val tampered = sealed.copyOf().also {
            val last = it.size - 1
            it[last] = (it[last].toInt() xor 0x01).toByte()
        }
        assertNull(aesGcmOpen(gcmKey, gcmNonce, aad = null, sealed = tampered))
    }

    @Test
    fun `changed associated data is refused`() {
        val sealed = aesGcmSeal(gcmKey, gcmNonce, "one".encodeToByteArray(), "x".encodeToByteArray())
        assertNull(aesGcmOpen(gcmKey, gcmNonce, "two".encodeToByteArray(), sealed))
    }

    @Test
    fun `a wrong key is refused`() {
        val sealed = aesGcmSeal(gcmKey, gcmNonce, aad = null, plaintext = "x".encodeToByteArray())
        val otherKey = gcmKey.copyOf().also { it[0] = (it[0].toInt() xor 0xff).toByte() }
        assertNull(aesGcmOpen(otherKey, gcmNonce, aad = null, sealed = sealed))
    }

    @Test
    fun `a blob too short to hold a tag is refused rather than throwing`() {
        // Reachable from a truncated database row. `RecordEnvelope` length-checks before it gets
        // here, but `ArkCipher` and `PassphraseCipher` hand the seam whatever was stored.
        assertNull(aesGcmOpen(gcmKey, gcmNonce, aad = null, sealed = ByteArray(4)))
        assertNull(aesGcmOpen(gcmKey, gcmNonce, aad = null, sealed = ByteArray(0)))
    }

    @Test
    fun `an empty plaintext seals to a bare tag and opens back to nothing`() {
        val sealed = aesGcmSeal(gcmKey, gcmNonce, aad = null, plaintext = ByteArray(0))
        assertEquals(16, sealed.size, "an empty plaintext should seal to the tag alone")
        assertContentEquals(ByteArray(0), aesGcmOpen(gcmKey, gcmNonce, aad = null, sealed = sealed))
    }

    // ---------------------------------------------------------------------------------------
    // Randomness
    // ---------------------------------------------------------------------------------------

    @Test
    fun `secure random returns the requested length and does not repeat`() {
        // Not a statistical test — a unit test cannot be one, and pretending otherwise would be
        // worse than not trying. It catches the two failures that actually happen when an actual
        // is wired up wrong: a buffer that is never written to (all zeros), and one that is
        // written once and cached.
        val first = secureRandomBytes(32)
        val second = secureRandomBytes(32)
        assertEquals(32, first.size)
        assertEquals(32, second.size)
        assertTrue(first.any { it != 0.toByte() }, "32 zero bytes is not a random draw")
        assertNotEquals(first.toHex(), second.toHex(), "two draws must not be identical")
    }

    @Test
    fun `secure random rejects a non-positive length`() {
        assertFailsWith<IllegalArgumentException> { secureRandomBytes(0) }
    }
}
