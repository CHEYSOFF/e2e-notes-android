package my.cheysoff.feature_pairing

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import my.cheysoff.feature_pairing.di.PairingKeyMaterial
import my.cheysoff.feature_pairing.identity.DeviceIdentity
import my.cheysoff.feature_pairing.protocol.AccountBundle
import my.cheysoff.feature_pairing.protocol.AccountDeviceSession
import my.cheysoff.feature_pairing.protocol.NewDeviceSession
import my.cheysoff.feature_pairing.protocol.OfferOutcome
import my.cheysoff.feature_pairing.protocol.PairingFailure
import my.cheysoff.feature_pairing.protocol.PairingProtocol
import my.cheysoff.feature_pairing.ui.CameraPermission
import my.cheysoff.feature_pairing.ui.PairingIntent
import my.cheysoff.feature_pairing.ui.PairingRole
import my.cheysoff.feature_pairing.ui.PairingStage
import my.cheysoff.feature_pairing.ui.PairingViewModel
import my.cheysoff.feature_pairing.ui.ScanHint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The sequencing layer: role choice, which session is live, and the rule that matters most —
 * **nothing is committed until the user confirms the six digits**.
 *
 * The Compose screen itself is not tested (there is no Compose UI test infrastructure in this
 * project beyond `ExampleInstrumentedTest`, and a viewfinder needs a camera), but everything the
 * screen merely renders is decided here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PairingViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val ark = ByteArray(32) { (it + 1).toByte() }
    private val bundle = AccountBundle(ark, "acct-test", "{}")

    // -- availability -------------------------------------------------------------------------

    /**
     * The Phase-1 gate. On this branch `PairingKeyMaterial.isBound` is false, the screen says so,
     * and no session is ever started — which is what keeps the unbound `KeyDerivation` placeholder
     * unreachable rather than a runtime landmine.
     */
    @Test
    fun reportsUnavailableWhenThePhaseOneSeamIsUnbound() = runTest {
        val vm = viewModel(keyMaterial = FakeKeyMaterial(bound = false))
        assertFalse(vm.state.value.available)
        assertFalse(vm.state.value.canShareAccount)

        vm.onIntent(PairingIntent.RoleChosen(PairingRole.NewDevice))
        advanceUntilIdle()
        // Still on the chooser: no session, no derivation, nothing thrown.
        assertEquals(PairingStage.ChoosingRole, vm.state.value.stage)
    }

    /** A device with no ARK cannot play the account-holder role, and the chooser must know. */
    @Test
    fun aDeviceWithoutAnArkCannotShareAnAccount() = runTest {
        val withArk = viewModel(keyMaterial = FakeKeyMaterial(bound = true, bundle = bundle))
        assertTrue(withArk.state.value.canShareAccount)

        val withoutArk = viewModel(keyMaterial = FakeKeyMaterial(bound = true, bundle = null))
        assertTrue(withoutArk.state.value.available)
        assertFalse(withoutArk.state.value.canShareAccount)
    }

    /** ...and if the UI somehow offers it anyway, it fails rather than sealing something empty. */
    @Test
    fun choosingTheAccountRoleWithoutAnArkFails() = runTest {
        val vm = viewModel(keyMaterial = FakeKeyMaterial(bound = true, bundle = null))
        vm.onIntent(PairingIntent.RoleChosen(PairingRole.HasMyNotes))
        advanceUntilIdle()
        assertTrue(vm.state.value.stage is PairingStage.Failed)
    }

    // -- the new device's flow ----------------------------------------------------------------

    @Test
    fun theNewDeviceShowsAnOfferThenScansTheReplyThenConfirms() = runTest {
        val identity = FakeIdentity()
        val material = FakeKeyMaterial(bound = true, bundle = null)
        val clock = FakeClock()
        val vm = viewModel(keyMaterial = material, identity = identity, clock = clock)

        vm.onIntent(PairingIntent.RoleChosen(PairingRole.NewDevice))
        val showing = vm.state.value.stage as PairingStage.ShowingOffer
        assertEquals(120, showing.secondsRemaining)

        // The other phone -- a real AccountDeviceSession, not a stub -- answers.
        val accountDevice = AccountDeviceSession(TestHkdf, clock, bundle)
        val accepted = accountDevice.onScanned(showing.code) as OfferOutcome.Accepted

        vm.onIntent(PairingIntent.OfferShown)
        assertTrue(vm.state.value.stage is PairingStage.ScanningSeal)

        vm.onIntent(PairingIntent.CodeScanned(accepted.sealCode))
        val confirming = vm.state.value.stage as PairingStage.Confirming
        assertEquals(accepted.sas, confirming.sas)
        assertEquals(PairingRole.NewDevice, confirming.role)

        // Nothing stored yet. This is the load-bearing assertion of the whole class.
        assertNull(material.adopted)

        vm.onIntent(PairingIntent.SasConfirmed)
        advanceUntilIdle()
        assertArrayEquals(ark, material.adopted?.ark)
        assertEquals(1, identity.provisionCount)
        assertEquals(PairingStage.Finished(PairingRole.NewDevice), vm.state.value.stage)
    }

    /**
     * The user says the codes do not match.
     *
     * The ARK was already opened and is sitting in the ViewModel at that moment, so "discard it"
     * has to be a real code path rather than an absence of one.
     */
    @Test
    fun rejectingTheSasDiscardsTheReceivedAccountKey() = runTest {
        val identity = FakeIdentity()
        val material = FakeKeyMaterial(bound = true, bundle = null)
        val clock = FakeClock()
        val vm = viewModel(keyMaterial = material, identity = identity, clock = clock)

        vm.onIntent(PairingIntent.RoleChosen(PairingRole.NewDevice))
        val showing = vm.state.value.stage as PairingStage.ShowingOffer
        val accepted = AccountDeviceSession(TestHkdf, clock, bundle)
            .onScanned(showing.code) as OfferOutcome.Accepted
        vm.onIntent(PairingIntent.OfferShown)
        vm.onIntent(PairingIntent.CodeScanned(accepted.sealCode))
        assertTrue(vm.state.value.stage is PairingStage.Confirming)

        vm.onIntent(PairingIntent.SasRejected)
        advanceUntilIdle()

        assertNull("the ARK must not be stored", material.adopted)
        assertEquals(0, identity.provisionCount)
        val failed = vm.state.value.stage as PairingStage.Failed
        // A person stopped this, not the protocol -- so there is no protocol failure to report.
        assertNull(failed.failure)

        // And confirming afterwards cannot resurrect it.
        vm.onIntent(PairingIntent.SasConfirmed)
        advanceUntilIdle()
        assertNull(material.adopted)
    }

    // -- the account device's flow ------------------------------------------------------------

    @Test
    fun theAccountDeviceScansAnOfferThenShowsTheSealThenConfirms() = runTest {
        val identity = FakeIdentity()
        val material = FakeKeyMaterial(bound = true, bundle = bundle)
        val clock = FakeClock()
        val vm = viewModel(keyMaterial = material, identity = identity, clock = clock)

        vm.onIntent(PairingIntent.RoleChosen(PairingRole.HasMyNotes))
        assertTrue(vm.state.value.stage is PairingStage.ScanningOffer)

        val newDevice = NewDeviceSession(TestHkdf, clock)
        vm.onIntent(PairingIntent.CodeScanned(newDevice.offerCode))
        val showing = vm.state.value.stage as PairingStage.ShowingSeal
        assertEquals(120, showing.secondsRemaining)

        // The code on screen really is one the new device can open.
        assertTrue(newDevice.onScanned(showing.code) is my.cheysoff.feature_pairing.protocol.SealOutcome.Paired)

        vm.onIntent(PairingIntent.SealShown)
        val confirming = vm.state.value.stage as PairingStage.Confirming
        assertEquals(showing.sas, confirming.sas)
        assertEquals(PairingRole.HasMyNotes, confirming.role)

        vm.onIntent(PairingIntent.SasConfirmed)
        advanceUntilIdle()
        // The account device stores nothing -- it already had the ARK -- but it does provision its
        // identity key, because it has just joined a two-device account.
        assertNull(material.adopted)
        assertEquals(1, identity.provisionCount)
        assertEquals(PairingStage.Finished(PairingRole.HasMyNotes), vm.state.value.stage)
    }

    // -- scanning noise -----------------------------------------------------------------------

    /** Every symbol in view reaches the ViewModel. The ordinary ones must not disturb anything. */
    @Test
    fun unrelatedCodesLeaveTheStageAlone() = runTest {
        val clock = FakeClock()
        val vm = viewModel(keyMaterial = FakeKeyMaterial(bound = true, bundle = bundle), clock = clock)
        vm.onIntent(PairingIntent.RoleChosen(PairingRole.HasMyNotes))

        for (noise in listOf("https://example.com", "hello", "MNP1:@@@")) {
            vm.onIntent(PairingIntent.CodeScanned(noise))
            val stage = vm.state.value.stage as PairingStage.ScanningOffer
            assertNull("$noise produced a hint", stage.lastHint)
        }
        assertTrue(vm.state.value.stage is PairingStage.ScanningOffer)
    }

    /** A pairing code from another session becomes a hint, not a dead session. */
    @Test
    fun aCodeFromAnotherSessionBecomesAHint() = runTest {
        val clock = FakeClock()
        val material = FakeKeyMaterial(bound = true, bundle = null)
        val vm = viewModel(keyMaterial = material, clock = clock)

        vm.onIntent(PairingIntent.RoleChosen(PairingRole.NewDevice))
        vm.onIntent(PairingIntent.OfferShown)

        // A complete pairing between two unrelated sessions, replayed at this one.
        val strangerOffer = NewDeviceSession(TestHkdf, clock)
        val strangerSeal = AccountDeviceSession(TestHkdf, clock, bundle)
            .onScanned(strangerOffer.offerCode) as OfferOutcome.Accepted

        vm.onIntent(PairingIntent.CodeScanned(strangerSeal.sealCode))
        val stage = vm.state.value.stage as PairingStage.ScanningSeal
        assertEquals(ScanHint.OtherSession, stage.lastHint)
        assertNull(material.adopted)

        // Stops the ViewModel's countdown loop, which would otherwise still be queued on the test
        // scheduler when the test body ends.
        vm.onIntent(PairingIntent.StartOver)
    }

    /** The other step's code is a sequencing hint. */
    @Test
    fun theWrongStepsCodeBecomesAHint() = runTest {
        val clock = FakeClock()
        val vm = viewModel(keyMaterial = FakeKeyMaterial(bound = true, bundle = null), clock = clock)
        vm.onIntent(PairingIntent.RoleChosen(PairingRole.NewDevice))
        val showing = vm.state.value.stage as PairingStage.ShowingOffer
        vm.onIntent(PairingIntent.OfferShown)

        // Its own QR1 shown back at it.
        vm.onIntent(PairingIntent.CodeScanned(showing.code))
        assertEquals(ScanHint.WrongStep, (vm.state.value.stage as PairingStage.ScanningSeal).lastHint)

        vm.onIntent(PairingIntent.StartOver)
    }

    /** A tampered seal is terminal and reaches the user as a message. */
    @Test
    fun aTamperedSealEndsTheAttempt() = runTest {
        val clock = FakeClock()
        val material = FakeKeyMaterial(bound = true, bundle = null)
        val vm = viewModel(keyMaterial = material, clock = clock)

        vm.onIntent(PairingIntent.RoleChosen(PairingRole.NewDevice))
        val showing = vm.state.value.stage as PairingStage.ShowingOffer
        val accepted = AccountDeviceSession(TestHkdf, clock, bundle)
            .onScanned(showing.code) as OfferOutcome.Accepted
        vm.onIntent(PairingIntent.OfferShown)

        val frame = java.util.Base64.getUrlDecoder()
            .decode(accepted.sealCode.removePrefix(PairingProtocol.QR_PREFIX))
        frame[110] = (frame[110].toInt() xor 0x01).toByte()
        val tampered = PairingProtocol.QR_PREFIX +
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(frame)

        vm.onIntent(PairingIntent.CodeScanned(tampered))
        val failed = vm.state.value.stage as PairingStage.Failed
        assertEquals(PairingFailure.SEAL_REJECTED, failed.failure)
        assertTrue(failed.message.isNotBlank())
        assertNull(material.adopted)
    }

    // -- countdown and expiry -----------------------------------------------------------------

    /** The on-screen counter ticks down once a second. */
    @Test
    fun theCountdownTicks() = runTest(mainDispatcherRule.dispatcher) {
        val clock = FakeClock()
        val vm = viewModel(keyMaterial = FakeKeyMaterial(bound = true, bundle = null), clock = clock)
        vm.onIntent(PairingIntent.RoleChosen(PairingRole.NewDevice))
        assertEquals(120, (vm.state.value.stage as PairingStage.ShowingOffer).secondsRemaining)

        clock.advance(30_000)
        advanceTimeBy(1_100)
        assertEquals(90, (vm.state.value.stage as PairingStage.ShowingOffer).secondsRemaining)

        vm.onIntent(PairingIntent.StartOver)
    }

    /** When the code runs out the screen says so rather than silently going dead. */
    @Test
    fun anExpiredCodeFailsTheAttempt() = runTest(mainDispatcherRule.dispatcher) {
        val clock = FakeClock()
        val vm = viewModel(keyMaterial = FakeKeyMaterial(bound = true, bundle = null), clock = clock)
        vm.onIntent(PairingIntent.RoleChosen(PairingRole.NewDevice))

        clock.advance(PairingProtocol.CODE_TTL_MILLIS)
        advanceTimeBy(1_100)

        val failed = vm.state.value.stage as PairingStage.Failed
        assertEquals(PairingFailure.EXPIRED, failed.failure)
    }

    // -- housekeeping -------------------------------------------------------------------------

    @Test
    fun startingOverReturnsToTheChooser() = runTest {
        val vm = viewModel(keyMaterial = FakeKeyMaterial(bound = true, bundle = bundle))
        vm.onIntent(PairingIntent.RoleChosen(PairingRole.HasMyNotes))
        assertTrue(vm.state.value.stage is PairingStage.ScanningOffer)

        vm.onIntent(PairingIntent.StartOver)
        advanceUntilIdle()
        assertEquals(PairingStage.ChoosingRole, vm.state.value.stage)
        assertTrue(vm.state.value.canShareAccount)
    }

    @Test
    fun cameraPermissionResultsMapToTheThreeStates() = runTest {
        val vm = viewModel(keyMaterial = FakeKeyMaterial(bound = true, bundle = bundle))
        assertEquals(CameraPermission.Unknown, vm.state.value.cameraPermission)

        vm.onIntent(PairingIntent.CameraPermissionChanged(granted = false, permanentlyDenied = false))
        assertEquals(CameraPermission.Denied, vm.state.value.cameraPermission)

        vm.onIntent(PairingIntent.CameraPermissionChanged(granted = false, permanentlyDenied = true))
        assertEquals(CameraPermission.PermanentlyDenied, vm.state.value.cameraPermission)

        vm.onIntent(PairingIntent.CameraPermissionChanged(granted = true, permanentlyDenied = false))
        assertEquals(CameraPermission.Granted, vm.state.value.cameraPermission)
    }

    /** Codes that arrive after a transition are dropped rather than fed to a finished session. */
    @Test
    fun codesArrivingOutsideAScanningStageAreIgnored() = runTest {
        val clock = FakeClock()
        val vm = viewModel(keyMaterial = FakeKeyMaterial(bound = true, bundle = bundle), clock = clock)
        vm.onIntent(PairingIntent.CodeScanned(NewDeviceSession(TestHkdf, clock).offerCode))
        assertEquals(PairingStage.ChoosingRole, vm.state.value.stage)
    }

    // -- fakes --------------------------------------------------------------------------------

    private fun viewModel(
        keyMaterial: PairingKeyMaterial,
        identity: FakeIdentity = FakeIdentity(),
        clock: FakeClock = FakeClock(),
    ) = PairingViewModel(
        keyDerivation = TestHkdf,
        keyMaterial = keyMaterial,
        clock = clock,
        deviceIdentity = identity,
    )

    private class FakeKeyMaterial(
        bound: Boolean,
        private val bundle: AccountBundle? = null,
    ) : PairingKeyMaterial {
        override val isBound = bound
        var adopted: AccountBundle? = null
            private set

        override fun accountBundle(): AccountBundle? = bundle

        override fun adopt(bundle: AccountBundle) {
            adopted = bundle
        }
    }

    private class FakeIdentity : DeviceIdentity {
        var provisionCount = 0
            private set

        override fun ensureProvisioned(): ByteArray {
            provisionCount++
            return ByteArray(65)
        }

        override fun isProvisioned(): Boolean = provisionCount > 0
    }
}
