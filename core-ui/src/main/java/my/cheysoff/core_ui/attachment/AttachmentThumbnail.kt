package my.cheysoff.core_ui.attachment

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import my.cheysoff.core_domain.model.AttachmentPreview

/**
 * Renders [preview]'s thumbnail -- and only the thumbnail. [AttachmentPreview] has no `bytes`
 * field to reach for by mistake in the first place (see that type's own KDoc); this composable
 * doesn't touch [my.cheysoff.core_domain.model.AttachmentData] at all, so there is nothing here
 * that could be tempted to.
 *
 * Decoding is cached in a `remember` keyed on the attachment's [AttachmentPreview.id] and its
 * [AttachmentPreview.thumbBytes], so a rail that scrolls this composable in and out of view never
 * re-decodes a thumbnail it already has. `BitmapFactory.decodeByteArray` synchronously, on the
 * composing thread, is fine here specifically because [AttachmentPreview.thumbBytes] is capped at
 * 64 KiB -- the same call against [my.cheysoff.core_domain.model.AttachmentData.bytes] (up to
 * 1 MiB) would not be, which is exactly why the full-screen viewer decodes off the main thread
 * instead (see `AttachmentViewerScreen`).
 *
 * No `contentDescription` -- these tiles are decorative previews of a photo the user just picked;
 * the accessible action is the tap that opens the full-screen viewer, handled by the caller.
 */
@Composable
fun AttachmentThumbnail(
    preview: AttachmentPreview,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val bitmap = remember(preview.id, preview.thumbBytes) {
        BitmapFactory.decodeByteArray(preview.thumbBytes, 0, preview.thumbBytes.size).asImageBitmap()
    }
    Image(
        bitmap = bitmap,
        contentDescription = null,
        contentScale = contentScale,
        modifier = modifier,
    )
}
