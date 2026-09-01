package my.cheysoff.feature_pairing

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import my.cheysoff.feature_pairing.di.PairingKeyMaterial
import my.cheysoff.feature_pairing.identity.DeviceEnroller
import my.cheysoff.feature_pairing.identity.DeviceIdentity
import my.cheysoff.feature_pairing.identity.EnrolmentResult
import my.cheysoff.core_pairing.protocol.AccountBundle
import my.cheysoff.core_pairing.protocol.AccountDeviceSession
import my.cheysoff.core_pairing.protocol.AccountInviteSession
import my.cheysoff.core_pairing.protocol.CollectResult
import my.cheysoff.core_pairing.protocol.DepositResult
import my.cheysoff.core_pairing.protocol.HkdfKeyDerivation
import my.cheysoff.core_pairing.protocol.NewDeviceSession
import my.cheysoff.core_pairing.protocol.PairingConfig
import my.cheysoff.core_pairing.protocol.PairingFailure
import my.cheysoff.core_pairing.protocol.PairingProtocol
import my.cheysoff.core_pairing.protocol.RendezvousClient
import my.cheysoff.core_pairing.protocol.RendezvousClientFactory
import my.cheysoff.core_pairing.protocol.RendezvousSlot
import my.cheysoff.core_pairing.protocol.P256
import my.cheysoff.core_pairing.protocol.RendezvousUrl
import my.cheysoff.core_pairing.protocol.ReplyOutcome
import my.cheysoff.core_pairing.protocol.SealOutcome
import my.cheysoff.core_pairing.protocol.ServerHint
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
     * The `PairingKeyMaterial.isBound` gate. When it is false the screen says so and no session is
     * ever started. No shipped build has it false any more, but the branch is the backstop for one
     * that does, so it stays covered.
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

    /**
     * Drawing the chooser must not create an account key.
     *
     * `accountBundle()` mints the ARK on a device that has none — that is how the very first
     * device gets one — so where it is called from is a real decision and not a detail. Calling it
     * to populate the role chooser would mean opening the pairing screen created an account, on a
     * phone whose user may be about to choose "this is my new phone" and adopt someone else's.
     * The chooser therefore reads `canShareAccount()`, which is a pure query.
     */
    @Test
    fun openingTheChooserDoesNotAskForAnAccountBundle() = runTest {
        val keyMaterial = FakeKeyMaterial(bound = true, bundle = bundle)
        val vm = viewModel(keyMaterial = keyMaterial)

        assertTrue(vm.state.value.canShareAccount)
        assertEquals(0, keyMaterial.bundleRequests)

        vm.onIntent(PairingIntent.StartOver)
        advanceUntilIdle()
        assertEquals(0, keyMaterial.bundleRequests)

        // ...and it IS asked for once the user commits to the role that shares one.
        vm.onIntent(PairingIntent.RoleChosen(PairingRole.HasMyNotes))
        advanceUntilIdle()
        assertEquals(1, keyMaterial.bundleRequests)
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
        val accountDevice = AccountDeviceSession(HkdfKeyDerivation, clock, bundle.ark, bundle.accountId)
        val accepted = accountDevice.accept(showing.code)!!

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
        val accepted = AccountDeviceSession(HkdfKeyDerivation, clock, bundle.ark, bundle.accountId)
            .accept(showing.code)!!
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

        val newDevice = NewDeviceSession(HkdfKeyDerivation, clock)
        vm.onIntent(PairingIntent.CodeScanned(newDevice.offerCode))
        val showing = vm.state.value.stage as PairingStage.ShowingSeal
        assertEquals(120, showing.secondsRemaining)

        // The code on screen really is one the new device can open.
        assertTrue(newDevice.onScanned(showing.code) is my.cheysoff.core_pairing.protocol.SealOutcome.Paired)

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
        val strangerOffer = NewDeviceSession(HkdfKeyDerivation, clock)
        val strangerSeal = AccountDeviceSession(HkdfKeyDerivation, clock, bundle.ark, bundle.accountId)
            .accept(strangerOffer.offerCode)!!

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
        val accepted = AccountDeviceSession(HkdfKeyDerivation, clock, bundle.ark, bundle.accountId)
            .accept(showing.code)!!
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
        vm.onIntent(PairingIntent.CodeScanned(NewDeviceSession(HkdfKeyDerivation, clock).offerCode))
        assertEquals(PairingStage.ChoosingRole, vm.state.value.stage)
    }

    // -- pairing with a computer ---------------------------------------------------------------

    /**
     * A QR1 carrying a server address routes the seal to the rendezvous instead of to a QR code.
     *
     * The whole of the new branch. Note what is asserted about *when* the request happens: nothing
     * is sent on scanning, only on [PairingIntent.SendSeal], because the address arrived
     * unauthenticated inside the code that was just scanned.
     */
    @Test
    fun anOfferNamingAServerSendsTheSealInsteadOfShowingIt() = runTest {
        val material = FakeKeyMaterial(bound = true, bundle = bundle)
        val rendezvous = FakeRendezvous()
        val clock = FakeClock()
        val vm = viewModel(keyMaterial = material, clock = clock, rendezvous = rendezvous)

        vm.onIntent(PairingIntent.RoleChosen(PairingRole.HasMyNotes))
        val computer = NewDeviceSession(
            HkdfKeyDerivation, clock, ServerHint(url = "https://pair.example.test"),
        )
        vm.onIntent(PairingIntent.CodeScanned(computer.offerCode))

        val sending = vm.state.value.stage as PairingStage.SendingSeal
        assertEquals("pair.example.test", sending.host)
        assertTrue(sending.secure)
        assertFalse(sending.sending)
        assertTrue("nothing may be sent before the user asks", rendezvous.deposits.isEmpty())

        vm.onIntent(PairingIntent.SendSeal)
        advanceUntilIdle()

        assertEquals(1, rendezvous.deposits.size)
        val (server, sid, sealCode) = rendezvous.deposits.single()
        assertEquals("https://pair.example.test", server.base)
        // Filed under the *computer's* sid, which is the name it will ask for and the value bound
        // into both the HKDF salt and the GCM AAD.
        assertArrayEquals(computer.sid, sid)

        // What travels is exactly the QR2 payload -- the computer feeds it to the same
        // `onScanned` a camera would have.
        assertTrue(sealCode.startsWith(PairingProtocol.QR_PREFIX))
        assertTrue(computer.onScanned(sealCode) is SealOutcome.Paired)

        val confirming = vm.state.value.stage as PairingStage.Confirming
        assertEquals(sending.sas, confirming.sas)
        assertEquals(PairingRole.HasMyNotes, confirming.role)
    }

    /**
     * The key that gets vouched for is the key that arrived in QR1, and the id that comes back is
     * sealed into the bundle.
     *
     * Both halves matter and both are silent when wrong. Enrolling a *different* key would enrol a
     * device nobody pointed a camera at - the visual channel is the only thing authenticating this
     * exchange, so a key from any other source is a key of the server's choosing. And an id that
     * does not reach the joining device leaves it holding the account key and unable to open a
     * single session, which is exactly the state this whole path exists to end.
     */
    @Test
    fun theKeyFromQr1IsVouchedForAndTheAssignedIdIsSealedIn() = runTest {
        val enroller = FakeEnroller()
        val rendezvous = FakeRendezvous()
        val clock = FakeClock()
        val vm = viewModel(
            keyMaterial = FakeKeyMaterial(bound = true, bundle = bundle),
            clock = clock,
            rendezvous = rendezvous,
            enroller = enroller,
        )

        val deviceKey = aDeviceKey()
        val computer = NewDeviceSession(
            HkdfKeyDerivation, clock, ServerHint(url = "https://pair.example.test"), deviceKey,
        )
        vm.onIntent(PairingIntent.RoleChosen(PairingRole.HasMyNotes))
        vm.onIntent(PairingIntent.CodeScanned(computer.offerCode))

        // Nothing is enrolled before the user asks, for the same reason nothing is deposited: the
        // address arrived unauthenticated and the host is on screen waiting to be approved.
        assertTrue(enroller.requests.isEmpty())

        vm.onIntent(PairingIntent.SendSeal)
        advanceUntilIdle()

        val (server, vouchedKey, label) = enroller.requests.single()
        assertEquals("https://pair.example.test", server.base)
        assertArrayEquals("the vouched key must be the one QR1 carried", deviceKey, vouchedKey)
        assertEquals("Computer", label)

        // The far side opens the bundle the way it really would, and finds both values.
        val paired = computer.onScanned(rendezvous.deposits.single().third) as SealOutcome.Paired
        val config = PairingConfig.decode(paired.bundle.config)!!
        assertEquals("https://pair.example.test", config.serverUrl)
        assertEquals("srv-device-7", config.deviceId)
    }

    /**
     * A refused enrolment stops before the bundle is deposited, and is retriable.
     *
     * Deliberately not "seal it anyway without an id". A bundle that names a server the joining
     * device cannot authenticate to produces a device that pairs, looks fine, and never syncs -
     * discovered days later, on the device that by then holds notes nothing else has.
     */
    @Test
    fun aRefusedEnrolmentDepositsNothingAndCanBeRetried() = runTest {
        var attempts = 0
        val enroller = FakeEnroller {
            attempts++
            if (attempts == 1) EnrolmentResult.Refused("the server said no") else
                EnrolmentResult.Enrolled("srv-device-7")
        }
        val rendezvous = FakeRendezvous()
        val clock = FakeClock()
        val vm = viewModel(
            keyMaterial = FakeKeyMaterial(bound = true, bundle = bundle),
            clock = clock,
            rendezvous = rendezvous,
            enroller = enroller,
        )

        vm.onIntent(PairingIntent.RoleChosen(PairingRole.HasMyNotes))
        vm.onIntent(
            PairingIntent.CodeScanned(
                NewDeviceSession(
                    HkdfKeyDerivation, clock, ServerHint(url = "https://pair.example.test"),
                    aDeviceKey(),
                ).offerCode
            )
        )
        vm.onIntent(PairingIntent.SendSeal)
        advanceUntilIdle()

        assertTrue("nothing may be deposited after a refused vouch", rendezvous.deposits.isEmpty())
        val stage = vm.state.value.stage as PairingStage.SendingSeal
        assertFalse(stage.sending)
        assertEquals("the server said no", stage.message)

        vm.onIntent(PairingIntent.SendSeal)
        advanceUntilIdle()
        assertEquals(1, rendezvous.deposits.size)
        assertTrue(vm.state.value.stage is PairingStage.Confirming)
    }

    /**
     * A retry after a failed deposit re-sends the same bytes and does not vouch a second time.
     *
     * The server refuses a second `authorize` for a key it has already enrolled, so a retry that
     * re-vouched would turn one dropped connection into a pairing that can never complete.
     */
    @Test
    fun retryingASendDoesNotVouchAgain() = runTest {
        var attempts = 0
        val rendezvous = FakeRendezvous { _, _ ->
            attempts++
            if (attempts == 1) DepositResult.Unreachable("timeout") else DepositResult.Deposited(0L)
        }
        val enroller = FakeEnroller()
        val clock = FakeClock()
        val vm = viewModel(
            keyMaterial = FakeKeyMaterial(bound = true, bundle = bundle),
            clock = clock,
            rendezvous = rendezvous,
            enroller = enroller,
        )

        vm.onIntent(PairingIntent.RoleChosen(PairingRole.HasMyNotes))
        vm.onIntent(
            PairingIntent.CodeScanned(
                NewDeviceSession(
                    HkdfKeyDerivation, clock, ServerHint(url = "https://pair.example.test"),
                    aDeviceKey(),
                ).offerCode
            )
        )
        vm.onIntent(PairingIntent.SendSeal)
        advanceUntilIdle()
        vm.onIntent(PairingIntent.SendSeal)
        advanceUntilIdle()

        assertEquals("the vouch must happen once", 1, enroller.requests.size)
        assertEquals(2, rendezvous.deposits.size)
        assertEquals(
            "a retry re-sends the same bytes",
            rendezvous.deposits[0].third,
            rendezvous.deposits[1].third,
        )
        assertTrue(vm.state.value.stage is PairingStage.Confirming)
    }

    /**
     * A QR1 that names a server but offers no device key is sealed with a server and no id.
     *
     * That is what a build older than the device-key field produces, and the right answer is to
     * pair anyway: the ARK still crosses, the joining device still learns the address, and what it
     * does not get is a session. Refusing the whole pairing would be a worse trade than a device
     * that syncs once someone enrols it.
     */
    @Test
    fun anOfferWithNoDeviceKeyEnrolsNothingAndSealsNoId() = runTest {
        val enroller = FakeEnroller()
        val rendezvous = FakeRendezvous()
        val clock = FakeClock()
        val vm = viewModel(
            keyMaterial = FakeKeyMaterial(bound = true, bundle = bundle),
            clock = clock,
            rendezvous = rendezvous,
            enroller = enroller,
        )

        val computer = NewDeviceSession(
            HkdfKeyDerivation, clock, ServerHint(url = "https://pair.example.test"),
        )
        vm.onIntent(PairingIntent.RoleChosen(PairingRole.HasMyNotes))
        vm.onIntent(PairingIntent.CodeScanned(computer.offerCode))
        vm.onIntent(PairingIntent.SendSeal)
        advanceUntilIdle()

        assertTrue("there is no key to vouch for", enroller.requests.isEmpty())
        val paired = computer.onScanned(rendezvous.deposits.single().third) as SealOutcome.Paired
        val config = PairingConfig.decode(paired.bundle.config)!!
        assertEquals("https://pair.example.test", config.serverUrl)
        assertNull(config.deviceId)
    }

    /**
     * **The phone-to-phone flow opens no socket.** This is the regression test for the constraint
     * that mattered most: the rendezvous is an additional path, not a replacement.
     */
    @Test
    fun pairingWithAnotherPhoneNeverTouchesTheNetwork() = runTest {
        val rendezvous = FakeRendezvous()
        val enroller = FakeEnroller()
        val clock = FakeClock()
        val vm = viewModel(
            keyMaterial = FakeKeyMaterial(bound = true, bundle = bundle),
            clock = clock,
            rendezvous = rendezvous,
            enroller = enroller,
        )

        vm.onIntent(PairingIntent.RoleChosen(PairingRole.HasMyNotes))
        // A phone's QR1 carries ServerHint.NONE, which is what NewDeviceSession still defaults to.
        vm.onIntent(PairingIntent.CodeScanned(NewDeviceSession(HkdfKeyDerivation, clock).offerCode))

        assertTrue(vm.state.value.stage is PairingStage.ShowingSeal)
        assertTrue(rendezvous.deposits.isEmpty())
        assertTrue("a phone has nothing to enrol on", enroller.requests.isEmpty())

        vm.onIntent(PairingIntent.SealShown)
        vm.onIntent(PairingIntent.SasConfirmed)
        advanceUntilIdle()
        assertTrue(rendezvous.deposits.isEmpty())
        assertTrue(enroller.requests.isEmpty())
    }

    /**
     * A plain `http://` address is refused with a sentence rather than left to fail in the socket.
     *
     * Android has blocked cleartext HTTP by default since it started targeting API 28, so this
     * would otherwise surface as an unexplained "cannot reach the server" that no amount of
     * retrying fixes.
     */
    @Test
    fun aCleartextServerIsRefusedBeforeAnyRequestIsMade() = runTest {
        val rendezvous = FakeRendezvous()
        val clock = FakeClock()
        val vm = viewModel(
            keyMaterial = FakeKeyMaterial(bound = true, bundle = bundle),
            clock = clock,
            rendezvous = rendezvous,
        )

        vm.onIntent(PairingIntent.RoleChosen(PairingRole.HasMyNotes))
        vm.onIntent(
            PairingIntent.CodeScanned(
                NewDeviceSession(HkdfKeyDerivation, clock, ServerHint(url = "http://10.0.0.5:8080")).offerCode
            )
        )

        val stage = vm.state.value.stage as PairingStage.SendingSeal
        assertFalse(stage.secure)

        vm.onIntent(PairingIntent.SendSeal)
        advanceUntilIdle()

        assertTrue(rendezvous.deposits.isEmpty())
        val after = vm.state.value.stage as PairingStage.SendingSeal
        assertTrue(after.message!!.contains("https://"))
    }

    /** A failed send is retriable and does not kill the attempt: a phone's connection drops. */
    @Test
    fun aFailedSendKeepsTheAttemptAliveAndCanBeRetried() = runTest {
        var attempts = 0
        val rendezvous = FakeRendezvous { _, _ ->
            attempts++
            if (attempts == 1) DepositResult.Unreachable("timeout") else DepositResult.Deposited(0L)
        }
        val clock = FakeClock()
        val vm = viewModel(
            keyMaterial = FakeKeyMaterial(bound = true, bundle = bundle),
            clock = clock,
            rendezvous = rendezvous,
        )

        vm.onIntent(PairingIntent.RoleChosen(PairingRole.HasMyNotes))
        vm.onIntent(
            PairingIntent.CodeScanned(
                NewDeviceSession(HkdfKeyDerivation, clock, ServerHint(url = "https://pair.example.test")).offerCode
            )
        )

        vm.onIntent(PairingIntent.SendSeal)
        advanceUntilIdle()
        val failed = vm.state.value.stage as PairingStage.SendingSeal
        assertFalse(failed.sending)
        assertTrue(failed.message!!.contains("timeout"))

        vm.onIntent(PairingIntent.SendSeal)
        advanceUntilIdle()
        assertTrue(vm.state.value.stage is PairingStage.Confirming)
    }

    /**
     * A `409` moves on rather than stopping.
     *
     * Either a previous attempt landed and its response was lost — in which case the computer has
     * the bundle and comparing digits is exactly right — or somebody else got there first, in which
     * case the computer collects something it cannot open and aborts loudly. The SAS comparison is
     * the check for both, which is what it is for.
     */
    @Test
    fun anAlreadyDepositedResponseProceedsToTheSasComparison() = runTest {
        val rendezvous = FakeRendezvous { _, _ -> DepositResult.AlreadyDeposited }
        val clock = FakeClock()
        val vm = viewModel(
            keyMaterial = FakeKeyMaterial(bound = true, bundle = bundle),
            clock = clock,
            rendezvous = rendezvous,
        )

        vm.onIntent(PairingIntent.RoleChosen(PairingRole.HasMyNotes))
        vm.onIntent(
            PairingIntent.CodeScanned(
                NewDeviceSession(HkdfKeyDerivation, clock, ServerHint(url = "https://pair.example.test")).offerCode
            )
        )
        vm.onIntent(PairingIntent.SendSeal)
        advanceUntilIdle()

        assertTrue(vm.state.value.stage is PairingStage.Confirming)
    }

    /** An unusable address in QR1 falls back to the QR code rather than to a broken send. */
    @Test
    fun anUnparseableServerHintFallsBackToShowingTheCode() = runTest {
        val rendezvous = FakeRendezvous()
        val clock = FakeClock()
        val vm = viewModel(
            keyMaterial = FakeKeyMaterial(bound = true, bundle = bundle),
            clock = clock,
            rendezvous = rendezvous,
        )

        vm.onIntent(PairingIntent.RoleChosen(PairingRole.HasMyNotes))
        vm.onIntent(
            PairingIntent.CodeScanned(
                NewDeviceSession(HkdfKeyDerivation, clock, ServerHint(url = "file:///etc/passwd")).offerCode
            )
        )

        assertTrue(vm.state.value.stage is PairingStage.ShowingSeal)
        assertTrue(rendezvous.deposits.isEmpty())

        // `ShowingSeal` starts the countdown, which is a `delay` loop on the test dispatcher.
        // `runTest` drains the scheduler when the body returns, and a loop that reschedules itself
        // forever means it never finishes draining -- so the test hangs rather than fails. Every
        // other test in this file that ends on a stage with a live countdown does the same thing.
        vm.onIntent(PairingIntent.StartOver)
    }

    /** Starting over drops the sealed bundle rather than leaving it available to a later send. */
    @Test
    fun startingOverDiscardsTheUnsentSeal() = runTest {
        val rendezvous = FakeRendezvous()
        val clock = FakeClock()
        val vm = viewModel(
            keyMaterial = FakeKeyMaterial(bound = true, bundle = bundle),
            clock = clock,
            rendezvous = rendezvous,
        )

        vm.onIntent(PairingIntent.RoleChosen(PairingRole.HasMyNotes))
        vm.onIntent(
            PairingIntent.CodeScanned(
                NewDeviceSession(HkdfKeyDerivation, clock, ServerHint(url = "https://pair.example.test")).offerCode
            )
        )
        vm.onIntent(PairingIntent.StartOver)
        vm.onIntent(PairingIntent.SendSeal)
        advanceUntilIdle()

        assertTrue(rendezvous.deposits.isEmpty())
        assertEquals(PairingStage.ChoosingRole, vm.state.value.stage)
    }

    // -- joining an account held by a computer ------------------------------------------------
    //
    // Every test below tears its ViewModel down in a `finally`, and that is not tidiness. The stage
    // this direction ends on polls the bundle slot every 1.5 s, and `runTest` does not return while
    // a coroutine on its scheduler keeps rescheduling itself -- so a build that started that loop
    // at the wrong moment would HANG the suite rather than fail a test, and a hang names nothing.
    // `stopPolling` cancels the loop, and putting it in a `finally` means an assertion that has
    // already failed still gets its name into the report.

    /**
     * The whole invite direction, with a real `AccountInviteSession` playing the computer.
     *
     * The order under test is the direction's security property: this phone sends only public
     * values, both sides show the same six digits, and the account key does not move until a person
     * confirms. Every step here is driven by an intent, because every step is meant to be a thing
     * the user does.
     */
    @Test
    fun joiningFromAComputerSendsOnlyTheReplyAndOnlyAfterTheDigitsAgree() = runTest {
        val rendezvous = FakeRendezvous()
        val identity = FakeIdentity()
        val keyMaterial = FakeKeyMaterial(bound = true)
        val clock = FakeClock()
        val vm = viewModel(
            keyMaterial = keyMaterial,
            identity = identity,
            clock = clock,
            rendezvous = rendezvous,
        )
        try {

            val computer = AccountInviteSession(
                keyDerivation = HkdfKeyDerivation,
                clock = clock,
                server = RendezvousUrl.parse("https://pair.example.test")!!,
            )

            vm.onIntent(PairingIntent.RoleChosen(PairingRole.JoinFromComputer))
            assertTrue(vm.state.value.stage is PairingStage.ScanningInvite)
            assertTrue("nothing is sent by reading a code", rendezvous.deposits.isEmpty())

            vm.onIntent(PairingIntent.CodeScanned(computer.inviteCode))
            val answering = vm.state.value.stage as PairingStage.AnsweringInvite
            assertEquals("pair.example.test", answering.host)
            assertTrue(answering.secure)
            assertTrue("the send is an act, not a consequence of scanning", rendezvous.deposits.isEmpty())

            vm.onIntent(PairingIntent.SendReply)
            // runCurrent(), not advanceUntilIdle(): see `pumpJoining`'s note.
            runCurrent()

            assertEquals(listOf(RendezvousSlot.REPLY), rendezvous.depositedSlots)
            val confirming = vm.state.value.stage as PairingStage.Confirming
            assertEquals(PairingRole.JoinFromComputer, confirming.role)

            // The computer's half, run for real against what this phone deposited.
            val agreed = computer.onReply(rendezvous.deposits.single().third) as ReplyOutcome.Agreed
            assertEquals("the two screens must show the same digits", confirming.sas, agreed.sas)

            // Nothing has been asked for yet: the account key is not even sealed on the other side.
            assertTrue(rendezvous.collectedSlots.isEmpty())

            val sealCode = computer.confirm()!!.seal(bundle.ark, bundle.accountId, PairingConfig.encode(
                serverUrl = "https://pair.example.test",
                deviceId = "srv-phone-3",
            ))!!
            rendezvous.collectAnswer = { _, slot ->
                if (slot == RendezvousSlot.BUNDLE) CollectResult.Collected(sealCode)
                else CollectResult.Pending
            }

            vm.onIntent(PairingIntent.SasConfirmed)
            runCurrent()

            assertEquals(listOf(RendezvousSlot.BUNDLE), rendezvous.collectedSlots)
            assertEquals(PairingStage.Finished(PairingRole.JoinFromComputer), vm.state.value.stage)
            assertArrayEquals(bundle.ark, keyMaterial.adopted!!.ark)
            // The config the phone needs to sync, which has no other channel to travel on.
            val config = PairingConfig.decode(keyMaterial.adopted!!.config)!!
            assertEquals("https://pair.example.test", config.serverUrl)
            assertEquals("srv-phone-3", config.deviceId)
        } finally {
            stopPolling(vm)
        }
    }

    /**
     * **The account key is not asked for until the user confirms the digits.**
     *
     * The mirror of the desktop's own ordering test. Confirming is what starts the collect, so a
     * build that polled from the moment the reply landed would be treating the comparison as
     * decoration — and in this direction the comparison is the only defence there is.
     */
    @Test
    fun theAccountKeyIsNotCollectedUntilTheSasIsConfirmed() = runTest {
        val rendezvous = FakeRendezvous()
        val clock = FakeClock()
        val vm = viewModel(
            keyMaterial = FakeKeyMaterial(bound = true),
            clock = clock,
            rendezvous = rendezvous,
        )
        try {
            val computer = AccountInviteSession(
                HkdfKeyDerivation, clock, RendezvousUrl.parse("https://pair.example.test")!!,
            )

            vm.onIntent(PairingIntent.RoleChosen(PairingRole.JoinFromComputer))
            vm.onIntent(PairingIntent.CodeScanned(computer.inviteCode))
            vm.onIntent(PairingIntent.SendReply)
            // runCurrent(), not advanceUntilIdle(). A build that started collecting here would poll
            // every 1.5 s for ever, and advanceUntilIdle() would spin virtual time rather than return
            // -- so the mutant would hang the suite instead of failing this test by name. Draining only
            // what is scheduled now is also exactly the question being asked: what has this phone done
            // by the time the digits are on screen?
            runCurrent()

            assertTrue(vm.state.value.stage is PairingStage.Confirming)
            assertTrue(
                "the bundle slot must not be polled before the user confirms",
                rendezvous.collectedSlots.isEmpty(),
            )
        } finally {
            stopPolling(vm)
        }
    }

    /** Saying the digits differ discards everything and asks the server for nothing. */
    @Test
    fun rejectingTheDigitsOnTheJoiningSideCollectsNothing() = runTest {
        val rendezvous = FakeRendezvous()
        val keyMaterial = FakeKeyMaterial(bound = true)
        val clock = FakeClock()
        val vm = viewModel(keyMaterial = keyMaterial, clock = clock, rendezvous = rendezvous)
        val computer = AccountInviteSession(
            HkdfKeyDerivation, clock, RendezvousUrl.parse("https://pair.example.test")!!,
        )
        try {

            vm.onIntent(PairingIntent.RoleChosen(PairingRole.JoinFromComputer))
            vm.onIntent(PairingIntent.CodeScanned(computer.inviteCode))
            vm.onIntent(PairingIntent.SendReply)
            runCurrent()
            vm.onIntent(PairingIntent.SasRejected)
            runCurrent()

            assertTrue(vm.state.value.stage is PairingStage.Failed)
            assertTrue(rendezvous.collectedSlots.isEmpty())
            assertNull("nothing may be adopted after a rejection", keyMaterial.adopted)
        } finally {
            stopPolling(vm)
        }
    }

    /**
     * The key this phone offers is its own Keystore key, and nothing else.
     *
     * The computer will vouch for exactly what arrives in this reply, so a phone that sent any
     * other key would be asking a server to trust something it does not hold the private half of —
     * and would then fail every session handshake it ever made.
     */
    @Test
    fun theReplyCarriesThisPhonesOwnDeviceKey() = runTest {
        val rendezvous = FakeRendezvous()
        val identity = FakeIdentity()
        val clock = FakeClock()
        val vm = viewModel(
            keyMaterial = FakeKeyMaterial(bound = true),
            identity = identity,
            clock = clock,
            rendezvous = rendezvous,
        )
        try {
            val computer = AccountInviteSession(
                HkdfKeyDerivation, clock, RendezvousUrl.parse("https://pair.example.test")!!,
            )

            vm.onIntent(PairingIntent.RoleChosen(PairingRole.JoinFromComputer))
            vm.onIntent(PairingIntent.CodeScanned(computer.inviteCode))
            vm.onIntent(PairingIntent.SendReply)
            runCurrent()

            computer.onReply(rendezvous.deposits.single().third) as ReplyOutcome.Agreed
            assertArrayEquals(identity.publicKey, computer.receivedDeviceKey)
        } finally {
            stopPolling(vm)
        }
    }

    /**
     * A plain `http://` invite is refused with a sentence rather than left to fail in the socket.
     *
     * The same rule the sending side applies, and it has to be the same rule: Android blocks
     * cleartext by default and `network_security_config.xml` opens exactly the loopback hole and no
     * other.
     */
    @Test
    fun aCleartextInviteIsRefusedBeforeAnyRequestIsMade() = runTest {
        val rendezvous = FakeRendezvous()
        val clock = FakeClock()
        val vm = viewModel(
            keyMaterial = FakeKeyMaterial(bound = true),
            clock = clock,
            rendezvous = rendezvous,
        )
        try {
            val computer = AccountInviteSession(
                HkdfKeyDerivation, clock, RendezvousUrl.parse("http://notes.example.test")!!,
            )

            vm.onIntent(PairingIntent.RoleChosen(PairingRole.JoinFromComputer))
            vm.onIntent(PairingIntent.CodeScanned(computer.inviteCode))
            vm.onIntent(PairingIntent.SendReply)
            runCurrent()

            val stage = vm.state.value.stage as PairingStage.AnsweringInvite
            assertFalse(stage.secure)
            assertTrue(stage.message!!.contains("https://"))
            assertTrue(rendezvous.deposits.isEmpty())
        } finally {
            stopPolling(vm)
        }
    }

    /** A QR that is not an invite is a hint, not a failure: the camera sees a lot of the world. */
    @Test
    fun anUnrelatedCodeLeavesTheInviteScannerRunning() = runTest {
        val vm = viewModel(keyMaterial = FakeKeyMaterial(bound = true))
        try {
            vm.onIntent(PairingIntent.RoleChosen(PairingRole.JoinFromComputer))
            vm.onIntent(PairingIntent.CodeScanned("WIFI:S=Cafe;T=WPA;P=hunter2;;"))

            val stage = vm.state.value.stage as PairingStage.ScanningInvite
            assertNull(stage.lastHint)
        } finally {
            stopPolling(vm)
        }
    }

    /** Cancels whatever the run left in flight, so `runTest` can return. See the note above. */
    private fun TestScope.stopPolling(vm: PairingViewModel) {
        vm.onIntent(PairingIntent.StartOver)
        runCurrent()
    }

    // -- fakes --------------------------------------------------------------------------------

    private fun viewModel(
        keyMaterial: PairingKeyMaterial,
        identity: FakeIdentity = FakeIdentity(),
        clock: FakeClock = FakeClock(),
        rendezvous: FakeRendezvous = FakeRendezvous(),
        enroller: FakeEnroller = FakeEnroller(),
    ) = PairingViewModel(
        keyDerivation = HkdfKeyDerivation,
        keyMaterial = keyMaterial,
        clock = clock,
        deviceIdentity = identity,
        deviceEnroller = enroller,
        rendezvousClients = rendezvous,
        // The rule's own dispatcher, so the send runs on the scheduler `runTest` advances. With
        // the real `Dispatchers.IO` here, `advanceUntilIdle()` returns before the deposit has
        // happened and every assertion about its result reads a value that has not been written
        // yet -- which is how this parameter came to exist.
        ioDispatcher = mainDispatcherRule.dispatcher,
    )

    /**
     * A rendezvous that records what it was asked to send.
     *
     * [deposits] being empty is the assertion that matters most in this file: the phone-to-phone
     * tests must never touch it, because that flow opens no socket.
     */
    private class FakeRendezvous(
        private val result: (ByteArray, String) -> DepositResult =
            { _, _ -> DepositResult.Deposited(expiresAt = 0L) },
    ) : RendezvousClientFactory {

        /** Every (server, sid, code) this was asked to deposit, in order. */
        val deposits = mutableListOf<Triple<RendezvousUrl, ByteArray, String>>()

        /** Which slots were deposited into, in the same order as [deposits]. */
        val depositedSlots = mutableListOf<RendezvousSlot>()

        /** What a collect answers. Set by the tests that drive the joining role. */
        var collectAnswer: (ByteArray, RendezvousSlot) -> CollectResult =
            { _, _ -> CollectResult.Pending }

        val collectedSlots = mutableListOf<RendezvousSlot>()

        override fun create(server: RendezvousUrl): RendezvousClient = object : RendezvousClient {
            override fun deposit(
                sid: ByteArray,
                slot: RendezvousSlot,
                code: String,
            ): DepositResult {
                deposits += Triple(server, sid, code)
                depositedSlots += slot
                return result(sid, code)
            }

            override fun collect(sid: ByteArray, slot: RendezvousSlot): CollectResult {
                collectedSlots += slot
                return collectAnswer(sid, slot)
            }
        }
    }

    /**
     * A device enroller that records what it was asked to vouch for.
     *
     * [requests] being empty is the assertion that matters most about it: a phone pairing with a
     * phone reaches no server, so nothing may ask this to enrol anything. What it *records* matters
     * almost as much -- the key it is handed has to be the one that arrived in QR1, because a
     * pairing screen that enrolled some other key would be enrolling a device nobody looked at.
     */
    private class FakeEnroller(
        private val result: (ByteArray) -> EnrolmentResult =
            { EnrolmentResult.Enrolled("srv-device-7") },
    ) : DeviceEnroller {

        /** Every (server, joining key, label) this was asked to enrol, in order. */
        val requests = mutableListOf<Triple<RendezvousUrl, ByteArray, String>>()

        override suspend fun enrol(
            server: RendezvousUrl,
            joiningDeviceKey: ByteArray,
            label: String,
        ): EnrolmentResult {
            requests += Triple(server, joiningDeviceKey, label)
            return result(joiningDeviceKey)
        }
    }

    private class FakeKeyMaterial(
        bound: Boolean,
        private val bundle: AccountBundle? = null,
    ) : PairingKeyMaterial {
        override val isBound = bound
        var adopted: AccountBundle? = null
            private set

        /** How many times the ViewModel asked for a bundle. Minting one is not free. */
        var bundleRequests: Int = 0
            private set

        override fun canShareAccount(): Boolean = bundle != null

        override fun accountBundle(): AccountBundle? {
            bundleRequests++
            return bundle
        }

        override suspend fun adopt(bundle: AccountBundle) {
            adopted = bundle
        }
    }

    /**
     * A stand-in for the Keystore key.
     *
     * The public half is a **real P-256 point**, not 65 zero bytes: the invite direction puts it in
     * a frame an account device runs through `P256.decodePublicKey`, and a stub that could not pass
     * an on-curve check would make every test of that path pass or fail for the wrong reason.
     */
    private class FakeIdentity : DeviceIdentity {
        var provisionCount = 0
            private set

        val publicKey: ByteArray = P256.encodePublicKey(
            P256.generateKeyPair().public as java.security.interfaces.ECPublicKey
        )

        override fun ensureProvisioned(): ByteArray {
            provisionCount++
            return publicKey
        }

        override fun isProvisioned(): Boolean = provisionCount > 0
    }
}
