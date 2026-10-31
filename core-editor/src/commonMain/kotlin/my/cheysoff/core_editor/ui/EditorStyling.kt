package my.cheysoff.core_editor.ui

import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.cheysoff.core_blocks.BlockType
import my.cheysoff.core_editor.Markers
import my.cheysoff.core_editor.lineRanges

/**
 * How the editor's text looks, as the field's [OutputTransformation]: styles laid over the
 * buffer for display only.
 *
 * None of this may live in the buffer. [my.cheysoff.core_editor.EditorModel.blocks] reads every
 * span style in the buffer back as inline styles, and the toolbar's restyle drops span attributes
 * that are not inline styles, so a heading size or a grey "done" colour stored there would be
 * saved as bold/strike or silently lost on the next toolbar press. An output transformation
 * changes only what is drawn and leaves offsets alone, which the overlays rely on: they find
 * each marker in the layout by its offset in the field.
 *
 * - Markers are transparent; the overlays draw the checkbox, bullet or number over them. A bullet
 *   or number marker gets [MarkerSpacing] of letter spacing; a checklist marker gets room for the
 *   [CheckBoxSize] box plus a [CheckGap] before its text.
 * - A picture or drawing line is its one marker, sized to make the line [pictureHeight] tall,
 *   scaled by its size ([pictureScale] of the line's index), which is where the overlay draws the
 *   picture.
 * - Headings are sized and bold.
 * - A ticked item's text is struck through and greyed.
 * - Paragraph spacing: every line break carries a transparent span [LineGap] taller than the body
 *   text, so each line is a little taller than its text. This is the plan's fallback: a
 *   hanging-indent `ParagraphStyle` makes Compose 1.12 lay out an extra empty line at every styled
 *   line break (measured by `ParagraphStyle indent adds no blank line`), so there is no hanging
 *   indent and a wrapped list item continues under its marker.
 */
internal class EditorStyling(
    private val bodySize: TextUnit,
    private val pictureHeight: Dp,
    private val fontScale: Float,
    private val doneColor: Color,
    private val pictureScale: (line: Int) -> Float = { 1f },
) : OutputTransformation {

    override fun TextFieldBuffer.transformOutput() {
        val text = asCharSequence()
        for ((k, line) in lineRanges(text).withIndex()) {
            if (line.end < text.length) addStyle(lineBreak, line.end, line.end + 1)
            if (line.start == line.end || !Markers.isPrivateUse(text[line.start])) continue
            val (type, done) = Markers.typeOf(text.subSequence(line.start, line.end))
            val markerEnd = line.start + 1
            when (type) {
                BlockType.PICTURE, BlockType.DRAWING -> addStyle(pictureMarker(pictureScale(k)), line.start, markerEnd)
                BlockType.H1, BlockType.H2, BlockType.H3 -> {
                    addStyle(Transparent, line.start, markerEnd)
                    if (line.end > markerEnd) addStyle(heading(type), markerEnd, line.end)
                }
                BlockType.CHECK, BlockType.BULLET, BlockType.NUMBER -> {
                    addStyle(if (type == BlockType.CHECK) checkMarker else listMarker, line.start, markerEnd)
                    if (done && line.end > markerEnd) addStyle(doneText, markerEnd, line.end)
                }
                // A stray marker in front of text (mid-edit only, see Markers.typeOf): hide it.
                BlockType.PARAGRAPH -> addStyle(Transparent, line.start, markerEnd)
            }
        }
    }

    private val lineBreak = SpanStyle(color = Color.Transparent, fontSize = (bodySize.value + LineGap.value).sp)

    // A font's line is about 1.17 times its size; the overlay uses the laid-out line, so this need
    // only be close. Divided by the font scale because sp grow with it and the picture should not.
    private fun pictureMarker(scale: Float) = SpanStyle(
        color = Color.Transparent,
        fontSize = (pictureHeight.value * scale / LineFactor / fontScale).sp,
    )

    private val listMarker = SpanStyle(color = Color.Transparent, letterSpacing = MarkerSpacing)

    // The checkbox is drawn in dp, so its room is too: divided by the font scale like the picture.
    private val checkMarker = SpanStyle(
        color = Color.Transparent,
        letterSpacing = ((CheckBoxSize.value + CheckGap.value) / fontScale).sp,
    )

    private val doneText = SpanStyle(color = doneColor, textDecoration = TextDecoration.LineThrough)

    private fun heading(type: BlockType) = when (type) {
        BlockType.H1 -> SpanStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold)
        BlockType.H2 -> SpanStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold)
        else -> SpanStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }

    private companion object {
        val Transparent = SpanStyle(color = Color.Transparent)
        val LineGap = 6.sp
        val MarkerSpacing = 10.sp
        const val LineFactor = 1.17f
    }
}

/** The drawn checkbox's side. */
internal val CheckBoxSize = 18.dp

/** Space between a checkbox and its item's text. */
internal val CheckGap = 10.dp
