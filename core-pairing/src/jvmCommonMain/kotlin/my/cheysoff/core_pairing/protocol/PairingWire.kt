package my.cheysoff.core_pairing.protocol

import java.util.Base64

/**
 * The reason a pairing step did not succeed.
 *
 * [isTerminal] separates "ignore this and keep looking at the camera" from "stop, something is
 * wrong". The scanner feeds *every* QR symbol the camera resolves into the session — most of what a
 * phone points at is not a pairing code at all — so a non-terminal failure has to be cheap and
 * silent. A terminal one kills the session and is shown to the user.
 */
enum class PairingFailure(val isTerminal: Boolean) {

    /** Not a Mañana pairing code at all: no `MNP1:` prefix, or not valid base64url. */
    NOT_A_PAIRING_CODE(isTerminal = false),

    /**
     * A pairing code from a protocol version this build does not implement.
     *
     * Non-terminal so a user waving the camera around is not stopped by it, but the UI surfaces it
     * separately from [NOT_A_PAIRING_CODE]: "the other phone is running a different version" is an
     * actionable message and "that is a bus timetable" is not.
     */
    UNSUPPORTED_VERSION(isTerminal = false),

    /** A valid pairing code, but the wrong one for this step — QR2 offered where QR1 was wanted. */
    WRONG_CODE_KIND(isTerminal = false),

    /** Right prefix and version, but the bytes underneath do not parse. */
    MALFORMED(isTerminal = false),

    /**
     * The peer's ephemeral public key is not a valid P-256 point. **Terminal.**
     *
     * A QR symbol is error-corrected and checksummed, so a frame that decodes decoded correctly.
     * An off-curve point inside an otherwise well-formed v1 frame is not damage — it is an
     * invalid-curve attack, and continuing to scan would just offer the attacker another try.
     */
    INVALID_PEER_KEY(isTerminal = true),

    /**
     * The `sid` in the scanned code is not this session's `sid`.
     *
     * Non-terminal: the honest cause is a stale QR still on a screen nearby, or the user scanning
     * the wrong phone. The important part is that it does not *succeed* — this is the replay
     * protection, and it holds regardless of what the UI does with the message.
     */
    SESSION_MISMATCH(isTerminal = false),

    /** The session outlived [PairingProtocol.CODE_TTL_MILLIS]. **Terminal.** */
    EXPIRED(isTerminal = true),

    /**
     * AES-GCM rejected the seal. **Terminal, and loud.**
     *
     * This is the single most important failure in the protocol. A tag failure means the ciphertext,
     * the nonce, the AAD or the derived key is not what the sealing device produced — i.e. the
     * pairing is with the wrong device, or someone modified the code in flight. It is never a
     * transient condition and it must never be retried silently: a retry loop in front of a tampered
     * seal is an oracle. The session dies here and the user is told.
     */
    SEAL_REJECTED(isTerminal = true),

    /** The session already finished or already died. Any further input is refused. */
    SESSION_CLOSED(isTerminal = true),
}

/** Internal signal used by the wire codec; mapped to a [PairingFailure] at the session boundary. */
internal class PairingWireException(
    val failure: PairingFailure,
    message: String,
) : IllegalArgumentException(message)

/**
 * Protocol-wide constants and the exact byte layout of both QR payloads.
 *
 * ---------------------------------------------------------------------------------------------
 * # WIRE FORMAT — `manana/pair/v1`
 * ---------------------------------------------------------------------------------------------
 *
 * A future implementer must be able to reproduce both payloads byte for byte from this comment
 * alone. Everything below is normative. All integers are **big-endian and unsigned**. `‖` is
 * concatenation. Byte offsets are given for the fixed-position fields; everything after a
 * variable-length field is positioned relative to it.
 *
 * ## The QR symbol
 *
 * The text encoded into the QR symbol is ASCII:
 *
 * ```
 *     "MNP1:" ‖ base64url_nopad(frame)
 * ```
 *
 * `base64url_nopad` is RFC 4648 §5 (alphabet `A-Z a-z 0-9 - _`) with padding `=` omitted.
 * Base64url rather than raw bytes in QR byte-mode because the alphabet is URL- and log-safe and
 * survives every text pipeline a QR library might put it through; the ~33% size cost is affordable
 * at these payload sizes (QR1 ≈ 300 chars, QR2 ≈ 300 chars — both well inside a version-13 symbol
 * at error-correction level M, which is what [my.cheysoff.core_pairing.qr.QrCodes] emits).
 *
 * The `MNP1:` prefix is a fast reject: a camera pointed at the world resolves QR codes constantly,
 * and a five-character string comparison is what keeps the decoder from base64-decoding a Wi-Fi
 * credential on every frame. It is **not** a security boundary and it is **not** the version: the
 * version byte inside the frame is authoritative, and the prefix deliberately did not change when
 * that byte went from `0x01` to `0x02`. Keeping it means a build that speaks only v1 reports a v2
 * code as [PairingFailure.UNSUPPORTED_VERSION] — "the other device is running a different version",
 * which a person can act on — instead of [PairingFailure.NOT_A_PAIRING_CODE], which is silent.
 *
 * ## Frame header — both kinds
 *
 * ```
 *   off  len  field
 *   0    1    ver     = 0x02   protocol version. A frame with any other value is UNSUPPORTED_VERSION.
 *   1    1    kind    = 0x01 for QR1 (the offer, emitted by the new device)
 *                     = 0x02 for QR2 (the seal,  emitted by the device that holds the ARK)
 *   2    16   sid              session id: 16 bytes from SecureRandom, minted by the new device
 *   18   ..   body             kind-specific, below
 * ```
 *
 * ### Why v2 exists
 *
 * v1's QR1 body ended after the SPKI pin. v2 appends the new device's **long-lived device public
 * key**, and that is the whole difference; QR2 is byte-identical apart from its version byte, and
 * the key schedule, the seal and the SAS are unchanged.
 *
 * The version is bumped rather than the field being made optional, even though the decoder could
 * have tolerated its absence. A frame whose trailing bytes are optional is a frame two builds can
 * read differently, and [ByteReader.requireExhausted] refuses trailing bytes for exactly that
 * reason. The cost is that a v1 build and a v2 build cannot pair — which is the honest outcome,
 * because a v1 device offers no key and therefore cannot be enrolled on the account it is joining.
 *
 * `sid` appears in QR1 and is echoed verbatim in QR2. It is also the HKDF salt **and** part of the
 * GCM AAD, which is what binds a seal to exactly one pairing attempt: a QR2 captured from an
 * earlier session decodes fine, fails the `sid` comparison, and — even if that comparison were
 * removed — would derive a different `Ks` and fail the GCM tag.
 *
 * ## QR1 body — `kind = 0x01`, emitted by device B (the new device, no ARK)
 *
 * ```
 *   off  len       field
 *   18   1         epLen    = 65
 *   19   epLen     EB       SEC1 uncompressed P-256 point: 0x04 ‖ X(32) ‖ Y(32)
 *   84   2         urlLen   0..1024
 *   86   urlLen    url      server base URL, UTF-8. MAY be empty (length 0).
 *   ..   1         pinLen   0 or 32
 *   ..   pinLen    spkiPin  SHA-256 of the server's SubjectPublicKeyInfo. Absent when pinLen = 0.
 *   ..   1         dkLen    0 or 65
 *   ..   dkLen     DB       the new device's LONG-LIVED device public key, SEC1 uncompressed.
 *                          Absent when dkLen = 0.
 * ```
 *
 * `url` and `spkiPin` are a *hint*, not a decision: they carry a server the new device has already
 * been pointed at, so the user does not have to type it twice. They are unauthenticated at this
 * point (nothing has been agreed yet), and the authoritative server configuration is the `cfg`
 * field inside the seal, which is authenticated. Both may be empty, and both are on the
 * phone-to-phone path, where no server is involved at all.
 *
 * ### `DB`, and why it travels here rather than being fetched
 *
 * `DB` is **not** an ephemeral key and takes no part in the key schedule. It is the P-256 public key
 * the new device will sign its server sessions with, and it is in QR1 so that the account device
 * vouches for a key **a human pointed a camera at**. That is the property worth the 88 characters:
 * the only alternative — the account device asking the server which key to authorise — would let
 * whoever runs the server nominate the key, and enrolling an attacker-supplied device is precisely
 * what `POST /v1/devices/authorize` exists to make impossible.
 *
 * It is validated as a point on decode ([P256.decodePublicKey]) rather than relayed as opaque
 * bytes, for the reason [PairingFailure.INVALID_PEER_KEY] gives: a QR symbol that decodes decoded
 * correctly, so an off-curve point in a well-formed frame was put there deliberately.
 *
 * `dkLen = 0` is legal and means "this device is not asking to be enrolled" — which is every phone,
 * because the phone-to-phone flow never touches a server. Nothing about the handshake depends on
 * `DB`; a frame without it pairs exactly as v1 did.
 *
 * ## QR2 body — `kind = 0x02`, emitted by device A (the device that holds the ARK)
 *
 * ```
 *   off  len       field
 *   18   1         epLen    = 65
 *   19   epLen     EA       SEC1 uncompressed P-256 point
 *   84   12        nonce    AES-GCM nonce, 12 bytes from SecureRandom
 *   96   2         sealLen
 *   98   sealLen   seal     AES-256-GCM ciphertext ‖ 16-byte tag
 * ```
 *
 * ## Key schedule
 *
 * ```
 *   Z   = ECDH(eA, EB)  =  ECDH(eB, EA)          32 bytes, the x-coordinate, left-padded
 *   Ks  = KeyDerivation(ikm = Z,
 *                       salt = sid,               16 bytes
 *                       info = "manana/pair/v1" ‖ EA ‖ EB,   14 + 65 + 65 = 144 bytes
 *                       outLen = 32)
 * ```
 *
 * `EA` **before** `EB`, always, on both devices: each device knows which point is its own by its
 * role, so the ordering is fixed by role and not by who scanned first. `"manana/pair/v1"` is the
 * 14 ASCII bytes `6d 61 6e 61 6e 61 2f 70 61 69 72 2f 76 31` — no NUL, no length prefix. Binding
 * both public keys into `info` is what makes `Ks` a commitment to this exact pair of ephemeral
 * keys.
 *
 * ## Seal
 *
 * ```
 *   seal = AES-256-GCM(key = Ks, iv = nonce, aad = "manana/pair/v1" ‖ sid, plaintext = bundle)
 *   tag  = 128 bits, appended to the ciphertext (JCA's default layout)
 * ```
 *
 * with
 *
 * ```
 *   bundle:
 *     off  len       field
 *     0    1         pver   = 0x01   bundle version, versioned independently of the frame
 *     1    1         arkLen = 32
 *     2    arkLen    ARK    the Account Root Key, opaque to this module
 *     ..   1         idLen
 *     ..   idLen     accountId  UTF-8
 *     ..   2         cfgLen
 *     ..   cfgLen    cfg    UTF-8, opaque to this module (Phase 3 owns its schema)
 * ```
 *
 * `sid` is in the AAD as well as in the HKDF salt. Belt and braces on purpose: the salt binding
 * means a different `sid` yields a different key, and the AAD binding means that even if the salt
 * were ever dropped from the schedule, a seal still cannot be lifted from one session into another.
 *
 * ## SAS
 *
 * ```
 *   sas = decimal( KeyDerivation(ikm = ARK, salt = sid,
 *                                info = "manana/pair/v1/confirm", outLen = 8)  mod 1_000_000 )
 * ```
 * zero-padded to six digits. See [Sas].
 * ---------------------------------------------------------------------------------------------
 */
object PairingProtocol {

    /** ASCII marker on the front of every pairing QR. Not a security boundary — a fast reject. */
    const val QR_PREFIX = "MNP1:"

    /**
     * The only frame version this build produces or accepts.
     *
     * `0x02` since QR1 started carrying the joining device's long-lived public key. See the wire
     * format above for why that was a version bump rather than an optional trailing field.
     */
    const val VERSION: Byte = 0x02

    /** Frame kind: QR1, the offer from the new device. */
    const val KIND_OFFER: Byte = 0x01

    /** Frame kind: QR2, the seal from the device that holds the ARK. */
    const val KIND_SEAL: Byte = 0x02

    /** The only bundle (sealed plaintext) version this build produces or accepts. */
    const val BUNDLE_VERSION: Byte = 0x01

    /** Length of a session id, in bytes. 128 bits of SecureRandom. */
    const val SID_SIZE_BYTES = 16

    /** AES-GCM nonce length in bytes. 96 bits, the only length GCM is specified for. */
    const val GCM_NONCE_SIZE_BYTES = 12

    /** AES-GCM tag length in bits. */
    const val GCM_TAG_SIZE_BITS = 128

    /** Session key length in bytes: AES-256. */
    const val SESSION_KEY_SIZE_BYTES = 32

    /**
     * Domain separation string, used verbatim in three places: the HKDF `info` for `Ks`, the GCM
     * AAD, and (with `/confirm` appended) the HKDF `info` for the SAS.
     */
    const val DOMAIN = "manana/pair/v1"

    /** HKDF `info` for the short authentication string. */
    const val SAS_INFO = "$DOMAIN/confirm"

    /**
     * How long a pairing code stays usable, in milliseconds.
     *
     * Enforced **locally, per session, against a monotonic clock** — not from a timestamp inside
     * the QR. That is a deliberate choice rather than an omission:
     *
     *  - Two phones do not agree on wall-clock time, and `System.currentTimeMillis()` is
     *    user-settable, so a timestamp on the wire would have to carry a skew allowance large
     *    enough to make the check nearly vacuous.
     *  - The attack a wire timestamp would supposedly stop — photographing device B's QR1 and
     *    showing it to device A later — is not actually stopped by anything, because the photo
     *    carries `EB` and not `eB`. Whoever holds the photo cannot open the resulting seal. The
     *    real risk in that scenario is a user scanning an attacker's *own* QR1, and no timestamp
     *    helps there; the SAS comparison and the fact that a human deliberately aimed the camera
     *    are the defence.
     *
     * What the local TTL actually buys is a bounded window in which a session is live at all: B
     * refuses a QR2 more than [CODE_TTL_MILLIS] after it minted `sid`, which bounds the whole
     * exchange, and A withdraws QR2 from the screen on the same timer.
     */
    const val CODE_TTL_MILLIS = 120_000L

    /** [DOMAIN] as the exact bytes that go into `info` and the AAD. */
    val DOMAIN_BYTES: ByteArray = DOMAIN.toByteArray(Charsets.US_ASCII)

    /** The HKDF `info` for `Ks`: `"manana/pair/v1" ‖ EA ‖ EB`. */
    fun sessionKeyInfo(encodedEa: ByteArray, encodedEb: ByteArray): ByteArray =
        DOMAIN_BYTES + encodedEa + encodedEb

    /** The GCM AAD for the seal: `"manana/pair/v1" ‖ sid`. */
    fun sealAad(sid: ByteArray): ByteArray = DOMAIN_BYTES + sid
}

/**
 * The unauthenticated server hint carried in QR1.
 *
 * Both fields are optional and, on this branch, always absent — nothing in the app configures a
 * server yet. The type exists so that Phase 3 fills it in rather than changing the wire format.
 */
data class ServerHint(
    /** Server base URL, or empty when the new device has not been pointed at one. */
    val url: String = "",
    /** SHA-256 of the server's SubjectPublicKeyInfo, or null. Exactly 32 bytes when present. */
    val spkiPinSha256: ByteArray? = null,
) {
    init {
        require(url.toByteArray(Charsets.UTF_8).size <= MAX_URL_BYTES) { "server url too long" }
        require(spkiPinSha256 == null || spkiPinSha256.size == SPKI_PIN_SIZE_BYTES) {
            "an SPKI pin is a 32-byte SHA-256 digest"
        }
    }

    // data class equals/hashCode compare ByteArray by identity, which is never what a caller
    // wants and is a classic source of "the test passes because both sides are the same object".
    override fun equals(other: Any?): Boolean =
        other is ServerHint && url == other.url &&
            (spkiPinSha256?.toList() == other.spkiPinSha256?.toList())

    override fun hashCode(): Int = 31 * url.hashCode() + (spkiPinSha256?.toList()?.hashCode() ?: 0)

    companion object {
        const val MAX_URL_BYTES = 1024
        const val SPKI_PIN_SIZE_BYTES = 32

        /** No server configured. */
        val NONE = ServerHint()
    }
}

/**
 * Everything device A hands device B, sealed under `Ks`.
 *
 * [ark] is opaque here on purpose: generating it, wrapping it under the device passphrase and
 * storing it are `SecureUnlockManager`'s job, in `:core-crypto`. This module receives 32 bytes,
 * seals them, and hands 32 bytes back out on the far side. It never persists them and never logs
 * them.
 */
class AccountBundle(
    val ark: ByteArray,
    val accountId: String,
    /** Opaque Phase-3 configuration blob (server URL, pin, whatever it turns out to need). */
    val config: String = "",
) {
    init {
        require(ark.size == ARK_SIZE_BYTES) { "an ARK is $ARK_SIZE_BYTES bytes" }
        require(accountId.toByteArray(Charsets.UTF_8).size <= MAX_ACCOUNT_ID_BYTES) {
            "accountId too long"
        }
        require(config.toByteArray(Charsets.UTF_8).size <= MAX_CONFIG_BYTES) { "config too long" }
    }

    /**
     * Deliberately does not print [ark], [accountId] or [config]. `accountId` is a server-visible
     * handle rather than a secret, but it is derived from the ARK and identifies the account to
     * anyone reading a bug report, so it stays out of logs with the rest.
     */
    override fun toString(): String = "AccountBundle(ark=<32 bytes, redacted>, accountId=<redacted>)"

    companion object {
        const val ARK_SIZE_BYTES = 32
        const val MAX_ACCOUNT_ID_BYTES = 255
        const val MAX_CONFIG_BYTES = 4096
    }
}

/** A decoded QR1. */
internal class OfferFrame(
    val sid: ByteArray,
    val encodedEphemeralKey: ByteArray,
    val serverHint: ServerHint,
    /**
     * The new device's long-lived device public key, SEC1 uncompressed, or null when the frame
     * carried none. Not validated here — [PairingCodec] only checks the length, and
     * [AccountDeviceSession] runs it through [P256.decodePublicKey] alongside the ephemeral key so
     * that both on-curve checks are in one place.
     */
    val encodedDeviceKey: ByteArray?,
)

/** A decoded QR2. */
internal class SealFrame(
    val sid: ByteArray,
    val encodedEphemeralKey: ByteArray,
    val nonce: ByteArray,
    val seal: ByteArray,
)

/**
 * Encoder and decoder for the format documented on [PairingProtocol].
 *
 * The decoder never trusts a length it reads: every read goes through [ByteReader], which bounds-
 * checks against the remaining input and throws [PairingWireException] rather than
 * `ArrayIndexOutOfBoundsException`. That is the reason the point fields carry a length byte even
 * though the only legal value is 65 — one read primitive, one place bounds are enforced, and a
 * future compressed-point variant is a version bump rather than a reparse.
 */
internal object PairingCodec {

    private val base64Encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val base64Decoder: Base64.Decoder = Base64.getUrlDecoder()

    fun encodeOffer(
        sid: ByteArray,
        encodedEphemeralKey: ByteArray,
        hint: ServerHint,
        encodedDeviceKey: ByteArray? = null,
    ): String {
        require(encodedDeviceKey == null || encodedDeviceKey.size == P256.POINT_SIZE_BYTES) {
            "a device key is a ${P256.POINT_SIZE_BYTES}-byte SEC1 uncompressed P-256 point"
        }
        val writer = ByteWriter()
        writeHeader(writer, PairingProtocol.KIND_OFFER, sid)
        writer.writeLengthPrefixed8(encodedEphemeralKey)
        writer.writeLengthPrefixed16(hint.url.toByteArray(Charsets.UTF_8))
        writer.writeLengthPrefixed8(hint.spkiPinSha256 ?: ByteArray(0))
        writer.writeLengthPrefixed8(encodedDeviceKey ?: ByteArray(0))
        return wrap(writer.toByteArray())
    }

    fun encodeSeal(
        sid: ByteArray,
        encodedEphemeralKey: ByteArray,
        nonce: ByteArray,
        seal: ByteArray,
    ): String {
        val writer = ByteWriter()
        writeHeader(writer, PairingProtocol.KIND_SEAL, sid)
        writer.writeLengthPrefixed8(encodedEphemeralKey)
        writer.writeRaw(nonce)
        writer.writeLengthPrefixed16(seal)
        return wrap(writer.toByteArray())
    }

    /** Decode a QR1. Throws [PairingWireException] for anything that is not one. */
    fun decodeOffer(text: String): OfferFrame {
        val reader = openFrame(text, PairingProtocol.KIND_OFFER)
        val sid = reader.readRaw(PairingProtocol.SID_SIZE_BYTES)
        val point = reader.readLengthPrefixed8()
        val url = reader.readLengthPrefixed16()
        val pin = reader.readLengthPrefixed8()
        val deviceKey = reader.readLengthPrefixed8()
        reader.requireExhausted()

        if (url.size > ServerHint.MAX_URL_BYTES) fail(PairingFailure.MALFORMED, "url too long")
        if (pin.isNotEmpty() && pin.size != ServerHint.SPKI_PIN_SIZE_BYTES) {
            fail(PairingFailure.MALFORMED, "spki pin is not a SHA-256 digest")
        }
        // MALFORMED rather than INVALID_PEER_KEY: a field of the wrong length is a frame that does
        // not parse, and nothing has been read as a point yet. The on-curve check — the one that is
        // terminal — happens where the key is used, in `AccountDeviceSession`.
        if (deviceKey.isNotEmpty() && deviceKey.size != P256.POINT_SIZE_BYTES) {
            fail(PairingFailure.MALFORMED, "device key is not a SEC1 uncompressed P-256 point")
        }
        return OfferFrame(
            sid = sid,
            encodedEphemeralKey = point,
            serverHint = ServerHint(
                url = String(url, Charsets.UTF_8),
                spkiPinSha256 = pin.takeIf { it.isNotEmpty() },
            ),
            encodedDeviceKey = deviceKey.takeIf { it.isNotEmpty() },
        )
    }

    /** Decode a QR2. Throws [PairingWireException] for anything that is not one. */
    fun decodeSeal(text: String): SealFrame {
        val reader = openFrame(text, PairingProtocol.KIND_SEAL)
        val sid = reader.readRaw(PairingProtocol.SID_SIZE_BYTES)
        val point = reader.readLengthPrefixed8()
        val nonce = reader.readRaw(PairingProtocol.GCM_NONCE_SIZE_BYTES)
        val seal = reader.readLengthPrefixed16()
        reader.requireExhausted()

        // A GCM output is at least its own tag, so anything shorter cannot be a seal at all and is
        // rejected here rather than by the Cipher, which would report it as a tag failure — and a
        // tag failure is terminal and alarming, which a truncated frame does not deserve.
        if (seal.size <= PairingProtocol.GCM_TAG_SIZE_BITS / 8) {
            fail(PairingFailure.MALFORMED, "seal is shorter than a GCM tag")
        }
        return SealFrame(sid = sid, encodedEphemeralKey = point, nonce = nonce, seal = seal)
    }

    fun encodeBundle(bundle: AccountBundle): ByteArray {
        val writer = ByteWriter()
        writer.writeByte(PairingProtocol.BUNDLE_VERSION)
        writer.writeLengthPrefixed8(bundle.ark)
        writer.writeLengthPrefixed8(bundle.accountId.toByteArray(Charsets.UTF_8))
        writer.writeLengthPrefixed16(bundle.config.toByteArray(Charsets.UTF_8))
        return writer.toByteArray()
    }

    /**
     * Parse the plaintext that came out of the seal.
     *
     * Reached only after GCM has already authenticated these bytes, so a failure here is a version
     * or a bug rather than an attack — but it is still parsed defensively, because "authenticated"
     * means "device A wrote it", not "device A wrote it correctly".
     */
    fun decodeBundle(plaintext: ByteArray): AccountBundle {
        val reader = ByteReader(plaintext)
        val version = reader.readByte()
        if (version != PairingProtocol.BUNDLE_VERSION) {
            fail(PairingFailure.UNSUPPORTED_VERSION, "bundle version $version")
        }
        val ark = reader.readLengthPrefixed8()
        val accountId = reader.readLengthPrefixed8()
        val config = reader.readLengthPrefixed16()
        reader.requireExhausted()
        if (ark.size != AccountBundle.ARK_SIZE_BYTES) {
            fail(PairingFailure.MALFORMED, "ark is ${ark.size} bytes")
        }
        return AccountBundle(
            ark = ark,
            accountId = String(accountId, Charsets.UTF_8),
            config = String(config, Charsets.UTF_8),
        )
    }

    /**
     * Read the prefix, base64, version and kind, and position a reader at the start of `sid`.
     *
     * The order matters and is the order of increasing specificity: prefix (is this ours at all),
     * base64 (is it even a frame), version (can we speak it), kind (is it the one we asked for).
     * Each step's failure is distinguishable, which is what lets the UI say "different app version"
     * instead of "invalid code".
     */
    private fun openFrame(text: String, expectedKind: Byte): ByteReader {
        if (!text.startsWith(PairingProtocol.QR_PREFIX)) {
            fail(PairingFailure.NOT_A_PAIRING_CODE, "missing the " + PairingProtocol.QR_PREFIX + " prefix")
        }
        val body = text.substring(PairingProtocol.QR_PREFIX.length)
        val bytes = try {
            base64Decoder.decode(body)
        } catch (e: IllegalArgumentException) {
            fail(PairingFailure.NOT_A_PAIRING_CODE, "body is not base64url: ${e.message}")
        }
        val reader = ByteReader(bytes)
        val version = reader.readByte()
        if (version != PairingProtocol.VERSION) {
            fail(PairingFailure.UNSUPPORTED_VERSION, "frame version $version")
        }
        val kind = reader.readByte()
        if (kind != expectedKind) {
            fail(PairingFailure.WRONG_CODE_KIND, "frame kind $kind, wanted $expectedKind")
        }
        return reader
    }

    private fun writeHeader(writer: ByteWriter, kind: Byte, sid: ByteArray) {
        require(sid.size == PairingProtocol.SID_SIZE_BYTES)
        writer.writeByte(PairingProtocol.VERSION)
        writer.writeByte(kind)
        writer.writeRaw(sid)
    }

    private fun wrap(frame: ByteArray): String =
        PairingProtocol.QR_PREFIX + base64Encoder.encodeToString(frame)

    private fun fail(failure: PairingFailure, message: String): Nothing =
        throw PairingWireException(failure, message)
}

/** Append-only byte builder. Nothing clever; it exists so the encoders read like the spec. */
internal class ByteWriter {
    private val out = java.io.ByteArrayOutputStream(320)

    fun writeByte(value: Byte) = out.write(value.toInt())

    fun writeRaw(value: ByteArray) = out.write(value, 0, value.size)

    fun writeLengthPrefixed8(value: ByteArray) {
        require(value.size <= 255) { "field of ${value.size} bytes needs a 16-bit length" }
        out.write(value.size)
        out.write(value, 0, value.size)
    }

    fun writeLengthPrefixed16(value: ByteArray) {
        require(value.size <= 0xFFFF) { "field of ${value.size} bytes does not fit a 16-bit length" }
        out.write((value.size ushr 8) and 0xFF)
        out.write(value.size and 0xFF)
        out.write(value, 0, value.size)
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

/**
 * Bounds-checked reader over untrusted bytes.
 *
 * Every method verifies there is enough input *before* reading, and reports a shortfall as
 * [PairingFailure.MALFORMED]. Nothing here can throw an index exception, which matters because the
 * input is whatever a stranger printed on a piece of paper.
 */
internal class ByteReader(private val bytes: ByteArray) {
    private var offset = 0

    private fun requireAvailable(count: Int) {
        if (count < 0 || offset + count > bytes.size) {
            throw PairingWireException(
                PairingFailure.MALFORMED,
                "frame wants $count more bytes at offset $offset of ${bytes.size}",
            )
        }
    }

    fun readByte(): Byte {
        requireAvailable(1)
        return bytes[offset++]
    }

    fun readRaw(count: Int): ByteArray {
        requireAvailable(count)
        val out = bytes.copyOfRange(offset, offset + count)
        offset += count
        return out
    }

    fun readLengthPrefixed8(): ByteArray = readRaw(readByte().toInt() and 0xFF)

    fun readLengthPrefixed16(): ByteArray {
        val high = readByte().toInt() and 0xFF
        val low = readByte().toInt() and 0xFF
        return readRaw((high shl 8) or low)
    }

    /**
     * Reject trailing bytes.
     *
     * Not pedantry: a frame with an unread tail is a frame two implementations would disagree
     * about, and "extra bytes are ignored" is how a parser becomes a place to hide things.
     */
    fun requireExhausted() {
        if (offset != bytes.size) {
            throw PairingWireException(
                PairingFailure.MALFORMED,
                "${bytes.size - offset} trailing bytes after a complete frame",
            )
        }
    }
}
