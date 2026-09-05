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
 * [MinScale]..[MaxScale]; pan is clamped against the *current* scale so the image can be dragged
 * further once zoomed in, but never at all at 1x -- at `scale == 1f` the bound collapses to zero,
 * which is what keeps the image from being thrown off-screen while unzoomed. The bound uses the
 * viewer's own measured size as a stand-in for the rendered image rect (`ContentScale.Fit`
 * letterboxes to it), which is an approximation, not pixel-exact math against the photo's own
 * aspect ratio -- adequate for "can't drag it away", which is all this needs to guarantee.
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
        } else {
            attachment = data
            bitmap = withContext(Dispatchers.Default) {
                BitmapFactory.decodeByteArray(data.bytes, 0, data.bytes.size).asImageBitmap()
            }
        }
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
                            // At newScale == MinScale both bounds are zero, so the image snaps
                            // back to centered -- the "can't drag it off-screen at 1x" rule.
                            val maxX = (containerSize.width * (newScale - 1f) / 2f).coerceAtLeast(0f)
                            val maxY = (containerSize.height * (newScale - 1f) / 2f).coerceAtLeast(0f)
                            offset = Offset(
                                x = (offset.x + pan.x * newScale).coerceIn(-maxX, maxX),
                                y = (offset.y + pan.y * newScale).coerceIn(-maxY, maxY),
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
