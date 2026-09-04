package my.cheysoff.core_sync_codec

import my.cheysoff.core_domain.model.SketchData
import my.cheysoff.core_domain.sync.Hlc
import my.cheysoff.core_domain.sync.RecordType
import my.cheysoff.core_domain.sync.SyncValues

/**
 * A sketch as this device holds it: the domain object plus the clocks that decide a merge, plus
 * per-device sync bookkeeping.
 *
 * [dirty] and [lastSyncedSeq] are per-device state -- the same split `RecordRows` draws between a
 * domain object and Room's own columns on `NoteEntity`/`FolderEntity` -- so they are not carried
 * across the wire: [SketchRecords.fromPayload] has no way to know what this device's own
 * book-keeping should read for a record it is only now receiving, and does not guess.
 *
 * [rowClock] and [clocks] are exactly [NoteRow]'s -- the same split `SyncRecord`/`LocalRecord`
 * makes one layer down. They matter here even more pointedly than for a note: `anchor` and
 * `strokes` are independently editable (a later release re-stamps `anchor` when text above a
 * drawing is edited, while `strokes` moves only when someone draws), so collapsing them to one
 * clock would let a text reflow silently discard a concurrent drawing edit on the next merge.
 */
data class SketchRow(
    val sketch: SketchData,
    val rowClock: Hlc,
    val clocks: Map<String, Hlc>,
    val dirty: Boolean,
    val lastSyncedSeq: Long,
)

/**
 * Converts between a [SketchRow] and the payload that goes inside an envelope.
 *
 * Mirrors [NoteRecords]: every value crosses as text, using [SyncValues] for the one boolean
 * column, and a numeric column that will not parse refuses the whole record rather than
 * substituting a default -- see [fromPayload]. [SketchData.strokes] crosses opaque, exactly as its
 * own KDoc says: this object never decodes it, so there is nothing here for `StrokeCodec` to do.
 *
 * [toPayload] carries [SketchRow.rowClock] and [SketchRow.clocks] as-is -- it must not mint
 * [Hlc.ZERO]. A zero row clock sent against a clean, previously-clocked remote row trips
 * `Merge.merge`'s rollback guard (`!local.dirty && remote.rowClock < local.record.rowClock`) and
 * the sketch is rejected silently, forever: no error, no retry, nothing anywhere saying why the
 * drawing never arrived.
 */
object SketchRecords {

    fun toPayload(row: SketchRow, createdAt: Long): RecordPayload = RecordPayload(
        recType = RecordType.SKETCH,
        uuid = row.sketch.id,
        rowClock = row.rowClock,
        fields = mapOf(
            PayloadFields.NOTE_ID to row.sketch.noteId,
            PayloadFields.ANCHOR to row.sketch.anchor.toString(),
            PayloadFields.ORDER to row.sketch.order.toString(),
            PayloadFields.STROKES to row.sketch.strokes,
            PayloadFields.CREATED_AT to createdAt.toString(),
            PayloadFields.UPDATED_AT to row.sketch.updatedAt.toString(),
            PayloadFields.IS_DELETED to SyncValues.of(row.sketch.isDeleted),
            PayloadFields.DELETED_AT to row.sketch.deletedAt?.toString(),
        ),
        clocks = row.clocks,
    )

    /**
     * Rebuilds a row from a payload, or returns null if a numeric column is not a number.
     *
     * Null rather than a default: [SketchData.anchor] and [SketchData.order] decide where a
     * drawing sits in its note, and substituting 0 for an unreadable one would silently move
     * someone's drawing to the top of the note, on every device, with nothing anywhere saying why.
     * `createdAt`/`updatedAt`/`deletedAt` get the same treatment `NoteRecords` gives them, for the
     * same reason.
     */
    fun fromPayload(payload: RecordPayload): SketchRow? {
        val anchor = payload.field(PayloadFields.ANCHOR)?.toIntOrNull() ?: return null
        val order = payload.field(PayloadFields.ORDER)?.toIntOrNull() ?: return null
        val createdAt = payload.field(PayloadFields.CREATED_AT)?.toLongOrNull() ?: return null
        val updatedAt = payload.field(PayloadFields.UPDATED_AT)?.toLongOrNull() ?: return null
        val deletedAtText = payload.field(PayloadFields.DELETED_AT)
        val deletedAt = if (deletedAtText == null) null else deletedAtText.toLongOrNull() ?: return null
        return SketchRow(
            sketch = SketchData(
                id = payload.uuid,
                noteId = payload.field(PayloadFields.NOTE_ID).orEmpty(),
                anchor = anchor,
                order = order,
                strokes = payload.field(PayloadFields.STROKES).orEmpty(),
                createdAt = createdAt,
                updatedAt = updatedAt,
                isDeleted = SyncValues.toBoolean(payload.field(PayloadFields.IS_DELETED)),
                deletedAt = deletedAt,
            ),
            rowClock = payload.rowClock,
            clocks = payload.clocks,
            dirty = false,
            lastSyncedSeq = 0L,
        )
    }
}
