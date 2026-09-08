package my.cheysoff.feature_notes.ui.attachment

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.cheysoff.core_domain.attachment.sortAttachments
import my.cheysoff.core_domain.model.AttachmentData
import my.cheysoff.core_domain.model.AttachmentPreview
import my.cheysoff.core_ui.theme.AccentIndigo
import my.cheysoff.core_ui.theme.AppBlack
import my.cheysoff.core_ui.theme.BodyGrey
import my.cheysoff.core_ui.theme.SurfaceDark
import my.cheysoff.core_ui.theme.TitleGrey

private const val MinScale = 1f
private const val MaxScale = 6f

/**
 * Full-screen viewer for a note's photos, opened by tapping a tile in [AttachmentSection].
 *
 * [loadAttachment] is the **one** place in this whole feature that reads
 * [AttachmentData.bytes] -- everywhere else (the rail, the note list, any future preview) reads
 * [AttachmentPreview], which physically cannot carry them.
 *
 * ## Paging, and the gesture it collides with
 *
 * The viewer pages horizontally through the note's photos in the order the rail shows them, which
 * is why it takes the whole list rather than one id: [sortAttachments] is called here on the same
 * input the rail sorts, so "the next photo" means the next tile rather than a second opinion about
 * ordering.
 *
 * A pager's swipe and a zoomed image's horizontal pan are the same gesture, so they are separated
 * by state rather than by geometry: `userScrollEnabled` is false whenever the current page is
 * zoomed past [MinScale]. Zoomed in, a horizontal drag pans; at fit, where [panBounds] already
 * clamps the pan to zero on both axes, it pages. Zoom out to move on -- the same rule Google
 * Photos uses, and the only one that does not make a zoomed photo un-pannable sideways.
 *
 * Each page owns its own decode and its own zoom, keyed on its id, and resets that zoom when it
 * stops being the current page, so returning to a photo never finds it mysteriously magnified.
 * Only pages in or entering the viewport are composed, which matters here more than usual: a page
 * holds a decoded bitmap several megabytes larger than the 1 MiB it was stored as.
 *
 * ## What this screen owns
 *
 * This is the **only** `BackHandler` active while the viewer is showing: `SingleNoteScreen`
 * disables its own for exactly this reason (see that screen's `BackHandler(enabled = ...)`), the
 * one that closes the editor. Both exit paths -- back and the close button -- go through
 * [onClose], and deleting goes through [onDeleted] after a confirm dialog whose wording says the
 * thing that cannot be undone: attachments are not in Trash (`TrashEntryKind` is `{NOTE, FOLDER}`),
 * so unlike "Move to Trash" elsewhere in this app, this delete really is final, and a dialog that
 * implied otherwise would be worse than none (`docs/design/image-attachments.md` §8).
 */
@Composable
fun AttachmentViewerScreen(
    attachments: List<AttachmentPreview>,
    initialAttachmentId: String,
    loadAttachment: suspend (String) -> AttachmentData?,
    onClose: () -> Unit,
    onDeleted: (String) -> Unit,
) {
    BackHandler { onClose() }

    val ordered = remember(attachments) { sortAttachments(attachments) }

    // Nothing left to show. Reachable if the last photo is deleted from under the viewer, and
    // closing is the only honest answer -- an empty pager would render a black screen with a
    // delete button that refers to nothing.
    if (ordered.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }

    val initialPage = remember(ordered, initialAttachmentId) {
        ordered.indexOfFirst { it.id == initialAttachmentId }.coerceAtLeast(0)
    }
    val pagerState = rememberPagerState(initialPage = initialPage) { ordered.size }

    // The current page's zoom, reported upward by whichever page is current, and read back only to
    // decide whether a horizontal drag belongs to the pager or to that page's pan.
    var currentScale by remember { mutableFloatStateOf(MinScale) }
    var confirmDelete by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(AppBlack)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = currentScale == MinScale,
            // One page composed either side, so the neighbours have already loaded and decoded by
            // the time a swipe reaches them. Without it every swipe landed on a spinner: a page is
            // only composed as it scrolls in, and the load is a database read plus a JPEG decode.
            //
            // One, not more. Each live page holds a decoded bitmap several megabytes larger than
            // the 1 MiB it was stored as, so this is three at a time rather than a whole note's
            // worth -- the reason the cap is here and not raised further.
            beyondViewportPageCount = 1,
            key = { page -> ordered[page].id },
        ) { page ->
            AttachmentPage(
                attachmentId = ordered[page].id,
                isCurrent = page == pagerState.currentPage,
                loadAttachment = loadAttachment,
                onScaleChanged = { currentScale = it },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(imageVector = Icons.Filled.Close, contentDescription = "Close", tint = TitleGrey)
            }
            // Only once the dot row has stopped being able to show every photo. Below that the
            // dots already answer "which one, how many" exactly, and a number beside them would be
            // the same fact twice; past it the dots become a window and the count is the only
            // precise thing left.
            if (ordered.size > MaxDots) {
                Text(
                    text = "${pagerState.currentPage + 1} of ${ordered.size}",
                    color = BodyGrey,
                )
            } else {
                Spacer(Modifier.width(1.dp))
            }
            // Deliberately always enabled, unlike the rest of this screen's dependence on a loaded
            // row: a photo whose bytes will not decode is exactly the one a person most wants to
            // remove, and gating this on a successful decode left it unremovable from the only
            // screen that offers a delete at all.
            IconButton(onClick = { confirmDelete = true }) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = "Delete photo",
                    tint = TitleGrey,
                )
            }
        }

        if (ordered.size > 1) {
            PageDots(
                count = ordered.size,
                current = pagerState.currentPage,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 20.dp),
            )
        }
    }

    if (confirmDelete) {
        // Read at click time rather than captured: the page can change while the dialog is open
        // only if the pager scrolls under it, which it cannot -- but taking the id here keeps the
        // dialog and the button describing the same photo without a second piece of state.
        val targetId = ordered.getOrNull(pagerState.currentPage)?.id
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
                    targetId?.let(onDeleted)
                }) { Text("Delete", color = AccentIndigo) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = BodyGrey) }
            },
        )
    }
}

/**
 * One photo: its own decode, its own zoom, both keyed on [attachmentId].
 *
 * Reports its zoom upward through [onScaleChanged] so the pager can tell a swipe from a pan, and
 * drops back to fit when it stops being current -- a page left magnified would both strand the
 * pager's `userScrollEnabled` on a stale value and surprise whoever swipes back to it.
 */
@Composable
private fun AttachmentPage(
    attachmentId: String,
    isCurrent: Boolean,
    loadAttachment: suspend (String) -> AttachmentData?,
    onScaleChanged: (Float) -> Unit,
) {
    var attachment by remember(attachmentId) { mutableStateOf<AttachmentData?>(null) }
    var bitmap by remember(attachmentId) { mutableStateOf<ImageBitmap?>(null) }
    var loadFailed by remember(attachmentId) { mutableStateOf(false) }

    var scale by remember(attachmentId) { mutableFloatStateOf(MinScale) }
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

    // Both directions of the same rule. Current: the pager needs this page's zoom to decide who
    // owns a horizontal drag. Not current: reset, so a photo swiped away from mid-zoom is at fit
    // when it comes back and cannot leave the pager scroll-locked behind it.
    LaunchedEffect(isCurrent, scale) {
        if (isCurrent) {
            onScaleChanged(scale)
        } else if (scale != MinScale) {
            scale = MinScale
            offset = Offset.Zero
        }
    }

    val currentBitmap = bitmap
    when {
        loadFailed -> Box(modifier = Modifier.fillMaxSize()) {
            Text(
                text = "This photo couldn't be loaded.",
                color = BodyGrey,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        currentBitmap == null -> Box(modifier = Modifier.fillMaxSize()) {
            CircularProgressIndicator(
                color = AccentIndigo,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        else -> Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { containerSize = it }
                // Hand-rolled rather than `detectTransformGestures`, and that is the whole reason
                // paging works: that detector consumes every position change once the gesture
                // passes touch slop, unconditionally. Consumed changes never reach the pager, so
                // with it in place the image swallowed the swipe and the pager never moved.
                //
                // The rule here is "consume only what is actually ours": a pinch (two fingers) is
                // always ours, a one-finger drag is ours only while zoomed in. At fit, a
                // one-finger drag is left unconsumed and the pager above picks it up -- which
                // costs nothing, because `panBounds` clamps the pan to zero on both axes at fit
                // anyway, so there was never anything for it to do.
                .pointerInput(attachmentId) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.any { it.isConsumed }) break

                            val pressed = event.changes.count { it.pressed }
                            // Read at gesture time, not captured: `scale` is snapshot state, so
                            // this is the zoom as it is now rather than as it was when this
                            // pointerInput was set up.
                            val ours = pressed > 1 || scale > MinScale
                            if (ours) {
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                val newScale = (scale * zoom).coerceIn(MinScale, MaxScale)
                                // Bound against the rendered image rect (panBounds), not the box
                                // -- see that function's own KDoc. `attachment` is guaranteed
                                // non-null here (it is set in the same LaunchedEffect, just before
                                // `bitmap`, and this branch only renders once `bitmap` is
                                // non-null), but the `?: Offset.Zero` fallback keeps this block
                                // from ever needing `!!`.
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
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
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
}

/**
 * How many dots the row will draw before it starts sliding instead of growing.
 *
 * Seven is about where a glance stops being able to count them, and it is also the point past
 * which the dots stop being able to say exactly where you are -- which is why it is the same
 * number that decides whether the top bar shows a count.
 */
private const val MaxDots = 7

/**
 * The row of dots under the photo: which one is showing, and how many there are.
 *
 * Past [MaxDots] the row becomes a window that slides with [current] rather than growing without
 * limit, and the dot at each end where photos continue is drawn smaller -- the standard way of
 * saying "there is more this way" without a number. That taper is the only thing distinguishing a
 * window from a complete row, so a full row deliberately never tapers.
 *
 * Not interactive. Tapping a dot to jump is a fine idea and a different one; these are a position
 * indicator, and giving them a touch target here would put one over the bottom of the photo.
 */
@Composable
private fun PageDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    val window = dotWindow(count, current)
    val start = window.first
    val end = window.last + 1

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (index in start until end) {
            val selected = index == current
            val tapered = (index == start && start > 0) || (index == end - 1 && end < count)
            val size = when {
                selected -> 8.dp
                tapered -> 4.dp
                else -> 6.dp
            }
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(if (selected) TitleGrey else BodyGrey.copy(alpha = 0.55f)),
            )
        }
    }
}

/**
 * Which dot indices the row draws for [count] photos with [current] showing, as an inclusive range.
 *
 * Centred on [current] where it can be and pinned at either end where it cannot, so the first and
 * last photo are reachable rather than sitting half off the row. Everything below [MaxDots] returns
 * the whole range, which is what makes a short row never taper.
 *
 * Pulled out of the composable because it is the only part of the dot row that can be got wrong
 * quietly -- an off-by-one here shows up as a row that never reaches the last photo, or one that
 * jitters by a dot as you page -- and because it is pure arithmetic, testable without a device for
 * the same reason `panBounds` is.
 */
internal fun dotWindow(count: Int, current: Int): IntRange {
    if (count <= 0) return IntRange.EMPTY
    val clamped = current.coerceIn(0, count - 1)
    val start = (clamped - MaxDots / 2).coerceIn(0, maxOf(0, count - MaxDots))
    return start until minOf(count, start + MaxDots)
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
