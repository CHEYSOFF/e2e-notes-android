package my.cheysoff.core_pairing.protocol

import java.security.SecureRandom

/** What one poll of the rendezvous produced. */
sealed interface PollOutcome {

    /** Nothing yet. The ordinary answer until the other device sends. [detail] is for the UI. */
    class Waiting(val detail: String? = null) : PollOutcome

    /**
     * The bundle is open and the ARK is on this device.
     *
     * [bundle] must be handed to `DesktopVault.setUp(passphrase, AccountOrigin.PAIRED, ark)` and
     * nowhere else — **after** the user has confirmed [sas]. This class does not store it, does not
     * log it, and drops its own reference the moment this is returned.
     */
    class Paired(val bundle: AccountBundle, val sas: String) : PollOutcome

    /**
     * Stopped, and not by a timeout.
     *
     * [failure] is the protocol's own reason where there is one — a GCM tag failure, an off-curve
     * point — and null when the *server* was the problem rather than the bundle.
     */
    class Failed(val failure: PairingFailure?, val message: String) : PollOutcome

    /** The session outlived its TTL. Terminal, and the same rule the scanned flow enforces. */
    data object Expired : PollOutcome
}

/**
 * Device **B** over the rendezvous: shows QR1, then polls instead of scanning QR2.
 *
 * ## What this is, and what it deliberately is not
 *
 * It is a [NewDeviceSession] with an HTTP source in front of it. It is **not** a second
 * implementation of B's half of the protocol: every collected body goes through
 * [RendezvousProtocol.fromBlob] and straight into [NewDeviceSession.onScanned], which is the same
 * method a camera frame reaches. The `sid` comparison, the on-curve check on `EA`, the key
 * schedule, the GCM open and every terminal failure are the scanned flow's, unchanged and unforked.
 *
 * That is the whole design rule for this class. If a guard ever needs to be added for the HTTP leg
 * specifically, it belongs in [RendezvousProtocol] or the client, not here — because a check that
 * lives only on this path is a check the phone-to-phone path does not have, and vice versa.
 *
 * ## Failure policy
 *
 * A poll that does not complete is [PollOutcome.Waiting], not a failure: a laptop's network drops,
 * and the deadline that matters is the session TTL, which is already counting. A poll that
 * *completes* with something unusable is terminal — the server answering wrongly is not a
 * condition that improves by asking again.
 *
 * **Not thread-safe**, for the same reason [NewDeviceSession] is not: it is a per-screen object
 * driven from one owner. The owner may run [poll] on a background thread, but only one at a time.
 */
class NewDeviceRendezvous(
    private val client: RendezvousClient,
    keyDerivation: KeyDerivation,
    clock: MonotonicClock,
    /** The server this device is asking the phone to send through. Travels in QR1. */
    server: RendezvousUrl,
    /**
     * This device's long-lived device public key, SEC1 uncompressed.
     *
     * Not optional on this path, unlike on [NewDeviceSession]. A device pairing through a server is
     * by definition a device that intends to sync with one, and a pairing that handed over the ARK
     * without enrolling the key would leave the new device holding an account it cannot open a
     * session for — able to read its own notes and never able to receive another device's.
     */
    devicePublicKey: ByteArray,
    ttlMillis: Long = PairingProtocol.CODE_TTL_MILLIS,
    random: SecureRandom = SecureRandom(),
) {

    private val session = NewDeviceSession(
        keyDerivation = keyDerivation,
        clock = clock,
        // `ServerHint` has carried a url since the format was written and it was empty until this
        // class filled it. It is a hint, not configuration: the account device shows the host to
        // the user and asks, and the authoritative copy is the one inside the seal.
        serverHint = ServerHint(url = server.base),
        devicePublicKey = devicePublicKey,
        ttlMillis = ttlMillis,
        random = random,
    )

    /** Whether this session finished or died. Once true, [poll] only ever returns the same answer. */
    private var finished = false

    /** QR1: the text to render. Constant for the life of the session. */
    val offerCode: String get() = session.offerCode

    /** Milliseconds until this session expires, floored at zero. */
    fun remainingMillis(): Long = session.remainingMillis()

    /**
     * Ask the server once.
     *
     * Blocking — it makes an HTTP request on the calling thread. The caller dispatches and paces;
     * nothing here sleeps, so the polling interval is a decision the UI makes and can show.
     */
    fun poll(): PollOutcome {
        if (finished) return PollOutcome.Failed(PairingFailure.SESSION_CLOSED, SESSION_OVER)
        // Checked before the request rather than after, so an expired session stops asking a server
        // for something it would refuse to use anyway.
        if (session.isExpired()) {
            finished = true
            return PollOutcome.Expired
        }

        return when (val result = client.collect(session.sid)) {
            is CollectResult.Pending -> PollOutcome.Waiting()

            is CollectResult.Unreachable -> PollOutcome.Waiting(result.detail)

            is CollectResult.Unusable -> {
                finished = true
                // failure = null: nothing about the *pairing* failed. The server said something
                // this client cannot use, which is a different sentence to say to the user than
                // "that bundle would not open".
                PollOutcome.Failed(null, "The pairing server answered with something unusable: ${result.detail}")
            }

            is CollectResult.Collected -> open(result.sealCode)
        }
    }

    private fun open(sealCode: String): PollOutcome {
        finished = true
        return when (val outcome = session.onScanned(sealCode)) {
            is SealOutcome.Paired -> PollOutcome.Paired(outcome.bundle, outcome.sas)
            is SealOutcome.Rejected -> PollOutcome.Failed(outcome.failure, messageFor(outcome.failure))
        }
    }

    private companion object {
        const val SESSION_OVER = "This pairing attempt is finished. Start over to try again."
    }
}

/**
 * User-facing text for a failure on the rendezvous path.
 *
 * Deliberately a separate list from the phone's, and worth the duplication: the two flows fail for
 * the same protocol reasons but the user is looking at different things. "It was meant for a
 * different phone" is the right sentence when two phones are held up to each other; on a laptop
 * that has just polled a server, the useful sentence names the server.
 */
private fun messageFor(failure: PairingFailure): String = when (failure) {
    PairingFailure.SEAL_REJECTED ->
        "The bundle from the server could not be opened. It was sealed for a different pairing, " +
            "or it was modified on the way here. Nothing was saved — start over."

    PairingFailure.INVALID_PEER_KEY ->
        "The bundle from the server contains an invalid key. One produced by Mañana never does, " +
            "so this one did not come from your phone. Nothing was saved."

    PairingFailure.EXPIRED ->
        "This code expired. Codes are good for two minutes; start over when your phone is ready."

    PairingFailure.SESSION_MISMATCH ->
        "The server returned a bundle from a different pairing attempt. Nothing was saved — " +
            "start over."

    PairingFailure.SESSION_CLOSED -> "This pairing attempt is finished. Start over to try again."

    // The remaining wire failures are non-terminal for a camera pointed at the world, where the
    // next frame is another chance. Over HTTP there is no next frame: the one body the server had
    // did not parse, and it is not going to send a second one.
    PairingFailure.NOT_A_PAIRING_CODE,
    PairingFailure.UNSUPPORTED_VERSION,
    PairingFailure.WRONG_CODE_KIND,
    PairingFailure.MALFORMED,
    -> "The server returned something that is not a pairing bundle this version understands. " +
        "Nothing was saved — start over."
}
