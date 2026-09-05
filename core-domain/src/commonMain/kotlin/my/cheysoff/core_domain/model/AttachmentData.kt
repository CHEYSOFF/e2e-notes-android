package my.cheysoff.core_domain.model

/**
 * One image attachment, as a record: its identity, where it belongs, its geometry, and both the
 * full-size and thumbnail encodings as bytes.
 *
 * Timestamps are caller-owned, exactly as [SketchData]'s are.
 *
 * [bytes] and [thumbBytes] are `ByteArray`, which a `data class` would otherwise compare and hash by
 * identity rather than content -- two attachments built from the same pixels would compare unequal.
 * [equals] and [hashCode] are overridden below to compare content instead. See
 * `docs/design/image-attachments.md` §5 for the schema this mirrors and §3 for why the full-size
 * bytes exist at all (no original-resolution copy is kept).
 */
data class AttachmentData(
    val id: String,
    val noteId: String,
    /** Index over the owning note's top-level blocks. See `NoteBlocks`. */
    val anchor: Int,
    /** Position among attachments sharing one anchor; ties break by [id]. See `AttachmentOrdering`. */
    val order: Int,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val bytes: ByteArray,
    val thumbWidth: Int,
    val thumbHeight: Int,
    val thumbBytes: ByteArray,
    val createdAt: Long,
    val updatedAt: Long,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    /**
     * An opaque escape hatch, carried verbatim -- never parsed, validated, or given a format by
     * this build. Every column an existing sync record type carries is frozen the moment it ships:
     * `RecordPayloadCodec.decodeOrThrow` checks a payload's key set for *exact* equality against
     * `PayloadFields.columnsOf(type)`, so a record carrying one column an older device does not
     * expect decodes as `Malformed`, becomes an `UNREADABLE` fault, and after five such records
     * halts the whole account -- not a graceful skip the way a wholly unknown record *type* gets.
     * `meta` exists so a later build can put a caption, alt text, or an original filename inside
     * this string without ever changing `attachments`' column set again.
     *
     * **A build that does not understand what is inside this string must preserve it untouched.**
     * This build only ever writes `""` and must round-trip whatever it reads back unmodified --
     * normalising it, clearing it on save, or otherwise "cleaning it up" destroys data a future
     * build put there on purpose. See `docs/design/image-attachments.md` §5/§6.
     */
    val meta: String = "",
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AttachmentData) return false
        return id == other.id && noteId == other.noteId && anchor == other.anchor &&
            order == other.order && mimeType == other.mimeType &&
            width == other.width && height == other.height &&
            bytes.contentEquals(other.bytes) &&
            thumbWidth == other.thumbWidth && thumbHeight == other.thumbHeight &&
            thumbBytes.contentEquals(other.thumbBytes) &&
            createdAt == other.createdAt && updatedAt == other.updatedAt &&
            isDeleted == other.isDeleted && deletedAt == other.deletedAt && meta == other.meta
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + noteId.hashCode()
        result = 31 * result + anchor
        result = 31 * result + order
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
        result = 31 * result + meta.hashCode()
        return result
    }
}
