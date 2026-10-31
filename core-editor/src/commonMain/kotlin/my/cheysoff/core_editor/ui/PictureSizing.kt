package my.cheysoff.core_editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.cheysoff.core_editor.MIN_PICTURE_SIZE
import kotlin.math.abs
import kotlin.math.roundToInt

/** How a selected picture or drawing is resized in [BlockEditor]. */
enum class PictureSizing {
    /** A handle on the picture's bottom-right corner, dragged with the mouse. */
    Handle,

    /** A row of size buttons over the picture, for a finger: S, M, L and Full. */
    Presets,
}

/**
 * Whether the picture or drawing being drawn by `pictureContent` is selected. A host opens its
 * viewer only on a tap of a selected picture, so the first tap selects it (showing the resize
 * controls) and the next opens it.
 */
val LocalPictureSelected = compositionLocalOf { false }

/** A picture's scale from its [size] (see `Block.size`): 1 at full size. */
internal fun pictureScale(size: Int?): Float = (size ?: 100) / 100f

/** The S/M/L/Full sizes, in percent (null is full). */
internal val PresetSizes: List<Pair<String, Int?>> = listOf("S" to 25, "M" to 50, "L" to 75, "Full" to null)

/** How close to a quarter step a dragged size snaps onto it, in percent. */
private const val SNAP = 3

/**
 * The size a drag to [right] (the picture's new right edge, in px) over a field [width] px wide
 * makes: in percent, at least [MIN_PICTURE_SIZE], null at full size, and snapped to the nearest
 * quarter when close to one.
 */
internal fun draggedSize(right: Float, width: Float): Int? {
    if (width <= 0f) return null
    val raw = (right / width * 100f).roundToInt().coerceIn(MIN_PICTURE_SIZE, 100)
    val quarter = ((raw + 12) / 25) * 25
    val snapped = if (abs(raw - quarter) <= SNAP) quarter else raw
    return snapped.coerceIn(MIN_PICTURE_SIZE, 100).takeIf { it < 100 }
}

/**
 * The corner handle of picture [id], inside the bottom-right corner of its box, which spans
 * [right] x [bottom] px from the field's top left. Dragging it puts the picture's right edge
 * where the pointer is (less where in the handle it was grabbed): positions, not deltas, because
 * the handle itself moves as the picture grows, and deltas measured in a moving node shrink.
 */
@Composable
internal fun PictureResizeHandle(
    controller: BlockEditorController,
    id: String,
    right: Float,
    bottom: Float,
    fieldWidth: Float,
    accent: Color,
) {
    val density = LocalDensity.current
    val handle = 16.dp
    val side = with(density) { handle.toPx() }
    val left by rememberUpdatedState(right - side)
    val width by rememberUpdatedState(fieldWidth)
    Box(
        Modifier
            .offset { IntOffset((right - side).roundToInt(), (bottom - side).roundToInt()) }
            .size(handle)
            .pointerHoverIcon(PointerIcon.Crosshair)
            .pointerInput(id) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val grab = side - down.position.x
                    val start = controller.model.pictureSizes[id]
                    var last = start
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        last = draggedSize(left + change.position.x + grab, width)
                        controller.previewPictureSize(id, last)
                    }
                    controller.resizePicture(id, last, from = start)
                }
            }
            .testTag("picture-resize-$id")
            .clip(CircleShape)
            .background(Color.White)
            .border(2.dp, accent, CircleShape),
    )
}

/** S, M, L and Full over the bottom-left of picture [id], whose box spans [top]..[bottom] px. */
@Composable
internal fun PictureSizeBar(
    controller: BlockEditorController,
    id: String,
    top: Float,
    bottom: Float,
    accent: Color,
) {
    val height = 32.dp
    val margin = 8.dp
    val current = controller.model.pictureSizes[id]
    Row(
        Modifier
            .offset {
                val y = maxOf(top + margin.toPx(), bottom - margin.toPx() - height.toPx())
                IntOffset(margin.roundToPx(), y.roundToInt())
            }
            .height(height)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xE61C1C22))
            .padding(horizontal = 4.dp)
            .testTag("picture-sizes-$id"),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for ((label, size) in PresetSizes) {
            val on = size == current
            Box(
                Modifier
                    .height(26.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(if (on) accent else Color.Transparent)
                    .clickable { controller.resizePicture(id, size) }
                    .padding(horizontal = 10.dp)
                    .testTag("picture-size-$label-$id"),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(label, style = TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium))
            }
        }
    }
}
