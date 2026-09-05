package my.cheysoff.feature_notes.ui.attachment

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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import my.cheysoff.core_domain.attachment.sortAttachments
import my.cheysoff.core_domain.model.AttachmentPreview
import my.cheysoff.core_ui.attachment.AttachmentThumbnail

/**
 * A horizontal rail of photo thumbnails, below the note's text and below [SketchSection] -- never
 * interleaved with either. Same reasoning as [SketchSection]'s own KDoc: the body is one
 * `BasicRichTextEditor`, so there is no way to host a composable *between* two of its paragraphs.
 *
 * Deliberately a [LazyRow] of small square tiles rather than [SketchSection]'s column of
 * full-width, full-aspect-ratio cards: a sketch's own aspect ratio IS the drawing, so letterboxing
 * it away would be wrong, but a photo rail sitting right below that column must not also grow
 * without bound as more photos are attached -- one row of fixed-height tiles keeps this section's
 * height constant regardless of how many photos a note holds, which is the "must not fight over
 * vertical space" requirement this section exists to satisfy.
 *
 * Ordered by [sortAttachments] -- the one function both this rail and the desktop's own render use,
 * so the same note lists its photos in the same order on both devices (see that function's own
 * KDoc). Tapping a tile opens the full-screen viewer via [onTapped]; nothing here loads
 * [my.cheysoff.core_domain.model.AttachmentData.bytes] -- [AttachmentPreview] has no such field, and
 * [AttachmentThumbnail] only ever decodes [AttachmentPreview.thumbBytes].
 *
 * Tap handling goes through `pointerInput` + `detectTapGestures` rather than `Modifier.clickable`,
 * matching the sketch canvas' own swatches -- see that screen's KDoc on why `detectDragGestures`'
 * `onDragStart` (which only fires past touch slop) is the wrong tool for a tap target, even though
 * nothing here shares a gesture surface with a drag detector today.
 *
 * There is no delete affordance here: unlike [SketchSection]'s cards, a tile in this rail is not
 * itself the confirm-and-delete surface -- that lives in the full-screen viewer
 * (`AttachmentViewerScreen`), matching `docs/design/image-attachments.md` §8.
 */
@Composable
fun AttachmentSection(
    attachments: List<AttachmentPreview>,
    onTapped: (AttachmentPreview) -> Unit,
) {
    if (attachments.isEmpty()) return

    val ordered = remember(attachments) { sortAttachments(attachments) }

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(ordered, key = { it.id }) { preview ->
            AttachmentTile(preview = preview, onTapped = { onTapped(preview) })
        }
    }
}

private val AttachmentTileSize = 92.dp

@Composable
private fun AttachmentTile(preview: AttachmentPreview, onTapped: () -> Unit) {
    Box(
        modifier = Modifier
            .size(AttachmentTileSize)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1C1C22))
            .pointerInput(preview.id) {
                detectTapGestures(onTap = { onTapped() })
            },
    ) {
        AttachmentThumbnail(preview = preview, modifier = Modifier.fillMaxSize())
    }
}
