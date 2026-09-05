package my.cheysoff.core_data.data.sync

import my.cheysoff.core_crypto.sync.Base64Url
import my.cheysoff.core_data.data.local.AttachmentEntity
import my.cheysoff.core_data.data.local.FolderEntity
import my.cheysoff.core_data.data.local.NoteEntity
import my.cheysoff.core_data.data.local.SketchEntity
import my.cheysoff.core_domain.sync.FieldClocks
import my.cheysoff.core_domain.sync.FieldValue
import my.cheysoff.core_domain.sync.Hlc
import my.cheysoff.core_domain.sync.RecordType
import my.cheysoff.core_domain.sync.SyncRecord
import my.cheysoff.core_domain.sync.SyncValues

/**
 * A Room row to the merge's `SyncRecord` and back.
 *
 * ## Why this is not `NoteEntity.toDomain()`
 *
 * `Note` is what the UI renders; `SyncRecord` is what two devices argue about. They carry almost
 * the same columns and are not the same object, and the difference is the whole point: a
 * `SyncRecord` carries the row clock and the per-field clocks and does **not** carry `dirty`,
 * `lastSyncedSeq` or `contentSyncedHlc` — those are per-device bookkeeping that is *supposed* to
 * differ between two converged replicas, and putting them in the shared type is how a convergence
 * assertion comes to compare them.
 *
 * ## Every value crosses as text
 *
 * The merge treats values as opaque: it compares them, and in one unreachable-in-theory case orders
 * them. It never parses one. So the spelling has to be agreed, and it is agreed in `SyncValues` —
 * `"1"`/`"0"` for a boolean, matching how SQLite stores the column, so a value round-tripping
 * through the database and out to the wire keeps the same spelling on every device.
 */
internal object RecordRows {

    fun toRecord(note: NoteEntity): SyncRecord = SyncRecord(
        type = RecordType.NOTE,
        uuid = note.id,
        rowClock = note.rowHlc(),
        fieldClocks = FieldClocks.parse(note.fieldHlc),
        fields = mapOf(
            FieldClocks.TITLE to FieldValue.of(note.title),
            // Two columns, one value, one clock: a body read back with the wrong parser is silent
            // corruption, and `FieldValue` is what makes taking them from different sides
            // impossible rather than merely discouraged.
            FieldClocks.CONTENT to FieldValue.of(note.content, note.contentFormat),
            FieldClocks.CHECKLIST to FieldValue.of(note.checklist),
            FieldClocks.PINNED to FieldValue.of(SyncValues.of(note.isPinned)),
            FieldClocks.FAVORITE to FieldValue.of(SyncValues.of(note.isFavorite)),
            FieldClocks.FOLDER to FieldValue.of(note.folderId),
            FieldClocks.UPDATED_AT to FieldValue.of(note.updatedAt.toString()),
            FieldClocks.DELETED to FieldValue.of(SyncValues.of(note.isDeleted), note.deletedAt?.toString()),
        ),
    ).normalized()

    fun toRecord(folder: FolderEntity): SyncRecord = SyncRecord(
        type = RecordType.FOLDER,
        uuid = folder.id,
        rowClock = folder.rowHlc(),
        fieldClocks = FieldClocks.parse(folder.fieldHlc),
        fields = mapOf(
            FieldClocks.NAME to FieldValue.of(folder.name),
            FieldClocks.COLOR to FieldValue.of(folder.colorArgb?.toString()),
            FieldClocks.UPDATED_AT to FieldValue.of(folder.updatedAt.toString()),
            FieldClocks.DELETED to FieldValue.of(SyncValues.of(folder.isDeleted), folder.deletedAt?.toString()),
        ),
    ).normalized()

    fun toRecord(sketch: SketchEntity): SyncRecord = SyncRecord(
        type = RecordType.SKETCH,
        uuid = sketch.uuid,
        rowClock = sketch.rowHlc(),
        fieldClocks = FieldClocks.parse(sketch.fieldHlc),
        fields = mapOf(
            FieldClocks.NOTE_ID to FieldValue.of(sketch.noteId),
            FieldClocks.ANCHOR to FieldValue.of(sketch.anchor.toString()),
            FieldClocks.ORDER to FieldValue.of(sketch.sortOrder.toString()),
            FieldClocks.STROKES to FieldValue.of(sketch.strokes),
            FieldClocks.UPDATED_AT to FieldValue.of(sketch.updatedAt.toString()),
            FieldClocks.DELETED to FieldValue.of(SyncValues.of(sketch.isDeleted), sketch.deletedAt?.toString()),
        ),
    ).normalized()

    /**
     * Mirrors [toRecord] for a sketch, with `strokes` split into the two clocked binary values an
     * attachment carries.
     *
     * `bytes` and `thumbBytes` cross as [Base64Url] text because a `SyncRecord`'s values are text
     * by construction -- the merge compares them and never parses one -- and because that is the
     * spelling the payload uses, so a value round-tripping through a merge and out to the wire
     * keeps the same bytes. `mimeType`/`width`/`height` travel inside `image`'s single
     * [FieldValue], and `thumbWidth`/`thumbHeight` inside `thumb`'s, so a merge can never describe
     * one device's pixels with another device's dimensions.
     *
     * `meta` is deliberately absent: it has no clock, is not in `FieldClocks.ATTACHMENT_FIELDS`,
     * and travels beside the record rather than inside it. See `PayloadFields.META`.
     */
    fun toRecord(attachment: AttachmentEntity): SyncRecord = SyncRecord(
        type = RecordType.ATTACHMENT,
        uuid = attachment.uuid,
        rowClock = attachment.rowHlc(),
        fieldClocks = FieldClocks.parse(attachment.fieldHlc),
        fields = mapOf(
            FieldClocks.NOTE_ID to FieldValue.of(attachment.noteId),
            FieldClocks.ANCHOR to FieldValue.of(attachment.anchor.toString()),
            FieldClocks.ORDER to FieldValue.of(attachment.sortOrder.toString()),
            FieldClocks.IMAGE to FieldValue.of(
                Base64Url.encode(attachment.bytes),
                attachment.mimeType,
                attachment.width.toString(),
                attachment.height.toString(),
            ),
            FieldClocks.THUMB to FieldValue.of(
                Base64Url.encode(attachment.thumbBytes),
                attachment.thumbWidth.toString(),
                attachment.thumbHeight.toString(),
            ),
            FieldClocks.UPDATED_AT to FieldValue.of(attachment.updatedAt.toString()),
            FieldClocks.DELETED to FieldValue.of(
                SyncValues.of(attachment.isDeleted), attachment.deletedAt?.toString(),
            ),
        ),
    ).normalized()

    /**
     * The row a merged [record] should become.
     *
     * @param createdAt the one column the merge does not model. See [createdAtFor], which is where
     *   the value comes from and where the reasoning is.
     */
    fun toNoteEntity(
        record: SyncRecord,
        createdAt: Long,
        dirty: Boolean,
        lastSyncedSeq: Long,
        contentBaseline: Hlc?,
    ): NoteEntity {
        val normalized = record.normalized()
        val content = normalized.valueOf(FieldClocks.CONTENT)
        val deleted = normalized.valueOf(FieldClocks.DELETED)
        return NoteEntity(
            id = normalized.uuid,
            title = normalized.valueOf(FieldClocks.TITLE).parts[0].orEmpty(),
            content = content.parts[0].orEmpty(),
            contentFormat = content.parts[1].orEmpty(),
            checklist = normalized.valueOf(FieldClocks.CHECKLIST).parts[0].orEmpty(),
            isPinned = SyncValues.toBoolean(normalized.valueOf(FieldClocks.PINNED).parts[0]),
            isFavorite = SyncValues.toBoolean(normalized.valueOf(FieldClocks.FAVORITE).parts[0]),
            folderId = normalized.valueOf(FieldClocks.FOLDER).parts[0],
            createdAt = createdAt,
            // A non-numeric timestamp cannot reach here: `SyncRecords.fromPayload` refuses the
            // record at the boundary rather than defaulting it, precisely so that this fallback is
            // unreachable instead of quietly moving a note to the end of the user's list.
            updatedAt = normalized.valueOf(FieldClocks.UPDATED_AT).parts[0]?.toLongOrNull() ?: 0L,
            isDeleted = SyncValues.toBoolean(deleted.parts[0]),
            deletedAt = deleted.parts[1]?.toLongOrNull(),
            hlcMs = normalized.rowClock.ms,
            hlcCounter = normalized.rowClock.counter,
            hlcNode = normalized.rowClock.node,
            fieldHlc = FieldClocks.serialize(
                RecordType.NOTE.fields.mapNotNull { f -> normalized.fieldClocks[f]?.let { f to it } }.toMap(),
            ),
            dirty = dirty,
            lastSyncedSeq = lastSyncedSeq,
            contentSyncedHlc = contentBaseline?.toString().orEmpty(),
        )
    }

    fun toFolderEntity(
        record: SyncRecord,
        createdAt: Long,
        dirty: Boolean,
        lastSyncedSeq: Long,
    ): FolderEntity {
        val normalized = record.normalized()
        val deleted = normalized.valueOf(FieldClocks.DELETED)
        return FolderEntity(
            id = normalized.uuid,
            name = normalized.valueOf(FieldClocks.NAME).parts[0].orEmpty(),
            colorArgb = normalized.valueOf(FieldClocks.COLOR).parts[0]?.toLongOrNull(),
            createdAt = createdAt,
            updatedAt = normalized.valueOf(FieldClocks.UPDATED_AT).parts[0]?.toLongOrNull() ?: 0L,
            isDeleted = SyncValues.toBoolean(deleted.parts[0]),
            deletedAt = deleted.parts[1]?.toLongOrNull(),
            hlcMs = normalized.rowClock.ms,
            hlcCounter = normalized.rowClock.counter,
            hlcNode = normalized.rowClock.node,
            fieldHlc = FieldClocks.serialize(
                RecordType.FOLDER.fields.mapNotNull { f -> normalized.fieldClocks[f]?.let { f to it } }.toMap(),
            ),
            dirty = dirty,
            lastSyncedSeq = lastSyncedSeq,
        )
    }

    /**
     * Mirrors [toFolderEntity]: no `contentBaseline` (a sketch has no body worth conflict-copying,
     * so it never advances one), and every field the merge decided is written straight through.
     */
    fun toSketchEntity(
        record: SyncRecord,
        createdAt: Long,
        dirty: Boolean,
        lastSyncedSeq: Long,
    ): SketchEntity {
        val normalized = record.normalized()
        val deleted = normalized.valueOf(FieldClocks.DELETED)
        return SketchEntity(
            uuid = normalized.uuid,
            noteId = normalized.valueOf(FieldClocks.NOTE_ID).parts[0].orEmpty(),
            anchor = normalized.valueOf(FieldClocks.ANCHOR).parts[0]?.toIntOrNull() ?: 0,
            sortOrder = normalized.valueOf(FieldClocks.ORDER).parts[0]?.toIntOrNull() ?: 0,
            strokes = normalized.valueOf(FieldClocks.STROKES).parts[0].orEmpty(),
            createdAt = createdAt,
            updatedAt = normalized.valueOf(FieldClocks.UPDATED_AT).parts[0]?.toLongOrNull() ?: 0L,
            isDeleted = SyncValues.toBoolean(deleted.parts[0]),
            deletedAt = deleted.parts[1]?.toLongOrNull(),
            hlcMs = normalized.rowClock.ms,
            hlcCounter = normalized.rowClock.counter,
            hlcNode = normalized.rowClock.node,
            fieldHlc = FieldClocks.serialize(
                RecordType.SKETCH.fields.mapNotNull { f -> normalized.fieldClocks[f]?.let { f to it } }.toMap(),
            ),
            dirty = dirty,
            lastSyncedSeq = lastSyncedSeq,
        )
    }

    /**
     * Mirrors [toSketchEntity], with two extra caller-supplied columns.
     *
     * @param meta the opaque escape hatch, which the merge does not model. The caller decides it:
     *   the incoming payload's value when there is one, otherwise the row's existing one. Never
     *   normalised, never defaulted to `""` by anything but the absence of a row -- see
     *   `AttachmentData.meta`.
     *
     * The `?: ByteArray(0)` and `?: 0` fallbacks below are unreachable rather than merely
     * unlikely: `SyncRecords.fromPayload` refuses a record whose numeric columns will not parse or
     * whose image columns will not base64-decode, at the boundary, precisely so that a blank grey
     * box cannot appear in a note here.
     */
    fun toAttachmentEntity(
        record: SyncRecord,
        createdAt: Long,
        meta: String,
        dirty: Boolean,
        lastSyncedSeq: Long,
    ): AttachmentEntity {
        val normalized = record.normalized()
        val image = normalized.valueOf(FieldClocks.IMAGE)
        val thumb = normalized.valueOf(FieldClocks.THUMB)
        val deleted = normalized.valueOf(FieldClocks.DELETED)
        return AttachmentEntity(
            uuid = normalized.uuid,
            noteId = normalized.valueOf(FieldClocks.NOTE_ID).parts[0].orEmpty(),
            anchor = normalized.valueOf(FieldClocks.ANCHOR).parts[0]?.toIntOrNull() ?: 0,
            sortOrder = normalized.valueOf(FieldClocks.ORDER).parts[0]?.toIntOrNull() ?: 0,
            mimeType = image.parts[1].orEmpty(),
            width = image.parts[2]?.toIntOrNull() ?: 0,
            height = image.parts[3]?.toIntOrNull() ?: 0,
            bytes = image.parts[0]?.let(Base64Url::decode) ?: ByteArray(0),
            thumbWidth = thumb.parts[1]?.toIntOrNull() ?: 0,
            thumbHeight = thumb.parts[2]?.toIntOrNull() ?: 0,
            thumbBytes = thumb.parts[0]?.let(Base64Url::decode) ?: ByteArray(0),
            createdAt = createdAt,
            updatedAt = normalized.valueOf(FieldClocks.UPDATED_AT).parts[0]?.toLongOrNull() ?: 0L,
            isDeleted = SyncValues.toBoolean(deleted.parts[0]),
            deletedAt = deleted.parts[1]?.toLongOrNull(),
            hlcMs = normalized.rowClock.ms,
            hlcCounter = normalized.rowClock.counter,
            hlcNode = normalized.rowClock.node,
            fieldHlc = FieldClocks.serialize(
                RecordType.ATTACHMENT.fields.mapNotNull { f -> normalized.fieldClocks[f]?.let { f to it } }.toMap(),
            ),
            dirty = dirty,
            lastSyncedSeq = lastSyncedSeq,
            meta = meta,
        )
    }

    /**
     * The `createdAt` to write for a merged record, in order of preference: the row's own, then the
     * one the incoming payload carried, and only then the record's `updatedAt`.
     *
     * ## An existing row keeps what it has, always
     *
     * Nothing may move a `createdAt` that is already set. It is the one column in this schema with
     * no history to fall back on, and the merge does not model it: `FieldClocks.NOTE_FIELDS`
     * excludes it because no write path moves it, so it has no clock and nothing contests it.
     *
     * ## Why the remote value, and why it is not a merge
     *
     * A record's creation time is a property of the record, not a value two devices can disagree
     * about — so on a first receipt the right answer is simply the one the payload carries. It
     * always carried it (§5.1 lists it, both codecs write it); it was **dropped** at the
     * `SyncRecords.fromPayload` boundary, because that builds its fields from `recType.fields` and
     * `createdAt` is not one of them. So the value existed, arrived, and was thrown away one step
     * before it was needed. It is now carried beside the record, on `MergedWrite.remoteCreatedAt`.
     *
     * That is issue #90's second option, and the cheaper one: the alternative was to give
     * `createdAt` a clock and a place in the field set, which changes the `clocks` object on the
     * wire and would need every device to agree at once. This changes no bytes at all — only what a
     * receiver does with bytes it was already being sent.
     *
     * ## The fallback, and who still uses it
     *
     * `updatedAt` remains for a record whose payload carried no `createdAt`, and for the write that
     * has no remote at all: a conflict copy is a NEW record minted on this device, and the choice
     * `ConflictCopies` makes for it is deliberate — a preserved body came into existence carrying
     * that body at that time, and dating it to the moment of the merge would put it in the user's
     * Recent list stamped with a moment they were not editing.
     */
    fun createdAtFor(existing: Long?, remote: Long?, record: SyncRecord): Long {
        if (existing != null && existing != 0L) return existing
        if (remote != null && remote != 0L) return remote
        return record.valueOf(FieldClocks.UPDATED_AT).parts[0]?.toLongOrNull() ?: 0L
    }
}
