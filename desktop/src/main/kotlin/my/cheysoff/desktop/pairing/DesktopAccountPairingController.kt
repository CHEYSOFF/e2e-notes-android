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
import my.cheysoff.core_pairing.protocol.AccountInviteSession
import my.cheysoff.core_pairing.protocol.CollectResult
import my.cheysoff.core_pairing.protocol.DepositResult
import my.cheysoff.core_pairing.protocol.HkdfKeyDerivation
import my.cheysoff.core_pairing.protocol.HttpRendezvousClient
import my.cheysoff.core_pairing.protocol.MonotonicClock
import my.cheysoff.core_pairing.protocol.PairingConfig
import my.cheysoff.core_pairing.protocol.PairingFailure
import my.cheysoff.core_pairing.protocol.RendezvousClient
import my.cheysoff.core_pairing.protocol.RendezvousSlot
import my.cheysoff.core_pairing.protocol.RendezvousUrl
import my.cheysoff.core_pairing.protocol.ReplyOutcome
import kotlin.math.ceil

/** Where an invite has got to. Everything `AccountInviteScreen` draws comes from this. */
sealed interface InviteStep {

    /**
     * The QR is on screen and this computer is polling for an answer.
     *
     * [code] is the payload the screen renders. [note] is the last thing that went wrong with a
     * poll — a network blip — and is deliberately not a failure: the deadline is
     * [secondsRemaining], the session's own TTL on a monotonic clock.
     */
    data class Showing(
        val code: String,
        val secondsRemaining: Int,
        val host: String,
        val secure: Boolean,
        val note: String? = null,
    ) : InviteStep

    /**
     * A phone answered and both devices are showing six digits.
     *
     * **Nothing has been sealed, vouched or sent.** In this direction the comparison the user is
     * about to make is the only thing standing between an attacker who controls the rendezvous and
     * the account key, so it comes first and the account key does not move until it succeeds.
     */
    data class Confirming(val sas: String) : InviteStep

    /** The user confirmed. Vouching for the phone's key, sealing, and sending. */
    data object Finishing : InviteStep

    /** The phone has everything it needs. [enrolled] is false when the vouch was refused. */
    data class Done(val enrolled: Boolean, val note: String? = null) : InviteStep

    /** Stopped. Recoverable only by starting over. */
    data class Failed(val message: String) : InviteStep
}

/**
 * What this computer needs in order to be the account device: the key, the handle, and the id the
 * server knows it by.
 *
 * A parameter object rather than four constructor arguments, because the three are only meaningful
 * together — an ARK without the device id is an account this computer cannot vouch on, and a device
 * id without the ARK is an identity with nothing to share.
 */
class InviteAccount(
    /**
     * Hands back a **copy** of the account root key, which this controller zeroes the moment the
     * seal is built. A provider returning the live array would have the open vault's own key wiped
     * underneath it.
     */
    val arkProvider: () -> ByteArray?,
    /** `HKDF(ARK, ".../account")` as unpadded base64url — the handle the server files under. */
    val accountId: String,
    /** This computer's own device row, which is what makes it able to vouch for another. */
    val voucherDeviceId: String,
)

/** The one network act between the confirmation and the seal. */
fun interface DeviceVoucher {

    /**
     * Enrol [joiningDeviceKey] on the account and return the id the server assigned, or null.
     *
     * Null is not fatal to the pairing: the ARK can still cross, and the phone ends up with the
     * account and no way to open a session — which the screen says out loud rather than the user
     * discovering it at the first sync.
     */
    suspend fun vouchFor(joiningDeviceKey: ByteArray): String?
}

/**
 * Drives one invite: this computer holds the account and admits a phone to it.
 *
 * The mirror of [DesktopPairingController], and the ordering is the opposite way round in the one
 * place that matters. There, the ARK arrives sealed and is held until the user confirms. Here the
 * ARK is *sent*, so it must not be sealed at all until the user confirms — and the protocol makes
 * that structural rather than a matter of statement order: [AccountInviteSession] has no method
 * that seals, and `confirm()` is the sole factory for the object that does.
 *
 * So the sequence in [confirmSas], and nowhere else, is:
 *
 *  1. take the sealing authority the confirmation mints;
 *  2. vouch for the phone's device key, which yields the id that has to go inside the bundle;
 *  3. seal;
 *  4. deposit.
 *
 * Every one of those is after the person said the digits match.
 * `DesktopAccountPairingControllerTest.nothingIsSealedOrVouchedUntilTheSasIsConfirmed` is the test
 * that fails if any of it moves earlier.
 *
 * ## Logging
 *
 * There is none, for the reason the joining controller gives: the QR payload, the ARK and the SAS
 * are all either key material or a direct function of it, so the safe amount of logging is zero
 * rather than a careful amount.
 */
class DesktopAccountPairingController(
    private val scope: CoroutineScope,
    /** The rendezvous both devices meet at. It travels inside the QR, so the phone reads it here. */
    private val server: RendezvousUrl,
    private val account: InviteAccount,
    private val voucher: DeviceVoucher,
    /** A parameter so a test can drive the whole screen with no sockets. */
    private val clientFor: (RendezvousUrl) -> RendezvousClient = { HttpRendezvousClient(it) },
    /**
     * `System.nanoTime()`, not `currentTimeMillis()`: the TTL must not be movable from the Date &
     * Time control panel.
     */
    private val clock: MonotonicClock = MonotonicClock { System.nanoTime() / 1_000_000 },
    private val pollIntervalMillis: Long = POLL_INTERVAL_MILLIS,
    /** Injected, because work that escapes to the real IO pool is work a test cannot advance. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {

    private val session = AccountInviteSession(
        keyDerivation = HkdfKeyDerivation,
        clock = clock,
        server = server,
    )

    private val client: RendezvousClient = clientFor(server)

    var step by mutableStateOf<InviteStep>(
        InviteStep.Showing(
            code = session.inviteCode,
            secondsRemaining = session.remainingMillis().toSeconds(),
            host = server.host,
            secure = server.secure,
        )
    )
        private set

    private var pollJob: Job? = null
    private var finishJob: Job? = null

    init {
        startPolling()
    }

    // -- waiting for the phone ------------------------------------------------------------------

    /**
     * Ask the reply slot on a fixed interval until a phone answers or the invite expires.
     *
     * The countdown shown to the user and the poll are the same loop, so a stalled poll cannot
     * leave a timer running that says everything is fine.
     */
    private fun startPolling() {
        pollJob = scope.launch {
            while (isActive) {
                if (session.isExpired()) {
                    step = InviteStep.Failed(
                        "This code expired. Codes are good for two minutes; start over when your " +
                            "phone is ready."
                    )
                    return@launch
                }
                // Blocking HTTP, moved off whatever dispatcher the UI scope uses. On Compose
                // Desktop that scope is the AWT event thread, and a ten-second connect timeout on
                // it is a frozen window.
                val result = withContext(ioDispatcher) {
                    client.collect(session.sid, RendezvousSlot.REPLY)
                }
                when (result) {
                    is CollectResult.Pending -> tick(null)

                    // A poll that does not complete is not a failure: a laptop's network drops, and
                    // the deadline that matters is already counting.
                    is CollectResult.Unreachable -> tick(result.detail)

                    is CollectResult.Unusable -> {
                        step = InviteStep.Failed(
                            "The pairing server answered with something unusable: ${result.detail}"
                        )
                        return@launch
                    }

                    is CollectResult.Collected -> {
                        when (val outcome = session.onReply(result.sealCode)) {
                            is ReplyOutcome.Agreed -> step = InviteStep.Confirming(outcome.sas)
                            is ReplyOutcome.Rejected ->
                                step = InviteStep.Failed(messageFor(outcome.failure))
                        }
                        return@launch
                    }
                }
                delay(pollIntervalMillis)
            }
        }
    }

    private fun tick(detail: String?) {
        val current = step as? InviteStep.Showing ?: return
        step = current.copy(
            secondsRemaining = session.remainingMillis().toSeconds(),
            note = detail?.let { "Cannot reach the server: $it. Still trying." },
        )
    }

    // -- finishing ------------------------------------------------------------------------------

    /**
     * The user says the six digits match on both screens.
     *
     * This is the moment the account key becomes sealable, and everything that hands it over
     * happens inside this call's coroutine. Nothing before it vouched for a key or produced a
     * ciphertext.
     */
    fun confirmSas() {
        if (step !is InviteStep.Confirming) return
        val authority = session.confirm() ?: run {
            step = InviteStep.Failed("This pairing attempt is finished. Start over to try again.")
            return
        }
        step = InviteStep.Finishing
        finishJob = scope.launch {
            // First, because the id the server assigns has to go INSIDE the seal: every endpoint
            // that would tell the phone its own id needs a session, and opening a session needs the
            // id. The pairing seal is the one channel that exists.
            val deviceId = voucher.vouchFor(authority.joiningDeviceKey)

            val ark = account.arkProvider()
            if (ark == null) {
                authority.discard()
                step = InviteStep.Failed(
                    "This computer's account key is no longer available — it locked. Unlock it and " +
                        "start over."
                )
                return@launch
            }
            val sealCode = try {
                authority.seal(
                    ark = ark,
                    accountId = account.accountId,
                    config = PairingConfig.encode(
                        serverUrl = server.base,
                        deviceId = deviceId.orEmpty(),
                    ),
                )
            } finally {
                // A copy, zeroed the moment it has been sealed. The vault's own array is untouched.
                ark.fill(0)
            }
            if (sealCode == null) {
                step = InviteStep.Failed("This pairing attempt is finished. Start over to try again.")
                return@launch
            }

            when (val result = withContext(ioDispatcher) {
                client.deposit(session.sid, RendezvousSlot.BUNDLE, sealCode)
            }) {
                // Both move on, and the second deliberately. A 409 means a previous attempt landed
                // and its response was lost -- in which case the phone already has it -- or that
                // somebody else got there first, in which case the phone collects a bundle it
                // cannot open and aborts loudly.
                is DepositResult.Deposited, DepositResult.AlreadyDeposited -> {
                    step = InviteStep.Done(
                        enrolled = deviceId != null,
                        note = if (deviceId == null) {
                            "Your phone has the account key, but this computer could not enrol it " +
                                "on the server, so it cannot sync yet."
                        } else {
                            null
                        },
                    )
                }

                is DepositResult.Refused ->
                    step = InviteStep.Failed("The server refused to take the bundle: ${result.detail}")

                is DepositResult.Unreachable -> step = InviteStep.Failed(
                    "Could not reach ${server.host}: ${result.detail}. Nothing was sent; start over."
                )
            }
        }
    }

    /** The user says the digits do not match. Nothing was sealed, and now nothing ever will be. */
    fun rejectSas() {
        cancel()
        step = InviteStep.Failed(
            "The two codes did not match, so nothing was sent. That means the answer did not come " +
                "from the phone you are holding. Start over on a network you trust."
        )
    }

    /** Abandon whatever is in flight. Called when the screen goes away. */
    fun cancel() {
        pollJob?.cancel()
        pollJob = null
        finishJob?.cancel()
        finishJob = null
        session.cancel()
    }

    override fun close() = cancel()

    private companion object {

        /**
         * 1.5 seconds — the same interval the joining controller uses, and for the same arithmetic:
         * a two-minute window is about 80 requests rather than thousands, which keeps an honest
         * client comfortably inside the server's general rate limit.
         */
        const val POLL_INTERVAL_MILLIS = 1_500L
    }
}

/** Round *up*, so a code with 400 ms left reads "1s" rather than "0s" while it still works. */
private fun Long.toSeconds(): Int = ceil(this / 1000.0).toInt()

/**
 * User-facing text for a failure while waiting for a reply.
 *
 * Written for someone looking at a laptop that has just been answered by a server, which is why
 * none of these say "the other phone": at this point nothing has established that a phone was
 * involved at all, and that is precisely what has gone wrong in most of these cases.
 */
private fun messageFor(failure: PairingFailure): String = when (failure) {
    PairingFailure.INVALID_PEER_KEY ->
        "The answer contains an invalid key. One produced by Mañana never does, so this did not " +
            "come from your phone. Nothing was sent."

    PairingFailure.EXPIRED ->
        "This code expired. Codes are good for two minutes; start over when your phone is ready."

    PairingFailure.SESSION_MISMATCH ->
        "The server returned an answer from a different pairing attempt. Nothing was sent — " +
            "start over."

    PairingFailure.SESSION_CLOSED -> "This pairing attempt is finished. Start over to try again."

    // Over HTTP there is no next frame: the one body the server had did not parse, and it is not
    // going to send a second one. So the failures that are merely a hint to a camera are terminal
    // here, exactly as they are on the joining side's rendezvous path.
    PairingFailure.NOT_A_PAIRING_CODE,
    PairingFailure.UNSUPPORTED_VERSION,
    PairingFailure.WRONG_CODE_KIND,
    PairingFailure.MALFORMED,
    PairingFailure.SEAL_REJECTED,
    -> "The server returned something that is not an answer this version understands. Nothing was " +
        "sent — start over."
}
