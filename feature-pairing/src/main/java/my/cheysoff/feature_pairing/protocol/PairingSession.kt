package my.cheysoff.feature_pairing.protocol

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
     * QR1 accepted. [sealCode] is the text to render as QR2, and [sas] is the six digits to show
     * next to it.
     */
    data class Accepted(val sealCode: String, val sas: String) : OfferOutcome

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
    val offerCode: String = PairingCodec.encodeOffer(sid, encodedEb, serverHint)

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
 * A's half is a single step — scan QR1, produce QR2 — so this class is mostly the same guards as
 * [NewDeviceSession] pointing the other way. The one asymmetry worth knowing about is when the
 * clock starts: A cannot tell how old a QR1 is (see [PairingProtocol.CODE_TTL_MILLIS] for why no
 * timestamp travels on the wire), so A's TTL starts when it *accepts* an offer and governs how
 * long QR2 stays on screen. The binding expiry is B's, which runs from the moment B minted `sid`.
 *
 * **Not thread-safe**, for the same reason as [NewDeviceSession].
 */
class AccountDeviceSession(
    private val keyDerivation: KeyDerivation,
    private val clock: MonotonicClock,
    private val bundle: AccountBundle,
    private val ttlMillis: Long = PairingProtocol.CODE_TTL_MILLIS,
    private val random: SecureRandom = SecureRandom(),
) {

    private var closedWith: PairingFailure? = null
    private var acceptedAt: Long? = null

    /**
     * The server hint device B sent, available after a successful [onScanned].
     *
     * Unauthenticated — it arrives in the clear in QR1, before anything has been agreed — so it is
     * exposed as a hint for Phase 3 to show the user, never as configuration to apply.
     */
    var receivedServerHint: ServerHint? = null
        private set

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
     * On success this seals the ARK. That is the moment the account key leaves this device, so
     * every guard that could refuse has already run by the time [PairingSeal.seal] is called.
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

        val nonce = ByteArray(PairingProtocol.GCM_NONCE_SIZE_BYTES).also(random::nextBytes)
        val seal = PairingSeal.seal(sessionKey, nonce, frame.sid, bundle)
        val code = PairingCodec.encodeSeal(frame.sid, encodedEa, nonce, seal)

        acceptedAt = clock.elapsedMillis()
        receivedServerHint = frame.serverHint
        return OfferOutcome.Accepted(sealCode = code, sas = Sas.derive(keyDerivation, bundle.ark, frame.sid))
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
private fun deriveSessionKey(
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
