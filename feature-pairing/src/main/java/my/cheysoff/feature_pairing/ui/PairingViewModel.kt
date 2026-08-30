package my.cheysoff.feature_pairing.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import my.cheysoff.feature_pairing.di.PairingKeyMaterial
import my.cheysoff.feature_pairing.identity.DeviceIdentity
import my.cheysoff.feature_pairing.protocol.AccountBundle
import my.cheysoff.feature_pairing.protocol.AccountDeviceSession
import my.cheysoff.feature_pairing.protocol.KeyDerivation
import my.cheysoff.feature_pairing.protocol.MonotonicClock
import my.cheysoff.feature_pairing.protocol.NewDeviceSession
import my.cheysoff.feature_pairing.protocol.OfferOutcome
import my.cheysoff.feature_pairing.protocol.PairingFailure
import my.cheysoff.feature_pairing.protocol.SealOutcome
import javax.inject.Inject
import kotlin.math.ceil

/**
 * Drives one pairing attempt.
 *
 * ## What lives here and what does not
 *
 * The protocol itself is in `my.cheysoff.feature_pairing.protocol` and knows nothing about this
 * class. What is left here is sequencing: which role was chosen, which session object is live,
 * when the countdown ticks, and — the one genuinely load-bearing decision — that **nothing is
 * committed until the user has confirmed the SAS**. The new device holds the received bundle in a
 * field until [PairingIntent.SasConfirmed]; on [PairingIntent.SasRejected] it is dropped and never
 * reaches storage.
 *
 * ## Threading
 *
 * The pairing sessions are explicitly not thread-safe, and the camera analyser calls
 * [PairingIntent.CodeScanned] from its own executor. Every intent is therefore funnelled through
 * [onIntent], which the screen invokes on the main thread — the analyser's callback is bridged by
 * the screen, not called into here directly from the executor. If that ever changes, this class
 * needs a dispatcher confinement rather than a lock.
 *
 * ## Logging
 *
 * There is none. Not "no key material is logged" — no logging at all, so there is nothing to audit
 * later for a leak. The QR payloads, the ARK and the SAS are all either key material or a direct
 * function of it.
 */
@HiltViewModel
class PairingViewModel @Inject constructor(
    private val keyDerivation: KeyDerivation,
    private val keyMaterial: PairingKeyMaterial,
    private val clock: MonotonicClock,
    private val deviceIdentity: DeviceIdentity,
) : ViewModel() {

    private val _state = MutableStateFlow(
        PairingScreenState(
            available = keyMaterial.isBound,
            canShareAccount = keyMaterial.isBound && keyMaterial.accountBundle() != null,
        )
    )
    val state = _state.asStateFlow()

    private var newDeviceSession: NewDeviceSession? = null
    private var accountDeviceSession: AccountDeviceSession? = null

    /**
     * The bundle the new device opened out of QR2, held until the user confirms the SAS.
     *
     * The one piece of key material this class ever touches. It is cleared on confirmation (after
     * being handed to storage), on rejection, and on [PairingIntent.StartOver].
     */
    private var pendingBundle: AccountBundle? = null

    private var countdownJob: Job? = null

    fun onIntent(intent: PairingIntent) {
        when (intent) {
            is PairingIntent.RoleChosen -> startSession(intent.role)
            PairingIntent.OfferShown -> advanceToScanningSeal()
            PairingIntent.SealShown -> advanceToConfirming()
            is PairingIntent.CodeScanned -> onCode(intent.text)
            PairingIntent.SasConfirmed -> commit()
            // failure = null: the protocol succeeded and a person stopped it. See PairingStage.Failed.
            PairingIntent.SasRejected -> abandon(
                null,
                "The two codes did not match, so nothing was saved. Start over and make sure " +
                    "you are pointing each phone at the other one.",
            )
            PairingIntent.StartOver -> reset()
            is PairingIntent.CameraPermissionChanged -> _state.update {
                it.copy(
                    cameraPermission = when {
                        intent.granted -> CameraPermission.Granted
                        intent.permanentlyDenied -> CameraPermission.PermanentlyDenied
                        else -> CameraPermission.Denied
                    }
                )
            }
        }
    }

    // -- role selection ---------------------------------------------------------------------

    private fun startSession(role: PairingRole) {
        if (!keyMaterial.isBound) return
        when (role) {
            PairingRole.NewDevice -> {
                val session = NewDeviceSession(keyDerivation = keyDerivation, clock = clock)
                newDeviceSession = session
                accountDeviceSession = null
                _state.update {
                    it.copy(
                        stage = PairingStage.ShowingOffer(
                            code = session.offerCode,
                            secondsRemaining = session.remainingMillis().toSeconds(),
                        )
                    )
                }
                startCountdown()
            }

            PairingRole.HasMyNotes -> {
                // A device with no account key has nothing to seal. The chooser already hides this
                // option (`canShareAccount`), so reaching it means the state and the UI disagreed;
                // failing loudly is better than sealing something empty.
                val bundle = keyMaterial.accountBundle() ?: return abandon(
                    PairingFailure.SESSION_CLOSED,
                    "This device does not have an account key to share yet.",
                )
                accountDeviceSession = AccountDeviceSession(
                    keyDerivation = keyDerivation,
                    clock = clock,
                    bundle = bundle,
                )
                newDeviceSession = null
                _state.update { it.copy(stage = PairingStage.ScanningOffer(lastHint = null)) }
            }
        }
    }

    private fun advanceToScanningSeal() {
        val session = newDeviceSession ?: return
        _state.update {
            it.copy(
                stage = PairingStage.ScanningSeal(
                    secondsRemaining = session.remainingMillis().toSeconds(),
                    lastHint = null,
                )
            )
        }
    }

    // -- scanning ---------------------------------------------------------------------------

    /**
     * Feed one decoded symbol to whichever session is live.
     *
     * Called many times a second. Nothing here allocates a session, touches storage or logs.
     */
    private fun onCode(text: String) {
        when (val stage = _state.value.stage) {
            is PairingStage.ScanningOffer -> onOfferScanned(text)
            is PairingStage.ScanningSeal -> onSealScanned(text, stage)
            // Every other stage has no camera on screen. Frames can still arrive for a moment
            // after a transition, and they are dropped rather than fed to a finished session.
            else -> Unit
        }
    }

    private fun onOfferScanned(text: String) {
        val session = accountDeviceSession ?: return
        when (val outcome = session.onScanned(text)) {
            is OfferOutcome.Accepted -> {
                _state.update {
                    it.copy(
                        stage = PairingStage.ShowingSeal(
                            code = outcome.sealCode,
                            sas = outcome.sas,
                            secondsRemaining = session.remainingMillis().toSeconds(),
                        )
                    )
                }
                startCountdown()
            }

            is OfferOutcome.Rejected -> applyRejection(outcome.failure) { hint ->
                val current = _state.value.stage
                if (current is PairingStage.ScanningOffer) {
                    _state.update { it.copy(stage = current.copy(lastHint = hint)) }
                }
            }
        }
    }

    private fun onSealScanned(text: String, stage: PairingStage.ScanningSeal) {
        val session = newDeviceSession ?: return
        when (val outcome = session.onScanned(text)) {
            is SealOutcome.Paired -> {
                stopCountdown()
                pendingBundle = outcome.bundle
                _state.update {
                    it.copy(
                        stage = PairingStage.Confirming(
                            sas = outcome.sas,
                            role = PairingRole.NewDevice,
                        )
                    )
                }
            }

            is SealOutcome.Rejected -> applyRejection(outcome.failure) { hint ->
                val current = _state.value.stage
                if (current is PairingStage.ScanningSeal) {
                    _state.update { it.copy(stage = current.copy(lastHint = hint)) }
                }
            }
        }
    }

    /**
     * Turn a [PairingFailure] into either a UI hint (keep scanning) or a dead session.
     *
     * The split is [PairingFailure.isTerminal], and it is the protocol's decision rather than the
     * UI's: a tag failure or an off-curve point stops everything, and "that was a bus timetable"
     * does not even get a message.
     */
    private fun applyRejection(failure: PairingFailure, showHint: (ScanHint?) -> Unit) {
        if (failure.isTerminal) {
            abandon(failure, messageFor(failure))
            return
        }
        showHint(
            when (failure) {
                PairingFailure.UNSUPPORTED_VERSION -> ScanHint.DifferentVersion
                PairingFailure.WRONG_CODE_KIND -> ScanHint.WrongStep
                PairingFailure.SESSION_MISMATCH -> ScanHint.OtherSession
                // NOT_A_PAIRING_CODE and MALFORMED are the ordinary case for a camera pointed at
                // the world. Showing anything for them would mean a message flickering on every
                // frame that missed.
                else -> null
            }
        )
    }

    // -- finishing ----------------------------------------------------------------------------

    /**
     * The user confirmed the six digits match.
     *
     * This is the only place a pairing is committed. On the new device that means storing the ARK;
     * on both devices it means making sure the Keystore identity key exists, because that is what
     * Phase 3's device enrolment will sign with.
     */
    private fun commit() {
        val stage = _state.value.stage
        if (stage !is PairingStage.Confirming) return
        stopCountdown()

        if (stage.role == PairingRole.NewDevice) {
            val bundle = pendingBundle ?: return abandon(
                PairingFailure.SESSION_CLOSED,
                "The pairing result was already discarded. Start over.",
            )
            keyMaterial.adopt(bundle)
            pendingBundle = null
        }
        deviceIdentity.ensureProvisioned()
        clearSessions()
        _state.update { it.copy(stage = PairingStage.Finished(stage.role)) }
    }

    /**
     * Move the account device on to the SAS confirmation.
     *
     * Driven by the user, because the account device has no way to observe that the other phone
     * scanned QR2 — it emitted the code and the exchange is over as far as its own state machine
     * is concerned.
     */
    private fun advanceToConfirming() {
        val stage = _state.value.stage
        if (stage !is PairingStage.ShowingSeal) return
        stopCountdown()
        _state.update {
            it.copy(stage = PairingStage.Confirming(sas = stage.sas, role = PairingRole.HasMyNotes))
        }
    }

    private fun abandon(failure: PairingFailure?, message: String) {
        stopCountdown()
        // Drop the received key material before anything else, including before the state update:
        // the failure paths are exactly the ones where it must not survive.
        pendingBundle = null
        clearSessions()
        _state.update { it.copy(stage = PairingStage.Failed(failure, message)) }
    }

    private fun reset() {
        stopCountdown()
        pendingBundle = null
        clearSessions()
        _state.update {
            it.copy(
                stage = PairingStage.ChoosingRole,
                canShareAccount = keyMaterial.isBound && keyMaterial.accountBundle() != null,
            )
        }
    }

    private fun clearSessions() {
        newDeviceSession = null
        accountDeviceSession = null
    }

    // -- countdown ----------------------------------------------------------------------------

    /**
     * Tick the on-screen TTL once a second, and fail the session when it runs out.
     *
     * The countdown is display only — the authority is the session's own clock check on every
     * scan, which holds even if this job never runs (the screen went to the background, the device
     * dozed). This exists so the user sees the code go stale rather than discovering it silently
     * stopped working.
     */
    private fun startCountdown() {
        stopCountdown()
        countdownJob = viewModelScope.launch {
            while (isActive) {
                val remaining = when (val stage = _state.value.stage) {
                    is PairingStage.ShowingOffer, is PairingStage.ScanningSeal ->
                        newDeviceSession?.remainingMillis()

                    is PairingStage.ShowingSeal -> accountDeviceSession?.remainingMillis()
                    else -> null
                } ?: return@launch

                if (remaining <= 0L) {
                    abandon(
                        PairingFailure.EXPIRED,
                        "The pairing code expired. Codes are good for two minutes; start over " +
                            "when both phones are ready.",
                    )
                    return@launch
                }
                publishRemaining(remaining.toSeconds())
                delay(TICK_MILLIS)
            }
        }
    }

    private fun publishRemaining(seconds: Int) {
        _state.update { current ->
            when (val stage = current.stage) {
                is PairingStage.ShowingOffer -> current.copy(stage = stage.copy(secondsRemaining = seconds))
                is PairingStage.ScanningSeal -> current.copy(stage = stage.copy(secondsRemaining = seconds))
                is PairingStage.ShowingSeal -> current.copy(stage = stage.copy(secondsRemaining = seconds))
                else -> current
            }
        }
    }

    private fun stopCountdown() {
        countdownJob?.cancel()
        countdownJob = null
    }

    override fun onCleared() {
        super.onCleared()
        // Leaving this screen abandons the attempt. Anything received but unconfirmed is dropped
        // rather than left in a ViewModel that Compose may keep alive across a configuration change.
        pendingBundle = null
        clearSessions()
    }

    private companion object {
        const val TICK_MILLIS = 1_000L
    }
}

/** Round *up*, so a code with 400 ms left reads "1s" rather than "0s" while it still works. */
private fun Long.toSeconds(): Int = ceil(this / 1000.0).toInt()

/** User-facing text for a terminal failure. Deliberately says what happened, not what to blame. */
private fun messageFor(failure: PairingFailure): String = when (failure) {
    PairingFailure.SEAL_REJECTED ->
        "That code could not be opened. It was meant for a different phone, or it was " +
            "modified. Nothing was saved — start over."

    PairingFailure.INVALID_PEER_KEY ->
        "That code contains an invalid key. A code produced by Mañana never does, so this one " +
            "did not come from the other phone. Nothing was saved."

    PairingFailure.EXPIRED ->
        "The pairing code expired. Codes are good for two minutes; start over when both phones " +
            "are ready."

    PairingFailure.SESSION_CLOSED -> "This pairing attempt is finished. Start over to try again."

    // The non-terminal failures never reach here -- applyRejection routes them to a hint instead.
    // A message is provided anyway so this `when` stays exhaustive without an `else` that would
    // silently absorb a new failure kind.
    PairingFailure.NOT_A_PAIRING_CODE,
    PairingFailure.UNSUPPORTED_VERSION,
    PairingFailure.WRONG_CODE_KIND,
    PairingFailure.MALFORMED,
    PairingFailure.SESSION_MISMATCH,
    -> "That is not a code this step can use. Start over to try again."
}
