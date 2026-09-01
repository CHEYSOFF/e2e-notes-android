package my.cheysoff.feature_pairing.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.cheysoff.feature_pairing.di.PairingIoDispatcher
import my.cheysoff.feature_pairing.di.PairingKeyMaterial
import my.cheysoff.feature_pairing.identity.DeviceIdentity
import my.cheysoff.core_pairing.protocol.AccountBundle
import my.cheysoff.core_pairing.protocol.AccountDeviceSession
import my.cheysoff.core_pairing.protocol.DepositResult
import my.cheysoff.core_pairing.protocol.KeyDerivation
import my.cheysoff.core_pairing.protocol.MonotonicClock
import my.cheysoff.core_pairing.protocol.NewDeviceSession
import my.cheysoff.core_pairing.protocol.OfferOutcome
import my.cheysoff.core_pairing.protocol.PairingFailure
import my.cheysoff.core_pairing.protocol.RendezvousClientFactory
import my.cheysoff.core_pairing.protocol.RendezvousUrl
import my.cheysoff.core_pairing.protocol.SealOutcome
import javax.inject.Inject
import kotlin.math.ceil

/**
 * Drives one pairing attempt.
 *
 * ## What lives here and what does not
 *
 * The protocol itself is in `my.cheysoff.core_pairing.protocol` and knows nothing about this
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
    /**
     * How a rendezvous client is built for the address a computer named in QR1.
     *
     * A factory rather than a client, because the address is only known once a QR code has been
     * read. Never used on the phone-to-phone path — that flow opens no socket at all.
     */
    private val rendezvousClients: RendezvousClientFactory,
    /**
     * Where the one blocking network call runs.
     *
     * Injected rather than written as `Dispatchers.IO` at the call site, because a hard-coded
     * dispatcher is one a test cannot advance: `runTest`'s scheduler owns `Main`, and work that
     * escapes to the real IO pool completes on a wall clock the test has no handle on. That is not
     * a hypothetical — it is what the first version of the send path did, and
     * `PairingViewModelTest` could observe the request but never its result.
     */
    @PairingIoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _state = MutableStateFlow(
        PairingScreenState(
            available = keyMaterial.isBound,
            canShareAccount = keyMaterial.isBound && keyMaterial.canShareAccount(),
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

    /**
     * The sealed bundle waiting to be sent, and where to send it.
     *
     * Held rather than sent immediately: see [PairingStage.SendingSeal] for why the send is an
     * explicit act. It is a **sealed** bundle — AES-256-GCM under a key derived from an ECDH whose
     * private halves never left either device — so unlike [pendingBundle] this is not key material
     * in the clear. It is still dropped on every exit path, because there is no reason to keep it
     * once the screen is done with it.
     */
    private var pendingSeal: PendingSeal? = null

    private var sendJob: Job? = null

    /** What a send needs: the address, the session id it is filed under, and the QR2 payload. */
    private class PendingSeal(
        val server: RendezvousUrl,
        val sid: ByteArray,
        val sealCode: String,
    )

    fun onIntent(intent: PairingIntent) {
        when (intent) {
            is PairingIntent.RoleChosen -> startSession(intent.role)
            PairingIntent.OfferShown -> advanceToScanningSeal()
            PairingIntent.SealShown -> advanceToConfirming()
            is PairingIntent.CodeScanned -> onCode(intent.text)
            PairingIntent.SendSeal -> sendSeal()
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
                // The one call that can mint an ARK, and it is here rather than in `init` on
                // purpose: an account is created when the user says this phone holds the notes,
                // not when they open the screen. A null means the device locked between the
                // chooser and this line, or that a stored account key will not open -- both are
                // states where sealing something would be worse than stopping.
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
                // The one place the two flows diverge, and the divergence is entirely in *how the
                // sealed bundle travels*. Everything above this line -- the scan, the on-curve
                // check, the key schedule, the seal itself -- has already happened and is identical.
                //
                // An empty hint means the other device is a phone: it will scan QR2, exactly as it
                // always has, with no server and no network. A hint with a usable address means the
                // other device has no camera to point, and asks for the bundle to be sent instead.
                val server = session.receivedServerHint?.url
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { RendezvousUrl.parse(it) }

                if (server == null) {
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
                } else {
                    pendingSeal = PendingSeal(server, session.receivedSid!!, outcome.sealCode)
                    _state.update {
                        it.copy(
                            stage = PairingStage.SendingSeal(
                                host = server.host,
                                secure = server.secure,
                                sas = outcome.sas,
                            )
                        )
                    }
                }
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

    // -- sending ------------------------------------------------------------------------------

    /**
     * POST the sealed bundle to the address the other device named.
     *
     * What crosses the wire is the QR2 payload, byte for byte — the very thing the phone-to-phone
     * flow renders as a symbol. The server stores it, cannot open it, and deletes it the moment the
     * other device collects it. See `RendezvousProtocol` for what the server does and does not
     * learn from that.
     *
     * The plain-`http` refusal below is a platform fact reported honestly rather than a policy this
     * app invents. Android has blocked cleartext HTTP by default since it started targeting API 28,
     * so an `http://` address here would fail somewhere inside `HttpURLConnection` and surface as
     * an unexplained "cannot reach the server". Saying which of the two it is turns an unfixable
     * retry loop into an instruction. Nothing is weakened by the refusal: what would have travelled
     * is ciphertext either way, and the cost of cleartext is that an on-path attacker can disrupt
     * the pairing, not read it.
     */
    private fun sendSeal() {
        val stage = _state.value.stage
        if (stage !is PairingStage.SendingSeal || stage.sending) return
        val seal = pendingSeal ?: return abandon(
            PairingFailure.SESSION_CLOSED,
            "There is nothing left to send. Start over.",
        )

        if (!seal.server.secure) {
            _state.update {
                it.copy(
                    stage = stage.copy(
                        message = "That computer offered a plain http:// address, and Android " +
                            "refuses unencrypted connections. Put the server behind https:// and " +
                            "start over.",
                    )
                )
            }
            return
        }

        _state.update { it.copy(stage = stage.copy(sending = true, message = null)) }
        sendJob = viewModelScope.launch {
            // The client is blocking; the ViewModel's scope is the main dispatcher.
            val result = withContext(ioDispatcher) {
                rendezvousClients.create(seal.server).deposit(seal.sid, seal.sealCode)
            }
            when (result) {
                // Both of these move on, and the second deliberately so. A 409 means either that a
                // previous attempt landed and its response was lost -- in which case the other
                // device already has it and comparing digits is exactly right -- or that someone
                // else got there first, in which case the other device will collect a bundle it
                // cannot open and abort loudly. The SAS comparison is the check for both, which is
                // what it is for.
                is DepositResult.Deposited, DepositResult.AlreadyDeposited -> {
                    pendingSeal = null
                    _state.update {
                        it.copy(
                            stage = PairingStage.Confirming(
                                sas = stage.sas,
                                role = PairingRole.HasMyNotes,
                            )
                        )
                    }
                }

                is DepositResult.Refused -> _state.update {
                    it.copy(
                        stage = stage.copy(
                            sending = false,
                            message = "The server refused: ${result.detail}",
                        )
                    )
                }

                is DepositResult.Unreachable -> _state.update {
                    it.copy(
                        stage = stage.copy(
                            sending = false,
                            message = "Could not reach ${stage.host}: ${result.detail}",
                        )
                    )
                }
            }
        }
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
                canShareAccount = keyMaterial.isBound && keyMaterial.canShareAccount(),
            )
        }
    }

    /**
     * Drop every per-attempt object.
     *
     * [pendingSeal] and [sendJob] are cleared here rather than at each of the four call sites,
     * which is what makes "an abandoned attempt leaves nothing in flight" a property of one
     * function instead of a thing to remember.
     */
    private fun clearSessions() {
        newDeviceSession = null
        accountDeviceSession = null
        pendingSeal = null
        sendJob?.cancel()
        sendJob = null
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
