package my.cheysoff.core_editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.cheysoff.core_blocks.BlockType
import my.cheysoff.core_editor.Line
import my.cheysoff.core_editor.Markers
import my.cheysoff.core_editor.lineRanges
import my.cheysoff.core_editor.pictureTypeOf
import kotlin.math.roundToInt

/**
 * What is drawn over the field's marker characters: checkboxes, bullets, list numbers, pictures
 * and drawings. Each is placed from the field's [TextLayoutResult] at its line's offset, so it
 * moves with the text as lines are typed, wrapped or scrolled, and nothing about the blocks is
 * kept here: the marker in the text is the only truth.
 *
 * A picture's content is the caller's ([pictureContent]): this module knows no records or image
 * decoding. When the caller cannot resolve the record (not synced yet, failed to decode), it shows
 * its own placeholder there. The block is untouched by that: it stays in the text, so it is in
 * every `onChange` and every save, and its record is not deleted (Review Focus 3).
 *
 * Test tags: `check-<block id>` on a checkbox, `picture-<block id>` on a picture's overlay.
 */
@Composable
internal fun BlockOverlays(
    controller: BlockEditorController,
    view: LaidOutText,
    drag: PictureDrag,
    accent: Color,
    textColor: Color,
    pictureContent: @Composable (recordId: String, kind: BlockType) -> Unit,
) {
    // Placed from the last layout that matched the text (see LaidOutText), never dropped while a
    // newer one is pending: leaving composition would dispose every overlay, the host's picture
    // content included, and re-decode each picture on every keystroke.
    val layout = view.layout
    val text = view.text
    val lines = lineRanges(text)
    val ids = view.ids
    val refs = view.refs
    val density = LocalDensity.current
    var number = 0
    lines.forEachIndexed { k, line ->
        val marker = text.getOrNull(line.start).takeIf { line.end > line.start }
        if (marker != Markers.NUMBER) number = 0
        if (marker == null || !Markers.isPrivateUse(marker)) return@forEachIndexed
        val id = ids[k]
        key(id) {
            val box = layout.getBoundingBox(line.start)
            val textX = if (line.end > line.start + 1) layout.getHorizontalPosition(line.start + 1, true) else box.right
            val centreY = (box.top + box.bottom) / 2
            when {
                pictureTypeOf(text, line) != null -> {
                    // A block saved without `ref` still names its record in its id ("pic:<record>").
                    val ref = refs[id] ?: id.substringAfter(':')
                    val kind = if (marker == Markers.DRAWING) BlockType.DRAWING else BlockType.PICTURE
                    PictureOverlay(controller, layout, drag, lines[k], id, accent) { pictureContent(ref, kind) }
                }
                marker == Markers.CHECK_OPEN || marker == Markers.CHECK_DONE -> {
                    val done = marker == Markers.CHECK_DONE
                    // The visible box sits at the line's start; the tap target around it spans
                    // the item's line from a little left of the box up to the text, so a tap near
                    // the box ticks it instead of putting the cursor in the item.
                    val touchLeft = (box.left - with(density) { CheckTouchSlop.toPx() }).coerceAtLeast(0f)
                    val (touchWidth, touchHeight) = with(density) {
                        (textX - touchLeft).toDp().coerceAtLeast(CheckBoxSize) to
                            (box.bottom - box.top).toDp().coerceAtLeast(CheckBoxSize)
                    }
                    Box(
                        Modifier.offset {
                            IntOffset(touchLeft.roundToInt(), (centreY - touchHeight.toPx() / 2).roundToInt())
                        }.size(touchWidth, touchHeight)
                            .clickable(interactionSource = null, indication = null) {
                                // By id: the line may have moved since this layout.
                                controller.model.ids.indexOf(id).takeIf { it >= 0 }?.let(controller::toggleCheck)
                            }
                            .testTag("check-$id"),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Box(
                            Modifier.offset { IntOffset((box.left - touchLeft).roundToInt(), 0) }
                                .size(CheckBoxSize).clip(RoundedCornerShape(5.dp))
                                .background(if (done) accent else Color.Transparent)
                                .border(1.5.dp, accent, RoundedCornerShape(5.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (done) Canvas(Modifier.size(10.dp)) {
                                val w = 2.dp.toPx()
                                drawLine(Color.White, Offset(0f, size.height * .55f), Offset(size.width * .4f, size.height), w)
                                drawLine(Color.White, Offset(size.width * .4f, size.height), Offset(size.width, 0f), w)
                            }
                        }
                    }
                }
                marker == Markers.BULLET -> {
                    val dot = 6.dp
                    Box(
                        Modifier.offset {
                            val x = (box.left + textX) / 2 - dot.toPx() / 2
                            IntOffset(x.roundToInt(), (centreY - dot.toPx() / 2).roundToInt())
                        }.size(dot).clip(CircleShape).background(textColor),
                    )
                }
                marker == Markers.NUMBER -> {
                    number++
                    val label = "$number."
                    val width = with(density) { (textX - box.left).toDp() }
                    Box(
                        Modifier.offset { IntOffset(box.left.roundToInt(), box.top.roundToInt()) }
                            .width(width).height(with(density) { (box.bottom - box.top).toDp() }),
                        contentAlignment = Alignment.CenterStart,
                    ) { BasicText(label, style = TextStyle(color = textColor, fontSize = 14.sp)) }
                }
            }
        }
    }
}

/** A picture or drawing over its marker line, filling the line, and draggable to another gap. */
@Composable
private fun PictureOverlay(
    controller: BlockEditorController,
    layout: TextLayoutResult,
    drag: PictureDrag,
    at: Line,
    id: String,
    accent: Color,
    content: @Composable () -> Unit,
) {
    val line = layout.getLineForOffset(at.start)
    val top = layout.getLineTop(line)
    val bottom = layout.getLineBottom(line)
    val density = LocalDensity.current
    val sel = controller.model.state.selection
    val selected = at.start in sel.min until sel.max
    val dragging = drag.dragging == id
    with(density) {
        Box(
            Modifier
                .offset {
                    val d = if (dragging) drag.delta else Offset.Zero
                    IntOffset(d.x.roundToInt(), (top + d.y).roundToInt())
                }
                .width((layout.size.width / density.density).dp)
                .height(((bottom - top) / density.density).dp)
                .testTag("picture-$id")
                .pictureDragGesture(
                    key = id,
                    onStart = { at -> drag.start(id, at) },
                    onDrag = { by ->
                        // The line may have moved since composition; find it again by id.
                        val cur = controller.laidOut
                        val now = cur?.ids?.indexOf(id) ?: -1
                        if (cur != null && now >= 0) {
                            val ls = lineRanges(cur.text)
                            val t = cur.layout.getLineTop(cur.layout.getLineForOffset(ls[now].start))
                            drag.move(by, cur.layout, ls, now, t)
                        }
                    },
                    onEnd = {
                        val slot = drag.end()
                        val from = controller.model.ids.indexOf(id)
                        if (slot != null && from >= 0) controller.movePicture(from, slot.gap)
                    },
                    onCancel = { drag.cancel() },
                )
                .pointerInput(id) {
                    detectTapGestures {
                        // A tap selects the picture, so Delete or Cut acts on it.
                        val at = controller.model.ids.indexOf(id)
                        if (at >= 0) {
                            val start = lineRanges(controller.model.state.text)[at].start
                            controller.model.edit { selection = TextRange(start, start + 1) }
                        }
                    }
                }
                // After pointerInput (spike finding): scaling before it would shrink the hit area mid-drag.
                .graphicsLayer { with(drag) { thumbnail(id) } }
                .clip(RoundedCornerShape(10.dp))
                .then(if (selected || dragging) Modifier.border(3.dp, accent, RoundedCornerShape(10.dp)) else Modifier),
        ) { content() }
    }
}

/**
 * A field layout together with the text, line ids and picture records it was made for. The
 * field's layout trails its text by a frame after each edit (composition sees the new text,
 * `onTextLayout` comes during measure), so the overlays keep using the last consistent one until
 * the next arrives, rather than mixing the new text with old line positions.
 */
internal class LaidOutText(
    val layout: TextLayoutResult,
    val text: String,
    val ids: List<String>,
    val refs: Map<String, String>,
)

/** How far left of the checkbox a tap still ticks it. */
private val CheckTouchSlop = 8.dp
