package manana.sync.server

import kotlinx.serialization.Serializable

/**
 * Every JSON body the server accepts or produces.
 *
 * All binary fields -- public keys, signatures, envelopes, sealed device labels -- travel as
 * unpadded base64url strings, the same encoding the client's `core-crypto/.../sync/Base64Url.kt`
 * emits for blinded record IDs.
 *
 * **Nothing here describes a record except the handle it is filed under and the sequence number
 * this server gave it.** A record's type and its hybrid logical clock were once fields of
 * [UpsertRequestItem] and [RecordDto]; the server never read either -- it length-checked, stored
 * and echoed them -- so they moved inside the sealed envelope, where `recType` distinguishing a
 * note from a folder and an HLC node naming the device that made an edit are encrypted like
 * everything else. A field the server does not itself act on does not belong in this file.
 *
 * Decoding is **strict**: an unknown field is a decode failure and therefore a `400`. That is the
 * opposite of the client's usual `ignoreUnknownKeys = true` posture and it is deliberate. On the
 * client, an unknown field means "an older app is reading a newer note" and dropping it loses user
 * data quietly; on the server it means "a client is speaking a protocol this build does not
 * implement", and quietly ignoring a field that a future version made security-relevant is exactly
 * the failure that is impossible to notice. A protocol change gets a new field *and* a rejection
 * from every server that has not been updated to expect it.
 */
@Serializable
class ErrorBody(val error: String, val message: String)

// --------------------------------------------------------------------------------------------
// POST /v1/account
// --------------------------------------------------------------------------------------------

@Serializable
class ClaimRequest(
    val accountId: String,
    /** SEC1 uncompressed P-256 point, base64url. */
    val devicePublicKey: String,
    /**
     * The device's name, sealed by `core-crypto/.../sync/DeviceLabelCipher` and base64url encoded.
     * Opaque here: the server stores it, returns it in [DeviceDto], and can do nothing else with
     * it. Empty for a client that sends no name.
     */
    val sealedLabel: String = "",
    /** Client wall-clock, epoch milliseconds. Must be within the server's freshness window. */
    val ts: Long,
    /** DER `SHA256withECDSA` over `SignedMessage.claim(...)`, base64url. */
    val signature: String,
)

@Serializable
class ClaimResponse(val accountId: String, val deviceId: String, val createdAt: Long)

// --------------------------------------------------------------------------------------------
// POST /v1/devices/authorize
// --------------------------------------------------------------------------------------------

@Serializable
class AuthorizeRequest(
    val accountId: String,
    /** The joining device's public key, SEC1 uncompressed, base64url. */
    val newPublicKey: String,
    /** The joining device's sealed name, base64url. See [ClaimRequest.sealedLabel]. */
    val sealedLabel: String = "",
    val ts: Long,
    /** Which enrolled device is vouching. Its stored public key verifies [signature]. */
    val voucherDeviceId: String,
    /** DER `SHA256withECDSA` over `SignedMessage.authorize(...)`, base64url. */
    val signature: String,
)

@Serializable
class AuthorizeResponse(val deviceId: String, val createdAt: Long)

// --------------------------------------------------------------------------------------------
// POST /v1/session/challenge, POST /v1/session
// --------------------------------------------------------------------------------------------

@Serializable
class ChallengeRequest(val accountId: String, val deviceId: String)

@Serializable
class ChallengeResponse(val challenge: String, val expiresAt: Long)

@Serializable
class SessionRequest(
    val accountId: String,
    val deviceId: String,
    val challenge: String,
    /** DER `SHA256withECDSA` over `SignedMessage.session(...)`, base64url. */
    val signature: String,
)

@Serializable
class SessionResponse(val token: String, val expiresAt: Long)

// --------------------------------------------------------------------------------------------
// GET /v1/devices, DELETE /v1/devices/{id}
// --------------------------------------------------------------------------------------------

@Serializable
class DeviceDto(
    val deviceId: String,
    /** Whatever sealed blob was sent at enrolment, base64url, byte for byte. */
    val sealedLabel: String,
    /** SEC1 uncompressed P-256 point, base64url -- so a client can show a fingerprint. */
    val publicKey: String,
    val createdAt: Long,
    val revokedAt: Long? = null,
    /** True for the device whose token made this call. */
    val self: Boolean,
)

@Serializable
class DevicesResponse(val devices: List<DeviceDto>)

@Serializable
class RevokeResponse(val deviceId: String, val revoked: Boolean = true)

// --------------------------------------------------------------------------------------------
// GET /v1/changes, POST /v1/records, GET /v1/records/{id}/history
// --------------------------------------------------------------------------------------------

@Serializable
class RecordDto(
    val blindedId: String,
    /** This version's per-account monotonic sequence number. The cursor is made of these. */
    val seq: Long,
    /** The sealed envelope, base64url. The server never opens, parses or validates it. */
    val envelope: String,
)

@Serializable
class ChangesResponse(
    val records: List<RecordDto>,
    /** Where the client's cursor should be after applying [records]. */
    val nextCursor: Long,
    /** True when the page was full, so another pull will return more. */
    val hasMore: Boolean,
)

@Serializable
class UpsertRequestItem(
    val blindedId: String,
    /** The seq this edit was based on; 0 asserts the record does not exist yet. */
    val baseSeq: Long,
    val envelope: String,
)

@Serializable
class UpsertRequest(val items: List<UpsertRequestItem>)

@Serializable
class UpsertResultItem(
    val blindedId: String,
    /** `"ok"` or `"conflict"`. */
    val status: String,
    /** Present when [status] is `"ok"`: the seq the accepted version was given. */
    val seq: Long? = null,
    /** Present when [status] is `"conflict"`: the version that blocked this write. */
    val current: RecordDto? = null,
)

@Serializable
class UpsertResponse(val results: List<UpsertResultItem>, val accountSeq: Long)

@Serializable
class HistoryResponse(val blindedId: String, val versions: List<RecordDto>)

@Serializable
class HealthResponse(val status: String, val version: String)
