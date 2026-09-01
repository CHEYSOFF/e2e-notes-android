package my.cheysoff.core_pairing.protocol

import java.net.URI
import java.util.Base64

/**
 * The server-assisted return leg of a pairing, for the case where the new device cannot be
 * scanned.
 *
 * ---------------------------------------------------------------------------------------------
 * # WHY THIS EXISTS
 * ---------------------------------------------------------------------------------------------
 *
 * The phone-to-phone handshake is two QR codes: the new device shows QR1, the account device scans
 * it and shows QR2, the new device scans that. It is fully offline and no server is involved, and
 * **that flow is unchanged** — two phones still pair with nothing in the middle.
 *
 * A laptop breaks the second scan. It has no camera anyone can rely on, and QR2 carries a sealed
 * ARK — several kilobytes — which is not something a person can retype.
 *
 * So the second leg, and **only** the second leg, travels through a server: the account device
 * POSTs the sealed bundle under `sid`, and the new device polls for it. The first leg is untouched
 * — QR1 is still shown on the new device's screen and read by the account device's camera.
 *
 * ---------------------------------------------------------------------------------------------
 * # WHY THIS DOES NOT WEAKEN ANYTHING
 * ---------------------------------------------------------------------------------------------
 *
 * The QR remains an **authenticated visual channel**, and it is the whole reason a man in the
 * middle is structurally impossible here. The only key the account device has to authenticate is
 * `EB`, and it obtains `EB` by a human pointing a camera at the new device's screen. There is no
 * channel for an attacker to interpose on: they would have to be physically holding the laptop the
 * user is looking at.
 *
 * What travels over HTTP is the **QR2 frame, byte for byte** — the same bytes the phone-to-phone
 * flow renders as a QR symbol. It is AES-256-GCM ciphertext under `Ks`, and `Ks` comes from an
 * ECDH between two ephemeral keys the server has never seen one half of. The server holds
 * ciphertext under a key it does not have and cannot derive.
 *
 * ## What the server DOES learn
 *
 * Being precise about this matters more than the reassurance above:
 *
 *  - **That a pairing happened, and when.** A row appears and is collected.
 *  - **From which IP addresses.** The depositing device's and the collecting device's, and that
 *    the two are the same pairing. On a home connection that is one address for both.
 *  - **The blob's size**, which varies with the account id and the config blob inside the seal.
 *    That is a few bytes of shape, not content.
 *  - **`sid`**, which is 16 random bytes minted for this attempt and meaningful nowhere else.
 *
 * What it does not learn: the ARK, the account id, the config, or anything derived from them. It
 * cannot decrypt the blob, and it has no code that could.
 *
 * ## What plain HTTP costs
 *
 * The server speaks plain HTTP and expects a TLS-terminating proxy in front of it (see
 * `server/Main.kt`). Without one, an on-path attacker sees exactly what the server sees, and can
 * additionally **race the collect** or **substitute the blob**. Neither is a compromise: a stolen
 * blob still does not open without `eB`, and a substituted one fails the GCM tag, which
 * [NewDeviceSession] treats as terminal and loud. Both are denial of service, and both end with
 * the user starting over.
 */
object RendezvousProtocol {

    /**
     * Path template for one pairing rendezvous. `{sid}` is [encodeSid]'s output.
     *
     * Versioned as `v1` alongside the rest of the server's routes rather than with the pairing
     * protocol's own version byte: this is an HTTP resource, and the thing that would change its
     * shape is a server change, not a change to what is sealed inside the blob.
     */
    const val PATH_PREFIX = "/v1/pair/"

    /** JSON field carrying the blob, in both directions. */
    const val FIELD_SEALED = "sealed"

    /** JSON field carrying the deposit's expiry, epoch milliseconds. Advisory; see [Deposited]. */
    const val FIELD_EXPIRES_AT = "expiresAt"

    /**
     * Largest blob this client will send or accept, in decoded bytes.
     *
     * Derived from the protocol rather than picked, so it cannot drift away from what
     * [PairingCodec.encodeSeal] can actually produce: a QR2 frame is a fixed 98-byte head plus a
     * seal, and a seal is the largest legal bundle plus a GCM tag.
     *
     * The server enforces a bound of its own and a looser one, deliberately — it does not parse the
     * frame, so an exact size there would be the server asserting a client format it has chosen not
     * to know. This is the tight check, and it runs on both devices.
     */
    val MAX_SEALED_BYTES: Int = run {
        val bundle = 1 + // bundle version
            1 + AccountBundle.ARK_SIZE_BYTES +
            1 + AccountBundle.MAX_ACCOUNT_ID_BYTES +
            2 + AccountBundle.MAX_CONFIG_BYTES
        val seal = bundle + PairingProtocol.GCM_TAG_SIZE_BITS / 8
        val head = 1 + // frame version
            1 + // kind
            PairingProtocol.SID_SIZE_BYTES +
            1 + P256.POINT_SIZE_BYTES +
            PairingProtocol.GCM_NONCE_SIZE_BYTES +
            2 // seal length prefix
        head + seal
    }

    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder: Base64.Decoder = Base64.getUrlDecoder()

    /** `sid` as the path segment it is filed under: 22 characters of unpadded base64url. */
    fun encodeSid(sid: ByteArray): String {
        require(sid.size == PairingProtocol.SID_SIZE_BYTES) {
            "a sid is ${PairingProtocol.SID_SIZE_BYTES} bytes"
        }
        return encoder.encodeToString(sid)
    }

    /**
     * The body of a deposit: QR2's payload with its `MNP1:` prefix removed.
     *
     * The prefix is a fast reject for a camera pointed at the world and means nothing over HTTP,
     * where the URL already says what the bytes are. Dropping it also keeps the field pure
     * base64url, which is the only thing the server checks it for.
     *
     * @throws IllegalArgumentException if [sealCode] is not something [PairingCodec.encodeSeal]
     *   produced. That is a programming error, not untrusted input: the only caller holds the
     *   string it was just handed by its own session.
     */
    fun toBlob(sealCode: String): String {
        require(sealCode.startsWith(PairingProtocol.QR_PREFIX)) {
            "a rendezvous blob is made from a QR2 payload"
        }
        return sealCode.substring(PairingProtocol.QR_PREFIX.length)
    }

    /**
     * The inverse: a collected blob as the QR2 payload [NewDeviceSession.onScanned] expects.
     *
     * Putting the prefix back rather than teaching the session a second input shape is the point of
     * this pair of functions. The new device runs **exactly** the code path it would have run had a
     * camera read the symbol — same decode, same `sid` comparison, same on-curve check, same GCM
     * open, same terminal failures — so the HTTP leg cannot skip a guard the scanned leg has.
     */
    fun fromBlob(blob: String): String = PairingProtocol.QR_PREFIX + blob

    /**
     * True when [blob] is base64url of at most [MAX_SEALED_BYTES] bytes.
     *
     * Checked on the receiving side before the string is handed anywhere, because a collect is the
     * one input on the new device that arrives from a machine nobody watched the user point at.
     */
    fun isPlausibleBlob(blob: String): Boolean {
        if (blob.isEmpty()) return false
        // 4 base64 characters per 3 bytes; reject on length before allocating the decode.
        if (blob.length > 4 * (MAX_SEALED_BYTES + 2) / 3 + 4) return false
        val bytes = try {
            decoder.decode(blob)
        } catch (e: IllegalArgumentException) {
            return false
        }
        return bytes.isNotEmpty() && bytes.size <= MAX_SEALED_BYTES
    }
}

/**
 * A rendezvous server address that has been checked for shape.
 *
 * The raw string arrives in QR1 **in the clear and unauthenticated** — nothing has been agreed at
 * the point it is read — so the account device must never simply act on it. [host] exists so the
 * device can show the user where the sealed bundle is about to go, and the user, who is looking at
 * their own laptop, is the one who decides that it is right.
 *
 * That check is not theatre. Without it, QR1 is a primitive for making someone's phone POST to an
 * arbitrary address: a code printed on a poster would send the reader's phone at whatever host the
 * printer chose. The bundle is sealed to a key that host does not have, so nothing leaks — but the
 * request itself, from inside the user's network, is worth refusing to make silently.
 */
class RendezvousUrl private constructor(
    /** The normalised base, with no trailing slash. */
    val base: String,
    /** `host` or `host:port` — what the user is shown and asked about. */
    val host: String,
    /** True for `https`. False means an on-path attacker can see and disrupt; see below. */
    val secure: Boolean,
) {
    override fun toString(): String = base

    companion object {

        /** Largest URL accepted, matching the QR1 field's own bound. */
        const val MAX_CHARS = ServerHint.MAX_URL_BYTES

        /**
         * Parse and normalise, or return null.
         *
         * Rejects, and each for a reason rather than for tidiness:
         *  - anything but `http`/`https`, so a `file:`, `jar:` or app-scheme URL in a QR code
         *    cannot reach a URL opener;
         *  - a missing host;
         *  - **user info** (`http://user:pass@host/`), which is a credential in a QR code and is
         *    also how a host is disguised in a string a user is being asked to read;
         *  - a query or a fragment, which this client would drop anyway when it appends its path —
         *    silently changing where a request goes is worse than refusing.
         *
         * `http` is accepted, not only `https`. The server ships speaking plain HTTP behind a
         * TLS-terminating proxy, and refusing `http` would make a LAN deployment unpairable while
         * buying nothing this protocol depends on: what crosses the wire is ciphertext under an
         * ephemeral key, and [secure] is exposed so the UI can say which of the two it got.
         */
        fun parse(raw: String): RendezvousUrl? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.length > MAX_CHARS) return null
            val uri = try {
                URI(trimmed)
            } catch (e: Exception) {
                return null
            }
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            val host = uri.host ?: return null
            if (host.isEmpty()) return null
            if (uri.userInfo != null) return null
            if (uri.query != null || uri.fragment != null) return null

            val port = uri.port
            val authority = if (port == -1) host else "$host:$port"
            val path = uri.path.orEmpty().trimEnd('/')
            val base = "$scheme://$authority$path"
            // Bounded in BYTES as well as characters, because that is the unit QR1's `url` field
            // is bounded in. A non-ASCII host is more bytes than characters, so a string that
            // passed the character check above can still be one `ServerHint` would reject with an
            // IllegalArgumentException -- from a constructor, on a screen with no way to catch it.
            if (base.toByteArray(Charsets.UTF_8).size > ServerHint.MAX_URL_BYTES) return null
            return RendezvousUrl(base = base, host = authority, secure = scheme == "https")
        }
    }
}

/** What a deposit did. */
sealed interface DepositResult {

    /**
     * The server accepted the blob.
     *
     * [expiresAt] is the server's own epoch-millisecond deadline and is **advisory**: it is the
     * server's clock, not this device's, and the binding deadline is the new device's session TTL
     * measured against a monotonic clock. It is carried so a UI can say "about two minutes" rather
     * than invent a number.
     */
    class Deposited(val expiresAt: Long) : DepositResult

    /**
     * Something under this `sid` was already deposited.
     *
     * Not retried and not overwritten. First write wins, which is what stops an attacker who can
     * guess `sid` from replacing a real blob with a decoy — and a second deposit from this device
     * would mean the ARK was sealed twice for one attempt.
     */
    data object AlreadyDeposited : DepositResult

    /** The server refused: full, rate-limited, or it does not speak this. [detail] is for the UI. */
    class Refused(val detail: String) : DepositResult

    /** The request did not complete. Retriable; the caller decides whether to. */
    class Unreachable(val detail: String) : DepositResult
}

/** What a collect attempt found. */
sealed interface CollectResult {

    /** The blob, as a QR2 payload ready for [NewDeviceSession.onScanned]. */
    class Collected(val sealCode: String) : CollectResult

    /** Nothing filed under this `sid` yet. The ordinary answer while polling. */
    data object Pending : CollectResult

    /**
     * The server answered, and what it answered is not usable.
     *
     * Terminal on purpose: a body that is not a plausible blob means this is not the server the
     * user thinks it is, or the row was written by something else. Polling past it would be
     * polling something hostile.
     */
    class Unusable(val detail: String) : CollectResult

    /** The request did not complete. Retriable. */
    class Unreachable(val detail: String) : CollectResult
}

/**
 * The two calls the rendezvous needs.
 *
 * An interface, so the desktop's polling loop and the phone's send path can both be driven in a
 * unit test with no server, no sockets and no clock — the same reason [KeyDerivation] and
 * [MonotonicClock] are interfaces. There is exactly one production implementation.
 *
 * **Blocking.** Both methods do network I/O on the calling thread. Callers dispatch; nothing here
 * chooses a dispatcher, because the two callers are a Compose desktop app and an Android ViewModel
 * and they do not agree on what "IO" means.
 */
interface RendezvousClient {

    /** Send the sealed QR2 payload for [sid]. Called once, by the device that holds the ARK. */
    fun deposit(sid: ByteArray, sealCode: String): DepositResult

    /** Ask for the blob filed under [sid]. Called repeatedly, by the new device. */
    fun collect(sid: ByteArray): CollectResult
}

/**
 * How a [RendezvousClient] is obtained for an address.
 *
 * A seam, because the address is only known at runtime — it is typed on the desktop and read off a
 * QR code on the phone — so a client cannot simply be injected. Production binds
 * `HttpRendezvousClient(url)`; a test binds an in-memory drop and drives the whole flow with no
 * sockets.
 */
fun interface RendezvousClientFactory {
    fun create(server: RendezvousUrl): RendezvousClient
}
