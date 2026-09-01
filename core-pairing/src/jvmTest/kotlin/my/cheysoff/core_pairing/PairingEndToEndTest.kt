package my.cheysoff.core_pairing

import my.cheysoff.core_crypto.sync.AccountRootKey
import my.cheysoff.core_crypto.sync.ArkCipher
import my.cheysoff.core_crypto.sync.Base64Url
import my.cheysoff.core_pairing.protocol.AccountBundle
import my.cheysoff.core_pairing.protocol.AccountDeviceSession
import my.cheysoff.core_pairing.protocol.HkdfKeyDerivation
import my.cheysoff.core_pairing.protocol.KeyDerivation
import my.cheysoff.core_pairing.protocol.NewDeviceSession
import my.cheysoff.core_pairing.protocol.OfferOutcome
import my.cheysoff.core_pairing.protocol.PairingFailure
import my.cheysoff.core_pairing.protocol.SealOutcome
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two devices, the whole way across the seam, with nothing faked.
 *
 * ## What this test is for
 *
 * The two halves of this feature were built apart and each was fully tested against its own
 * primitives — Phase 1's HKDF and ARK hierarchy in `:core-crypto`, Phase 2's pairing protocol
 * here, bound in tests to a second HKDF of its own. Both suites passed, and **both would have gone
 * on passing if the two HKDFs had disagreed.** The only symptom would have been two real phones
 * that cannot pair. This file is the test that closes that: every derivation on both sides runs
 * through [HkdfKeyDerivation], the production binding, and the key that crosses is a real
 * [AccountRootKey.generateArk] output stored with the real [ArkCipher] at both ends.
 *
 * The one thing it does not exercise is `SecureUnlockManager`'s preferences plumbing, which needs
 * Robolectric and is covered by `SecureUnlockManagerArkTest` in `:core-crypto`. Between the two,
 * every link from "device A mints an ARK" to "device B unlocks and reads the same ARK" is under
 * test.
 */
class PairingEndToEndTest {

    /** Device A's database passphrase. Per-device and never shared — that is the point of the ARK. */
    private val passphraseA = ByteArray(32) { (it * 3 + 1).toByte() }

    /** Device B's, deliberately different. B rewraps under its own, it never receives A's. */
    private val passphraseB = ByteArray(32) { (0xF0 - it).toByte() }

    /**
     * The full exchange, from an ARK on device A to the same ARK stored on device B.
     *
     *  1. A mints an ARK and wraps it, exactly as `SecureUnlockManager.ensureArk` does;
     *  2. B builds QR1;
     *  3. A scans it and builds QR2, sealing the ARK;
     *  4. B scans QR2 and opens the seal;
     *  5. B wraps what it got under its OWN passphrase and reads it back.
     *
     * The assertions are the two things a user can actually observe going wrong: B ends up with
     * different key bytes than A sent, or the two phones show different six digits.
     */
    @Test
    fun aSealsItsArkAndBRecoversExactlyThoseBytes() {
        // 1. Device A: the account key, minted and stored the way the app does it.
        val arkOnA = AccountRootKey.generateArk()
        val storedOnA = ArkCipher.wrap(arkOnA, passphraseA)
        val accountId = Base64Url.encode(AccountRootKey.derive(arkOnA).accountId)
        val bundle = AccountBundle(
            ark = ArkCipher.unwrap(storedOnA, passphraseA)!!,
            accountId = accountId,
        )

        // 2-4. The protocol. Both sessions get the production KDF.
        val clock = FakeClock()
        val deviceB = NewDeviceSession(HkdfKeyDerivation, clock)
        val deviceA = AccountDeviceSession(HkdfKeyDerivation, clock, bundle.ark, bundle.accountId)

        val qr2 = deviceA.accept(deviceB.offerCode)!!
        val paired = deviceB.onScanned(qr2.sealCode) as SealOutcome.Paired

        // The bytes crossed intact.
        assertArrayEquals("B did not recover A's ARK", arkOnA, paired.bundle.ark)
        assertEquals(accountId, paired.bundle.accountId)

        // And both devices reached the same six digits independently — A from the key it has held
        // all along, B only by having opened the seal.
        assertEquals("the two phones would show different codes", qr2.sas, paired.sas)
        assertEquals(6, paired.sas.length)
        assertTrue(paired.sas.all { it.isDigit() })

        // 5. B stores it under its own passphrase and reads it back.
        val storedOnB = ArkCipher.wrap(paired.bundle.ark, passphraseB)
        assertArrayEquals(arkOnA, ArkCipher.unwrap(storedOnB, passphraseB))
        assertNotEquals(
            "the two devices' stored ciphertexts must not be the same blob",
            storedOnA.ciphertext.toList(),
            storedOnB.ciphertext.toList(),
        )

        // Both devices now derive the same account keys from it, which is what syncing means.
        val keysOnA = AccountRootKey.derive(arkOnA)
        val keysOnB = AccountRootKey.derive(ArkCipher.unwrap(storedOnB, passphraseB)!!)
        assertArrayEquals(keysOnA.kContent, keysOnB.kContent)
        assertArrayEquals(keysOnA.kId, keysOnB.kId)
        assertArrayEquals(keysOnA.accountId, keysOnB.accountId)
    }

    /**
     * The seam mismatch, simulated: one device derives with a different `info`.
     *
     * This is the failure the two branches could have shipped — an HKDF or a domain string that
     * differs by one byte on one side only. It must show up as a GCM tag failure, terminal and
     * loud, and it must never look like a successful pairing with a different key.
     *
     * It is also the standing proof that [aSealsItsArkAndBRecoversExactlyThoseBytes] is sensitive
     * to exactly that: the two tests differ only in which KDF device A uses.
     */
    @Test
    fun oneSideDerivingWithADifferentInfoCannotPair() {
        val ark = AccountRootKey.generateArk()
        val bundle = AccountBundle(ark, "acct")
        val clock = FakeClock()

        val deviceB = NewDeviceSession(HkdfKeyDerivation, clock)
        val deviceA = AccountDeviceSession(HkdfKeyDerivation, clock, bundle.ark, bundle.accountId)

        // Device A, but with the first byte of every `info` flipped. Everything else -- the ECDH,
        // the salt, the wire format, the AAD -- is identical.
        val driftedA = AccountDeviceSession(
            keyDerivation = OneByteOffInfo(HkdfKeyDerivation),
            clock = clock,
            ark = bundle.ark,
            accountId = bundle.accountId,
        )

        val goodOffer = deviceB.offerCode
        val drifted = driftedA.accept(goodOffer)!!
        val outcome = deviceB.onScanned(drifted.sealCode) as SealOutcome.Rejected

        assertEquals(PairingFailure.SEAL_REJECTED, outcome.failure)
        assertTrue("a seam mismatch must be terminal", outcome.failure.isTerminal)

        // And the control, so the test cannot pass for the wrong reason: an identical device B
        // pairs with the undrifted device A, on the same clock, in the same run.
        val freshB = NewDeviceSession(HkdfKeyDerivation, clock)
        val honest = deviceA.accept(freshB.offerCode)!!
        val ok = freshB.onScanned(honest.sealCode) as SealOutcome.Paired
        assertArrayEquals(ark, ok.bundle.ark)
        assertEquals(honest.sas, ok.sas)
    }

    /** A [KeyDerivation] identical to [delegate] except for one byte of `info`. */
    private class OneByteOffInfo(private val delegate: KeyDerivation) : KeyDerivation {
        override fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, outLen: Int): ByteArray {
            val altered = info.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
            return delegate.derive(ikm, salt, altered, outLen)
        }
    }
}
