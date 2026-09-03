package my.cheysoff.desktop.pairing

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import my.cheysoff.core_pairing.protocol.AccountBundle
import my.cheysoff.core_pairing.protocol.AccountDeviceSession
import my.cheysoff.core_pairing.protocol.CollectResult
import my.cheysoff.core_pairing.protocol.DepositResult
import my.cheysoff.core_pairing.protocol.HkdfKeyDerivation
import my.cheysoff.core_pairing.protocol.MonotonicClock
import my.cheysoff.core_pairing.protocol.OfferOutcome
import my.cheysoff.core_pairing.protocol.PairingProtocol
import my.cheysoff.core_pairing.protocol.RendezvousClient
import my.cheysoff.core_pairing.protocol.RendezvousProtocol
import my.cheysoff.core_pairing.protocol.RendezvousSlot
import my.cheysoff.core_pairing.protocol.RendezvousUrl
import org.junit.Test
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * The desktop's pairing sequencing: which step is showing, when it polls, and the rule that carries
 * the whole screen — **the ARK does not leave this object until the user confirms the six digits**.
 *
 * The protocol underneath is `:core-pairing`'s and is tested there, against real crypto. What is
 * checked here is everything a UI can get wrong: polling that never stops, a bundle that survives a
 * rejection, an address that is acted on before it is validated.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopPairingControllerTest {

    private val ark = ByteArray(32) { (it + 9).toByte() }
    private val bundle = AccountBundle(ark, "acct-desktop", "{}")

    // -----------------------------------------------------------------------------------------
    // The address
    // -----------------------------------------------------------------------------------------

    /**
     * A bad address is a correction, not a burnt session.
     *
     * Validated before a [my.cheysoff.core_pairing.protocol.NewDeviceRendezvous] exists, so a typo
     * does not cost a `sid` and a QR code that was never going to work.
     */
    @Test
    fun anUnusableAddressIsRefusedWithoutStartingASession() = runTest {
        val drop = FakeDrop()
        val controller = controller(drop)

        controller.editAddress("not a url")
        controller.start()
        pump()

        val step = controller.step as PairingStep.Address
        assertTrue(step.message!!.contains("http"))
        assertEquals("a rejected address must not start polling", 0, drop.collects)
    }

    @Test
    fun avalidAddressStartsTheSessionAndShowsAQrCode() = runTest {
        val controller = controller(FakeDrop())

        controller.editAddress("https://pair.example.test")
        controller.start()

        val step = controller.step as PairingStep.Waiting
        assertTrue(step.code.startsWith(PairingProtocol.QR_PREFIX))
        assertEquals("pair.example.test", step.host)
        assertTrue(step.secure)
        assertEquals(120, step.secondsRemaining)

        controller.close()
    }

    /** The unsecured case is surfaced rather than hidden; the screen colours it as a warning. */
    @Test
    fun aCleartextAddressIsAcceptedAndFlagged() = runTest {
        val controller = controller(FakeDrop())
        controller.editAddress("http://127.0.0.1:8080")
        controller.start()

        assertFalse((controller.step as PairingStep.Waiting).secure)
        controller.close()
    }

    // -----------------------------------------------------------------------------------------
    // Polling
    // -----------------------------------------------------------------------------------------

    /** The whole flow, with a real phone session on the other end of a fake drop. */
    @Test
    fun aDepositedBundleIsCollectedAndOffersItsSasForConfirmation() = runTest {
        val drop = FakeDrop()
        val controller = controller(drop)
        controller.editAddress("https://pair.example.test")
        controller.start()

        val waiting = controller.step as PairingStep.Waiting
        val phone = AccountDeviceSession(HkdfKeyDerivation, MonotonicClock { 0 }, bundle.ark, bundle.accountId)
        val accepted = phone.onScanned(waiting.code) as OfferOutcome.Accepted
        // `onScanned` accepts the offer and yields the SAS; sealing is a second, separate step, so
        // that a device cannot hand over the account root key before its user has had the chance to
        // compare digits. `""` is the empty config, exactly as `PairingViewModel` sends it.
        drop.deposit(phone.receivedSid!!, RendezvousSlot.BUNDLE, phone.seal("")!!)

        pump()

        val confirming = controller.step as PairingStep.Confirming
        assertEquals(accepted.sas, confirming.sas)
        // Nothing has crossed out of the controller yet. This is the load-bearing assertion.
        assertNull(controller.takeBundle())
    }

    /** Polling stops the moment the bundle arrives; it must not keep asking for a second one. */
    @Test
    fun pollingStopsOnSuccess() = runTest {
        val drop = FakeDrop()
        val controller = controller(drop)
        controller.editAddress("https://pair.example.test")
        controller.start()

        val phone = AccountDeviceSession(HkdfKeyDerivation, MonotonicClock { 0 }, bundle.ark, bundle.accountId)
        val accepted = phone.onScanned((controller.step as PairingStep.Waiting).code) as OfferOutcome.Accepted
        drop.deposit(phone.receivedSid!!, RendezvousSlot.BUNDLE, phone.seal("")!!)
        pump()

        val collectsAtSuccess = drop.collects
        pump()
        assertEquals(collectsAtSuccess, drop.collects)
    }

    /**
     * The session dies at its TTL even if the server never answers.
     *
     * The clock is the controller's own monotonic one, not the test scheduler's virtual time, so
     * this is the *session's* deadline being enforced rather than a `delay` running out.
     */
    @Test
    fun theSessionExpiresAtItsTtl() = runTest {
        val clock = MovableClock()
        val drop = FakeDrop()
        val controller = DesktopPairingController(
            scope = backgroundScope,
            clientFor = { drop },
            clock = clock,
            pollIntervalMillis = 1,
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            rememberedServer = { "" },
            recordWorkingServer = {},
        )
        controller.editAddress("https://pair.example.test")
        controller.start()
        // The loop is still running here, so this is a bounded pump rather than a wait for idle.
        // See `pump`.
        pump()
        assertTrue(controller.step is PairingStep.Waiting)

        clock.now += PairingProtocol.CODE_TTL_MILLIS
        pump()

        val failed = controller.step as PairingStep.Failed
        assertTrue(failed.message.contains("expired"))
    }

    /** A network blip is a note on the waiting screen, not the end of the attempt. */
    @Test
    fun anUnreachableServerKeepsWaitingWithANote() = runTest {
        val drop = FakeDrop()
        drop.answer = CollectResult.Unreachable("connection refused")
        val controller = controller(drop)
        controller.editAddress("https://pair.example.test")
        controller.start()
        pump()

        val waiting = controller.step as PairingStep.Waiting
        assertTrue(waiting.note!!.contains("connection refused"))
        assertTrue("it should still be polling", drop.collects > 1)

        controller.close()
    }

    /** A server answering with rubbish is terminal: polling past it is polling something hostile. */
    @Test
    fun anUnusableAnswerStopsTheAttempt() = runTest {
        val drop = FakeDrop()
        drop.answer = CollectResult.Unusable("not a bundle")
        val controller = controller(drop)
        controller.editAddress("https://pair.example.test")
        controller.start()
        pump()

        assertTrue(controller.step is PairingStep.Failed)
    }

    // -----------------------------------------------------------------------------------------
    // Handing the ARK over
    // -----------------------------------------------------------------------------------------

    @Test
    fun confirmingTheSasReleasesTheBundleExactlyOnce() = runTest {
        val controller = pairedController()

        controller.confirmSas()
        assertEquals(PairingStep.Confirmed, controller.step)

        val taken = controller.takeBundle()
        assertArrayEquals(ark, taken!!.ark)
        // A second take is null: the ARK reaching storage twice would mean two vaults.
        assertNull(controller.takeBundle())
    }

    /**
     * Rejecting the SAS destroys the ARK.
     *
     * Zeroed rather than merely dropped — a desktop process stays open for hours, and a dropped
     * array survives in the heap until a collection that may never come.
     */
    @Test
    fun rejectingTheSasDestroysTheBundle() = runTest {
        val controller = pairedController()

        controller.rejectSas()

        assertTrue(controller.step is PairingStep.Failed)
        assertNull(controller.takeBundle())
    }

    @Test
    fun startingOverDestroysTheBundleAndReturnsToTheAddress() = runTest {
        val controller = pairedController()

        controller.startOver()

        val step = controller.step as PairingStep.Address
        assertEquals("https://pair.example.test", step.url)
        assertNull(controller.takeBundle())
    }

    /** Closing the screen must not leave a poll loop running against a server. */
    @Test
    fun closingStopsPolling() = runTest {
        val drop = FakeDrop()
        val controller = controller(drop)
        controller.editAddress("https://pair.example.test")
        controller.start()
        pump()

        val before = drop.collects
        controller.close()
        pump()
        assertEquals(before, drop.collects)
    }

    // -----------------------------------------------------------------------------------------

    private fun TestScope.controller(drop: FakeDrop) =
        DesktopPairingController(
            scope = backgroundScope,
            clientFor = { drop },
            clock = MonotonicClock { 0 },
            // The poll cadence is virtual time here, so it is set to the smallest value that still
            // makes the loop a loop. What the real 1.5 s buys is documented on the constant.
            pollIntervalMillis = 1,
            // The scheduler `runTest` advances, so `pump()` sees the poll's *result*
            // and not merely the request.
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            // No touching the real user's preferences from a test.
            rememberedServer = { "" },
            recordWorkingServer = {},
        )

    /** A controller that has already collected and opened a bundle, sitting on the SAS. */
    private fun TestScope.pairedController(): DesktopPairingController {
        val drop = FakeDrop()
        val controller = controller(drop)
        controller.editAddress("https://pair.example.test")
        controller.start()

        val phone = AccountDeviceSession(HkdfKeyDerivation, MonotonicClock { 0 }, bundle.ark, bundle.accountId)
        val accepted = phone.onScanned((controller.step as PairingStep.Waiting).code) as OfferOutcome.Accepted
        drop.deposit(phone.receivedSid!!, RendezvousSlot.BUNDLE, phone.seal("")!!)
        pump()
        check(controller.step is PairingStep.Confirming) { "setup failed: ${controller.step}" }
        return controller
    }

    /**
     * Run the poll loop for a while.
     *
     * **Not `advanceUntilIdle()`.** The loop lives in `backgroundScope`, and `advanceUntilIdle`
     * deliberately ignores background work — so against a controller whose only pending task is its
     * own poll, it advances nothing and returns immediately, leaving every assertion reading the
     * state as it was before the first request. (The obvious alternative is worse: were the loop in
     * the foreground, `advanceUntilIdle` would never return, because a poll loop reschedules itself
     * forever.) Moving virtual time by a bounded amount runs the loop a bounded number of times,
     * which is what these tests actually want.
     */
    private fun TestScope.pump() {
        runCurrent()
        advanceTimeBy(POLLS)
        runCurrent()
    }

    private class MovableClock(var now: Long = 0) : MonotonicClock {
        override fun elapsedMillis(): Long = now
    }

    private companion object {
        /**
         * Virtual milliseconds to advance when the point is "it kept polling".
         *
         * With `pollIntervalMillis = 1` this is about ten passes — enough to distinguish a loop
         * from a single shot, and a bounded amount of work either way.
         */
        const val POLLS = 10L
    }

    /** An in-memory drop with the server's own single-use rule, plus a call counter. */
    private class FakeDrop : RendezvousClient {
        private val rows = HashMap<String, String>()
        var collects = 0
            private set

        /** When set, every collect returns this instead of looking at [rows]. */
        var answer: CollectResult? = null

        override fun deposit(sid: ByteArray, slot: RendezvousSlot, code: String): DepositResult {
            rows[key(sid, slot)] = RendezvousProtocol.toBlob(code)
            return DepositResult.Deposited(0L)
        }

        override fun collect(sid: ByteArray, slot: RendezvousSlot): CollectResult {
            collects++
            answer?.let { return it }
            val blob = rows.remove(key(sid, slot)) ?: return CollectResult.Pending
            return CollectResult.Collected(RendezvousProtocol.fromBlob(blob))
        }

        private fun key(sid: ByteArray, slot: RendezvousSlot): String =
            RendezvousProtocol.encodeSid(sid) + slot.pathSuffix
    }
}
