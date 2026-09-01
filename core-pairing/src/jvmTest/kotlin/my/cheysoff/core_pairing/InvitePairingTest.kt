package my.cheysoff.core_pairing

import my.cheysoff.core_pairing.protocol.AccountInviteSession
import my.cheysoff.core_pairing.protocol.BundleOutcome
import my.cheysoff.core_pairing.protocol.HkdfKeyDerivation
import my.cheysoff.core_pairing.protocol.InviteOutcome
import my.cheysoff.core_pairing.protocol.JoiningDeviceSession
import my.cheysoff.core_pairing.protocol.P256
import my.cheysoff.core_pairing.protocol.PairingCodec
import my.cheysoff.core_pairing.protocol.PairingFailure
import my.cheysoff.core_pairing.protocol.PairingProtocol
import my.cheysoff.core_pairing.protocol.RendezvousUrl
import my.cheysoff.core_pairing.protocol.ReplyOutcome
import my.cheysoff.core_pairing.protocol.Sas
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64

/**
 * The invite direction, driven end to end and then attacked.
 *
 * Pure JVM, production KDF, one fake clock — the same rules `PairingSessionTest` follows. What is
 * different is what the attacks are *for*: in the scanned direction the interesting failures are
 * mis-scans, because a man in the middle cannot exist. Here one can, so the tests that matter most
 * are the ones that pin the six digits apart when a key is substituted, and the one that pins the
 * account key to the confirmation.
 */
class InvitePairingTest {

    private val ark = ByteArray(32) { (it * 29 + 3).toByte() }
    private val accountId = "acct-invite"
    private val server = RendezvousUrl.parse("https://notes.example")!!
    private val deviceKey = P256.encodePublicKey(
        P256.generateKeyPair().public as java.security.interfaces.ECPublicKey
    )

    // -- the happy path -------------------------------------------------------------------------

    @Test
    fun theDesktopInvitesThePhoneAndTheAccountKeyCrosses() {
        val clock = FakeClock()
        val desktop = AccountInviteSession(HkdfKeyDerivation, clock, server)
        val phone = JoiningDeviceSession(HkdfKeyDerivation, clock, deviceKey)

        val invited = phone.onScanned(desktop.inviteCode) as InviteOutcome.Accepted
        val agreed = desktop.onReply(invited.replyCode) as ReplyOutcome.Agreed

        // The whole point of this direction: both screens show the same digits, computed
        // independently, before anything has been sealed.
        assertEquals(invited.sas, agreed.sas)
        assertEquals(6, agreed.sas.length)
        assertEquals(server.base, invited.server.url)

        val authority = desktop.confirm()!!
        assertArrayEquals(deviceKey, authority.joiningDeviceKey)

        val sealCode = authority.seal(ark, accountId, "{}")!!
        val opened = phone.onBundle(sealCode) as BundleOutcome.Opened
        assertArrayEquals(ark, opened.bundle.ark)
        assertEquals(accountId, opened.bundle.accountId)
        assertEquals("{}", opened.bundle.config)
    }

    // -- the man in the middle, which this direction actually has ------------------------------

    /**
     * The test this direction exists to be judged by.
     *
     * An attacker who controls the rendezvous replaces the phone's reply with one carrying their
     * own ephemeral key. The desktop agrees a secret with the attacker and shows the digits for
     * that agreement; the phone shows the digits for its own. **Nothing in the protocol notices** —
     * a person comparing two numbers is what notices, and this test is that person.
     */
    @Test
    fun aSubstitutedReplyKeyMakesTheTwoScreensDisagree() {
        val clock = FakeClock()
        val desktop = AccountInviteSession(HkdfKeyDerivation, clock, server)
        val phone = JoiningDeviceSession(HkdfKeyDerivation, clock, deviceKey)
        val invited = phone.onScanned(desktop.inviteCode) as InviteOutcome.Accepted

        // The attacker runs the joining half themselves against the same invite, and deposits
        // their reply instead of the phone's. They are a legitimate-looking peer in every way.
        val attacker = JoiningDeviceSession(HkdfKeyDerivation, clock, deviceKey)
        val attackerInvite = attacker.onScanned(desktop.inviteCode) as InviteOutcome.Accepted

        val agreed = desktop.onReply(attackerInvite.replyCode) as ReplyOutcome.Agreed
        assertEquals("the attacker and the desktop agree", attackerInvite.sas, agreed.sas)
        assertNotEquals(
            "the phone's digits must not match the desktop's once a key was swapped",
            invited.sas,
            agreed.sas,
        )
    }

    /** The SAS binds `sid` too, so two exchanges cannot be crossed even with the same points. */
    @Test
    fun theSasDependsOnTheSessionId() {
        val secret = ByteArray(32) { it.toByte() }
        val ea = ByteArray(65) { (it + 1).toByte() }
        val eb = ByteArray(65) { (it + 2).toByte() }
        val one = Sas.deriveFromAgreement(HkdfKeyDerivation, secret, ByteArray(16), ea, eb)
        val two = Sas.deriveFromAgreement(HkdfKeyDerivation, secret, ByteArray(16) { 9 }, ea, eb)
        assertNotEquals(one, two)
    }

    /** Both points are in `info`, so swapping either changes the digits. */
    @Test
    fun theSasDependsOnBothEphemeralKeys() {
        val secret = ByteArray(32) { it.toByte() }
        val sid = ByteArray(16) { 5 }
        val ea = ByteArray(65) { (it + 1).toByte() }
        val eb = ByteArray(65) { (it + 2).toByte() }
        val base = Sas.deriveFromAgreement(HkdfKeyDerivation, secret, sid, ea, eb)
        assertNotEquals(base, Sas.deriveFromAgreement(HkdfKeyDerivation, secret, sid, eb, eb))
        assertNotEquals(base, Sas.deriveFromAgreement(HkdfKeyDerivation, secret, sid, ea, ea))
    }

    /**
     * The two directions' six-digit strings are computed under different `info` strings, so a value
     * derived for one can never be presented as the other even if the key material collided.
     */
    @Test
    fun theTwoDirectionsSasAreDomainSeparated() {
        val sid = ByteArray(16) { 7 }
        val scanned = Sas.derive(HkdfKeyDerivation, ark, sid)
        val invite = Sas.deriveFromAgreement(HkdfKeyDerivation, ark, sid, ByteArray(0), ByteArray(0))
        assertNotEquals(scanned, invite)
        assertNotEquals(PairingProtocol.SAS_INFO, PairingProtocol.INVITE_SAS_INFO)
    }

    @Test
    fun anObserverWithBothPublicKeysCannotDeriveTheSas() {
        val clock = FakeClock()
        val desktop = AccountInviteSession(HkdfKeyDerivation, clock, server)
        val phone = JoiningDeviceSession(HkdfKeyDerivation, clock, deviceKey)
        val invited = phone.onScanned(desktop.inviteCode) as InviteOutcome.Accepted
        val agreed = desktop.onReply(invited.replyCode) as ReplyOutcome.Agreed

        // Everything a photographer of the screen plus a reader of the rendezvous has: both public
        // points and `sid`. Neither private half was ever transmitted, so the best they can do is
        // guess an ikm -- and any guess produces different digits.
        val ea = pointOf(desktop.inviteCode, PairingProtocol.KIND_INVITE)
        val eb = pointOf(invited.replyCode, PairingProtocol.KIND_REPLY)
        val guessed = Sas.deriveFromAgreement(HkdfKeyDerivation, ea + eb, desktop.sid, ea, eb)
        assertNotEquals(agreed.sas, guessed)
    }

    // -- confirm before seal --------------------------------------------------------------------

    /**
     * The sealing capability does not exist until the confirmation mints it, and it is minted once.
     *
     * This is the protocol-level half of the rule; the sequencing half is
     * `DesktopAccountPairingControllerTest.nothingIsSealedOrVouchedUntilTheSasIsConfirmed`.
     */
    @Test
    fun onlyConfirmationMintsTheAuthorityThatCanSeal() {
        val clock = FakeClock()
        val desktop = AccountInviteSession(HkdfKeyDerivation, clock, server)
        val phone = JoiningDeviceSession(HkdfKeyDerivation, clock, deviceKey)

        // Before a reply there is nothing to confirm and therefore nothing that can seal.
        assertNull(desktop.confirm())

        val invited = phone.onScanned(desktop.inviteCode) as InviteOutcome.Accepted
        desktop.onReply(invited.replyCode)

        val authority = desktop.confirm()
        assertNotNull(authority)
        // One confirmation, one authority. A second would be a second chance to hand over the ARK
        // behind one set of digits the user approved.
        assertNull(desktop.confirm())

        assertNotNull(authority!!.seal(ark, accountId, ""))
        assertNull("an authority seals exactly once", authority.seal(ark, accountId, ""))
    }

    @Test
    fun aDiscardedAuthorityCannotSeal() {
        val clock = FakeClock()
        val desktop = AccountInviteSession(HkdfKeyDerivation, clock, server)
        val phone = JoiningDeviceSession(HkdfKeyDerivation, clock, deviceKey)
        val invited = phone.onScanned(desktop.inviteCode) as InviteOutcome.Accepted
        desktop.onReply(invited.replyCode)

        val authority = desktop.confirm()!!
        authority.discard()
        assertNull(authority.seal(ark, accountId, ""))
    }

    @Test
    fun cancellingAnInviteRefusesAnyLaterConfirmation() {
        val clock = FakeClock()
        val desktop = AccountInviteSession(HkdfKeyDerivation, clock, server)
        val phone = JoiningDeviceSession(HkdfKeyDerivation, clock, deviceKey)
        val invited = phone.onScanned(desktop.inviteCode) as InviteOutcome.Accepted
        desktop.onReply(invited.replyCode)

        desktop.cancel()
        assertNull(desktop.confirm())
    }

    // -- the on-curve checks --------------------------------------------------------------------

    /**
     * `KeyFactory.generatePublic` does not check that a point is on the curve. Without this check an
     * attacker who deposits a small-order point recovers the desktop's ephemeral private key from
     * the agreement, and with it the key the ARK is sealed under.
     */
    @Test
    fun theDesktopRejectsAnOffCurveReplyKey() {
        val desktop = AccountInviteSession(HkdfKeyDerivation, FakeClock(), server)
        val reply = PairingCodec.encodeReply(desktop.sid, offCurvePoint(), deviceKey)
        val outcome = desktop.onReply(reply) as ReplyOutcome.Rejected
        assertEquals(PairingFailure.INVALID_PEER_KEY, outcome.failure)
        assertTrue(outcome.failure.isTerminal)
        assertNull("a rejected reply must leave nothing to confirm", desktop.confirm())
    }

    /**
     * The device key takes no part in the key schedule, so an off-curve one costs nothing
     * cryptographically — and it is still refused, because the only thing that would ever be done
     * with it is ask a server to trust it.
     */
    @Test
    fun theDesktopRejectsAnOffCurveDeviceKey() {
        val desktop = AccountInviteSession(HkdfKeyDerivation, FakeClock(), server)
        val ephemeral = P256.encodePublicKey(
            P256.generateKeyPair().public as java.security.interfaces.ECPublicKey
        )
        val reply = PairingCodec.encodeReply(desktop.sid, ephemeral, offCurvePoint())
        val outcome = desktop.onReply(reply) as ReplyOutcome.Rejected
        assertEquals(PairingFailure.INVALID_PEER_KEY, outcome.failure)
        assertNull(desktop.confirm())
    }

    @Test
    fun thePhoneRejectsAnOffCurveInviteKey() {
        val phone = JoiningDeviceSession(HkdfKeyDerivation, FakeClock(), deviceKey)
        val invite = PairingCodec.encodeInvite(
            ByteArray(16),
            offCurvePoint(),
            my.cheysoff.core_pairing.protocol.ServerHint(url = server.base),
        )
        val outcome = phone.onScanned(invite) as InviteOutcome.Rejected
        assertEquals(PairingFailure.INVALID_PEER_KEY, outcome.failure)
    }

    // -- session binding ------------------------------------------------------------------------

    @Test
    fun aReplyFromADifferentSessionIsRefused() {
        val clock = FakeClock()
        val desktop = AccountInviteSession(HkdfKeyDerivation, clock, server)
        val other = AccountInviteSession(HkdfKeyDerivation, clock, server)
        val phone = JoiningDeviceSession(HkdfKeyDerivation, clock, deviceKey)
        val invited = phone.onScanned(other.inviteCode) as InviteOutcome.Accepted

        val outcome = desktop.onReply(invited.replyCode) as ReplyOutcome.Rejected
        assertEquals(PairingFailure.SESSION_MISMATCH, outcome.failure)
    }

    @Test
    fun oneInviteAcceptsOneReply() {
        val clock = FakeClock()
        val desktop = AccountInviteSession(HkdfKeyDerivation, clock, server)
        val first = JoiningDeviceSession(HkdfKeyDerivation, clock, deviceKey)
        val second = JoiningDeviceSession(HkdfKeyDerivation, clock, deviceKey)
        val a = first.onScanned(desktop.inviteCode) as InviteOutcome.Accepted
        val b = second.onScanned(desktop.inviteCode) as InviteOutcome.Accepted

        assertTrue(desktop.onReply(a.replyCode) is ReplyOutcome.Agreed)
        val outcome = desktop.onReply(b.replyCode) as ReplyOutcome.Rejected
        assertEquals(PairingFailure.SESSION_CLOSED, outcome.failure)
    }

    @Test
    fun thePhoneAcceptsOneInvite() {
        val clock = FakeClock()
        val phone = JoiningDeviceSession(HkdfKeyDerivation, clock, deviceKey)
        val first = AccountInviteSession(HkdfKeyDerivation, clock, server)
        val second = AccountInviteSession(HkdfKeyDerivation, clock, server)
        assertTrue(phone.onScanned(first.inviteCode) is InviteOutcome.Accepted)
        val outcome = phone.onScanned(second.inviteCode) as InviteOutcome.Rejected
        assertEquals(PairingFailure.SESSION_CLOSED, outcome.failure)
    }

    /**
     * The phone derives its key from the `EA` it read off the screen, and refuses a bundle naming a
     * different one. Using the arriving copy instead would turn the exchange's one authenticated
     * value into an unauthenticated one.
     */
    @Test
    fun aBundleNamingADifferentEphemeralKeyIsRefused() {
        val clock = FakeClock()
        val desktop = AccountInviteSession(HkdfKeyDerivation, clock, server)
        val phone = JoiningDeviceSession(HkdfKeyDerivation, clock, deviceKey)
        val invited = phone.onScanned(desktop.inviteCode) as InviteOutcome.Accepted
        desktop.onReply(invited.replyCode)
        val sealCode = desktop.confirm()!!.seal(ark, accountId, "")!!

        val outcome = phone.onBundle(rewritePoint(sealCode, otherPoint())) as BundleOutcome.Rejected
        assertEquals(PairingFailure.SESSION_MISMATCH, outcome.failure)
    }

    @Test
    fun aBundleFromAnotherSessionIsRefused() {
        val clock = FakeClock()
        val desktop = AccountInviteSession(HkdfKeyDerivation, clock, server)
        val phone = JoiningDeviceSession(HkdfKeyDerivation, clock, deviceKey)
        val invited = phone.onScanned(desktop.inviteCode) as InviteOutcome.Accepted
        desktop.onReply(invited.replyCode)
        val sealCode = desktop.confirm()!!.seal(ark, accountId, "")!!

        val outcome = phone.onBundle(rewriteSid(sealCode, ByteArray(16) { 42 }))
            as BundleOutcome.Rejected
        assertEquals(PairingFailure.SESSION_MISMATCH, outcome.failure)
    }

    /** A tag failure is terminal and loud, and the session never answers again. */
    @Test
    fun aTamperedBundleAbortsLoudlyAndPermanently() {
        val clock = FakeClock()
        val desktop = AccountInviteSession(HkdfKeyDerivation, clock, server)
        val phone = JoiningDeviceSession(HkdfKeyDerivation, clock, deviceKey)
        val invited = phone.onScanned(desktop.inviteCode) as InviteOutcome.Accepted
        desktop.onReply(invited.replyCode)
        val sealCode = desktop.confirm()!!.seal(ark, accountId, "")!!

        val first = phone.onBundle(tamperWithSealBytes(sealCode)) as BundleOutcome.Rejected
        assertEquals(PairingFailure.SEAL_REJECTED, first.failure)
        assertTrue(first.failure.isTerminal)
        // And the untampered bundle is refused afterwards too: the session is dead, not retrying.
        val second = phone.onBundle(sealCode) as BundleOutcome.Rejected
        assertEquals(PairingFailure.SESSION_CLOSED, second.failure)
    }

    @Test
    fun aSuccessfulPairingClosesThePhoneSession() {
        val clock = FakeClock()
        val desktop = AccountInviteSession(HkdfKeyDerivation, clock, server)
        val phone = JoiningDeviceSession(HkdfKeyDerivation, clock, deviceKey)
        val invited = phone.onScanned(desktop.inviteCode) as InviteOutcome.Accepted
        desktop.onReply(invited.replyCode)
        val sealCode = desktop.confirm()!!.seal(ark, accountId, "")!!

        assertTrue(phone.onBundle(sealCode) is BundleOutcome.Opened)
        val again = phone.onBundle(sealCode) as BundleOutcome.Rejected
        assertEquals(PairingFailure.SESSION_CLOSED, again.failure)
    }

    // -- expiry ---------------------------------------------------------------------------------

    @Test
    fun anInviteIsGoodForTwoMinutes() {
        val clock = FakeClock()
        val desktop = AccountInviteSession(HkdfKeyDerivation, clock, server)
        assertEquals(120_000L, desktop.remainingMillis())
        clock.advance(119_999)
        assertTrue(desktop.remainingMillis() > 0)
        clock.advance(1)
        assertTrue(desktop.isExpired())
    }

    @Test
    fun anExpiredInviteRefusesAReplyAndIsTerminal() {
        val clock = FakeClock()
        val desktop = AccountInviteSession(HkdfKeyDerivation, clock, server)
        val phone = JoiningDeviceSession(HkdfKeyDerivation, clock, deviceKey)
        val invited = phone.onScanned(desktop.inviteCode) as InviteOutcome.Accepted

        clock.advance(120_000)
        val outcome = desktop.onReply(invited.replyCode) as ReplyOutcome.Rejected
        assertEquals(PairingFailure.EXPIRED, outcome.failure)
        assertNull(desktop.confirm())
    }

    /** The phone's clock starts when it accepts, because it cannot tell how old the QR was. */
    @Test
    fun thePhoneCountdownStartsAtAcceptance() {
        val clock = FakeClock()
        val desktop = AccountInviteSession(HkdfKeyDerivation, clock, server)
        val phone = JoiningDeviceSession(HkdfKeyDerivation, clock, deviceKey)
        clock.advance(60_000)
        assertEquals(120_000L, phone.remainingMillis())

        phone.onScanned(desktop.inviteCode)
        clock.advance(119_999)
        assertTrue(phone.remainingMillis() > 0)
        clock.advance(1)
        assertTrue(phone.isExpired())
    }

    @Test
    fun anExpiredPhoneSessionRefusesTheBundle() {
        val clock = FakeClock()
        val desktop = AccountInviteSession(HkdfKeyDerivation, clock, server)
        val phone = JoiningDeviceSession(HkdfKeyDerivation, clock, deviceKey)
        val invited = phone.onScanned(desktop.inviteCode) as InviteOutcome.Accepted
        desktop.onReply(invited.replyCode)
        val sealCode = desktop.confirm()!!.seal(ark, accountId, "")!!

        clock.advance(120_000)
        val outcome = phone.onBundle(sealCode) as BundleOutcome.Rejected
        assertEquals(PairingFailure.EXPIRED, outcome.failure)
    }

    // -- the wire -------------------------------------------------------------------------------

    @Test
    fun anInviteWithoutAnAddressDoesNotDecode() {
        // Hand-built, because the encoder refuses to make one: there is no offline variant of this
        // direction and a session must never be constructed around an invite with nowhere to reply.
        val body = Base64.getUrlDecoder().decode(
            AccountInviteSession(HkdfKeyDerivation, FakeClock(), server)
                .inviteCode.removePrefix(PairingProtocol.QR_PREFIX)
        )
        // 2 header + 16 sid + 1 epLen + 65 point = 84, then the 16-bit url length.
        val stripped = body.copyOfRange(0, 84) + byteArrayOf(0, 0) + byteArrayOf(0)
        val code = PairingProtocol.QR_PREFIX +
            Base64.getUrlEncoder().withoutPadding().encodeToString(stripped)
        val phone = JoiningDeviceSession(HkdfKeyDerivation, FakeClock(), deviceKey)
        val outcome = phone.onScanned(code) as InviteOutcome.Rejected
        assertEquals(PairingFailure.MALFORMED, outcome.failure)
    }

    @Test
    fun aReplyIsRefusedWhereAnInviteIsWanted() {
        val clock = FakeClock()
        val desktop = AccountInviteSession(HkdfKeyDerivation, clock, server)
        val phone = JoiningDeviceSession(HkdfKeyDerivation, clock, deviceKey)
        val invited = phone.onScanned(desktop.inviteCode) as InviteOutcome.Accepted

        val other = JoiningDeviceSession(HkdfKeyDerivation, clock, deviceKey)
        val outcome = other.onScanned(invited.replyCode) as InviteOutcome.Rejected
        assertEquals(PairingFailure.WRONG_CODE_KIND, outcome.failure)
    }

    @Test
    fun anInviteIsRefusedWhereAReplyIsWanted() {
        val desktop = AccountInviteSession(HkdfKeyDerivation, FakeClock(), server)
        val outcome = desktop.onReply(desktop.inviteCode) as ReplyOutcome.Rejected
        assertEquals(PairingFailure.WRONG_CODE_KIND, outcome.failure)
    }

    @Test
    fun aReplyCarryingNoDeviceKeyDoesNotDecode() {
        val desktop = AccountInviteSession(HkdfKeyDerivation, FakeClock(), server)
        val ephemeral = P256.encodePublicKey(
            P256.generateKeyPair().public as java.security.interfaces.ECPublicKey
        )
        val full = PairingCodec.encodeReply(desktop.sid, ephemeral, deviceKey)
        val frame = Base64.getUrlDecoder().decode(full.removePrefix(PairingProtocol.QR_PREFIX))
        // 2 header + 16 sid + 1 epLen + 65 point = 84, then the device key's length byte.
        val stripped = frame.copyOfRange(0, 84) + byteArrayOf(0)
        val code = PairingProtocol.QR_PREFIX +
            Base64.getUrlEncoder().withoutPadding().encodeToString(stripped)
        val outcome = desktop.onReply(code) as ReplyOutcome.Rejected
        assertEquals(PairingFailure.MALFORMED, outcome.failure)
    }

    @Test
    fun aScannedDirectionOfferIsNotAnInvite() {
        val phone = JoiningDeviceSession(HkdfKeyDerivation, FakeClock(), deviceKey)
        val offer = my.cheysoff.core_pairing.protocol.NewDeviceSession(
            HkdfKeyDerivation,
            FakeClock(),
        ).offerCode
        val outcome = phone.onScanned(offer) as InviteOutcome.Rejected
        assertEquals(PairingFailure.WRONG_CODE_KIND, outcome.failure)
    }

    @Test
    fun everyInviteMintsANewSessionId() {
        val seen = (0 until 32).map {
            AccountInviteSession(HkdfKeyDerivation, FakeClock(), server).sid.toHex()
        }
        assertEquals(seen.size, seen.toSet().size)
    }

    // -- helpers --------------------------------------------------------------------------------

    /** A syntactically perfect SEC1 point that is not on P-256. */
    private fun offCurvePoint(): ByteArray {
        val out = ByteArray(65)
        out[0] = 0x04
        out[32] = 2
        out[64] = 3
        return out
    }

    private fun otherPoint(): ByteArray = P256.encodePublicKey(
        P256.generateKeyPair(SecureRandom()).public as java.security.interfaces.ECPublicKey
    )

    /** The 65-byte point that follows the header and `sid` in either frame kind. */
    private fun pointOf(code: String, expectedKind: Byte): ByteArray {
        val frame = Base64.getUrlDecoder().decode(code.removePrefix(PairingProtocol.QR_PREFIX))
        assertEquals(expectedKind, frame[1])
        return frame.copyOfRange(19, 19 + P256.POINT_SIZE_BYTES)
    }

    private fun rewriteSid(code: String, sid: ByteArray): String {
        val frame = Base64.getUrlDecoder().decode(code.removePrefix(PairingProtocol.QR_PREFIX))
        sid.copyInto(frame, 2)
        return PairingProtocol.QR_PREFIX +
            Base64.getUrlEncoder().withoutPadding().encodeToString(frame)
    }

    private fun rewritePoint(code: String, point: ByteArray): String {
        val frame = Base64.getUrlDecoder().decode(code.removePrefix(PairingProtocol.QR_PREFIX))
        point.copyInto(frame, 19)
        return PairingProtocol.QR_PREFIX +
            Base64.getUrlEncoder().withoutPadding().encodeToString(frame)
    }

    private fun tamperWithSealBytes(code: String): String {
        val frame = Base64.getUrlDecoder().decode(code.removePrefix(PairingProtocol.QR_PREFIX))
        frame[100] = (frame[100].toInt() xor 0x01).toByte()
        return PairingProtocol.QR_PREFIX +
            Base64.getUrlEncoder().withoutPadding().encodeToString(frame)
    }
}
