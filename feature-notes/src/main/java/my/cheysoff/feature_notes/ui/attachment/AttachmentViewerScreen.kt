package my.cheysoff.feature_notes.ui.attachment

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.cheysoff.core_domain.model.AttachmentData
import my.cheysoff.core_ui.theme.AccentIndigo
import my.cheysoff.core_ui.theme.AppBlack
import my.cheysoff.core_ui.theme.BodyGrey
import my.cheysoff.core_ui.theme.SurfaceDark
import my.cheysoff.core_ui.theme.TitleGrey

private const val MinScale = 1f
private const val MaxScale = 6f

/**
 * Full-screen viewer for one attachment, opened by tapping a tile in [AttachmentSection].
 *
 * [loadAttachment] is the **one** place in this whole feature that reads
 * [AttachmentData.bytes] -- everywhere else (the rail, the note list, any future preview) reads
 * [my.cheysoff.core_domain.model.AttachmentPreview], which physically cannot carry them. This
 * screen decodes one attachment at a time, by id, exactly the shape
 * `docs/design/image-attachments.md` §5 requires of the one caller allowed to select `bytes`.
 *
 * [attachmentId] is looked up asynchronously rather than handed a decoded [AttachmentData]
 * up front, because the caller (`SingleNoteScreen`) only ever holds [AttachmentPreview]s in its
 * own state -- loading the full row, off the main thread, is this screen's job alone.
 *
 * ## Back handling
 * This is the **only** `BackHandler` active while the viewer is showing: `SingleNoteScreen`
 * disables its own for exactly this reason (see that screen's `BackHandler(enabled = ...)`), the
 * same split `SketchCanvasScreen` uses for the drawing canvas. Two active handlers would mean the
 * outer one wins and this screen's handler -- which is also where a future "confirm before losing
 * zoom state" could live -- would simply never run.
 *
 * ## Zoom and pan
 * `detectTransformGestures` drives a `graphicsLayer` scale/translation pair. Scale is clamped to
 * [MinScale]..[MaxScale]; pan is clamped by [panBounds] against the *rendered* image rect under
 * `ContentScale.Fit` -- not against the viewer's own box -- so a photo whose aspect ratio differs
 * from the screen's (the letterboxed axis renders smaller than the box) still cannot be dragged
 * until a gap opens along that axis. See [panBounds]'s own KDoc for the geometry and why the
 * naive box-bound version is wrong above 1x even though it happens to be exact at 1x.
 *
 * ## Delete
 * The overflow trash icon confirms before calling [onDeleted]. The dialog says plainly that this
 * cannot be undone: attachments are not in Trash (`TrashEntryKind` is `{NOTE, FOLDER}`), so unlike
 * "Move to Trash" elsewhere in this app, this delete really is final, and a dialog that implied
 * otherwise would be worse than none (`docs/design/image-attachments.md` §8).
 */
@Composable
fun AttachmentViewerScreen(
    attachmentId: String,
    loadAttachment: suspend (String) -> AttachmentData?,
    onClose: () -> Unit,
    onDeleted: (String) -> Unit,
) {
    BackHandler { onClose() }

    var attachment by remember(attachmentId) { mutableStateOf<AttachmentData?>(null) }
    var bitmap by remember(attachmentId) { mutableStateOf<ImageBitmap?>(null) }
    var loadFailed by remember(attachmentId) { mutableStateOf(false) }
    var confirmDelete by remember(attachmentId) { mutableStateOf(false) }

    var scale by remember(attachmentId) { mutableStateOf(MinScale) }
    var offset by remember(attachmentId) { mutableStateOf(Offset.Zero) }
    var containerSize by remember(attachmentId) { mutableStateOf(IntSize.Zero) }

    // Runs once per id: loads the full row (the one place this feature reads `bytes`) and decodes
    // it off the main thread -- unlike the rail's thumbnail, up to 1 MiB is not cheap enough to
    // decode inline in `remember` the way `AttachmentThumbnail` does with its 64 KiB.
    LaunchedEffect(attachmentId) {
        val data = loadAttachment(attachmentId)
        if (data == null) {
            loadFailed = true
            return@LaunchedEffect
        }
        // decodeByteArray returns a nullable Bitmap -- a row that exists but whose bytes fail to
        // decode (corrupt, truncated) lands in exactly the same "couldn't be loaded" branch as a
        // missing row below, rather than crashing this coroutine.
        val decoded = withContext(Dispatchers.Default) {
            BitmapFactory.decodeByteArray(data.bytes, 0, data.bytes.size)
        }
        if (decoded == null) {
            loadFailed = true
            return@LaunchedEffect
        }
        attachment = data
        bitmap = decoded.asImageBitmap()
    }

    Box(modifier = Modifier.fillMaxSize().background(AppBlack)) {
        val currentBitmap = bitmap
        when {
            loadFailed -> Text(
                text = "This photo couldn't be loaded.",
                color = BodyGrey,
                modifier = Modifier.align(Alignment.Center),
            )

            currentBitmap == null -> CircularProgressIndicator(
                color = AccentIndigo,
                modifier = Modifier.align(Alignment.Center),
            )

            else -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { containerSize = it }
                    .pointerInput(attachmentId) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val newScale = (scale * zoom).coerceIn(MinScale, MaxScale)
                            // Bound against the rendered image rect (panBounds), not the box --
                            // see that function's own KDoc. `attachment` is guaranteed non-null
                            // here (it is set in the same LaunchedEffect, just before `bitmap`,
                            // and this branch only renders once `bitmap` is non-null), but the
                            // `?: Offset.Zero` fallback keeps this block from ever needing `!!`.
                            val bounds = attachment?.let {
                                panBounds(
                                    imageWidth = it.width,
                                    imageHeight = it.height,
                                    boxWidth = containerSize.width.toFloat(),
                                    boxHeight = containerSize.height.toFloat(),
                                    scale = newScale,
                                )
                            } ?: Offset.Zero
                            offset = Offset(
                                x = (offset.x + pan.x * newScale).coerceIn(-bounds.x, bounds.x),
                                y = (offset.y + pan.y * newScale).coerceIn(-bounds.y, bounds.y),
                            )
                            scale = newScale
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = currentBitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = onClose) {
                Icon(imageVector = Icons.Filled.Close, contentDescription = "Close", tint = TitleGrey)
            }
            IconButton(onClick = { confirmDelete = true }, enabled = attachment != null) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = "Delete photo",
                    tint = TitleGrey,
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            containerColor = SurfaceDark,
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this photo?", color = TitleGrey) },
            text = {
                Text(
                    "This can't be undone. Photos aren't kept in Trash.",
                    color = BodyGrey,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDeleted(attachmentId)
                }) { Text("Delete", color = AccentIndigo) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = BodyGrey) }
            },
        )
    }
}

/**
 * How far the image may be panned from centre, in pixels, at [scale].
 *
 * Bound against the rect the image actually occupies under `ContentScale.Fit`, not against the
 * viewer's box: for any photo whose aspect ratio differs from the screen's, the letterboxed axis
 * renders smaller than the box, and clamping to the box lets a zoomed image be dragged until a
 * gap opens along one edge -- exactly the zoomed-in-on-a-receipt case the zoom exists for.
 *
 * [imageWidth]/[imageHeight] are the stored dimensions ([AttachmentData.width]/`.height`), which
 * are trustworthy for this: Task 5 was fixed to read them from the bitmap the encoder actually
 * produced, not the downscale target it requested, so they describe the same rect this decodes
 * and displays.
 *
 * Zero on both axes at scale 1, so a photo that exactly fits cannot be nudged at all -- the one
 * case the box-bound version got right by accident, since both its bounds collapse to zero there
 * regardless of aspect ratio.
 *
 * Degenerate inputs (a zero or negative width, height, or box dimension) return [Offset.Zero]
 * rather than dividing by zero.
 */
internal fun panBounds(
    imageWidth: Int,
    imageHeight: Int,
    boxWidth: Float,
    boxHeight: Float,
    scale: Float,
): Offset {
    if (imageWidth <= 0 || imageHeight <= 0 || boxWidth <= 0f || boxHeight <= 0f) return Offset.Zero
    val fitScale = minOf(boxWidth / imageWidth, boxHeight / imageHeight)
    val renderedWidth = imageWidth * fitScale
    val renderedHeight = imageHeight * fitScale
    val maxX = ((renderedWidth * scale - boxWidth) / 2f).coerceAtLeast(0f)
    val maxY = ((renderedHeight * scale - boxHeight) / 2f).coerceAtLeast(0f)
    return Offset(maxX, maxY)
}
