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
import my.cheysoff.feature_pairing.identity.DeviceEnroller
import my.cheysoff.feature_pairing.identity.DeviceIdentity
import my.cheysoff.feature_pairing.identity.EnrolmentResult
import my.cheysoff.core_pairing.protocol.AccountBundle
import my.cheysoff.core_pairing.protocol.AccountDeviceSession
import my.cheysoff.core_pairing.protocol.BundleOutcome
import my.cheysoff.core_pairing.protocol.CollectResult
import my.cheysoff.core_pairing.protocol.DepositResult
import my.cheysoff.core_pairing.protocol.KeyDerivation
import my.cheysoff.core_pairing.protocol.InviteOutcome
import my.cheysoff.core_pairing.protocol.JoiningDeviceSession
import my.cheysoff.core_pairing.protocol.MonotonicClock
import my.cheysoff.core_pairing.protocol.NewDeviceSession
import my.cheysoff.core_pairing.protocol.OfferOutcome
import my.cheysoff.core_pairing.protocol.PairingConfig
import my.cheysoff.core_pairing.protocol.PairingFailure
import my.cheysoff.core_pairing.protocol.RendezvousClientFactory
import my.cheysoff.core_pairing.protocol.RendezvousSlot
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
     * How the device on the other side of QR1 is enrolled on this account.
     *
     * Only ever called for a pairing that named a server; a phone pairing with a phone reaches no
     * server and has nothing to enrol on.
     */
    private val deviceEnroller: DeviceEnroller,
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
     * This phone's half of an invite from a computer, or null.
     *
     * A third session field rather than a shared one, because the three roles are three different
     * state machines with three different orders, and a single nullable "the session" would be a
     * field whose type a reader has to infer from the stage.
     */
    private var joiningSession: JoiningDeviceSession? = null

    /** What an accepted invite agreed, held between the scan and the send. */
    private var pendingReply: PendingReply? = null

    /**
     * The rendezvous an accepted invite named, held for the whole attempt.
     *
     * Separate from [pendingReply], which is dropped once the reply has landed: the same address is
     * needed again to collect the bundle, and re-parsing the QR would mean keeping the QR.
     */
    private var pendingServer: RendezvousUrl? = null

    private var collectJob: Job? = null

    /**
     * The reply this phone is about to deposit, and where.
     *
     * The contents are public — an ephemeral point and this device's own public key — so unlike
     * [pendingBundle] this is not key material. It is still dropped on every exit path, because
     * there is no reason to keep it once the screen is done with it.
     */
    private class PendingReply(
        val server: RendezvousUrl,
        val sid: ByteArray,
        val replyCode: String,
        val sas: String,
    )

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

    /**
     * What a send needs: the address, the session id the drop is filed under, and the joining
     * device's key.
     *
     * The **seal is not here**, and that is the change enrolment forced. The bundle carries the id
     * the server assigns to [joiningDeviceKey], so it cannot be built until the vouch has happened —
     * and the vouch is a network call, which must not run inside the method the camera analyser
     * drives. So this holds the inputs and `AccountDeviceSession.seal` produces the payload once
     * [sendSeal] knows what to put in it.
     */
    private class PendingSeal(
        val server: RendezvousUrl,
        val sid: ByteArray,
        /** As QR1 carried it, or null when the other device asked for no enrolment. */
        val joiningDeviceKey: ByteArray?,
    ) {
        /**
         * The payload, once the vouch and the seal have happened.
         *
         * A retry after a failed deposit re-sends **these bytes**; it does not vouch again and does
         * not seal again. Sealing again would put a second `deviceId` in a second bundle for one
         * pairing, which turns a dropped connection into a dead pairing -- so this still holds even
         * though the server side of it no longer does: enrolment is idempotent now, and vouching
         * again returns the id the first attempt assigned rather than `409 device_exists`. Keeping
         * the seal is the cheaper half of the guarantee and it does not depend on the server's.
         */
        var sealCode: String? = null
    }

    fun onIntent(intent: PairingIntent) {
        when (intent) {
            is PairingIntent.RoleChosen -> startSession(intent.role)
            PairingIntent.OfferShown -> advanceToScanningSeal()
            PairingIntent.SealShown -> advanceToConfirming()
            is PairingIntent.CodeScanned -> onCode(intent.text)
            PairingIntent.SendSeal -> sendSeal()
            PairingIntent.SendReply -> sendReply()
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

            PairingRole.JoinFromComputer -> {
                // The device identity key is provisioned HERE rather than on completion, because
                // its public half has to travel in the reply: the computer vouches for exactly this
                // key. It is idempotent, and a key on a device that then abandoned the pairing is a
                // key nothing was ever enrolled against.
                val devicePublicKey = deviceIdentity.ensureProvisioned()
                joiningSession = JoiningDeviceSession(
                    keyDerivation = keyDerivation,
                    clock = clock,
                    devicePublicKey = devicePublicKey,
                )
                newDeviceSession = null
                accountDeviceSession = null
                _state.update { it.copy(stage = PairingStage.ScanningInvite(lastHint = null)) }
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
                    ark = bundle.ark,
                    accountId = bundle.accountId,
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
            is PairingStage.ScanningInvite -> onInviteScanned(text)
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
                    // No server was named, so there is nothing to enrol on and nothing to tell the
                    // other device about one. The seal happens here, immediately, and this branch
                    // opens no socket -- which is the property
                    // `pairingWithAnotherPhoneNeverTouchesTheNetwork` pins.
                    val sealCode = session.seal("") ?: return abandon(
                        PairingFailure.SESSION_CLOSED,
                        "This pairing attempt is finished. Start over to try again.",
                    )
                    _state.update {
                        it.copy(
                            stage = PairingStage.ShowingSeal(
                                code = sealCode,
                                sas = outcome.sas,
                                secondsRemaining = session.remainingMillis().toSeconds(),
                            )
                        )
                    }
                    startCountdown()
                } else {
                    pendingSeal = PendingSeal(
                        server = server,
                        sid = session.receivedSid!!,
                        // Straight from the session, which read it out of QR1 and checked it
                        // against the curve. This is the only key that reaches the enroller, and
                        // taking it from anywhere else -- the rendezvous, a server lookup -- would
                        // be enrolling a device nobody pointed a camera at.
                        joiningDeviceKey = session.receivedDeviceKey,
                    )
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

    /**
     * One frame, on the path where a computer holds the account.
     *
     * Everything cryptographic happens inside [JoiningDeviceSession.onScanned]: the on-curve check
     * on the computer's ephemeral point, the agreement, the six digits and this phone's reply. What
     * is left here is that **nothing is sent yet** — see [PairingStage.AnsweringInvite].
     */
    private fun onInviteScanned(text: String) {
        val session = joiningSession ?: return
        when (val outcome = session.onScanned(text)) {
            is InviteOutcome.Accepted -> {
                val server = RendezvousUrl.parse(outcome.server.url)
                if (server == null) {
                    // The decoder already refuses an invite with no address, so this is an address
                    // that decoded and does not parse -- a version disagreement or a hostile code.
                    return abandon(
                        PairingFailure.MALFORMED,
                        "That code names a server address this phone cannot use. Nothing was sent.",
                    )
                }
                pendingServer = server
                pendingReply = PendingReply(
                    server = server,
                    sid = session.sid!!,
                    replyCode = outcome.replyCode,
                    sas = outcome.sas,
                )
                _state.update {
                    it.copy(
                        stage = PairingStage.AnsweringInvite(
                            host = server.host,
                            secure = server.secure,
                            sas = outcome.sas,
                        )
                    )
                }
            }

            is InviteOutcome.Rejected -> applyRejection(outcome.failure) { hint ->
                val current = _state.value.stage
                if (current is PairingStage.ScanningInvite) {
                    _state.update { it.copy(stage = current.copy(lastHint = hint)) }
                }
            }
        }
    }

    /**
     * Deposit this phone's ephemeral point and device public key in the invite's reply slot.
     *
     * ## What is being sent, and what it is not
     *
     * Two P-256 public points. Nothing here is secret, so a wrong host learns nothing and an
     * eavesdropper learns nothing — which is exactly why the confidentiality of this leg is not the
     * question. The question is **authenticity**, and this route has none: whoever controls it can
     * replace this reply with their own and the computer will agree a secret with them instead. The
     * six digits are what catches that, which is why [PairingStage.Confirming] comes next and why
     * nothing about the account key has happened yet on either side.
     *
     * ## The plain-`http` rule
     *
     * `https`, or `http` to a loopback address, and nothing else — `ServerEndpoint`'s rule, applied
     * here for the reason [sendSeal] gives at length: Android blocks cleartext by default and
     * `network_security_config.xml` opens exactly the same loopback hole and no other, so three
     * lists that must agree is two too many.
     */
    private fun sendReply() {
        val stage = _state.value.stage
        if (stage !is PairingStage.AnsweringInvite || stage.sending) return
        val reply = pendingReply ?: return abandon(
            PairingFailure.SESSION_CLOSED,
            "There is nothing left to send. Start over.",
        )

        if (!reply.server.secure && !isLoopback(reply.server.host)) {
            _state.update {
                it.copy(
                    stage = stage.copy(
                        message = "That computer offered a plain http:// address on a host this " +
                            "phone will not send to in the clear. Put the server behind https:// " +
                            "and start over.",
                    )
                )
            }
            return
        }

        _state.update { it.copy(stage = stage.copy(sending = true, message = null)) }
        sendJob = viewModelScope.launch {
            val result = withContext(ioDispatcher) {
                rendezvousClients.create(reply.server)
                    .deposit(reply.sid, RendezvousSlot.REPLY, reply.replyCode)
            }
            when (result) {
                // A 409 means either that a previous attempt of ours landed and its response was
                // lost, or that somebody else filled this slot first. The second is the man in the
                // middle -- and moving on is right for both, because the digits are the check: if
                // somebody else's reply is what the computer agreed with, its digits will not be
                // ours and the user stops there.
                is DepositResult.Deposited, DepositResult.AlreadyDeposited -> {
                    pendingReply = null
                    _state.update {
                        it.copy(
                            stage = PairingStage.Confirming(
                                sas = reply.sas,
                                role = PairingRole.JoinFromComputer,
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

    /**
     * Poll the bundle slot until the computer sends, this session expires, or something breaks.
     *
     * Started by [PairingIntent.SasConfirmed] and by nothing else. That ordering is not decoration:
     * the computer does not seal the account key until its own user confirms, so a phone that
     * started collecting earlier would only be asking for something that does not exist — and it
     * would make the confirmation on this side look like a formality rather than the one check that
     * matters.
     */
    private fun collectBundle() {
        val session = joiningSession ?: return abandon(
            PairingFailure.SESSION_CLOSED,
            "This pairing attempt is finished. Start over to try again.",
        )
        val server = pendingServer ?: return abandon(
            PairingFailure.SESSION_CLOSED,
            "This pairing attempt is finished. Start over to try again.",
        )
        val sid = session.sid ?: return abandon(
            PairingFailure.SESSION_CLOSED,
            "This pairing attempt is finished. Start over to try again.",
        )

        _state.update {
            it.copy(
                stage = PairingStage.CollectingBundle(
                    secondsRemaining = session.remainingMillis().toSeconds()
                )
            )
        }

        val client = rendezvousClients.create(server)
        collectJob = viewModelScope.launch {
            while (isActive) {
                if (session.isExpired()) {
                    return@launch abandon(
                        PairingFailure.EXPIRED,
                        "The pairing code expired before the computer sent the account key. " +
                            "Nothing was saved — start over.",
                    )
                }
                val result = withContext(ioDispatcher) {
                    client.collect(sid, RendezvousSlot.BUNDLE)
                }
                when (result) {
                    is CollectResult.Pending -> publishCollecting(session, null)

                    // A dropped connection is not a failure: the deadline is the session TTL, which
                    // is already counting.
                    is CollectResult.Unreachable ->
                        publishCollecting(session, "Cannot reach the server. Still trying.")

                    is CollectResult.Unusable -> return@launch abandon(
                        null,
                        "The pairing server answered with something unusable: ${result.detail}",
                    )

                    is CollectResult.Collected -> {
                        when (val outcome = session.onBundle(result.sealCode)) {
                            is BundleOutcome.Opened -> {
                                pendingBundle = outcome.bundle
                                adoptAndFinish(PairingRole.JoinFromComputer)
                            }

                            // The loud abort. A tag failure here means the bundle was not sealed by
                            // the computer this phone agreed with -- which, after a matching SAS,
                            // means it was modified in flight.
                            is BundleOutcome.Rejected ->
                                abandon(outcome.failure, messageFor(outcome.failure))
                        }
                        return@launch
                    }
                }
                delay(COLLECT_INTERVAL_MILLIS)
            }
        }
    }

    private fun publishCollecting(session: JoiningDeviceSession, note: String?) {
        val current = _state.value.stage
        if (current !is PairingStage.CollectingBundle) return
        _state.update {
            it.copy(
                stage = current.copy(
                    secondsRemaining = session.remainingMillis().toSeconds(),
                    note = note,
                )
            )
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
     * Enrol the other device, seal the bundle for it, and POST the result to the address it named.
     *
     * Three steps behind one button, in that order, and the order is the design:
     *
     *  1. **Vouch.** The server assigns the joining device an id, and that id has to be inside the
     *     seal — there is no other channel it could travel on, because every endpoint that would
     *     tell the joining device its own id needs a session and opening a session needs the id.
     *  2. **Seal.** With the address and the id, so the other device receives an authenticated copy
     *     of both rather than the unauthenticated hint it put in QR1 itself.
     *  3. **Deposit.** What crosses the wire is the QR2 payload, byte for byte — the very thing the
     *     phone-to-phone flow renders as a symbol. The server stores it, cannot open it, and deletes
     *     it the moment the other device collects it.
     *
     * ## The vouch happens before the SAS is confirmed, and that is not a weakening
     *
     * It cannot be otherwise: the id it produces goes inside the seal, and the seal is deposited
     * before either screen shows a confirmation. The ARK itself already leaves on exactly this
     * schedule — sealed and posted, then the digits are compared — so enrolling a signing key at the
     * same moment gives away strictly less than the step it accompanies. If the digits then do not
     * match, the account has a device row it can revoke and an attacker who could reach that point
     * already holds the account key.
     *
     * ## The plain-`http` rule
     *
     * `https`, or `http` to a loopback address, and nothing else. That is `ServerEndpoint`'s rule
     * for the sync transport, stated there at length: the server speaks plain HTTP behind a
     * TLS-terminating proxy, so loopback is the one case where no traffic leaves the machine and
     * there is nothing for TLS to protect. Anything else is refused here rather than left to fail
     * inside the HTTP stack as an unexplained "cannot reach the server", because Android blocks
     * cleartext by default and `network_security_config.xml` opens exactly the same loopback hole
     * and no other.
     */
    private fun sendSeal() {
        val stage = _state.value.stage
        if (stage !is PairingStage.SendingSeal || stage.sending) return
        val seal = pendingSeal ?: return abandon(
            PairingFailure.SESSION_CLOSED,
            "There is nothing left to send. Start over.",
        )
        val session = accountDeviceSession ?: return abandon(
            PairingFailure.SESSION_CLOSED,
            "This pairing attempt is finished. Start over to try again.",
        )

        if (!seal.server.secure && !isLoopback(seal.server.host)) {
            _state.update {
                it.copy(
                    stage = stage.copy(
                        message = "That computer offered a plain http:// address on a host this " +
                            "phone will not send to in the clear. Put the server behind https:// " +
                            "and start over.",
                    )
                )
            }
            return
        }

        _state.update { it.copy(stage = stage.copy(sending = true, message = null)) }
        sendJob = viewModelScope.launch {
            val sealCode = seal.sealCode ?: run {
                val enrolment = seal.joiningDeviceKey?.let { key ->
                    deviceEnroller.enrol(seal.server, key, label = JOINING_DEVICE_LABEL)
                }
                if (enrolment is EnrolmentResult.Refused) {
                    // Reported and stopped, rather than sealed without an id. A bundle naming a
                    // server the other device cannot open a session on produces a paired device
                    // that silently never syncs -- which is the failure this whole change exists to
                    // remove, and shipping a quieter version of it here would be perverse. The user
                    // can retry: the session is still live and the button comes back.
                    _state.update {
                        it.copy(stage = stage.copy(sending = false, message = enrolment.message))
                    }
                    return@launch
                }
                val produced = session.seal(
                    PairingConfig.encode(
                        serverUrl = seal.server.base,
                        deviceId = (enrolment as? EnrolmentResult.Enrolled)?.deviceId.orEmpty(),
                    )
                ) ?: return@launch abandon(
                    PairingFailure.SESSION_CLOSED,
                    "This pairing attempt is finished. Start over to try again.",
                )
                seal.sealCode = produced
                produced
            }

            // The client is blocking; the ViewModel's scope is the main dispatcher.
            val result = withContext(ioDispatcher) {
                rendezvousClients.create(seal.server).deposit(seal.sid, RendezvousSlot.BUNDLE, sealCode)
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

        when (stage.role) {
            // The account key is already here, opened out of QR2. Store it.
            PairingRole.NewDevice -> adoptAndFinish(stage.role)

            // Nothing to store: this device gave the account key away and kept the one it had.
            PairingRole.HasMyNotes -> {
                deviceIdentity.ensureProvisioned()
                clearSessions()
                _state.update { it.copy(stage = PairingStage.Finished(stage.role)) }
            }

            // The account key has not arrived yet, and this confirmation is what allows it to be
            // sent at all: the computer does not seal until its own user confirms. So this branch
            // starts asking, rather than finishing.
            PairingRole.JoinFromComputer -> collectBundle()
        }
    }

    /**
     * Store the received bundle and finish.
     *
     * Suspending work behind a launch, because adopting is no longer only an in-memory wrap: a
     * bundle that named a server carries the address and this device's server-assigned id, and
     * those are DataStore writes. The stage moves to [PairingStage.Finished] only after they land,
     * so "Finished" never means "the account key is stored and the server configuration was lost".
     */
    private fun adoptAndFinish(role: PairingRole) {
        val bundle = pendingBundle ?: return abandon(
            PairingFailure.SESSION_CLOSED,
            "The pairing result was already discarded. Start over.",
        )
        pendingBundle = null
        viewModelScope.launch {
            keyMaterial.adopt(bundle)
            deviceIdentity.ensureProvisioned()
            clearSessions()
            _state.update { it.copy(stage = PairingStage.Finished(role)) }
        }
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
        joiningSession = null
        pendingSeal = null
        pendingReply = null
        pendingServer = null
        sendJob?.cancel()
        sendJob = null
        collectJob?.cancel()
        collectJob = null
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
                    // Ticked by the collect loop itself, which is the authority on how long is
                    // left; a second countdown job for it would race that one.
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

    /**
     * Loopback, in the two spellings a URL can carry.
     *
     * The same set `ServerEndpoint.isLoopback` recognises, and it has to stay the same set: this
     * decides whether the phone will POST to a plain-http address, and `network_security_config.xml`
     * decides whether the platform lets it. Three lists that must agree is two too many, and the
     * cheapest way to notice a disagreement is that pairing to a loopback server stops working.
     */
    private fun isLoopback(hostAndPort: String): Boolean =
        hostAndPort.substringBeforeLast(':', hostAndPort).let {
            it == "localhost" || it == "127.0.0.1" || it == "[::1]"
        } || hostAndPort == "[::1]"

    private companion object {
        const val TICK_MILLIS = 1_000L

        /**
         * 1.5 seconds between polls of the bundle slot.
         *
         * The same cadence the desktop uses on its own poll loop, and for the same arithmetic: a
         * two-minute window is about 80 requests rather than thousands, which keeps an honest
         * client comfortably inside the server's general rate limit.
         */
        const val COLLECT_INTERVAL_MILLIS = 1_500L

        /**
         * The name the joining device is enrolled under.
         *
         * A constant, because this phone does not know what the other machine is called: QR1 carries
         * a key and an address, not a hostname. "Computer" is honest and it is what the rendezvous
         * path means — a laptop, which is the only kind of device that asks for this flow. The user
         * can rename it from the device list, and an unnamed row is worse than a generic one because
         * a device the user cannot identify is a device they will not revoke.
         */
        const val JOINING_DEVICE_LABEL = "Computer"
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
