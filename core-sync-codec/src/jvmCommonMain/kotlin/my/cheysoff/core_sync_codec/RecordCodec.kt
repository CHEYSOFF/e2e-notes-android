package my.cheysoff.core_sync_codec

import my.cheysoff.core_crypto.sync.AccountKeys
import my.cheysoff.core_crypto.sync.BlindedRecordId
import my.cheysoff.core_crypto.sync.RecordEnvelope

/** A sealed record and the label it is filed under. */
class SealedRecord(val blindedId: String, val envelope: ByteArray) {
    override fun equals(other: Any?): Boolean = other is SealedRecord &&
        blindedId == other.blindedId && envelope.contentEquals(other.envelope)

    override fun hashCode(): Int = 31 * blindedId.hashCode() + envelope.contentHashCode()
}

/** What [RecordCodec.open] made of a stored row. */
sealed interface OpenResult {
    data class Ok(val payload: RecordPayload) : OpenResult

    /** GCM refused it: a wrong key, a damaged blob, or an envelope filed under another ID. */
    data object Unreadable : OpenResult

    /**
     * It decrypted, but the identity inside does not hash to the ID it was filed under.
     *
     * This is the check §4 of the phase-3 plan puts on `RecordCodec`, and it is the reason
     * `recType` and `uuid` were allowed to move inside the envelope: the blinded ID *is* the HMAC
     * of `(recType, uuid)`, so recomputing it after opening restores the binding the associated
     * data used to provide, and extends it to `uuid`, which the associated data never covered.
     *
     * A server cannot produce this without `K_id`, so it is a client bug rather than an attack —
     * and the plan says so: halt and be loud, do not repair.
     */
    data object Mislabelled : OpenResult

    /** Written by a build whose payload or content-serializer version this one does not implement. */
    data class UnsupportedVersion(val payloadVersion: Int, val serializerVersion: Int) : OpenResult

    /** Decrypted and authentic, but not a payload this build can parse. */
    data class Malformed(val reason: String) : OpenResult
}

/**
 * Seals a [RecordPayload] into an envelope and opens one back, for one account.
 *
 * The **only** place that touches `K_content` or `K_id`. Everything above it deals in payloads and
 * everything below it deals in opaque blobs, which is what keeps the account keys out of the
 * repository, the UI and the store.
 *
 * No crypto is implemented here: [RecordEnvelope] and [BlindedRecordId] do all of it, and this
 * class is the wiring plus the one check neither of them can make on its own — that the record's
 * own idea of what it is agrees with the name it arrived under.
 */
class RecordCodec(private val keys: AccountKeys) {

    /** The label [payload] is filed under: `base64url(HMAC(K_id, recType ‖ ":" ‖ uuid)[0..16])`. */
    fun blindedIdOf(payload: RecordPayload): String =
        BlindedRecordId.compute(keys.kId, payload.recType.wireKey, payload.uuid)

    /** The same label, from an identity that has not been packed into a payload yet. */
    fun blindedIdOf(recTypeWireKey: String, uuid: String): String =
        BlindedRecordId.compute(keys.kId, recTypeWireKey, uuid)

    fun seal(payload: RecordPayload): SealedRecord {
        val blindedId = blindedIdOf(payload)
        val plaintext = RecordPayloadCodec.encode(payload)
        try {
            return SealedRecord(blindedId, RecordEnvelope.seal(keys.kContent, blindedId, plaintext))
        } finally {
            // The payload's plaintext is the note. `RecordEnvelope.seal` zeroes the padded copy it
            // makes; this is the copy this function made.
            plaintext.fill(0)
        }
    }

    fun open(blindedId: String, envelope: ByteArray): OpenResult {
        val plaintext = RecordEnvelope.open(keys.kContent, blindedId, envelope) ?: return OpenResult.Unreadable
        try {
            return when (val result = RecordPayloadCodec.decode(plaintext)) {
                is PayloadResult.Malformed -> OpenResult.Malformed(result.reason)
                is PayloadResult.UnsupportedVersion ->
                    OpenResult.UnsupportedVersion(result.payloadVersion, result.serializerVersion)

                is PayloadResult.Ok ->
                    if (blindedIdOf(result.payload) != blindedId) {
                        OpenResult.Mislabelled
                    } else {
                        OpenResult.Ok(result.payload)
                    }
            }
        } finally {
            plaintext.fill(0)
        }
    }
}
