package my.cheysoff.core_crypto.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A known-answer test for **AES-256-GCM itself**, not for any class in this package.
 *
 * Everything in `RecordEnvelope` is expressed in terms of the JCA provider's `AES/GCM/NoPadding`.
 * That is a moving part: the provider differs between a JVM unit test (SunJCE) and a device
 * (Conscrypt), and both get updated underneath the app. A provider that computed GCM differently —
 * or, more realistically, a future edit here that reached for a different transformation string or
 * a truncated tag — would still round-trip perfectly against itself while producing bytes no other
 * device could open. Only fixed published vectors catch that class of failure.
 *
 * Vectors are Test Case 15 and Test Case 16 from McGrew & Viega, *The Galois/Counter Mode of
 * Operation (GCM)*, the paper NIST SP 800-38D's GCM is specified from; they are the standard
 * AES-256 GCM vectors and are reproduced in essentially every GCM implementation's test suite.
 * Case 16 is the one that exercises additional authenticated data, which is the feature the record
 * envelope's rollback defence depends on.
 */
class AesGcmKnownAnswerTest {

    private val key = hex("feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308")
    private val iv = hex("cafebabefacedbaddecaf888")

    private fun encrypt(plaintext: ByteArray, aad: ByteArray?): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        aad?.let(cipher::updateAAD)
        return cipher.doFinal(plaintext).toHex()
    }

    @Test
    fun `GCM test case 15 - AES-256, 64 bytes of plaintext, no AAD`() {
        val plaintext = hex(
            "d9313225f88406e5a55909c5aff5269a" +
                "86a7a9531534f7da2e4c303d8a318a72" +
                "1c3c0c95956809532fcf0e2449a6b525" +
                "b16aedf5aa0de657ba637b391aafd255"
        )
        val expectedCiphertext =
            "522dc1f099567d07f47f37a32a84427d" +
                "643a8cdcbfe5c0c97598a2bd2555d1aa" +
                "8cb08e48590dbb3da7b08b1056828838" +
                "c5f61e6393ba7a0abcc9f662898015ad"
        val expectedTag = "b094dac5d93471bdec1a502270e3cc6c"

        // JCA returns ciphertext ‖ tag concatenated — the same layout the envelope relies on.
        assertEquals(expectedCiphertext + expectedTag, encrypt(plaintext, aad = null))
    }

    @Test
    fun `GCM test case 16 - AES-256, 60 bytes of plaintext, 20 bytes of AAD`() {
        val plaintext = hex(
            "d9313225f88406e5a55909c5aff5269a" +
                "86a7a9531534f7da2e4c303d8a318a72" +
                "1c3c0c95956809532fcf0e2449a6b525" +
                "b16aedf5aa0de657ba637b39"
        )
        val aad = hex("feedfacedeadbeeffeedfacedeadbeefabaddad2")
        val expectedCiphertext =
            "522dc1f099567d07f47f37a32a84427d" +
                "643a8cdcbfe5c0c97598a2bd2555d1aa" +
                "8cb08e48590dbb3da7b08b1056828838" +
                "c5f61e6393ba7a0abcc9f662"
        val expectedTag = "76fc6ece0f4e1768cddf8853bb2d551b"

        assertEquals(expectedCiphertext + expectedTag, encrypt(plaintext, aad))
    }

    @Test
    fun `the AAD changes the tag without changing the ciphertext`() {
        // Case 15's and case 16's ciphertexts agree on their common 60 bytes while their tags are
        // completely different. That is the defining property of GCM's associated data — it is
        // authenticated, never encrypted — and it is why the envelope can bind `recType`,
        // `blindedId` and `hlc` without transmitting them twice.
        val plaintext = hex(
            "d9313225f88406e5a55909c5aff5269a" +
                "86a7a9531534f7da2e4c303d8a318a72" +
                "1c3c0c95956809532fcf0e2449a6b525" +
                "b16aedf5aa0de657ba637b39"
        )

        val withoutAad = encrypt(plaintext, aad = null)
        val withAad = encrypt(plaintext, hex("feedfacedeadbeeffeedfacedeadbeefabaddad2"))
        val ciphertextHexLength = plaintext.size * 2

        assertEquals(
            withoutAad.substring(0, ciphertextHexLength),
            withAad.substring(0, ciphertextHexLength),
        )
        assertEquals(
            "76fc6ece0f4e1768cddf8853bb2d551b",
            withAad.substring(ciphertextHexLength),
        )
    }
}
