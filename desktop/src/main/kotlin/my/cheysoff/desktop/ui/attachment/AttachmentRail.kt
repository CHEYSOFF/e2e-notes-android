package my.cheysoff.desktop.ui.attachment

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import my.cheysoff.core_domain.attachment.sortAttachments
import my.cheysoff.core_domain.model.AttachmentData
import my.cheysoff.core_domain.model.AttachmentPreview
import org.jetbrains.skia.Image as SkiaImage

private val AttachmentTileSize = 92.dp

/**
 * A horizontal rail of photo thumbnails below the note's text and below [my.cheysoff.desktop.ui.notes.SketchSection]
 * -- never interleaved with either, matching `docs/design/image-attachments.md` §8 and the exact
 * placement the phone's own `AttachmentSection` uses.
 *
 * Ordered by [sortAttachments] -- the one function both this rail and the phone's own render use, so
 * the same note lists its photos in the same order on both devices. [sortAttachments] takes
 * [AttachmentPreview], not [AttachmentData] (see that function's own KDoc: the rail is its only
 * caller and never needs full-size bytes), so [attachments] is reduced to previews, sorted, and then
 * mapped back to the [AttachmentData] the caller already holds -- there is no second store to fetch
 * from on desktop, unlike the phone's DAO-backed rail.
 *
 * Tapping a tile calls [onTapped] with the full [AttachmentData] already in memory: unlike the
 * phone's viewer, there is no async lookup here, because [my.cheysoff.desktop.store.RecordNotesRepository]
 * already holds the decrypted row (see that class's KDoc on why the desktop store decrypts
 * everything at unlock). The tile itself only ever decodes [AttachmentData.thumbBytes] -- reaching
 * for [AttachmentData.bytes] here would decode up to a megabyte per tile where 64 KiB does the job;
 * [bytes] is for the one-at-a-time viewer.
 *
 * There is no delete affordance here: like the phone, that lives in the full-screen viewer
 * ([AttachmentViewer]), matching `docs/design/image-attachments.md` §8 ("Delete: from the viewer,
 * confirmed").
 */
@Composable
fun AttachmentRail(attachments: List<AttachmentData>, onTapped: (AttachmentData) -> Unit) {
    if (attachments.isEmpty()) return

    val ordered = remember(attachments) { orderedForDisplay(attachments) }

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(ordered, key = { it.id }) { attachment ->
            AttachmentTile(attachment = attachment, onTapped = { onTapped(attachment) })
        }
    }
}

@Composable
private fun AttachmentTile(attachment: AttachmentData, onTapped: () -> Unit) {
    Box(
        modifier = Modifier
            .size(AttachmentTileSize)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1C1C22))
            .pointerInput(attachment.id) {
                detectTapGestures(onTap = { onTapped() })
            },
    ) {
        // Keyed on the id plus a content hash of thumbBytes, not the ByteArray itself -- a
        // ByteArray has identity equality, so keying on the array would re-decode on every
        // recomposition a sibling attachment's own change triggers. See AttachmentThumbnail's
        // (Android) KDoc for the full reasoning; this is the same rule applied here.
        val bitmap = remember(attachment.id, attachment.thumbBytes.contentHashCode()) {
            decodeAttachmentImage(attachment.thumbBytes)
        }
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // else: the placeholder is just the tile's own dark background above -- a corrupt or
        // truncated thumbnail must not crash the whole note's composition. See
        // decodeAttachmentImage's KDoc.
    }
}

/**
 * Decodes [bytes] with Skia -- the decoder Compose Multiplatform already ships with, so this adds no
 * new dependency and, critically, writes no plaintext copy to an on-disk cache the way an
 * image-loading library's default disk cache would. The whole point of these bytes living in an
 * encrypted database is that nothing decoded from them ever touches the disk unencrypted.
 *
 * `Image.makeFromEncoded` throws on bytes that do not decode as an image (corrupt or truncated).
 * The Android side shipped exactly this bug twice, both caught in review: an unchecked decode
 * inside a *list* crashes the whole note's composition, not just the one tile that failed. Wrapped
 * in [runCatching] here for the same reason [AttachmentThumbnail] on Android treats a null
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

/** [attachments] in the shared display order, without ever building a second copy of [bytes]. */
private fun orderedForDisplay(attachments: List<AttachmentData>): List<AttachmentData> {
    val byId = attachments.associateBy { it.id }
    return sortAttachments(attachments.map { it.toPreview() }).mapNotNull { byId[it.id] }
}
