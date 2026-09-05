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
            isDeleted == other.isDeleted && deletedAt == other.deletedAt
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
        return result
    }
}
