package my.cheysoff.core_domain.model

/**
 * [AttachmentData] without [AttachmentData.bytes] -- everything a rail, a note list, or any other
 * multi-row query needs to show an attachment, and nothing that costs up to a megabyte per row.
 *
 * This is the type-level half of the "no multi-row query selects `bytes`" rule from
 * `docs/design/image-attachments.md` §5: a DAO method that returns `AttachmentPreview` physically
 * cannot leak the full-size bytes, regardless of what its SQL does. `AttachmentOrdering` takes a
 * `List<AttachmentPreview>` rather than `List<AttachmentData>` for the same reason -- the rail is its
 * only caller, and the rail never loads full-size bytes.
 *
 * [thumbBytes] is still a `ByteArray`, so it carries the same identity-`equals` trap as
 * [AttachmentData] and gets the same treatment.
 */
data class AttachmentPreview(
    val id: String,
    val noteId: String,
    val anchor: Int,
    val order: Int,
    val mimeType: String,
    val width: Int,
    val height: Int,
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
        if (other !is AttachmentPreview) return false
        return id == other.id && noteId == other.noteId && anchor == other.anchor &&
            order == other.order && mimeType == other.mimeType &&
            width == other.width && height == other.height &&
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
