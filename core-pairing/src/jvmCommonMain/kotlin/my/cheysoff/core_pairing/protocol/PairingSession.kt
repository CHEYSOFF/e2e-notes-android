package my.cheysoff.core_pairing.protocol

import java.security.KeyPair
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey

/**
 * What device A's session says after being shown a QR code.
 *
 * Sealed rather than a nullable pair so that "we ignored that, keep scanning" and "we produced
 * QR2" are not the same shape and cannot be confused at a call site.
 */
sealed interface OfferOutcome {

    /**
     * QR1 accepted. [sas] is the six digits to show, and [AccountDeviceSession.seal] now produces
     * QR2.
     *
     * The seal is **not** here, and the split is the whole point: between accepting an offer and
     * sealing the ARK, the account device may have to reach the server to enrol the device whose key
     * QR1 carried — a suspending call that cannot happen inside a method the camera analyser drives
     * frame by frame. The seal has to come afterwards because the id the server assigns goes
     * **inside** the sealed bundle; see [AccountDeviceSession.seal].
     */
    data class Accepted(val sas: String) : OfferOutcome

    /** Nothing usable. [failure] says whether that is fatal. */
    data class Rejected(val failure: PairingFailure) : OfferOutcome
}

/** What device B's session says after being shown a QR code. */
sealed interface SealOutcome {

    /**
     * QR2 opened. The account key is now on this device.
     *
     * [bundle] carries the ARK and must be handed straight to `SecureUnlockManager.adoptArk`,
     * which wraps it under this device's own passphrase. It is not stored by this module and the
     * session drops its own reference the moment this is returned.
     */
    data class Paired(val bundle: AccountBundle, val sas: String) : SealOutcome

    /** Nothing usable. [failure] says whether that is fatal. */
    data class Rejected(val failure: PairingFailure) : SealOutcome
}

/**
 * Device **B**: the new device, the one with no account key yet.
 *
 * Lifecycle, and the whole of B's half of the protocol:
 *  1. construction mints `sid` and the ephemeral pair `(eB, EB)` and builds [offerCode] (QR1);
 *  2. the UI renders [offerCode] and the user shows it to device A;
 *  3. every QR the camera resolves is handed to [onScanned] until one of them is device A's QR2;
 *  4. on success the caller gets the ARK and the SAS, and the session is closed.
 *
 * The class is a plain JVM object: no Android types, no coroutines, no I/O. It is driven entirely
 * by its constructor arguments and by [onScanned], which is what makes every branch below reachable
 * from a unit test.
 *
 * **Not thread-safe.** It is a per-screen object driven from a single ViewModel; the camera
 * analyser thread must post frames to that owner rather than call in directly. Serialising here
 * with a lock would hide the fact that this is a state machine with an order.
 */
class NewDeviceSession(
    private val keyDerivation: KeyDerivation,
    private val clock: MonotonicClock,
    serverHint: ServerHint = ServerHint.NONE,
    /**
     * This device's long-lived device public key, SEC1 uncompressed, or null.
     *
     * Null is the phone-to-phone case and is not a degraded one: that flow has no server, so there
     * is nothing to be enrolled on. A desktop always supplies one — it is the key the account device
     * will vouch for, and QR1 is the only channel in this protocol that a human authenticates.
     */
    devicePublicKey: ByteArray? = null,
    private val ttlMillis: Long = PairingProtocol.CODE_TTL_MILLIS,
    random: SecureRandom = SecureRandom(),
) {

    /** The session id. Public because the UI shows nothing of it but tests assert on it. */
    val sid: ByteArray = ByteArray(PairingProtocol.SID_SIZE_BYTES).also(random::nextBytes)

    private val ephemeral: KeyPair = P256.generateEphemeralKeyPair(random)
    private val encodedEb: ByteArray = P256.encodePublicKey(ephemeral.public as ECPublicKey)

    private val startedAt: Long = clock.elapsedMillis()

    /** Set once the session dies or completes; every later call is refused with this. */
    private var closedWith: PairingFailure? = null

    /** QR1: the text to render as a QR code. Constant for the life of the session. */
    val offerCode: String = PairingCodec.encodeOffer(sid, encodedEb, serverHint, devicePublicKey)

    /** Milliseconds until this session expires, floored at zero. */
    fun remainingMillis(): Long = (startedAt + ttlMillis - clock.elapsedMillis()).coerceAtLeast(0)

    /** True once the session is past its TTL. Checked on every scan, not only when asked. */
    fun isExpired(): Boolean = remainingMillis() == 0L

    /**
     * Offer one decoded QR string to the session.
     *
     * Called for **every** symbol the camera resolves, including the overwhelming majority that
     * are not pairing codes at all, so the common path is a five-character prefix comparison and a
     * [PairingFailure.NOT_A_PAIRING_CODE].
     */
    fun onScanned(text: String): SealOutcome {
        closedWith?.let { return SealOutcome.Rejected(PairingFailure.SESSION_CLOSED) }
        if (isExpired()) return SealOutcome.Rejected(close(PairingFailure.EXPIRED))

        val frame = try {
            PairingCodec.decodeSeal(text)
        } catch (e: PairingWireException) {
            return SealOutcome.Rejected(reject(e.failure))
        }

        // Replay binding, first of two. A QR2 from any other session -- an older attempt of our
        // own, or a code photographed off someone else's screen -- stops here. The second binding
        // is `sid` inside the GCM AAD and the HKDF salt, which would independently fail below even
        // if this comparison were deleted.
        if (!frame.sid.contentEquals(sid)) {
            return SealOutcome.Rejected(reject(PairingFailure.SESSION_MISMATCH))
        }

        val peer = try {
            // The invalid-curve check. Runs before any KeyAgreement exists. See P256.
            P256.decodePublicKey(frame.encodedEphemeralKey)
        } catch (e: InvalidPeerKeyException) {
            return SealOutcome.Rejected(reject(PairingFailure.INVALID_PEER_KEY))
        } catch (e: RuntimeException) {
            // Defence in depth against a provider throwing something unexpected at a stranger's
            // bytes. Reported as MALFORMED rather than as an invalid-curve attack because we do
            // not actually know which it was.
            return SealOutcome.Rejected(reject(PairingFailure.MALFORMED))
        }

        val encodedEa = frame.encodedEphemeralKey
        val sessionKey = deriveSessionKey(
            keyDerivation = keyDerivation,
            privateKey = ephemeral.private as ECPrivateKey,
            peer = peer,
            encodedEa = encodedEa,
            encodedEb = encodedEb,
            sid = sid,
        )

        val plaintext = PairingSeal.open(sessionKey, frame.nonce, sid, frame.seal)
            // The loud abort. A tag failure here is never transient and never retried: the session
            // is closed and the user is told the pairing failed. Retrying would mean re-running the
            // open against whatever the attacker shows next, which is the definition of an oracle.
            ?: return SealOutcome.Rejected(reject(PairingFailure.SEAL_REJECTED))

        val bundle = try {
            PairingCodec.decodeBundle(plaintext)
        } catch (e: PairingWireException) {
            // Authenticated but unparseable: device A is a version we do not understand. Terminal
            // regardless of what the wire failure's own isTerminal says, because we have already
            // consumed the one seal this session was going to get.
            return SealOutcome.Rejected(close(e.failure))
        } catch (e: IllegalArgumentException) {
            // AccountBundle's own `require`s -- an over-long accountId or config.
            return SealOutcome.Rejected(close(PairingFailure.MALFORMED))
        }

        val sas = Sas.derive(keyDerivation, bundle.ark, sid)
        // Success closes the session too: the ARK has been handed over, and a second scan must not
        // be able to hand over a different one.
        closedWith = PairingFailure.SESSION_CLOSED
        return SealOutcome.Paired(bundle = bundle, sas = sas)
    }

    /** Record a terminal failure; pass a non-terminal one straight through. */
    private fun reject(failure: PairingFailure): PairingFailure {
        if (failure.isTerminal) closedWith = failure
        return failure
    }

    private fun close(failure: PairingFailure): PairingFailure {
        closedWith = failure
        return failure
    }
}

/**
 * Device **A**: the device that already holds the account key.
 *
 * A's half is two steps — [onScanned] reads QR1, [seal] produces QR2 — and the gap between them is
 * deliberate. Everything cryptographic happens in [onScanned]: the on-curve checks, the ECDH, the
 * session key. What [seal] adds is the `config` blob, and that blob may have to be fetched from a
 * server first (the id the account's server assigns to the joining device), which is a suspending
 * call that cannot happen inside a method the camera analyser drives frame by frame.
 *
 * The other asymmetry worth knowing about is when the clock starts: A cannot tell how old a QR1 is
 * (see [PairingProtocol.CODE_TTL_MILLIS] for why no timestamp travels on the wire), so A's TTL
 * starts when it *accepts* an offer and governs how long QR2 stays on screen. The binding expiry is
 * B's, which runs from the moment B minted `sid`.
 *
 * **Not thread-safe**, for the same reason as [NewDeviceSession].
 */
class AccountDeviceSession(
    private val keyDerivation: KeyDerivation,
    private val clock: MonotonicClock,
    /**
     * The Account Root Key, opaque here. The caller owns the array; this class neither copies it nor
     * zeroes it, exactly as [AccountBundle] does not.
     */
    private val ark: ByteArray,
    /** The account handle that travels with the ARK. */
    private val accountId: String,
    private val ttlMillis: Long = PairingProtocol.CODE_TTL_MILLIS,
    private val random: SecureRandom = SecureRandom(),
) {

    // Takes the two halves of a bundle rather than an `AccountBundle`, because the third field --
    // `config` -- is not known when this session is constructed. It is decided between `onScanned`
    // and `seal`, which is the whole reason those are two calls; a session holding a `config` it
    // then ignored would be a field that lies.

    private var closedWith: PairingFailure? = null
    private var acceptedAt: Long? = null

    /**
     * What [onScanned] agreed, held until [seal] uses it. Null before an offer is accepted and
     * nulled the moment the seal is produced — the session key is the key the ARK is sealed under,
     * so it lives for exactly the window between the two calls and no longer.
     */
    private var accepted: AcceptedOffer? = null

    private class AcceptedOffer(
        val sid: ByteArray,
        val sessionKey: ByteArray,
        val encodedEa: ByteArray,
    )

    /**
     * The server hint device B sent, available after a successful [onScanned].
     *
     * Unauthenticated — it arrives in the clear in QR1, before anything has been agreed — so it is
     * exposed as a hint for Phase 3 to show the user, never as configuration to apply.
     */
    var receivedServerHint: ServerHint? = null
        private set

    /**
     * The `sid` this session accepted, available after a successful [onScanned].
     *
     * Exposed because the seal no longer necessarily goes back the way it came: when the new device
     * asked for a rendezvous, `sid` is the name the sealed bundle is filed under. It is not a
     * secret — it travels in QR1 in the clear and appears in a URL path — but it *is* this
     * session's identity, which is why the caller is handed the one the session actually used
     * rather than being trusted to re-parse QR1 and arrive at the same bytes.
     *
     * Copied on the way out: the caller must not be able to mutate the value the AAD and the HKDF
     * salt were built from.
     */
    var receivedSid: ByteArray? = null
        private set
        get() = field?.copyOf()

    /**
     * The joining device's long-lived public key, as it arrived in QR1, or null when the frame
     * carried none.
     *
     * **This is the only key this device may vouch for.** It came over the authenticated visual
     * channel — a person aimed this camera at that screen — which is what makes an enrolment built
     * on it meaningful. A key obtained any other way (asked of the server, read from the rendezvous)
     * would be a key whoever answered got to choose.
     *
     * Already validated as a point on P-256 by [onScanned]; a frame carrying an off-curve one is
     * rejected as [PairingFailure.INVALID_PEER_KEY] and never reaches here. Copied on the way out
     * for the same reason [receivedSid] is.
     */
    var receivedDeviceKey: ByteArray? = null
        private set
        get() = field?.copyOf()

    /**
     * Milliseconds until QR2 should be taken off the screen, floored at zero.
     *
     * Returns [ttlMillis] before an offer has been accepted: nothing is on screen yet, so nothing
     * is counting down.
     */
    fun remainingMillis(): Long {
        val accepted = acceptedAt ?: return ttlMillis
        return (accepted + ttlMillis - clock.elapsedMillis()).coerceAtLeast(0)
    }

    fun isExpired(): Boolean = acceptedAt != null && remainingMillis() == 0L

    /**
     * Offer one decoded QR string to the session. Called for every symbol the camera resolves.
     *
     * On success this agrees the session key and hands back the SAS. It does **not** seal anything:
     * see [seal], and [OfferOutcome.Accepted] for why the two are separate.
     */
    fun onScanned(text: String): OfferOutcome {
        closedWith?.let { return OfferOutcome.Rejected(PairingFailure.SESSION_CLOSED) }
        if (acceptedAt != null) {
            // One offer per session. A second QR1 would mean sealing the ARK a second time, to a
            // second device, from a screen the user believes is showing one finished pairing.
            return OfferOutcome.Rejected(PairingFailure.SESSION_CLOSED)
        }

        val frame = try {
            PairingCodec.decodeOffer(text)
        } catch (e: PairingWireException) {
            return OfferOutcome.Rejected(reject(e.failure))
        }

        val peer = try {
            // The invalid-curve check, on the side that matters most: this is the ECDH whose
            // private half seals the ARK. An attacker who could recover eA by feeding small-order
            // points would recover Ks and therefore the account key.
            P256.decodePublicKey(frame.encodedEphemeralKey)
        } catch (e: InvalidPeerKeyException) {
            return OfferOutcome.Rejected(reject(PairingFailure.INVALID_PEER_KEY))
        } catch (e: RuntimeException) {
            return OfferOutcome.Rejected(reject(PairingFailure.MALFORMED))
        }

        // The device key takes no part in the key schedule, so an invalid one costs nothing
        // cryptographically -- and it is still refused here rather than relayed, because the only
        // thing this device will ever do with it is ask a server to trust it. Validating at the
        // boundary means the vouching path never handles bytes that are not a point.
        val deviceKey = frame.encodedDeviceKey
        if (deviceKey != null) {
            try {
                P256.decodePublicKey(deviceKey)
            } catch (e: InvalidPeerKeyException) {
                return OfferOutcome.Rejected(reject(PairingFailure.INVALID_PEER_KEY))
            } catch (e: RuntimeException) {
                return OfferOutcome.Rejected(reject(PairingFailure.MALFORMED))
            }
        }

        val ephemeral = P256.generateEphemeralKeyPair(random)
        val encodedEa = P256.encodePublicKey(ephemeral.public as ECPublicKey)
        val encodedEb = frame.encodedEphemeralKey

        val sessionKey = deriveSessionKey(
            keyDerivation = keyDerivation,
            privateKey = ephemeral.private as ECPrivateKey,
            peer = peer,
            encodedEa = encodedEa,
            encodedEb = encodedEb,
            sid = frame.sid,
        )

        acceptedAt = clock.elapsedMillis()
        receivedServerHint = frame.serverHint
        receivedSid = frame.sid
        receivedDeviceKey = deviceKey
        accepted = AcceptedOffer(sid = frame.sid, sessionKey = sessionKey, encodedEa = encodedEa)
        return OfferOutcome.Accepted(sas = Sas.derive(keyDerivation, ark, frame.sid))
    }

    /**
     * Seal the ARK for the accepted offer and return QR2's payload. **Callable exactly once.**
     *
     * This is the moment the account key leaves this device, so every guard that could refuse has
     * already run: the offer was decoded, both points were checked against the curve, and the
     * session key was agreed. What is added here is [config] — the authenticated half of the
     * server configuration, carrying the address and the id the server assigned to the joining
     * device.
     *
     * Once for the same reason [NewDeviceSession] closes on success: a second call would seal the
     * ARK a second time, and a screen the user believes is showing one finished pairing would be
     * handing the account to whatever asked next.
     *
     * @return the QR2 payload, or null when there is no accepted offer to seal for — which covers
     *   both "nothing was scanned" and "this was already sealed".
     * @throws IllegalArgumentException if [config] is longer than [AccountBundle.MAX_CONFIG_BYTES].
     */
    fun seal(config: String): String? {
        val offer = accepted ?: return null
        // Cleared before the seal is built rather than after, so a caller that somehow re-enters
        // cannot find a live session key here.
        accepted = null
        val nonce = ByteArray(PairingProtocol.GCM_NONCE_SIZE_BYTES).also(random::nextBytes)
        val sealed = PairingSeal.seal(
            offer.sessionKey,
            nonce,
            offer.sid,
            AccountBundle(ark = ark, accountId = accountId, config = config),
        )
        return PairingCodec.encodeSeal(offer.sid, offer.encodedEa, nonce, sealed)
    }

    private fun reject(failure: PairingFailure): PairingFailure {
        if (failure.isTerminal) closedWith = failure
        return failure
    }
}

/**
 * `Ks = KeyDerivation(ikm = ECDH(own, peer), salt = sid, info = "manana/pair/v1" ‖ EA ‖ EB, 32)`.
 *
 * Shared by both roles precisely so there is one expression of the key schedule. The two devices
 * reach it with their arguments swapped in the ECDH (each holds its own private key and the
 * other's public one) and identical in `info`, which is what makes the outputs equal.
 *
 * A third party who photographs both QR codes has `EA`, `EB` and `sid` — every input to `info` and
 * to the salt — and still cannot compute `Ks`, because `ikm` is the ECDH secret and neither
 * private key was ever transmitted. `PairingSessionTest.observerWithBothPublicKeysCannotDerive`
 * is the test for that.
 */
internal fun deriveSessionKey(
    keyDerivation: KeyDerivation,
    privateKey: ECPrivateKey,
    peer: ECPublicKey,
    encodedEa: ByteArray,
    encodedEb: ByteArray,
    sid: ByteArray,
): ByteArray {
    val z = P256.sharedSecret(privateKey, peer)
    return keyDerivation.derive(
        ikm = z,
        salt = sid,
        info = PairingProtocol.sessionKeyInfo(encodedEa, encodedEb),
        outLen = PairingProtocol.SESSION_KEY_SIZE_BYTES,
    )
}
