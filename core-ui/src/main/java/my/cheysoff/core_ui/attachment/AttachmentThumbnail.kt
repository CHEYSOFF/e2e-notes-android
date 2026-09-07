package my.cheysoff.core_ui.attachment

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import my.cheysoff.core_domain.model.AttachmentPreview

/**
 * Renders [preview]'s thumbnail -- and only the thumbnail. [AttachmentPreview] has no `bytes`
 * field to reach for by mistake in the first place (see that type's own KDoc); this composable
 * doesn't touch [my.cheysoff.core_domain.model.AttachmentData] at all, so there is nothing here
 * that could be tempted to.
 *
 * Decoding is cached in a `remember` keyed on the attachment's [AttachmentPreview.id] and a
 * content hash of its [AttachmentPreview.thumbBytes] -- **not** the raw `ByteArray` itself, which
 * has no structural `equals` and so compares by identity. A second attachment added to the same
 * note makes the rail's whole list re-emit, including a freshly allocated (but byte-for-byte
 * identical) `thumbBytes` array for every attachment already on screen; keying on the array
 * itself would treat each of those as "changed" and re-decode a thumbnail that hasn't moved at
 * all. `contentHashCode()` is cheap at 64 KiB either way, so there is no cost to getting this
 * right.
 *
 * `BitmapFactory.decodeByteArray` synchronously, on the composing thread, is fine here
 * specifically because [AttachmentPreview.thumbBytes] is capped at 64 KiB -- the same call
 * against [my.cheysoff.core_domain.model.AttachmentData.bytes] (up to 1 MiB) would not be, which
 * is exactly why the full-screen viewer decodes off the main thread instead (see
 * `AttachmentViewerScreen`).
 *
 * `decodeByteArray` returns a **nullable** `Bitmap` -- a corrupt or truncated thumbnail row
 * decodes to `null` rather than throwing. This runs during composition, unconditionally, for
 * every tile the rail renders, so an unchecked `.asImageBitmap()` here would crash the *entire
 * note* on every open, with no way to route around it by not tapping (contrast the full-screen
 * viewer, which only loses the one photo actually tapped). A `null` decode renders a plain
 * placeholder square instead -- a rail with one grey tile is a working note; a rail that throws
 * is a lost one.
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
    val bitmap: ImageBitmap? = remember(preview.id, preview.thumbBytes.contentHashCode()) {
        BitmapFactory.decodeByteArray(preview.thumbBytes, 0, preview.thumbBytes.size)?.asImageBitmap()
    }
    if (bitmap == null) {
        Box(modifier = modifier.background(Color(0xFF1C1C22)))
        return
    }
    Image(
        bitmap = bitmap,
        contentDescription = null,
        contentScale = contentScale,
        modifier = modifier,
    )
}
