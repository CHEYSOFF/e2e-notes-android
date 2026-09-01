package my.cheysoff.desktop.pairing

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.cheysoff.core_pairing.protocol.AccountBundle
import my.cheysoff.core_pairing.protocol.HkdfKeyDerivation
import my.cheysoff.core_pairing.protocol.HttpRendezvousClient
import my.cheysoff.core_pairing.protocol.MonotonicClock
import my.cheysoff.core_pairing.protocol.NewDeviceRendezvous
import my.cheysoff.core_pairing.protocol.PollOutcome
import my.cheysoff.core_pairing.protocol.PairingConfig
import my.cheysoff.core_pairing.protocol.RendezvousClient
import my.cheysoff.core_pairing.protocol.RendezvousUrl
import my.cheysoff.desktop.vault.DeviceKeyPair
import my.cheysoff.desktop.vault.PairedEnrolment
import kotlin.math.ceil

/** Where a pairing attempt has got to. Everything [PairingScreen] draws comes from this. */
sealed interface PairingStep {

    /** Naming the server. The one thing the user has to type. */
    data class Address(val url: String, val message: String? = null) : PairingStep

    /**
     * QR1 is on screen and the laptop is polling.
     *
     * [code] is the payload the screen renders as a QR symbol. [note] is the last thing that went
     * wrong with a poll — a network blip — and is deliberately not a failure: the deadline is
     * [secondsRemaining], which is the session's own TTL on a monotonic clock.
     */
    data class Waiting(
        val code: String,
        val secondsRemaining: Int,
        val host: String,
        val secure: Boolean,
        val note: String? = null,
    ) : PairingStep

    /** Both devices are showing six digits. Nothing is saved until the user says they match. */
    data class Confirming(val sas: String) : PairingStep

    /** Stopped. Recoverable only by starting over. */
    data class Failed(val message: String) : PairingStep

    /**
     * The user confirmed the SAS. The ARK is held by the controller and the caller must now take
     * it, exactly once, through [DesktopPairingController.takeBundle].
     */
    data object Confirmed : PairingStep
}

/**
 * Drives one pairing attempt on the desktop.
 *
 * ## What lives here and what does not
 *
 * The protocol is in `:core-pairing` and knows nothing about this class — it is the same code the
 * phone runs, and the same code the phone-to-phone flow runs. What is here is sequencing and a
 * polling schedule.
 *
 * The one genuinely load-bearing decision is the same one the phone's ViewModel makes: **nothing
 * is committed until the user has confirmed the six digits.** The received bundle sits in a private
 * field until [confirmSas], and [rejectSas], [cancel] and [close] all destroy it — zeroed, not just
 * dropped, because on this platform it is a plain byte array in a long-lived heap.
 *
 * ## Logging
 *
 * There is none, for the reason the phone's ViewModel gives: the QR payload, the ARK and the SAS
 * are all either key material or a direct function of it, so the safe amount of logging is zero
 * rather than a careful amount.
 */
class DesktopPairingController(
    private val scope: CoroutineScope,
    /**
     * How a [RendezvousClient] is built for a confirmed address.
     *
     * A parameter so a test can drive the whole screen with no sockets. Production passes the one
     * real implementation.
     */
    private val clientFor: (RendezvousUrl) -> RendezvousClient = { HttpRendezvousClient(it) },
    /**
     * `System.nanoTime()`, not `currentTimeMillis()`.
     *
     * The TTL must not be movable from the Date & Time control panel, which is the same reason the
     * phone binds `SystemClock.elapsedRealtime()` here. `nanoTime` has no defined epoch and is
     * monotonic, which is exactly the contract [MonotonicClock] asks for.
     */
    private val clock: MonotonicClock = MonotonicClock { System.nanoTime() / 1_000_000 },
    private val pollIntervalMillis: Long = POLL_INTERVAL_MILLIS,
    /**
     * Where the blocking poll runs.
     *
     * Injected rather than written as `Dispatchers.IO` at the call site, because a hard-coded
     * dispatcher is one a test cannot advance: work that escapes to the real IO pool completes on a
     * wall clock `runTest` has no handle on, so every assertion about a poll's *result* reads a
     * value that has not been written yet.
     */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * The remembered address, and where a working one is recorded.
     *
     * Parameters rather than direct [PairingServer] calls so that a test does not write to the real
     * user's preferences — a suite that leaves a host name in the registry is a suite with a side
     * effect on the machine that ran it.
     */
    private val rememberedServer: () -> String = PairingServer::remembered,
    private val recordWorkingServer: (RendezvousUrl) -> Unit = PairingServer::remember,
    /**
     * This computer's long-lived signing key, minted for this attempt.
     *
     * Generated here rather than read from a vault, because on the path this class exists for there
     * **is** no vault yet: the public half has to be in QR1 before the phone will vouch for it, and
     * the phone's answer is what creates the vault. So it is held in memory across the attempt and
     * written to disk by `DesktopVault.setUp` if and only if the user confirms the SAS. An abandoned
     * pairing leaves a key pair that was never enrolled anywhere and is simply dropped.
     *
     * A parameter so a test can supply a fixed pair; production mints one per attempt.
     */
    private val deviceKey: DeviceKeyPair = DeviceKeyPair.generate(),
) : AutoCloseable {

    var step by mutableStateOf<PairingStep>(PairingStep.Address(rememberedServer()))
        private set

    private var session: NewDeviceRendezvous? = null
    private var pollJob: Job? = null

    /**
     * The opened bundle, held between the SAS being shown and the user confirming it.
     *
     * The only key material this class ever touches, and the reason [close] exists.
     */
    private var pending: AccountBundle? = null

    /** The address the current attempt is using, kept so it can be remembered on success. */
    private var server: RendezvousUrl? = null

    // -- naming the server ----------------------------------------------------------------------

    fun editAddress(url: String) {
        val current = step
        if (current is PairingStep.Address) step = current.copy(url = url, message = null)
    }

    /**
     * Validate the typed address and start the session.
     *
     * The address is checked before a session exists rather than after, so a typo costs a
     * correction instead of a burnt `sid` and a QR code that was never going to work.
     */
    fun start() {
        val current = step as? PairingStep.Address ?: return
        val parsed = RendezvousUrl.parse(current.url)
        if (parsed == null) {
            step = current.copy(
                message = "That is not an http:// or https:// address this can reach."
            )
            return
        }
        beginSession(parsed)
    }

    private fun beginSession(url: RendezvousUrl) {
        stopPolling()
        val rendezvous = NewDeviceRendezvous(
            client = clientFor(url),
            keyDerivation = HkdfKeyDerivation,
            clock = clock,
            server = url,
            // The public half goes into QR1, where a person's camera is what authenticates it. The
            // phone will vouch for exactly this key and for nothing it fetched from anywhere else.
            devicePublicKey = deviceKey.publicKeySec1,
        )
        session = rendezvous
        server = url
        step = PairingStep.Waiting(
            code = rendezvous.offerCode,
            secondsRemaining = rendezvous.remainingMillis().toSeconds(),
            host = url.host,
            secure = url.secure,
        )
        startPolling(rendezvous)
    }

    // -- polling --------------------------------------------------------------------------------

    /**
     * Ask the server on a fixed interval until the session ends.
     *
     * The countdown shown to the user and the poll are the same loop, so a stalled poll cannot
     * leave a timer running that says everything is fine. The session's own clock is still the
     * authority — [NewDeviceRendezvous.poll] checks expiry before every request — and this loop
     * would be redundant rather than wrong if it stopped ticking.
     */
    private fun startPolling(rendezvous: NewDeviceRendezvous) {
        pollJob = scope.launch {
            while (isActive) {
                // Blocking HTTP, moved off whatever dispatcher the UI scope uses. On Compose
                // Desktop that scope is the AWT event thread, and a ten-second connect timeout on
                // it is a frozen window.
                val outcome = withContext(ioDispatcher) { rendezvous.poll() }
                when (outcome) {
                    is PollOutcome.Waiting -> {
                        val current = step
                        if (current !is PairingStep.Waiting) return@launch
                        step = current.copy(
                            secondsRemaining = rendezvous.remainingMillis().toSeconds(),
                            note = outcome.detail?.let { "Cannot reach the server: $it. Still trying." },
                        )
                    }

                    is PollOutcome.Paired -> {
                        pending = outcome.bundle
                        // Remembered only now: an address that produced a bundle is one worth
                        // offering next time, and one that never did is a typo worth forgetting.
                        server?.let(recordWorkingServer)
                        step = PairingStep.Confirming(outcome.sas)
                        return@launch
                    }

                    is PollOutcome.Expired -> {
                        step = PairingStep.Failed(
                            "This code expired. Codes are good for two minutes; start over when " +
                                "your phone is ready."
                        )
                        return@launch
                    }

                    is PollOutcome.Failed -> {
                        step = PairingStep.Failed(outcome.message)
                        return@launch
                    }
                }
                delay(pollIntervalMillis)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    // -- finishing ------------------------------------------------------------------------------

    /** The user says the six digits match on both screens. */
    fun confirmSas() {
        if (step !is PairingStep.Confirming) return
        step = PairingStep.Confirmed
    }

    /** The user says they do not. Everything received is destroyed and never reaches storage. */
    fun rejectSas() {
        destroyPending()
        stopPolling()
        session = null
        step = PairingStep.Failed(
            "The two codes did not match, so nothing was saved. Start over, and make sure you " +
                "scanned this computer's code with your own phone."
        )
    }

    /** Back to the address field, from any state. */
    fun startOver() {
        destroyPending()
        stopPolling()
        session = null
        step = PairingStep.Address(server?.base ?: rememberedServer())
    }

    /**
     * Hand the received bundle to the caller, exactly once.
     *
     * Returns null after the first call and on every path that did not reach [PairingStep.Confirmed]
     * — including a rejected SAS. The single-use shape is deliberate: the ARK reaching storage twice
     * would mean two vaults, and the second one silently overwriting nothing is not a failure mode
     * worth leaving reachable.
     */
    fun takeBundle(): AccountBundle? {
        if (step !is PairingStep.Confirmed) return null
        val bundle = pending
        pending = null
        return bundle
    }

    /**
     * What the pairing agreed about the account's server, or null when it agreed nothing usable.
     *
     * Read from the **sealed** config, so the address here is the account device's authenticated
     * statement rather than the hint this computer typed and put in QR1 in the clear. Null covers
     * three cases and all three mean the same thing to a caller — this vault will not sync yet:
     *
     *  - the pairing is not confirmed;
     *  - the config named no server (a phone-to-phone-shaped bundle);
     *  - the config named a server but carried no `deviceId`, which is what an account device that
     *    could not vouch produces. A key with nothing to authenticate as cannot open a session, so
     *    storing the key and the address without the id would be storing a configuration that
     *    cannot work while looking as though it can.
     *
     * Takes the bundle's config from the argument rather than re-reading [pending], because
     * [takeBundle] has already consumed it and a second read would be null. The caller passes back
     * what it was handed.
     */
    fun enrolmentFrom(bundle: AccountBundle): PairedEnrolment? {
        val config = PairingConfig.decode(bundle.config) ?: return null
        val deviceId = config.deviceId ?: return null
        return PairedEnrolment(
            serverUrl = config.serverUrl,
            deviceId = deviceId,
            deviceKey = deviceKey,
        )
    }

    /** Abandon whatever is in flight. Called when the screen goes away and from [startOver]. */
    fun cancel() {
        destroyPending()
        stopPolling()
        session = null
    }

    override fun close() = cancel()

    private fun destroyPending() {
        // Zeroed rather than dropped. A dropped array is still in the heap until a collection that
        // may never come, and this process stays open for hours.
        pending?.ark?.fill(0)
        pending = null
    }

    private companion object {
        /**
         * 1.5 seconds.
         *
         * Fast enough that the pairing feels immediate once the phone sends, and slow enough that a
         * two-minute window is about 80 requests rather than thousands — which is what keeps an
         * honest client comfortably inside the server's general rate limit without needing an
         * exception carved for it.
         */
        const val POLL_INTERVAL_MILLIS = 1_500L
    }
}

/** Round *up*, so a code with 400 ms left reads "1s" rather than "0s" while it still works. */
private fun Long.toSeconds(): Int = ceil(this / 1000.0).toInt()
