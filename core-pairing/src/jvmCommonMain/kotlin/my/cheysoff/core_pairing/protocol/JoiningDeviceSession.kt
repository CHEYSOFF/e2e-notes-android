package my.cheysoff.core_pairing.protocol

import java.security.KeyPair
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey

/** What [JoiningDeviceSession] says about a scanned invite. */
sealed interface InviteOutcome {

    /**
     * The invite decoded and a secret is agreed.
     *
     * [sas] is what this device shows; it must be **compared with the account device's** before
     * anything else happens, because in this direction that comparison is the man-in-the-middle
     * defence and not a formality — see [AccountInviteSession].
     *
     * [replyCode] is the payload to deposit in the rendezvous' reply slot. It carries only public
     * values: this device's ephemeral point and its long-lived device public key.
     */
    data class Accepted(
        val sas: String,
        val replyCode: String,
        /** The rendezvous the account device named, read off that device's own screen. */
        val server: ServerHint,
    ) : InviteOutcome

    /** Nothing usable. [failure] says whether that is fatal. */
    data class Rejected(val failure: PairingFailure) : InviteOutcome
}

/** What [JoiningDeviceSession.onBundle] made of a collected bundle. */
sealed interface BundleOutcome {

    /**
     * The bundle opened. The account key is now on this device.
     *
     * [bundle] must be handed straight to the platform's ARK store and nowhere else. This class
     * does not keep it, does not log it, and drops its own reference the moment this is returned.
     *
     * No SAS is returned with it, unlike [SealOutcome.Paired]. In this direction the digits were
     * shown and compared *before* the bundle was requested; repeating a value here would invite a
     * caller to treat arrival as the confirmation, which is the exact ordering this direction
     * cannot afford to get wrong.
     */
    class Opened(val bundle: AccountBundle) : BundleOutcome

    /** Nothing usable. [failure] says whether that is fatal. */
    class Rejected(val failure: PairingFailure) : BundleOutcome
}

/**
 * The joining device in the invite direction: it reads the QR and answers through the rendezvous.
 *
 * The mirror of [AccountInviteSession]. Read that class first — it carries the account of what this
 * direction guarantees and what it does not.
 *
 * ## The one rule that is easy to get wrong
 *
 * `EA` is taken from the **QR code**, and from nowhere else. The bundle frame that arrives later
 * carries an `EA` too, and [onBundle] compares it rather than using it. The QR's copy crossed an
 * authenticated visual channel — a person aimed this camera at that screen — and the bundle's copy
 * arrived from a server. Deriving the key from the arriving copy would quietly turn the one
 * authenticated value in this exchange into an unauthenticated one.
 *
 * **Not thread-safe.** A per-screen object driven from one owner, exactly like [NewDeviceSession].
 */
class JoiningDeviceSession(
    private val keyDerivation: KeyDerivation,
    private val clock: MonotonicClock,
    /**
     * This device's long-lived device public key, SEC1 uncompressed.
     *
     * Not optional. A device that answers an invite is asking to be enrolled on the account's
     * server, and a reply carrying no key would produce a pairing that hands over the account key
     * and leaves this device unable to open a session — able to read its own notes and never able
     * to receive another device's.
     */
    private val devicePublicKey: ByteArray,
    private val ttlMillis: Long = PairingProtocol.CODE_TTL_MILLIS,
    private val random: SecureRandom = SecureRandom(),
) {

    private val ephemeral: KeyPair = P256.generateEphemeralKeyPair(random)
    private val encodedEb: ByteArray = P256.encodePublicKey(ephemeral.public as ECPublicKey)

    private var closedWith: PairingFailure? = null

    /**
     * When the invite was accepted, or null before that.
     *
     * The TTL runs from acceptance rather than from construction, for the reason
     * [AccountDeviceSession] gives about the other direction: this device cannot tell how old the
     * code on the screen is, because no timestamp travels on the wire, so the only honest deadline
     * it can enforce is one measured from its own first look.
     */
    private var acceptedAt: Long? = null

    /** What the accepted invite agreed. Null before acceptance. */
    private var accepted: AcceptedInvite? = null

    private class AcceptedInvite(
        val sid: ByteArray,
        val sessionKey: ByteArray,
        val encodedEa: ByteArray,
    )

    /**
     * The `sid` of the accepted invite — the name both rendezvous slots are filed under.
     *
     * Copied on the way out: the caller must not be able to mutate the value the AAD and the HKDF
     * salt were built from.
     */
    var sid: ByteArray? = null
        private set
        get() = field?.copyOf()

    /** Milliseconds until this session expires, floored at zero; [ttlMillis] before acceptance. */
    fun remainingMillis(): Long {
        val started = acceptedAt ?: return ttlMillis
        return (started + ttlMillis - clock.elapsedMillis()).coerceAtLeast(0)
    }

    fun isExpired(): Boolean = acceptedAt != null && remainingMillis() == 0L

    /**
     * Offer one decoded QR string to the session.
     *
     * Called for every symbol the camera resolves, so the common path is a five-character prefix
     * comparison and a [PairingFailure.NOT_A_PAIRING_CODE].
     *
     * On success this agrees the secret, derives the digits and builds the reply — but it does not
     * send anything. Depositing is the caller's act, because it is the first moment this device
     * touches the network and a screen that has not yet told the user which host it is about to
     * reach has no business reaching it.
     */
    fun onScanned(text: String): InviteOutcome {
        closedWith?.let { return InviteOutcome.Rejected(PairingFailure.SESSION_CLOSED) }
        if (acceptedAt != null) {
            // One invite per session. A second would mean two live exchanges behind one set of
            // digits on screen.
            return InviteOutcome.Rejected(PairingFailure.SESSION_CLOSED)
        }

        val frame = try {
            PairingCodec.decodeInvite(text)
        } catch (e: PairingWireException) {
            return InviteOutcome.Rejected(reject(e.failure))
        }

        val peer = try {
            // The invalid-curve check. `KeyFactory.generatePublic` does not validate that a point
            // is on the curve; without this, a malicious invite could feed a small-order point and
            // learn this device's ephemeral private key from the resulting agreement.
            P256.decodePublicKey(frame.encodedEphemeralKey)
        } catch (e: InvalidPeerKeyException) {
            return InviteOutcome.Rejected(reject(PairingFailure.INVALID_PEER_KEY))
        } catch (e: RuntimeException) {
            return InviteOutcome.Rejected(reject(PairingFailure.MALFORMED))
        }

        val sharedSecret = P256.sharedSecret(ephemeral.private as ECPrivateKey, peer)
        val sas = Sas.deriveFromAgreement(
            keyDerivation = keyDerivation,
            sharedSecret = sharedSecret,
            sid = frame.sid,
            encodedEa = frame.encodedEphemeralKey,
            encodedEb = encodedEb,
        )
        // The session key is derived here rather than deferred behind a confirmation, unlike the
        // account device's. Withholding it would protect nothing: this side receives a sealed
        // bundle, it does not produce one, and opening a bundle nobody confirmed leaks nothing —
        // whereas SEALING one before confirmation would hand over the account key.
        val sessionKey = deriveSessionKey(
            keyDerivation = keyDerivation,
            privateKey = ephemeral.private as ECPrivateKey,
            peer = peer,
            encodedEa = frame.encodedEphemeralKey,
            encodedEb = encodedEb,
            sid = frame.sid,
        )
        sharedSecret.fill(0)

        acceptedAt = clock.elapsedMillis()
        sid = frame.sid
        accepted = AcceptedInvite(
            sid = frame.sid,
            sessionKey = sessionKey,
            encodedEa = frame.encodedEphemeralKey,
        )
        return InviteOutcome.Accepted(
            sas = sas,
            replyCode = PairingCodec.encodeReply(frame.sid, encodedEb, devicePublicKey),
            server = frame.serverHint,
        )
    }

    /**
     * Open the bundle collected from the rendezvous.
     *
     * Every guard the scanned direction runs on a QR2 runs here, on the same decoder: the version,
     * the kind, the `sid` echo, the on-curve check, the GCM tag, the bundle parse. The one guard
     * this path adds is the `EA` comparison, which the scanned direction has no need of because
     * there the joining device never saw an `EA` before the seal arrived.
     */
    fun onBundle(text: String): BundleOutcome {
        closedWith?.let { return BundleOutcome.Rejected(PairingFailure.SESSION_CLOSED) }
        val invite = accepted ?: return BundleOutcome.Rejected(PairingFailure.SESSION_CLOSED)
        if (isExpired()) return BundleOutcome.Rejected(close(PairingFailure.EXPIRED))

        val frame = try {
            PairingCodec.decodeSeal(text)
        } catch (e: PairingWireException) {
            return BundleOutcome.Rejected(reject(e.failure))
        }

        if (!frame.sid.contentEquals(invite.sid)) {
            return BundleOutcome.Rejected(reject(PairingFailure.SESSION_MISMATCH))
        }

        // Compared, never used. The key was derived from the QR's `EA` at acceptance; a bundle
        // naming a different point was produced by something that is not the computer whose screen
        // this device read, and saying so is more useful than letting it fail later as a tag error.
        if (!frame.encodedEphemeralKey.contentEquals(invite.encodedEa)) {
            return BundleOutcome.Rejected(reject(PairingFailure.SESSION_MISMATCH))
        }

        val plaintext = PairingSeal.open(invite.sessionKey, frame.nonce, invite.sid, frame.seal)
            // The loud abort. A tag failure is never transient and never retried: retrying would
            // mean running the open against whatever the attacker sends next, which is an oracle.
            ?: return BundleOutcome.Rejected(reject(PairingFailure.SEAL_REJECTED))

        val bundle = try {
            PairingCodec.decodeBundle(plaintext)
        } catch (e: PairingWireException) {
            // Authenticated but unparseable: the account device is a version this build does not
            // understand. Terminal whatever the wire failure says, because the one bundle this
            // session was going to get has been consumed.
            return BundleOutcome.Rejected(close(e.failure))
        } catch (e: IllegalArgumentException) {
            return BundleOutcome.Rejected(close(PairingFailure.MALFORMED))
        }

        closedWith = PairingFailure.SESSION_CLOSED
        invite.sessionKey.fill(0)
        accepted = null
        return BundleOutcome.Opened(bundle)
    }

    /** Give up. The session key is zeroed rather than dropped. */
    fun cancel() {
        accepted?.sessionKey?.fill(0)
        accepted = null
        closedWith = PairingFailure.SESSION_CLOSED
    }

    private fun reject(failure: PairingFailure): PairingFailure {
        if (failure.isTerminal) closedWith = failure
        return failure
    }

    private fun close(failure: PairingFailure): PairingFailure {
        closedWith = failure
        return failure
    }
}
