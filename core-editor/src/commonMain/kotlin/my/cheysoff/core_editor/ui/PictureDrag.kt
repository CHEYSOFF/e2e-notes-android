package my.cheysoff.core_editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import my.cheysoff.core_editor.Line
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Moving a picture or drawing by dragging it, as ported from the editor spike.
 *
 * While dragged, the picture shrinks to a thumbnail around the grab point and follows the pointer,
 * and a bar with a dot shows the gap between lines it will land in ([nearestSlot]). On release it
 * moves there through `EditorCommands.movePicture`, one local edit, so it is one undo step and is
 * saved like any other change.
 *
 * The gesture is the platform's ([pictureDragGesture]): a mouse press-drag on the desktop, a long
 * press then drag on Android, where a plain drag must keep scrolling the note. The picture
 * overlay sits above the text field, so it takes the pointer and the field's text selection never
 * starts under a drag.
 */
@Stable
internal class PictureDrag {
    /** Block id of the picture being dragged, or null. */
    var dragging: String? by mutableStateOf(null)
        private set

    /** Pointer travel since the press. */
    var delta by mutableStateOf(Offset.Zero)
        private set

    /** Where in the picture it was grabbed, for the thumbnail's transform origin. */
    var grab by mutableStateOf(Offset.Zero)
        private set

    /** The gap it would land in now, or null. */
    var slot: Slot? by mutableStateOf(null)
        private set

    fun start(id: String, at: Offset) {
        dragging = id
        delta = Offset.Zero
        grab = at
        slot = null
    }

    fun move(by: Offset, layout: TextLayoutResult?, lines: List<Line>, own: Int, pictureTop: Float) {
        delta += by
        slot = layout?.let { nearestSlot(it, lines, own, pictureTop + grab.y + delta.y) }
    }

    /** Ends the drag and returns where to drop, if anywhere. */
    fun end(): Slot? = slot.also { cancel() }

    fun cancel() {
        dragging = null
        delta = Offset.Zero
        slot = null
    }

    /** The thumbnail transform for picture [id]: applied after `pointerInput` (spike finding), else the hit area shrinks with it. */
    fun GraphicsLayerScope.thumbnail(id: String) {
        if (dragging != id) return
        scaleX = THUMBNAIL_SCALE
        scaleY = THUMBNAIL_SCALE
        alpha = 0.9f
        if (size.width > 0 && size.height > 0) transformOrigin = TransformOrigin(grab.x / size.width, grab.y / size.height)
    }

    companion object {
        const val THUMBNAIL_SCALE = 0.35f
    }
}

/** A gap a dragged picture can land in: before line [gap] (`lines.size` is after the last), drawn at [y]. */
internal data class Slot(val gap: Int, val y: Float)

/**
 * The gap nearest [pointerY] (field pixels), skipping the two beside picture line [own], which
 * would leave it where it is. Null when there is nowhere else to go.
 */
internal fun nearestSlot(layout: TextLayoutResult, lines: List<Line>, own: Int, pointerY: Float): Slot? {
    val length = layout.layoutInput.text.length
    val slots = lines.indices.map { k ->
        Slot(k, layout.getLineTop(layout.getLineForOffset(lines[k].start.coerceAtMost(length))))
    } + Slot(lines.size, layout.getLineBottom(layout.lineCount - 1))
    return slots.filter { it.gap != own && it.gap != own + 1 }.minByOrNull { abs(it.y - pointerY) }
}

/** The landing indicator: a bar across the editor with a dot at its start, centred on [slot]'s y. */
@Composable
internal fun DropIndicator(slot: Slot, accent: Color) {
    val y = slot.y.roundToInt()
    Box(
        Modifier.offset { IntOffset(0, y - 2.dp.roundToPx()) }.fillMaxWidth().height(4.dp)
            .clip(RoundedCornerShape(2.dp)).background(accent),
    )
    Box(
        Modifier.offset { IntOffset(-5.dp.roundToPx(), y - 6.dp.roundToPx()) }.size(12.dp)
            .clip(CircleShape).background(accent),
    )
}

/**
 * The platform's picture drag gesture, keyed on [key]. [onStart] gets the press position inside
 * the picture, [onDrag] each movement. Every pointer change of the drag is consumed.
 */
internal expect fun Modifier.pictureDragGesture(
    key: Any,
    onStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
): Modifier
