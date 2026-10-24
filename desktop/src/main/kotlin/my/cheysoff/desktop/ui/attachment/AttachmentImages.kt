package my.cheysoff.desktop.ui.attachment

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import my.cheysoff.core_domain.attachment.sortAttachments
import my.cheysoff.core_domain.model.AttachmentData
import my.cheysoff.core_domain.model.AttachmentPreview
import org.jetbrains.skia.Image as SkiaImage

/**
 * Decodes [bytes] with Skia -- the decoder Compose Multiplatform already ships with, so this adds no
 * new dependency and, critically, writes no plaintext copy to an on-disk cache the way an
 * image-loading library's default disk cache would. The whole point of these bytes living in an
 * encrypted database is that nothing decoded from them ever touches the disk unencrypted.
 *
 * `Image.makeFromEncoded` throws on bytes that do not decode as an image (corrupt or truncated).
 * The Android side shipped exactly this bug twice, both caught in review: an unchecked decode
 * inside a *list* crashes the whole note's composition, not just the one tile that failed. Wrapped
 * in [runCatching] here for the same reason `AttachmentThumbnail` on Android treats a null
 * `Bitmap` as a placeholder rather than propagating the failure.
 *
 * `Image.makeFromEncoded(bytes).toComposeImageBitmap()` is exactly the pair the task brief names --
 * `toComposeImageBitmap()` lives in `androidx.compose.ui.graphics.SkiaImageAsset_skikoKt`, part of
 * `ui-graphics-desktop`, distinct from the unrelated `Image.asImageBitmap()` this artifact also
 * ships (`DesktopImageAsset_desktopKt`) that does not resolve against this receiver.
 */
internal fun decodeAttachmentImage(bytes: ByteArray): ImageBitmap? =
    runCatching { SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

/** [AttachmentData] reduced to the [AttachmentPreview] [sortAttachments] takes. */
private fun AttachmentData.toPreview(): AttachmentPreview = AttachmentPreview(
    id = id,
    noteId = noteId,
    anchor = anchor,
    order = order,
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
 * [attachments] in the shared display order, without ever building a second copy of [bytes].
 *
 * The full-screen viewer's left/right paging steps through this order: the order
 * [sortAttachments] gives both apps, so a second comparator cannot drift from the phone's (the drift
 * `sortAttachments`' own KDoc warns about).
 */
internal fun orderedForDisplay(attachments: List<AttachmentData>): List<AttachmentData> {
    val byId = attachments.associateBy { it.id }
    return sortAttachments(attachments.map { it.toPreview() }).mapNotNull { byId[it.id] }
}
