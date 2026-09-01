package my.cheysoff.core_pairing.protocol

import java.security.KeyPair
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey

/*
 * ---------------------------------------------------------------------------------------------
 * # THE INVITE DIRECTION
 * ---------------------------------------------------------------------------------------------
 *
 * The account device shows the code; the joining device reads it. That is the reverse of
 * [AccountDeviceSession]/[NewDeviceSession], and it exists because a laptop can create an account
 * and then has to admit a phone to it — and a laptop has a screen but no camera.
 *
 * ## The exchange
 *
 * ```
 *   account device (desktop)                      joining device (phone)
 *   ------------------------                      ----------------------
 *   mint sid, (eA, EA)
 *   show QR: {sid, EA, serverUrl}   ──screen──▶   camera reads it
 *                                                  mint (eB, EB)
 *                                                  Z = ECDH(eB, EA)
 *                                                  sas = f(Z, sid, EA, EB)
 *                    ◀──server, slot REPLY──       deposit {sid, EB, DB}
 *   Z = ECDH(eA, EB)
 *   sas = f(Z, sid, EA, EB)
 *
 *          ---- both screens show six digits; a PERSON compares them ----
 *
 *   confirm()  ⇒  the only object that can seal
 *   vouch for DB, seal {ARK, accountId, cfg}
 *   deposit  ──server, slot BUNDLE──▶             collect, open, abort loudly on a tag failure
 * ```
 *
 * ## What this direction gives up, said plainly
 *
 * In the scanned direction a man in the middle is **structurally impossible**. The account device
 * learns the joining device's ephemeral key `EB` by a person aiming a camera at the screen showing
 * it, so there is no channel to interpose on; the SAS there is a mis-scan check and nothing more.
 *
 * Here `EA` still crosses that authenticated visual channel — the phone reads it off the laptop's
 * own screen — but `EB` cannot come back the same way, because the laptop has no camera. It
 * travels through the rendezvous server. **An attacker who controls that path can substitute their
 * own `EB`,** and the protocol will happily agree a secret with them. Nothing detects it. The two
 * devices then display different six digits, and the person comparing those digits is the entire
 * defence.
 *
 * So: this direction's guarantee is *weaker* than the scanned direction's, it is probabilistic
 * (about one in a million per attempt, with no way to grind), and it is contingent on a human
 * actually comparing rather than tapping through. That is why the scanned direction remains the
 * one a first run offers first, why both screens must show the digits before anything is sealed,
 * and why the confirmation gate below is a capability object rather than a boolean.
 *
 * See [Sas.deriveFromAgreement], and `RendezvousProtocol` for what the server learns.
 * ---------------------------------------------------------------------------------------------
 */

/** What [AccountInviteSession] says about one collected reply. */
sealed interface ReplyOutcome {

    /**
     * The joining device answered and a secret is agreed. [sas] is the six digits to show.
     *
     * **Nothing is sealed at this point and nothing can be.** The account key has not been given
     * to this session, and the object that seals does not exist yet; see
     * [AccountInviteSession.confirm].
     */
    data class Agreed(val sas: String) : ReplyOutcome

    /** Nothing usable. [failure] says whether that is fatal. */
    data class Rejected(val failure: PairingFailure) : ReplyOutcome
}

/**
 * The account device in the invite direction: it shows the QR and waits to be answered.
 *
 * ## The confirmation gate
 *
 * This class **cannot seal**. It has no method that does, it is never given an ARK, and the key
 * the ARK would be sealed under is not derived while it is running. What [onReply] keeps is the
 * raw ECDH output, which is enough to display the SAS and is not enough to encrypt anything under
 * the protocol's schedule.
 *
 * [confirm] is the sole factory for [SealAuthority], whose constructor is `internal` to this
 * module. So before a person has confirmed the digits there is **no object in the process that can
 * produce a sealed bundle** — not one that is merely told to wait its turn, but none that exists.
 * Making the sealing capability come into being at confirmation is the strongest form of "this
 * cannot happen early" available without linear types: reordering statements does not reach it,
 * only adding a new factory would, and that is a change a reviewer sees.
 *
 * `DesktopAccountPairingControllerTest.nothingIsSealedOrVouchedUntilTheSasIsConfirmed` is the test
 * that fails if the sequencing above is ever moved earlier.
 *
 * **Not thread-safe**, for the same reason [AccountDeviceSession] is not.
 */
class AccountInviteSession(
    private val keyDerivation: KeyDerivation,
    private val clock: MonotonicClock,
    /**
     * The rendezvous this device is asking the phone to answer through.
     *
     * Travels inside the QR, so the phone reads it off this computer's own screen. That is what
     * makes it different from [ServerHint] on QR1, which the account device receives from a
     * stranger and must ask the user about: here the person holding the phone is looking at the
     * machine that named the address.
     */
    server: RendezvousUrl,
    private val ttlMillis: Long = PairingProtocol.CODE_TTL_MILLIS,
    private val random: SecureRandom = SecureRandom(),
) {

    /** The session id. It names both rendezvous slots and is the HKDF salt and part of the AAD. */
    val sid: ByteArray = ByteArray(PairingProtocol.SID_SIZE_BYTES).also(random::nextBytes)

    private val ephemeral: KeyPair = P256.generateEphemeralKeyPair(random)
    private val encodedEa: ByteArray = P256.encodePublicKey(ephemeral.public as ECPublicKey)

    private val startedAt: Long = clock.elapsedMillis()

    private var closedWith: PairingFailure? = null

    /** The QR payload this device displays. Constant for the life of the session. */
    val inviteCode: String =
        PairingCodec.encodeInvite(sid, encodedEa, ServerHint(url = server.base))

    /**
     * What [onReply] agreed, held until [confirm] takes it.
     *
     * Holds the raw ECDH secret and **not** the session key. The session key is derived inside
     * [confirm] and nowhere else, so for the whole window in which a person is looking at six
     * digits, the key the account root key would be sealed under has not been computed.
     */
    private var agreed: Agreement? = null

    private class Agreement(
        val sharedSecret: ByteArray,
        val encodedEb: ByteArray,
        val joiningDeviceKey: ByteArray,
    )

    /**
     * The joining device's long-lived public key, once a reply has been agreed.
     *
     * Exposed so the UI can say what it is about to vouch for. **It must not be enrolled from
     * here**: the vouch belongs after the confirmation, and [SealAuthority.joiningDeviceKey] is the
     * copy that only exists then. Copied on the way out.
     */
    var receivedDeviceKey: ByteArray? = null
        private set
        get() = field?.copyOf()

    /** Milliseconds until this invite expires, floored at zero. */
    fun remainingMillis(): Long = (startedAt + ttlMillis - clock.elapsedMillis()).coerceAtLeast(0)

    /** True once the session is past its TTL. Checked on every reply, not only when asked. */
    fun isExpired(): Boolean = remainingMillis() == 0L

    /**
     * Offer one collected reply frame to the session.
     *
     * Unlike the scanned direction's `onScanned`, this is fed from an HTTP response rather than
     * from a camera, so there is no stream of unrelated symbols to ignore cheaply — but the
     * failures are classified identically, because the same wire decoder produces them and a body
     * that arrived over HTTP deserves no more trust than one that arrived as pixels.
     */
    fun onReply(text: String): ReplyOutcome {
        closedWith?.let { return ReplyOutcome.Rejected(PairingFailure.SESSION_CLOSED) }
        if (isExpired()) return ReplyOutcome.Rejected(close(PairingFailure.EXPIRED))
        if (agreed != null) {
            // One reply per invite. A second would agree a second secret for one `sid`, and the
            // digits on screen would stop meaning the exchange the user is looking at.
            return ReplyOutcome.Rejected(PairingFailure.SESSION_CLOSED)
        }

        val frame = try {
            PairingCodec.decodeReply(text)
        } catch (e: PairingWireException) {
            return ReplyOutcome.Rejected(reject(e.failure))
        }

        if (!frame.sid.contentEquals(sid)) {
            return ReplyOutcome.Rejected(reject(PairingFailure.SESSION_MISMATCH))
        }

        val peer = try {
            // The invalid-curve check on the side that matters most: this is the ECDH whose private
            // half will seal the ARK. `KeyFactory.generatePublic` does NOT check that a point is on
            // the curve, so without this an attacker feeding small-order points recovers eA and
            // therefore the account key. P-256 gives nothing here for free; X25519 would have.
            P256.decodePublicKey(frame.encodedEphemeralKey)
        } catch (e: InvalidPeerKeyException) {
            return ReplyOutcome.Rejected(reject(PairingFailure.INVALID_PEER_KEY))
        } catch (e: RuntimeException) {
            return ReplyOutcome.Rejected(reject(PairingFailure.MALFORMED))
        }

        // The device key takes no part in the key schedule, so an invalid one costs nothing
        // cryptographically -- and it is still refused here rather than relayed, because the only
        // thing this device will ever do with it is ask a server to trust it.
        try {
            P256.decodePublicKey(frame.encodedDeviceKey)
        } catch (e: InvalidPeerKeyException) {
            return ReplyOutcome.Rejected(reject(PairingFailure.INVALID_PEER_KEY))
        } catch (e: RuntimeException) {
            return ReplyOutcome.Rejected(reject(PairingFailure.MALFORMED))
        }

        val sharedSecret = P256.sharedSecret(ephemeral.private as ECPrivateKey, peer)
        val sas = Sas.deriveFromAgreement(
            keyDerivation = keyDerivation,
            sharedSecret = sharedSecret,
            sid = sid,
            encodedEa = encodedEa,
            encodedEb = frame.encodedEphemeralKey,
        )
        agreed = Agreement(
            sharedSecret = sharedSecret,
            encodedEb = frame.encodedEphemeralKey,
            joiningDeviceKey = frame.encodedDeviceKey,
        )
        receivedDeviceKey = frame.encodedDeviceKey
        return ReplyOutcome.Agreed(sas)
    }

    /**
     * The person says the six digits match. **This is the only way a [SealAuthority] comes into
     * existence, and callable exactly once.**
     *
     * It derives the session key here, from the secret [onReply] agreed, and hands it to the
     * returned object. The session's own copy of the secret is zeroed on the way out, so after this
     * returns there is exactly one object in the process holding sealing material and it is the one
     * the caller is looking at.
     *
     * @return null when there is nothing to confirm — no reply agreed, or already confirmed.
     */
    fun confirm(): SealAuthority? {
        val agreement = agreed ?: return null
        // Cleared before the key is derived rather than after, so a re-entrant caller cannot find a
        // live secret here.
        agreed = null
        val sessionKey = keyDerivation.derive(
            ikm = agreement.sharedSecret,
            salt = sid,
            info = PairingProtocol.sessionKeyInfo(encodedEa, agreement.encodedEb),
            outLen = PairingProtocol.SESSION_KEY_SIZE_BYTES,
        )
        agreement.sharedSecret.fill(0)
        return SealAuthority(
            sid = sid.copyOf(),
            sessionKey = sessionKey,
            encodedEa = encodedEa.copyOf(),
            joiningDeviceKey = agreement.joiningDeviceKey,
            random = random,
        )
    }

    /** Give up on this invite. Any agreed secret is zeroed rather than dropped. */
    fun cancel() {
        agreed?.sharedSecret?.fill(0)
        agreed = null
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

/**
 * Permission to hand over the account key, minted by [AccountInviteSession.confirm] and by nothing
 * else.
 *
 * The constructor is `internal`, so no code outside this module can produce one, and the only
 * construction inside the module is the one line in `confirm`. That is the mechanism behind the
 * claim that the ARK cannot be sealed before a person confirmed the digits: it is not that the
 * seal is *scheduled* after the confirmation, it is that the object able to seal did not exist
 * before it.
 *
 * The account key is a parameter to [seal] rather than a field, so it also does not sit in this
 * object waiting: it arrives from the open vault at the moment it is used, and this class does not
 * copy, retain or log it.
 */
class SealAuthority internal constructor(
    private val sid: ByteArray,
    /** `Ks`. Zeroed by [seal] and by [discard]; there is no getter. */
    private var sessionKey: ByteArray?,
    private val encodedEa: ByteArray,
    /**
     * The joining device's long-lived public key, as the reply carried it and already checked
     * against the curve.
     *
     * **This is the only key that may be vouched for.** Asking the server which key to enrol would
     * hand that choice to whoever answered, which is precisely what `POST /v1/devices/authorize`
     * exists to prevent — and here it matters more than in the scanned direction, because the key
     * did not cross an authenticated visual channel. What ties it to the phone the user is holding
     * is that it was bound into the same reply whose `EB` produced the digits they just compared.
     */
    val joiningDeviceKey: ByteArray,
    private val random: SecureRandom,
) {

    /**
     * Seal the bundle for the confirmed exchange. **Callable exactly once.**
     *
     * @param ark the account root key. The caller owns the array; this class neither copies nor
     *   zeroes it, exactly as [AccountBundle] does not.
     * @return the payload to deposit in the rendezvous' bundle slot, or null if this authority has
     *   already been used.
     */
    fun seal(ark: ByteArray, accountId: String, config: String): String? {
        val key = sessionKey ?: return null
        // Taken before the seal is built, so a re-entrant caller finds nothing.
        sessionKey = null
        return try {
            val nonce = ByteArray(PairingProtocol.GCM_NONCE_SIZE_BYTES).also(random::nextBytes)
            val sealed = PairingSeal.seal(
                key,
                nonce,
                sid,
                AccountBundle(ark = ark, accountId = accountId, config = config),
            )
            PairingCodec.encodeSeal(sid, encodedEa, nonce, sealed)
        } finally {
            key.fill(0)
        }
    }

    /** Throw the capability away unused — a confirmation the caller then abandoned. */
    fun discard() {
        sessionKey?.fill(0)
        sessionKey = null
    }
}
