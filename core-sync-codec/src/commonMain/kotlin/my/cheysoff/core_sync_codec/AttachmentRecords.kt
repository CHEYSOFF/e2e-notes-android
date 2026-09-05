package my.cheysoff.core_sync_codec

import my.cheysoff.core_crypto.sync.Base64Url
import my.cheysoff.core_domain.model.AttachmentData
import my.cheysoff.core_domain.sync.Hlc
import my.cheysoff.core_domain.sync.RecordType
import my.cheysoff.core_domain.sync.SyncValues

/**
 * An attachment as this device holds it: the domain object plus the clocks that decide a merge,
 * plus per-device sync bookkeeping.
 *
 * A field-for-field mirror of [SketchRow], and everything that KDoc says applies here unchanged.
 * [dirty] and [lastSyncedSeq] are per-device state and so are not carried across the wire:
 * [AttachmentRecords.fromPayload] has no way to know what this device's own bookkeeping should read
 * for a record it is only now receiving, and does not guess.
 *
 * [rowClock] and [clocks] are the same split `SyncRecord`/`LocalRecord` makes one layer down.
 * `anchor` and `image` are independently editable — a later release re-stamps `anchor` when text
 * above a photograph is edited, while `image` moves only when the picture itself is replaced — so
 * collapsing them to one clock would let a text reflow silently discard a concurrent image edit on
 * the next merge.
 */
data class AttachmentRow(
    val attachment: AttachmentData,
    val rowClock: Hlc,
    val clocks: Map<String, Hlc>,
    val dirty: Boolean,
    val lastSyncedSeq: Long,
)

/**
 * Converts between an [AttachmentRow] and the payload that goes inside an envelope.
 *
 * Mirrors [SketchRecords] exactly, with two differences.
 *
 * **Bytes cross as text.** `bytes` and `thumbBytes` are encoded with [Base64Url] — the project's
 * canonical unpadded RFC 4648 §5 encoder, already used for every blinded id, account handle and
 * device key on the wire. A payload value is a JSON string, so an image has to become one; using
 * the encoder that already exists rather than a second one is what keeps the two ends able to read
 * each other.
 *
 * **`meta` is carried verbatim.** It is never parsed, validated, normalised or defaulted here. See
 * [PayloadFields.META] for why it is in the column set from the first shipped record and why it has
 * no clock of its own.
 *
 * [toPayload] carries [AttachmentRow.rowClock] and [AttachmentRow.clocks] as-is — it must not mint
 * [Hlc.ZERO]. A zero row clock sent against a clean, previously-clocked remote row trips
 * `Merge.merge`'s rollback guard (`!local.dirty && remote.rowClock < local.record.rowClock`) and the
 * attachment is rejected silently, forever: no error, no retry, nothing anywhere saying why the
 * photograph never arrived.
 */
object AttachmentRecords {

    fun toPayload(row: AttachmentRow, createdAt: Long): RecordPayload = RecordPayload(
        recType = RecordType.ATTACHMENT,
        uuid = row.attachment.id,
        rowClock = row.rowClock,
        fields = mapOf(
            PayloadFields.NOTE_ID to row.attachment.noteId,
            PayloadFields.ANCHOR to row.attachment.anchor.toString(),
            PayloadFields.ORDER to row.attachment.order.toString(),
            PayloadFields.BYTES to Base64Url.encode(row.attachment.bytes),
            PayloadFields.MIME_TYPE to row.attachment.mimeType,
            PayloadFields.WIDTH to row.attachment.width.toString(),
            PayloadFields.HEIGHT to row.attachment.height.toString(),
            PayloadFields.THUMB_BYTES to Base64Url.encode(row.attachment.thumbBytes),
            PayloadFields.THUMB_WIDTH to row.attachment.thumbWidth.toString(),
            PayloadFields.THUMB_HEIGHT to row.attachment.thumbHeight.toString(),
            // Verbatim. Not trimmed, not defaulted, not inspected -- see PayloadFields.META.
            PayloadFields.META to row.attachment.meta,
            PayloadFields.CREATED_AT to createdAt.toString(),
            PayloadFields.UPDATED_AT to row.attachment.updatedAt.toString(),
            PayloadFields.IS_DELETED to SyncValues.of(row.attachment.isDeleted),
            PayloadFields.DELETED_AT to row.attachment.deletedAt?.toString(),
        ),
        clocks = row.clocks,
    )

    /**
     * Rebuilds a row from a payload, or returns null if a numeric column is not a number or an
     * image column is not base64url.
     *
     * Null rather than a default, for [SketchRecords.fromPayload]'s reason applied to pixels.
     * Substituting 0 for an unreadable [AttachmentData.anchor] or [AttachmentData.order] would
     * silently move someone's photograph to the top of the note; substituting an empty
     * [AttachmentData.bytes] for base64 that will not decode would put a blank grey box in that
     * note on every device — in both cases with nothing anywhere saying why. A record this build
     * cannot read is one to count and skip, which is exactly what every caller of this does.
     *
     * `mimeType` and `meta` have no failure mode of their own: both are opaque strings, and an
     * absent one reads as `""` the way `SketchData.strokes` does.
     */
    fun fromPayload(payload: RecordPayload): AttachmentRow? {
        val anchor = payload.field(PayloadFields.ANCHOR)?.toIntOrNull() ?: return null
        val order = payload.field(PayloadFields.ORDER)?.toIntOrNull() ?: return null
        val width = payload.field(PayloadFields.WIDTH)?.toIntOrNull() ?: return null
        val height = payload.field(PayloadFields.HEIGHT)?.toIntOrNull() ?: return null
        val thumbWidth = payload.field(PayloadFields.THUMB_WIDTH)?.toIntOrNull() ?: return null
        val thumbHeight = payload.field(PayloadFields.THUMB_HEIGHT)?.toIntOrNull() ?: return null
        val bytes = Base64Url.decode(payload.field(PayloadFields.BYTES).orEmpty()) ?: return null
        val thumbBytes =
            Base64Url.decode(payload.field(PayloadFields.THUMB_BYTES).orEmpty()) ?: return null
        val createdAt = payload.field(PayloadFields.CREATED_AT)?.toLongOrNull() ?: return null
        val updatedAt = payload.field(PayloadFields.UPDATED_AT)?.toLongOrNull() ?: return null
        val deletedAtText = payload.field(PayloadFields.DELETED_AT)
        val deletedAt = if (deletedAtText == null) null else deletedAtText.toLongOrNull() ?: return null
        return AttachmentRow(
            attachment = AttachmentData(
                id = payload.uuid,
                noteId = payload.field(PayloadFields.NOTE_ID).orEmpty(),
                anchor = anchor,
                order = order,
                mimeType = payload.field(PayloadFields.MIME_TYPE).orEmpty(),
                width = width,
                height = height,
                bytes = bytes,
                thumbWidth = thumbWidth,
                thumbHeight = thumbHeight,
                thumbBytes = thumbBytes,
                createdAt = createdAt,
                updatedAt = updatedAt,
                isDeleted = SyncValues.toBoolean(payload.field(PayloadFields.IS_DELETED)),
                deletedAt = deletedAt,
                // Verbatim, again. A build that does not know what is in here must preserve it.
                meta = payload.field(PayloadFields.META).orEmpty(),
            ),
            rowClock = payload.rowClock,
            clocks = payload.clocks,
            dirty = false,
            lastSyncedSeq = 0L,
        )
    }
}
