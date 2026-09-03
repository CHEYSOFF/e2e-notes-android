package my.cheysoff.desktop.pairing

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import my.cheysoff.core_pairing.protocol.BundleOutcome
import my.cheysoff.core_pairing.protocol.CollectResult
import my.cheysoff.core_pairing.protocol.DepositResult
import my.cheysoff.core_pairing.protocol.HkdfKeyDerivation
import my.cheysoff.core_pairing.protocol.InviteOutcome
import my.cheysoff.core_pairing.protocol.JoiningDeviceSession
import my.cheysoff.core_pairing.protocol.MonotonicClock
import my.cheysoff.core_pairing.protocol.P256
import my.cheysoff.core_pairing.protocol.PairingConfig
import my.cheysoff.core_pairing.protocol.PairingProtocol
import my.cheysoff.core_pairing.protocol.RendezvousClient
import my.cheysoff.core_pairing.protocol.RendezvousProtocol
import my.cheysoff.core_pairing.protocol.RendezvousSlot
import my.cheysoff.core_pairing.protocol.RendezvousUrl
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.interfaces.ECPublicKey

/**
 * The desktop as the account device: it shows the code, waits, and hands the account key over.
 *
 * The protocol underneath is `:core-pairing`'s and is tested there against real crypto. What is
 * checked here is the sequencing — and in this direction the sequencing **is** a security property,
 * because the account key travels outward. The test the whole file is built around is
 * [nothingIsSealedOrVouchedUntilTheSasIsConfirmed]; the rest is the ordinary business of a screen
 * that polls.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopAccountPairingControllerTest {

    private val ark = ByteArray(32) { (it * 7 + 5).toByte() }
    private val accountId = "acct-desktop-holds-it"
    private val phoneDeviceKey =
        P256.encodePublicKey(P256.generateKeyPair().public as ECPublicKey)

    // -----------------------------------------------------------------------------------------
    // The rule
    // -----------------------------------------------------------------------------------------

    /**
     * **The account key is not sealed, and the phone's key is not vouched for, until a person has
     * said the six digits match.**
     *
     * In the scanned direction this ordering is a courtesy: the ARK is already on its way when the
     * digits appear, and the comparison is a mis-scan check. Here it is the entire defence. The
     * phone's ephemeral key arrived through a server that an attacker may control, and if they
     * substituted their own the two screens show different numbers and nothing else notices. So a
     * build that sealed on receipt would hand the account root key to whoever answered first.
     *
     * The protocol makes early sealing structurally impossible — `AccountInviteSession` has no
     * method that seals, and `confirm()` is the sole factory for the object that does — and this
     * test is the sequencing half of the same rule: it asserts that at the moment the digits are on
     * screen, **nothing has been deposited into the bundle slot and no vouch has been requested.**
     *
     * Move the vouch-and-seal block out of `confirmSas` and into the poll handler and this fails.
     */
    @Test
    fun nothingIsSealedOrVouchedUntilTheSasIsConfirmed() = runTest {
        val drop = FakeDrop()
        val voucher = RecordingVoucher()
        val controller = controller(drop, voucher)
        val phone = answerTheInvite(controller, drop)
        pump()

        val confirming = controller.step as InviteStep.Confirming
        assertEquals("both sides show the same digits", phone.sas, confirming.sas)

        // The two things that hand something over, and neither has happened.
        assertTrue(
            "a bundle was deposited before the user confirmed: ${drop.deposits}",
            drop.deposits.none { it.second == RendezvousSlot.BUNDLE },
        )
        assertEquals("a device was vouched for before the user confirmed", 0, voucher.calls)

        // And after confirming, both have.
        controller.confirmSas()
        pump()
        assertTrue(drop.deposits.any { it.second == RendezvousSlot.BUNDLE })
        assertEquals(1, voucher.calls)

        controller.close()
    }

    /**
     * Rejecting means nothing was ever produced, and nothing can be produced afterwards.
     *
     * The user saying "these do not match" is the report of a man in the middle, so the response is
     * not "cancel and tidy up" — it is that the sealing capability was never minted and the session
     * is dead.
     */
    @Test
    fun rejectingTheSasSealsNothingAndCannotBeUndone() = runTest {
        val drop = FakeDrop()
        val voucher = RecordingVoucher()
        val controller = controller(drop, voucher)
        answerTheInvite(controller, drop)
        pump()

        controller.rejectSas()
        pump()

        assertTrue(controller.step is InviteStep.Failed)
        assertTrue(drop.deposits.none { it.second == RendezvousSlot.BUNDLE })
        assertEquals(0, voucher.calls)

        // A confirmation after the fact reaches a session that is closed, so still nothing.
        controller.confirmSas()
        pump()
        assertTrue(drop.deposits.none { it.second == RendezvousSlot.BUNDLE })
        assertEquals(0, voucher.calls)

        controller.close()
    }

    // -----------------------------------------------------------------------------------------
    // The happy path
    // -----------------------------------------------------------------------------------------

    @Test
    fun theInviteShowsAQrCodeNamingTheServerAndCountsDown() = runTest {
        val controller = controller(FakeDrop(), RecordingVoucher())
        val step = controller.step as InviteStep.Showing
        assertTrue(step.code.startsWith(PairingProtocol.QR_PREFIX))
        assertEquals("pair.example.test", step.host)
        assertTrue(step.secure)
        assertEquals(120, step.secondsRemaining)
        controller.close()
    }

    @Test
    fun aConfirmedInviteVouchesSealsAndDepositsAndThePhoneOpensIt() = runTest {
        val drop = FakeDrop()
        val voucher = RecordingVoucher(deviceId = "dev-77")
        val controller = controller(drop, voucher)
        val phone = answerTheInvite(controller, drop)
        pump()

        controller.confirmSas()
        pump()

        assertArrayEquals(
            "the key vouched for must be the one the reply carried",
            phoneDeviceKey,
            voucher.lastKey,
        )
        val done = controller.step as InviteStep.Done
        assertTrue(done.enrolled)

        // The phone's half, run for real against what the drop now holds.
        val collected = drop.collect(phone.sid, RendezvousSlot.BUNDLE) as CollectResult.Collected
        val opened = phone.session.onBundle(collected.sealCode) as BundleOutcome.Opened
        assertArrayEquals("the ARK changed in transit", ark, opened.bundle.ark)
        assertEquals(accountId, opened.bundle.accountId)

        // The config the phone needs in order to sync: the server this computer named, and the id
        // the server assigned to the phone. Neither has any other channel to travel on.
        val config = PairingConfig.decode(opened.bundle.config)!!
        assertEquals("https://pair.example.test", config.serverUrl)
        assertEquals("dev-77", config.deviceId)

        controller.close()
    }

    /**
     * A refused vouch does not stop the pairing, and does not lie about it.
     *
     * The account key can still cross; what the phone will not have is a device id, so it cannot
     * open a session. Saying so on the screen beats letting the user discover it at the first sync.
     */
    @Test
    fun aRefusedVouchStillSendsTheKeyAndSaysTheDeviceIsNotEnrolled() = runTest {
        val drop = FakeDrop()
        val voucher = RecordingVoucher(deviceId = null)
        val controller = controller(drop, voucher)
        val phone = answerTheInvite(controller, drop)
        pump()

        controller.confirmSas()
        pump()

        val done = controller.step as InviteStep.Done
        assertFalse(done.enrolled)
        assertNotNull(done.note)

        val collected = drop.collect(phone.sid, RendezvousSlot.BUNDLE) as CollectResult.Collected
        val opened = phone.session.onBundle(collected.sealCode) as BundleOutcome.Opened
        // An enrolment that did not happen is an absent `deviceId`, not an empty one: "no
        // enrolment" has one spelling on the wire.
        assertNull(PairingConfig.decode(opened.bundle.config)!!.deviceId)

        controller.close()
    }

    // -----------------------------------------------------------------------------------------
    // Waiting, and the ways it ends
    // -----------------------------------------------------------------------------------------

    @Test
    fun itKeepsPollingTheReplySlotWhileNobodyAnswers() = runTest {
        val drop = FakeDrop()
        val controller = controller(drop, RecordingVoucher())
        pump()
        assertTrue("the invite should poll more than once", drop.collects > 1)
        assertTrue(drop.collectedSlots.all { it == RendezvousSlot.REPLY })
        assertTrue(controller.step is InviteStep.Showing)
        controller.close()
    }

    /** A network blip is a note on the same screen, not a failure: the TTL is the deadline. */
    @Test
    fun anUnreachableServerIsANoteRatherThanAFailure() = runTest {
        val drop = FakeDrop()
        drop.answer = CollectResult.Unreachable("connection refused")
        val controller = controller(drop, RecordingVoucher())
        pump()

        val step = controller.step as InviteStep.Showing
        assertTrue(step.note!!.contains("connection refused"))
        controller.close()
    }

    /** A server answering with something unusable is terminal: asking again will not improve it. */
    @Test
    fun anUnusableAnswerIsTerminal() = runTest {
        val drop = FakeDrop()
        drop.answer = CollectResult.Unusable("not json")
        val controller = controller(drop, RecordingVoucher())
        pump()

        assertTrue(controller.step is InviteStep.Failed)
        val stopped = drop.collects
        pump()
        assertEquals("a terminal failure must stop the loop", stopped, drop.collects)
        controller.close()
    }

    @Test
    fun anExpiredInviteStopsPollingAndSaysSo() = runTest {
        val clock = MovableClock()
        val drop = FakeDrop()
        val controller = controller(drop, RecordingVoucher(), clock)
        pump()
        assertTrue(controller.step is InviteStep.Showing)

        clock.now = 120_001
        pump()

        val failed = controller.step as InviteStep.Failed
        assertTrue(failed.message.contains("expired"))
        val stopped = drop.collects
        pump()
        assertEquals(stopped, drop.collects)
        controller.close()
    }

    /** A reply for another exchange never reaches the digits. */
    @Test
    fun aReplyFromADifferentSessionFailsRatherThanShowingDigits() = runTest {
        val drop = FakeDrop()
        val controller = controller(drop, RecordingVoucher())
        val step = controller.step as InviteStep.Showing

        // A phone that answered a different computer's invite, filed under this one's `sid`.
        val other = DesktopAccountPairingController(
            scope = backgroundScope,
            server = RendezvousUrl.parse("https://pair.example.test")!!,
            account = account(),
            voucher = RecordingVoucher(),
            clientFor = { FakeDrop() },
            clock = MonotonicClock { 0 },
            pollIntervalMillis = 1,
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )
        val phone = JoiningDeviceSession(HkdfKeyDerivation, MonotonicClock { 0 }, phoneDeviceKey)
        val accepted = phone.onScanned(
            (other.step as InviteStep.Showing).code
        ) as InviteOutcome.Accepted
        other.close()

        drop.force(sidOf(step.code), RendezvousSlot.REPLY, accepted.replyCode)
        pump()

        assertTrue(controller.step is InviteStep.Failed)
        controller.close()
    }

    /** Cancelling stops the loop; nothing keeps asking a server about an abandoned screen. */
    @Test
    fun cancellingStopsThePolling() = runTest {
        val drop = FakeDrop()
        val controller = controller(drop, RecordingVoucher())
        pump()
        controller.cancel()
        val stopped = drop.collects
        pump()
        assertEquals(stopped, drop.collects)
    }

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    private fun account() = InviteAccount(
        // A copy per call, exactly as the real caller hands one out: the controller zeroes what it
        // is given the moment the seal is built.
        arkProvider = { ark.copyOf() },
        accountId = accountId,
        voucherDeviceId = "dev-this-computer",
    )

    private fun TestScope.controller(
        drop: FakeDrop,
        voucher: RecordingVoucher,
        clock: MonotonicClock = MonotonicClock { 0 },
    ) = DesktopAccountPairingController(
        scope = backgroundScope,
        server = RendezvousUrl.parse("https://pair.example.test")!!,
        account = account(),
        voucher = voucher,
        clientFor = { drop },
        clock = clock,
        // Virtual time here, so this is the smallest value that still makes the loop a loop. What
        // the real 1.5 s buys is documented on the constant.
        pollIntervalMillis = 1,
        // The scheduler `runTest` advances, so `pump()` sees the poll's *result* rather than
        // merely the request.
        ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
    )

    /** A real joining session, answering the invite through the drop, as a phone would. */
    private fun answerTheInvite(
        controller: DesktopAccountPairingController,
        drop: FakeDrop,
    ): AnsweringPhone {
        val code = (controller.step as InviteStep.Showing).code
        val session = JoiningDeviceSession(HkdfKeyDerivation, MonotonicClock { 0 }, phoneDeviceKey)
        val accepted = session.onScanned(code) as InviteOutcome.Accepted
        drop.deposit(session.sid!!, RendezvousSlot.REPLY, accepted.replyCode)
        return AnsweringPhone(session, accepted.sas, session.sid!!)
    }

    private class AnsweringPhone(
        val session: JoiningDeviceSession,
        val sas: String,
        val sid: ByteArray,
    )

    private fun sidOf(inviteCode: String): ByteArray {
        val frame = java.util.Base64.getUrlDecoder()
            .decode(inviteCode.removePrefix(PairingProtocol.QR_PREFIX))
        return frame.copyOfRange(2, 2 + PairingProtocol.SID_SIZE_BYTES)
    }

    /**
     * Run the poll loop for a while.
     *
     * **Not `advanceUntilIdle()`**, for the reason `DesktopPairingControllerTest.pump` gives at
     * length: the loop lives in `backgroundScope`, which `advanceUntilIdle` deliberately ignores.
     */
    private fun TestScope.pump() {
        runCurrent()
        advanceTimeBy(POLLS)
        runCurrent()
    }

    private class MovableClock(var now: Long = 0) : MonotonicClock {
        override fun elapsedMillis(): Long = now
    }

    /** Counts vouches and answers with a fixed id, or with null for a server that refused. */
    private class RecordingVoucher(private val deviceId: String? = "dev-phone") : DeviceVoucher {
        var calls = 0
            private set
        var lastKey: ByteArray? = null
            private set

        override suspend fun vouchFor(joiningDeviceKey: ByteArray): String? {
            calls++
            lastKey = joiningDeviceKey
            return deviceId
        }
    }

    /** An in-memory two-slot drop with the server's own single-use rule, plus call counters. */
    private class FakeDrop : RendezvousClient {
        private val rows = HashMap<String, String>()

        val deposits = mutableListOf<Pair<String, RendezvousSlot>>()
        val collectedSlots = mutableListOf<RendezvousSlot>()
        val collects: Int get() = collectedSlots.size

        /** When set, every collect returns this instead of looking at the rows. */
        var answer: CollectResult? = null

        override fun deposit(sid: ByteArray, slot: RendezvousSlot, code: String): DepositResult {
            rows[key(sid, slot)] = RendezvousProtocol.toBlob(code)
            deposits += RendezvousProtocol.encodeSid(sid) to slot
            return DepositResult.Deposited(0L)
        }

        override fun collect(sid: ByteArray, slot: RendezvousSlot): CollectResult {
            collectedSlots += slot
            answer?.let { return it }
            val blob = rows.remove(key(sid, slot)) ?: return CollectResult.Pending
            return CollectResult.Collected(RendezvousProtocol.fromBlob(blob))
        }

        /** Park a blob regardless of what is already there. Only an attacker can do this. */
        fun force(sid: ByteArray, slot: RendezvousSlot, code: String) {
            rows[key(sid, slot)] = RendezvousProtocol.toBlob(code)
        }

        private fun key(sid: ByteArray, slot: RendezvousSlot): String =
            RendezvousProtocol.encodeSid(sid) + slot.pathSuffix
    }

    private companion object {
        /** Virtual milliseconds to advance when the point is "it kept polling". */
        const val POLLS = 10L
    }
}
