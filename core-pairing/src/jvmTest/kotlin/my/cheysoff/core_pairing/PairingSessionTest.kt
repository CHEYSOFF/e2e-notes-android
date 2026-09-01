package my.cheysoff.core_pairing

import my.cheysoff.core_pairing.protocol.AccountBundle
import my.cheysoff.core_pairing.protocol.AccountDeviceSession
import my.cheysoff.core_pairing.protocol.HkdfKeyDerivation
import my.cheysoff.core_pairing.protocol.NewDeviceSession
import my.cheysoff.core_pairing.protocol.OfferOutcome
import my.cheysoff.core_pairing.protocol.P256
import my.cheysoff.core_pairing.protocol.PairingCodec
import my.cheysoff.core_pairing.protocol.PairingFailure
import my.cheysoff.core_pairing.protocol.PairingProtocol
import my.cheysoff.core_pairing.protocol.PairingSeal
import my.cheysoff.core_pairing.protocol.Sas
import my.cheysoff.core_pairing.protocol.SealOutcome
import my.cheysoff.core_pairing.protocol.ServerHint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.interfaces.ECPrivateKey
import java.util.Base64

/**
 * The two role state machines, driven end to end and then attacked.
 *
 * Everything here is pure JVM: no camera, no Android, no coroutines. The KDF is the production
 * one -- [HkdfKeyDerivation] over `core-crypto`'s RFC-5869 HKDF, the same object the app binds --
 * so a disagreement across the seam fails here rather than on two real phones. The only fake left
 * is [FakeClock].
 */
class PairingSessionTest {

    private val ark = ByteArray(32) { (it * 13 + 7).toByte() }
    private val bundle = AccountBundle(ark, "acct-9f2c", """{"url":"https://notes.example/"}""")

    // -- the happy path -----------------------------------------------------------------------

    @Test
    fun twoDevicesPairAndAgreeOnTheAccountKeyAndTheSas() {
        val clock = FakeClock()
        val newDevice = NewDeviceSession(HkdfKeyDerivation, clock)
        val accountDevice = AccountDeviceSession(HkdfKeyDerivation, clock, bundle.ark, bundle.accountId)

        // The config is chosen at seal time rather than held by the session; this is the value a
        // real account device would have just built out of the server address and the id the server
        // assigned to the joining device.
        val accepted = accountDevice.accept(newDevice.offerCode, bundle.config)!!
        val paired = newDevice.onScanned(accepted.sealCode) as SealOutcome.Paired

        // The ARK crossed intact...
        assertArrayEquals(ark, paired.bundle.ark)
        assertEquals(bundle.accountId, paired.bundle.accountId)
        assertEquals(bundle.config, paired.bundle.config)
        // ...and both devices independently arrived at the same six digits, which is the whole
        // content of the user-facing confirmation step.
        assertEquals(accepted.sas, paired.sas)
        assertEquals(6, paired.sas.length)
    }

    /** The server hint travels in the clear in QR1 and reaches device A as a hint. */
    @Test
    fun theServerHintReachesTheAccountDevice() {
        val clock = FakeClock()
        val hint = ServerHint("https://notes.example/", ByteArray(32) { 9 })
        val newDevice = NewDeviceSession(HkdfKeyDerivation, clock, serverHint = hint)
        val accountDevice = AccountDeviceSession(HkdfKeyDerivation, clock, bundle.ark, bundle.accountId)

        assertTrue(accountDevice.onScanned(newDevice.offerCode) is OfferOutcome.Accepted)
        assertEquals(hint, accountDevice.receivedServerHint)
    }

    /** A fresh `sid` per session, or the replay binding would bind nothing. */
    @Test
    fun everySessionMintsANewSessionId() {
        val seen = (1..25).map { NewDeviceSession(HkdfKeyDerivation, FakeClock()).sid.toHex() }.toSet()
        assertEquals(25, seen.size)
        assertEquals(PairingProtocol.SID_SIZE_BYTES, NewDeviceSession(HkdfKeyDerivation, FakeClock()).sid.size)
    }

    // -- the eavesdropper ---------------------------------------------------------------------

    /**
     * A third party who photographs **both** QR codes has `EA`, `EB` and `sid` — every input to the
     * HKDF salt and to `info` — and still cannot open the seal, because the one remaining input is
     * the ECDH secret and neither private key was ever on screen.
     *
     * The test plays the eavesdropper's actual options: derive with their own ephemeral key against
     * each of the two public keys, and derive from the public material alone. All of them fail.
     */
    @Test
    fun observerWithBothPublicKeysCannotDerive() {
        val clock = FakeClock()
        val newDevice = NewDeviceSession(HkdfKeyDerivation, clock)
        val accountDevice = AccountDeviceSession(HkdfKeyDerivation, clock, bundle.ark, bundle.accountId)
        val accepted = accountDevice.accept(newDevice.offerCode)!!

        // Everything visible on the two screens.
        val offer = PairingCodec.decodeOffer(newDevice.offerCode)
        val seal = PairingCodec.decodeSeal(accepted.sealCode)
        val encodedEb = offer.encodedEphemeralKey
        val encodedEa = seal.encodedEphemeralKey
        val sid = offer.sid
        val info = PairingProtocol.sessionKeyInfo(encodedEa, encodedEb)

        val attempts = mutableListOf<ByteArray>()

        // 1. The attacker's own ephemeral key against each published public key. This is the whole
        //    of what an ECDH gives someone who holds no private key from the exchange.
        val attacker = P256.generateEphemeralKeyPair()
        for (peer in listOf(encodedEa, encodedEb)) {
            val z = P256.sharedSecret(attacker.private as ECPrivateKey, P256.decodePublicKey(peer))
            attempts += HkdfKeyDerivation.derive(z, sid, info, 32)
        }
        // 2. Deriving straight from the public material, which is the naive mistake the key
        //    schedule would allow if `ikm` were ever anything but the ECDH secret.
        attempts += HkdfKeyDerivation.derive(encodedEa + encodedEb, sid, info, 32)
        attempts += HkdfKeyDerivation.derive(sid, sid, info, 32)

        for (guess in attempts) {
            assertNull(PairingSeal.open(guess, seal.nonce, sid, seal.seal))
        }

        // The control, and it matters: without it every assertion above would also pass against a
        // seal that nobody at all can open. The device that actually holds `eB` opens it.
        assertTrue(newDevice.onScanned(accepted.sealCode) is SealOutcome.Paired)
    }

    // -- replay and session binding -----------------------------------------------------------

    /** A QR2 minted for a different session decodes fine and is refused on the `sid` comparison. */
    @Test
    fun rejectsAQr2FromADifferentSession() {
        val clock = FakeClock()
        val victim = NewDeviceSession(HkdfKeyDerivation, clock)

        // A complete, genuine pairing between two *other* sessions...
        val otherNewDevice = NewDeviceSession(HkdfKeyDerivation, clock)
        val accountDevice = AccountDeviceSession(HkdfKeyDerivation, clock, bundle.ark, bundle.accountId)
        val stolen = accountDevice.accept(otherNewDevice.offerCode)!!

        // ...replayed at the victim.
        val outcome = victim.onScanned(stolen.sealCode) as SealOutcome.Rejected
        assertEquals(PairingFailure.SESSION_MISMATCH, outcome.failure)
        // Non-terminal: a stale screen in view is the honest explanation, and the session must
        // stay alive for the real code.
        assertFalse(outcome.failure.isTerminal)
    }

    /**
     * The `sid` comparison is not the only replay defence.
     *
     * Here the attacker rewrites the `sid` field of a captured QR2 to the victim's own session id,
     * so the comparison passes. The seal still fails, twice over: `sid` is the HKDF salt (so the
     * victim derives a different `Ks`) and `sid` is in the GCM AAD (so even a matching key would
     * not authenticate). Dropping `sid` from `PairingProtocol.sealAad` alone does not make this
     * pass — the salt binding still holds — which is why the AAD has its own direct test in
     * `PairingSealTest.aadMustCarrySid`.
     */
    @Test
    fun qr2FromAnotherSessionIsNotAcceptedEvenIfSidCheckWereRemoved() {
        val clock = FakeClock()
        val victim = NewDeviceSession(HkdfKeyDerivation, clock)
        val otherNewDevice = NewDeviceSession(HkdfKeyDerivation, clock)
        val accountDevice = AccountDeviceSession(HkdfKeyDerivation, clock, bundle.ark, bundle.accountId)
        val stolen = accountDevice.accept(otherNewDevice.offerCode)!!

        val forged = rewriteSid(stolen.sealCode, victim.sid)
        val outcome = victim.onScanned(forged) as SealOutcome.Rejected
        assertEquals(PairingFailure.SEAL_REJECTED, outcome.failure)
        assertTrue("a tag failure must kill the session", outcome.failure.isTerminal)
    }

    /** A modified seal is a tag failure, and a tag failure is the end of the session. */
    @Test
    fun aTamperedSealAbortsLoudlyAndPermanently() {
        val clock = FakeClock()
        val newDevice = NewDeviceSession(HkdfKeyDerivation, clock)
        val accountDevice = AccountDeviceSession(HkdfKeyDerivation, clock, bundle.ark, bundle.accountId)
        val accepted = accountDevice.accept(newDevice.offerCode)!!

        val tampered = tamperWithSealBytes(accepted.sealCode)
        val outcome = newDevice.onScanned(tampered) as SealOutcome.Rejected
        assertEquals(PairingFailure.SEAL_REJECTED, outcome.failure)

        // The genuine code is now refused too. This is the "never retry silently" rule: a session
        // that survived a tag failure would re-run the open against whatever comes next.
        val afterwards = newDevice.onScanned(accepted.sealCode) as SealOutcome.Rejected
        assertEquals(PairingFailure.SESSION_CLOSED, afterwards.failure)
    }

    /** Once the ARK has been handed over, a second scan cannot hand over a different one. */
    @Test
    fun aSuccessfulPairingClosesTheSession() {
        val clock = FakeClock()
        val newDevice = NewDeviceSession(HkdfKeyDerivation, clock)
        val accountDevice = AccountDeviceSession(HkdfKeyDerivation, clock, bundle.ark, bundle.accountId)
        val accepted = accountDevice.accept(newDevice.offerCode)!!

        assertTrue(newDevice.onScanned(accepted.sealCode) is SealOutcome.Paired)
        val again = newDevice.onScanned(accepted.sealCode) as SealOutcome.Rejected
        assertEquals(PairingFailure.SESSION_CLOSED, again.failure)
    }

    /** One offer per account-device session: sealing the ARK twice would be sealing it to two phones. */
    @Test
    fun theAccountDeviceAcceptsOnlyOneOffer() {
        val clock = FakeClock()
        val accountDevice = AccountDeviceSession(HkdfKeyDerivation, clock, bundle.ark, bundle.accountId)

        assertTrue(accountDevice.onScanned(NewDeviceSession(HkdfKeyDerivation, clock).offerCode) is OfferOutcome.Accepted)
        val second = accountDevice.onScanned(NewDeviceSession(HkdfKeyDerivation, clock).offerCode) as OfferOutcome.Rejected
        assertEquals(PairingFailure.SESSION_CLOSED, second.failure)
    }

    // -- invalid points -----------------------------------------------------------------------

    /**
     * An off-curve `EB` offered to the device that holds the ARK.
     *
     * This is the side that matters most: it is device A's ephemeral private key that seals the
     * account root key, so an attacker who recovered it by feeding small-order points would recover
     * the ARK. The point never reaches `KeyAgreement`.
     */
    @Test
    fun theAccountDeviceRejectsAnOffCurveOfferKey() {
        val accountDevice = AccountDeviceSession(HkdfKeyDerivation, FakeClock(), bundle.ark, bundle.accountId)
        val code = PairingCodec.encodeOffer(ByteArray(16), offCurvePoint(), ServerHint.NONE)

        val outcome = accountDevice.onScanned(code) as OfferOutcome.Rejected
        assertEquals(PairingFailure.INVALID_PEER_KEY, outcome.failure)
        assertTrue("an off-curve point is deliberate, so it is terminal", outcome.failure.isTerminal)

        // Terminal means terminal: a genuine offer afterwards is refused rather than given a
        // second chance to leak another residue.
        val afterwards = accountDevice.onScanned(
            NewDeviceSession(HkdfKeyDerivation, FakeClock()).offerCode
        ) as OfferOutcome.Rejected
        assertEquals(PairingFailure.SESSION_CLOSED, afterwards.failure)
    }

    /** The same check on the new device's side, where `EA` arrives inside QR2. */
    @Test
    fun theNewDeviceRejectsAnOffCurveSealKey() {
        val newDevice = NewDeviceSession(HkdfKeyDerivation, FakeClock())
        val code = PairingCodec.encodeSeal(newDevice.sid, offCurvePoint(), ByteArray(12), ByteArray(48))

        val outcome = newDevice.onScanned(code) as SealOutcome.Rejected
        assertEquals(PairingFailure.INVALID_PEER_KEY, outcome.failure)
    }

    // -- expiry -------------------------------------------------------------------------------

    @Test
    fun aCodeIsGoodForTwoMinutes() {
        assertEquals(120_000L, PairingProtocol.CODE_TTL_MILLIS)
    }

    @Test
    fun theNewDeviceRefusesAQr2AfterItsSessionExpires() {
        val clock = FakeClock()
        val newDevice = NewDeviceSession(HkdfKeyDerivation, clock)
        val accountDevice = AccountDeviceSession(HkdfKeyDerivation, clock, bundle.ark, bundle.accountId)
        val accepted = accountDevice.accept(newDevice.offerCode)!!

        clock.advance(PairingProtocol.CODE_TTL_MILLIS)
        assertTrue(newDevice.isExpired())

        val outcome = newDevice.onScanned(accepted.sealCode) as SealOutcome.Rejected
        assertEquals(PairingFailure.EXPIRED, outcome.failure)
    }

    /** One millisecond before the deadline the very same code still works. */
    @Test
    fun aCodeStillWorksJustBeforeItExpires() {
        val clock = FakeClock()
        val newDevice = NewDeviceSession(HkdfKeyDerivation, clock)
        val accountDevice = AccountDeviceSession(HkdfKeyDerivation, clock, bundle.ark, bundle.accountId)
        val accepted = accountDevice.accept(newDevice.offerCode)!!

        clock.advance(PairingProtocol.CODE_TTL_MILLIS - 1)
        assertFalse(newDevice.isExpired())
        assertEquals(1L, newDevice.remainingMillis())
        assertTrue(newDevice.onScanned(accepted.sealCode) is SealOutcome.Paired)
    }

    /** Expiry is terminal: the session does not come back if the clock is later wound back. */
    @Test
    fun expiryIsTerminal() {
        val clock = FakeClock()
        val newDevice = NewDeviceSession(HkdfKeyDerivation, clock)
        val accountDevice = AccountDeviceSession(HkdfKeyDerivation, clock, bundle.ark, bundle.accountId)
        val accepted = accountDevice.accept(newDevice.offerCode)!!

        clock.advance(PairingProtocol.CODE_TTL_MILLIS + 5_000)
        newDevice.onScanned(accepted.sealCode)

        clock.now = 0
        val outcome = newDevice.onScanned(accepted.sealCode) as SealOutcome.Rejected
        assertEquals(PairingFailure.SESSION_CLOSED, outcome.failure)
    }

    /** The account device's own countdown starts when it accepts an offer, not before. */
    @Test
    fun theAccountDeviceCountdownStartsAtAcceptance() {
        val clock = FakeClock()
        val accountDevice = AccountDeviceSession(HkdfKeyDerivation, clock, bundle.ark, bundle.accountId)
        clock.advance(500_000)
        assertEquals(PairingProtocol.CODE_TTL_MILLIS, accountDevice.remainingMillis())
        assertFalse(accountDevice.isExpired())

        accountDevice.onScanned(NewDeviceSession(HkdfKeyDerivation, clock).offerCode)
        assertEquals(PairingProtocol.CODE_TTL_MILLIS, accountDevice.remainingMillis())
        clock.advance(PairingProtocol.CODE_TTL_MILLIS)
        assertTrue(accountDevice.isExpired())
    }

    // -- everything else the camera sees ------------------------------------------------------

    /**
     * The scanner feeds every symbol in view to the session. None of the ordinary ones may kill it.
     */
    @Test
    fun unrelatedCodesAreIgnoredWithoutClosingTheSession() {
        val clock = FakeClock()
        val newDevice = NewDeviceSession(HkdfKeyDerivation, clock)
        val accountDevice = AccountDeviceSession(HkdfKeyDerivation, clock, bundle.ark, bundle.accountId)

        for (noise in listOf(
            "",
            "https://example.com",
            "WIFI:S:home;T:WPA;P:hunter2;;",
            "MNP1:",
            "MNP1:%%%not-base64%%%",
        )) {
            val outcome = accountDevice.onScanned(noise) as OfferOutcome.Rejected
            assertFalse("\"$noise\" closed the session", outcome.failure.isTerminal)
        }
        // Still working afterwards.
        assertTrue(accountDevice.onScanned(newDevice.offerCode) is OfferOutcome.Accepted)
    }

    /** Showing a device its own step's code back is a sequencing mistake, not a fatal one. */
    @Test
    fun theWrongStepsCodeIsANonTerminalHint() {
        val clock = FakeClock()
        val newDevice = NewDeviceSession(HkdfKeyDerivation, clock)
        val accountDevice = AccountDeviceSession(HkdfKeyDerivation, clock, bundle.ark, bundle.accountId)
        val accepted = accountDevice.accept(newDevice.offerCode)!!

        val other = NewDeviceSession(HkdfKeyDerivation, clock)
        val outcome = other.onScanned(other.offerCode) as SealOutcome.Rejected
        assertEquals(PairingFailure.WRONG_CODE_KIND, outcome.failure)
        // ...and the real reply still lands.
        assertTrue(newDevice.onScanned(accepted.sealCode) is SealOutcome.Paired)
    }

    // -- SAS ----------------------------------------------------------------------------------

    @Test
    fun sasIsSixDigitsAndZeroPadded() {
        repeat(200) {
            val sas = Sas.derive(HkdfKeyDerivation, ByteArray(32).also { b -> java.util.Random().nextBytes(b) }, ByteArray(16))
            assertEquals(6, sas.length)
            assertTrue(sas.all { c -> c.isDigit() })
        }
    }

    @Test
    fun sasIsDeterministicAndDependsOnBothArkAndSid() {
        val sid = ByteArray(16) { 1 }
        val otherSid = ByteArray(16) { 2 }
        val otherArk = ByteArray(32) { 5 }

        assertEquals(Sas.derive(HkdfKeyDerivation, ark, sid), Sas.derive(HkdfKeyDerivation, ark, sid))
        assertNotEquals(Sas.derive(HkdfKeyDerivation, ark, sid), Sas.derive(HkdfKeyDerivation, ark, otherSid))
        assertNotEquals(Sas.derive(HkdfKeyDerivation, ark, sid), Sas.derive(HkdfKeyDerivation, otherArk, sid))
    }

    /** Two devices that ended up with *different* ARKs must show different digits. */
    @Test
    fun aWrongPairingProducesADifferentSas() {
        val clock = FakeClock()
        val newDevice = NewDeviceSession(HkdfKeyDerivation, clock)
        val rightAccount = AccountDeviceSession(HkdfKeyDerivation, clock, bundle.ark, bundle.accountId)
        val wrongAccount = AccountDeviceSession(
            HkdfKeyDerivation, clock, ByteArray(32) { 0x5A }, "other"
        )

        val right = rightAccount.accept(newDevice.offerCode)!!
        val wrong = wrongAccount.accept(NewDeviceSession(HkdfKeyDerivation, clock).offerCode)!!
        assertNotEquals(right.sas, wrong.sas)
    }

    // -- helpers ------------------------------------------------------------------------------

    /** A syntactically perfect SEC1 point that is not on P-256. */
    private fun offCurvePoint(): ByteArray {
        val out = ByteArray(65)
        out[0] = 0x04
        // x = 2, y = 3. y^2 = 9; x^3 + ax + b is a 256-bit number that is not 9.
        out[32] = 2
        out[64] = 3
        return out
    }

    /** Rewrite the 16 `sid` bytes of an encoded frame, leaving everything else untouched. */
    private fun rewriteSid(code: String, sid: ByteArray): String {
        val frame = Base64.getUrlDecoder().decode(code.removePrefix(PairingProtocol.QR_PREFIX))
        sid.copyInto(frame, 2)
        return PairingProtocol.QR_PREFIX +
            Base64.getUrlEncoder().withoutPadding().encodeToString(frame)
    }

    /** Flip one bit inside the sealed bytes of an encoded QR2. */
    private fun tamperWithSealBytes(code: String): String {
        val frame = Base64.getUrlDecoder().decode(code.removePrefix(PairingProtocol.QR_PREFIX))
        // Layout: 2 header + 16 sid + 1 epLen + 65 point + 12 nonce + 2 sealLen = 98, then the seal.
        frame[100] = (frame[100].toInt() xor 0x01).toByte()
        return PairingProtocol.QR_PREFIX +
            Base64.getUrlEncoder().withoutPadding().encodeToString(frame)
    }
}
