package my.cheysoff.desktop.ui.attachment

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.cheysoff.core_domain.model.AttachmentData
import my.cheysoff.desktop.ui.theme.AccentIndigo
import my.cheysoff.desktop.ui.theme.AppBlack
import my.cheysoff.desktop.ui.theme.BodyGrey
import my.cheysoff.desktop.ui.theme.SurfaceDark
import my.cheysoff.desktop.ui.theme.TitleGrey

private const val MinScale = 1f
private const val MaxScale = 6f

/** How much one scroll-wheel notch changes [scale] by, multiplicatively. */
private const val ScrollZoomStep = 1.08f

/**
 * The full-screen viewer for one attachment, opened by tapping a tile in [AttachmentRail] -- a
 * full-bleed overlay `Box` inside the app's one and only window, not a second OS window. This app
 * has exactly one native `Window` (see `DesktopApp.kt`'s `MananaWindow`); every other full-screen
 * or modal surface, including `SearchPalette`, is an overlay composed straight into that window's
 * root. `SearchPalette`'s own KDoc records why: a desktop `Popup` is non-focusable by default, and
 * inside one the search field never received a keystroke. This viewer wants scroll and drag
 * events, not text input, but the same principle applies -- there is nothing a second surface
 * would buy here that the window's own root does not already provide, and a second `Window` costs
 * real, visible things instead: a separate "Photo" entry in the taskbar and Alt-Tab, no Escape
 * binding, and a size that is not remembered across opens the way `WindowGeometry` remembers the
 * main window's. The caller (`NotesWorkspaceScreen`) renders this as a sibling of `SearchPalette`
 * in the workspace's own root `Box`, gated on `WorkspaceUiState.viewingAttachmentId`, and the
 * window's `onPreviewKeyEvent` (`DesktopApp.handleShortcut`) closes it on Escape the same way it
 * closes the search palette. Resizability comes for free this way: the overlay simply fills
 * whatever size the one real window currently is.
 *
 * Mirrors the phone's `AttachmentViewerScreen`: `ContentScale.Fit`, a clamped zoom/pan, and a
 * delete that confirms and says plainly that it is final.
 *
 * [attachment] is the full row the caller already holds -- unlike the phone, there is no async
 * lookup here; see [AttachmentRail]'s KDoc for why the desktop never needs one.
 *
 * ## Zoom and pan
 * Scroll-to-zoom rather than pinch -- a desktop pointer has no multi-touch surface -- via
 * `Modifier.onPointerEvent(PointerEventType.Scroll)`. A mouse drag pans once zoomed in, the way the
 * phone's `detectTransformGestures` folds pan into the same gesture. Both are clamped through
 * [panBounds], which bounds against the rect the image actually occupies under `ContentScale.Fit`,
 * not against the window's own box -- see that function's own KDoc for why the naive box-bound
 * version is wrong above 1x. [panBounds] is this module's own copy of
 * `AttachmentViewerScreen.panBounds` (Android): `:feature-notes` is an Android-only module this one
 * cannot depend on, the same reason [my.cheysoff.desktop.ui.notes.SketchSection] carries its own
 * copy of the sketch-drawing code rather than sharing `:core-ui`'s.
 *
 * Resizing the window recomputes [panBounds] against the new size in [onSizeChanged] itself and
 * clamps the *current* offset into it immediately, rather than waiting for the next drag or scroll.
 * Without that, shrinking the window after zooming in and panning toward a corner would leave the
 * image's rendered rect centred outside the new, smaller box -- stranded off to one side instead of
 * visible.
 *
 * ## Delete
 * Confirmed, and worded exactly like the phone's dialog and this module's own
 * [my.cheysoff.desktop.ui.notes.SketchSection] delete confirm: attachments are not in Trash
 * (`TrashEntryKind` is `{NOTE, FOLDER}`), so this delete is final, and a dialog that implied
 * otherwise would be worse than none (`docs/design/image-attachments.md` §8).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AttachmentViewer(
    attachment: AttachmentData,
    onClose: () -> Unit,
    onDelete: (String) -> Unit,
) {
    var confirmDelete by remember(attachment.id) { mutableStateOf(false) }
    var bitmap by remember(attachment.id) { mutableStateOf<ImageBitmap?>(null) }
    var loadFailed by remember(attachment.id) { mutableStateOf(false) }

    var scale by remember(attachment.id) { mutableStateOf(MinScale) }
    var offset by remember(attachment.id) { mutableStateOf(Offset.Zero) }
    var containerSize by remember(attachment.id) { mutableStateOf(IntSize.Zero) }

    // Decoded off the composing thread: unlike AttachmentRail's 64 KiB thumbnails, up to 1 MiB of
    // full-size bytes is not cheap enough to decode inline in `remember` -- mirrors the phone's own
    // `withContext(Dispatchers.Default)` in AttachmentViewerScreen. A failed decode (corrupt or
    // truncated bytes) is a message here, never a crash -- see decodeAttachmentImage's own KDoc.
    LaunchedEffect(attachment.id) {
        val decoded = withContext(Dispatchers.Default) { decodeAttachmentImage(attachment.bytes) }
        if (decoded == null) loadFailed = true else bitmap = decoded
    }

    // The overlay's own root, composed straight into the workspace window -- no second Window,
    // no Popup. See this function's own KDoc for why.
    run {
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
                        .onSizeChanged { newSize ->
                            containerSize = newSize
                            val bounds = panBounds(
                                imageWidth = attachment.width,
                                imageHeight = attachment.height,
                                boxWidth = newSize.width.toFloat(),
                                boxHeight = newSize.height.toFloat(),
                                scale = scale,
                            )
                            offset = offset.clampTo(bounds)
                        }
                        .onPointerEvent(PointerEventType.Scroll) { event ->
                            val scrollY = event.changes.firstOrNull()?.scrollDelta?.y ?: return@onPointerEvent
                            // Wheel-forward (negative delta) zooms in, matching every other app.
                            val factor = if (scrollY < 0f) ScrollZoomStep else 1f / ScrollZoomStep
                            val newScale = (scale * factor).coerceIn(MinScale, MaxScale)
                            val bounds = panBounds(
                                imageWidth = attachment.width,
                                imageHeight = attachment.height,
                                boxWidth = containerSize.width.toFloat(),
                                boxHeight = containerSize.height.toFloat(),
                                scale = newScale,
                            )
                            offset = offset.clampTo(bounds)
                            scale = newScale
                        }
                        .pointerInput(attachment.id) {
                            detectDragGestures { _, dragAmount ->
                                val bounds = panBounds(
                                    imageWidth = attachment.width,
                                    imageHeight = attachment.height,
                                    boxWidth = containerSize.width.toFloat(),
                                    boxHeight = containerSize.height.toFloat(),
                                    scale = scale,
                                )
                                offset = (offset + dragAmount).clampTo(bounds)
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
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .align(Alignment.TopCenter),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = onClose) {
                    Icon(imageVector = Icons.Filled.Close, contentDescription = "Close", tint = TitleGrey)
                }
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(imageVector = Icons.Filled.Delete, contentDescription = "Delete photo", tint = TitleGrey)
                }
            }
        }

        if (confirmDelete) {
            AlertDialog(
                containerColor = SurfaceDark,
                onDismissRequest = { confirmDelete = false },
                title = { Text("Delete this photo?", color = TitleGrey) },
                text = {
                    Text("This can't be undone. Photos aren't kept in Trash.", color = BodyGrey)
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDelete = false
                        onDelete(attachment.id)
                    }) { Text("Delete", color = AccentIndigo) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = BodyGrey) }
                },
            )
        }
    }
}

private fun Offset.clampTo(bounds: Offset): Offset =
    Offset(x.coerceIn(-bounds.x, bounds.x), y.coerceIn(-bounds.y, bounds.y))

/**
 * How far the image may be panned from centre, in pixels, at [scale]. The desktop's own copy of
 * `AttachmentViewerScreen.panBounds` (Android) -- identical geometry, restated here because
 * `:feature-notes` is an Android-only module this module cannot depend on (see this file's own
 * KDoc). Bound against the rect the image actually occupies under `ContentScale.Fit`, not against
 * the window's own box: for a photo whose aspect ratio differs from the window's, the letterboxed
 * axis renders smaller than the box, and clamping to the box would let a zoomed image be dragged
 * until a gap opens along that axis.
 *
 * Zero on both axes at scale 1, so a photo that exactly fits cannot be nudged at all. Degenerate
 * inputs (a zero or negative width, height, or box dimension -- which a window mid-resize can
 * briefly report) return [Offset.Zero] rather than dividing by zero.
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
