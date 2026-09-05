package my.cheysoff.core_data.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import my.cheysoff.core_domain.model.AttachmentData
import my.cheysoff.core_domain.model.AttachmentPreview
import my.cheysoff.core_domain.sync.Hlc

/**
 * One image attachment's row, added in v11. Structurally the same record as [SketchEntity] —
 * see `docs/design/image-attachments.md` §2 and §5 — with the strokes column replaced by the
 * full-size and thumbnail encodings.
 *
 * ## `sortOrder`, not `order`
 *
 * Same reasoning as [SketchEntity.sortOrder]: [AttachmentData.order] and the wire protocol both
 * call this `order`, and `order` is a SQL keyword. Only [toDomain] needs to know the two names are
 * the same value.
 *
 * ## No foreign key, no cascade
 *
 * There is deliberately no `FOREIGN KEY (noteId) REFERENCES notes(id)` and no `ON DELETE CASCADE`.
 * A SQL cascade would delete these rows on only the device that performed the delete, mint no
 * tombstone for them, and leave the other device — which never heard the attachments died —
 * pushing its still-live copies right back, reattached to a note that no longer exists anywhere.
 * See [AttachmentDao]'s KDoc and `RoomNotesRepository.deleteNote` for the tombstone cascade that
 * replaces it.
 *
 * ## The sync columns
 *
 * `hlcMs`/`hlcCounter`/`hlcNode`/`fieldHlc`/`dirty`/`lastSyncedSeq` are the same six columns
 * `sketches` carries — see [SketchEntity]'s KDoc for what each means and why the clock triple
 * carries no SQL `DEFAULT` here (this is a brand-new, empty table; there is nothing to backfill).
 *
 * `dirty` is the one exception, defaulting to `1` for the same reason `sketches.dirty` and
 * `notes.dirty` do: `0` would assert the server already has this row, and the first pull would
 * then read its absence as "deleted elsewhere". The default is pinned in three places that all
 * have to agree — this class's Kotlin default, `@ColumnInfo(defaultValue = "1")` below, and
 * `MIGRATION_10_11`'s DDL — so a mismatch fails Room's schema validation at startup rather than at
 * the first sync.
 */
@Entity(tableName = "attachments", indices = [Index("noteId")])
data class AttachmentEntity(
    @PrimaryKey val uuid: String,
    val noteId: String,
    val anchor: Int,
    val sortOrder: Int,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val bytes: ByteArray,
    val thumbWidth: Int,
    val thumbHeight: Int,
    val thumbBytes: ByteArray,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0")
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,

    /** Row clock, physical component. See `NoteEntity.hlcMs`. */
    val hlcMs: Long = 0L,
    /** Row clock, logical component. See `NoteEntity.hlcCounter`. */
    val hlcCounter: Int = 0,
    /** Row clock, node component. See `NoteEntity.hlcNode`. */
    val hlcNode: String = "",
    /** Per-field clocks, serialised — see `FieldClocks.ATTACHMENT_FIELDS`. */
    @ColumnInfo(defaultValue = "''")
    val fieldHlc: String = "",
    /**
     * True when this row holds changes the server has not acknowledged.
     *
     * **The default is 1 here, in the Kotlin default, and in `MIGRATION_10_11`. Changing any of
     * them to 0 is data loss, not a tidy-up** — see this class's own KDoc and `SketchEntity`'s.
     */
    @ColumnInfo(defaultValue = "1")
    val dirty: Boolean = true,
    @ColumnInfo(defaultValue = "0")
    val lastSyncedSeq: Long = 0L,
    /**
     * An opaque escape hatch this build never writes anything but `""` into, and must round-trip
     * verbatim whatever it reads back — see [AttachmentData.meta] for the full reasoning: this
     * sync protocol cannot add a column to a record type after it ships (an unexpected key fails
     * `RecordPayloadCodec.decodeOrThrow`'s exact key-set check and halts the account), so `meta` is
     * the one column future builds can repurpose — a caption, alt text, an original filename —
     * without ever touching `attachments`' column set again. Pinned in three places, the same
     * discipline as `dirty`: this Kotlin default, `@ColumnInfo(defaultValue = "''")` below, and
     * `MIGRATION_10_11`'s DDL.
     *
     * **It must be in `PayloadFields.ATTACHMENT_COLUMNS` from the very first shipped `ATTACHMENT`
     * record, not added later.** That is the one option this protocol does not offer — see this
     * property's KDoc for why an added column decodes as `Malformed` on every older device. And it
     * is deliberately **outside** `FieldClocks.ATTACHMENT_FIELDS`: it merges at the row clock, the
     * same precedent `PayloadFields.CREATED_AT` sets for `sketches` (present on the wire, absent
     * from `FieldClocks.SKETCH_FIELDS`, because nothing gives it a clock of its own).
     * `RoomNotesRepository.attachmentTouchedFields` does not mention `meta` and does not need to,
     * for the same reason.
     */
    @ColumnInfo(defaultValue = "''")
    val meta: String = "",
) {
    // ByteArray uses identity equals/hashCode by default; two rows built from the same pixels
    // would otherwise compare unequal. Same fix AttachmentData/AttachmentPreview already apply.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AttachmentEntity) return false
        return uuid == other.uuid && noteId == other.noteId && anchor == other.anchor &&
            sortOrder == other.sortOrder && mimeType == other.mimeType &&
            width == other.width && height == other.height &&
            bytes.contentEquals(other.bytes) &&
            thumbWidth == other.thumbWidth && thumbHeight == other.thumbHeight &&
            thumbBytes.contentEquals(other.thumbBytes) &&
            createdAt == other.createdAt && updatedAt == other.updatedAt &&
            isDeleted == other.isDeleted && deletedAt == other.deletedAt &&
            hlcMs == other.hlcMs && hlcCounter == other.hlcCounter && hlcNode == other.hlcNode &&
            fieldHlc == other.fieldHlc && dirty == other.dirty &&
            lastSyncedSeq == other.lastSyncedSeq && meta == other.meta
    }

    override fun hashCode(): Int {
        var result = uuid.hashCode()
        result = 31 * result + noteId.hashCode()
        result = 31 * result + anchor
        result = 31 * result + sortOrder
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + thumbWidth
        result = 31 * result + thumbHeight
        result = 31 * result + thumbBytes.contentHashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        result = 31 * result + isDeleted.hashCode()
        result = 31 * result + (deletedAt?.hashCode() ?: 0)
        result = 31 * result + hlcMs.hashCode()
        result = 31 * result + hlcCounter
        result = 31 * result + hlcNode.hashCode()
        result = 31 * result + fieldHlc.hashCode()
        result = 31 * result + dirty.hashCode()
        result = 31 * result + lastSyncedSeq.hashCode()
        result = 31 * result + meta.hashCode()
        return result
    }

    /** The row clock as one value. */
    fun rowHlc(): Hlc = Hlc(ms = hlcMs, counter = hlcCounter, node = hlcNode)

    /** Just the clock columns — see `NoteEntity.clocks`. */
    fun clocks(): RowClock = RowClock(hlcMs, hlcCounter, hlcNode, fieldHlc)
}

fun AttachmentEntity.toDomain() = AttachmentData(
    id = uuid,
    noteId = noteId,
    anchor = anchor,
    order = sortOrder,
    mimeType = mimeType,
    width = width,
    height = height,
    bytes = bytes,
    thumbWidth = thumbWidth,
    thumbHeight = thumbHeight,
    thumbBytes = thumbBytes,
    createdAt = createdAt,
    updatedAt = updatedAt,
    isDeleted = isDeleted,
    deletedAt = deletedAt,
    meta = meta,
)

/**
 * The Room-constructable projection [AttachmentDao.attachmentPreviewsByNoteId] selects into.
 *
 * A plain class next to [AttachmentEntity] rather than [AttachmentPreview] itself: Room needs a
 * type it can build from exactly the selected columns, and [AttachmentPreview] lives in
 * `core-domain` and must stay free of Room annotations. Field-for-field, this is [AttachmentEntity]
 * minus [AttachmentEntity.bytes] — see [AttachmentDao]'s class KDoc for why that column must never
 * be added back to this projection.
 */
data class AttachmentPreviewProjection(
    val uuid: String,
    val noteId: String,
    val anchor: Int,
    val sortOrder: Int,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val thumbWidth: Int,
    val thumbHeight: Int,
    val thumbBytes: ByteArray,
    val createdAt: Long,
    val updatedAt: Long,
    val isDeleted: Boolean,
    val deletedAt: Long?,
    /** See [AttachmentData.meta]. Included here (unlike `bytes`) because it is a small string,
     * never a megabyte-sized column, so it carries no CursorWindow risk in a multi-row query. */
    val meta: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AttachmentPreviewProjection) return false
        return uuid == other.uuid && noteId == other.noteId && anchor == other.anchor &&
            sortOrder == other.sortOrder && mimeType == other.mimeType &&
            width == other.width && height == other.height &&
            thumbWidth == other.thumbWidth && thumbHeight == other.thumbHeight &&
            thumbBytes.contentEquals(other.thumbBytes) &&
            createdAt == other.createdAt && updatedAt == other.updatedAt &&
            isDeleted == other.isDeleted && deletedAt == other.deletedAt && meta == other.meta
    }

    override fun hashCode(): Int {
        var result = uuid.hashCode()
        result = 31 * result + noteId.hashCode()
        result = 31 * result + anchor
        result = 31 * result + sortOrder
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + thumbWidth
        result = 31 * result + thumbHeight
        result = 31 * result + thumbBytes.contentHashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        result = 31 * result + isDeleted.hashCode()
        result = 31 * result + (deletedAt?.hashCode() ?: 0)
        result = 31 * result + meta.hashCode()
        return result
    }
}

fun AttachmentPreviewProjection.toDomain() = AttachmentPreview(
    id = uuid,
    noteId = noteId,
    anchor = anchor,
    order = sortOrder,
    mimeType = mimeType,
    width = width,
    height = height,
    thumbWidth = thumbWidth,
    thumbHeight = thumbHeight,
    thumbBytes = thumbBytes,
    createdAt = createdAt,
    updatedAt = updatedAt,
    isDeleted = isDeleted,
    deletedAt = deletedAt,
    meta = meta,
)

/**
 * The id and clocks of one attachment row — nothing else, and in particular no `bytes`.
 *
 * What [AttachmentDao.activeAttachmentsForNote] and [AttachmentDao.attachmentsDeletedAtForNote]
 * select into: both are read by `RoomNotesRepository`'s tombstone cascade purely to compute each
 * attachment's next `fieldHlc` from its current clocks, and `uuid` to address the follow-up
 * `UPDATE` by. Neither caller has ever looked at `bytes`, `mimeType`, or any other column, so a
 * `List<AttachmentClockRow>` for a note with twenty photos costs a few hundred bytes rather than
 * ~20 MiB read off disk to answer a question about clocks. `RowClock` is not reused here because
 * it carries no id — [RoomNotesRepository.deleteNote][my.cheysoff.core_data.data.RoomNotesRepository.deleteNote]
 * needs both the id (to address the `UPDATE`) and the clocks (to compute the tombstone's `fieldHlc`)
 * off the same row without a second query per attachment.
 */
data class AttachmentClockRow(
    val uuid: String,
    val hlcMs: Long,
    val hlcCounter: Int,
    val hlcNode: String,
    val fieldHlc: String,
) {
    /** The row clock as one value. */
    fun rowHlc(): Hlc = Hlc(ms = hlcMs, counter = hlcCounter, node = hlcNode)

    /** Just the clock columns — see `NoteEntity.clocks`. */
    fun clocks(): RowClock = RowClock(hlcMs, hlcCounter, hlcNode, fieldHlc)
}
