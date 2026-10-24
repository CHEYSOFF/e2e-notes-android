package my.cheysoff.core_editor

import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.text.TextRange
import my.cheysoff.core_blocks.BlockType
import my.cheysoff.core_blocks.InlineStyle
import my.cheysoff.core_blocks.StyleSpan

/**
 * The toolbar's commands. Each one is a single [EditorModel.edit], never a raw
 * `TextFieldState.edit`. That way the block ids stay in step, and one press is one change for undo.
 * `TextFieldState.edit` does not run the field's InputTransformation (ruling R3), so none of
 * [EditingRules] applies here. Each command keeps the text in the editor's shape itself: one
 * block per line, markers only at line starts, pictures alone on their lines.
 *
 * Text lines a command creates (the second half of a split) are not staged. [EditorModel.onEdited]
 * gives an unstaged new text line a fresh id, which is what a new block should have. Staging is
 * for lines that must come back under a chosen id. Picture markers are always staged, since the
 * field cannot say which record a marker stands for.
 *
 * Each command is a local change, so it is recorded in the model's [BlockHistory], if it has one,
 * as one entry ([localEdit]). A command that changes nothing records nothing.
 */
object EditorCommands {

    /**
     * Turns [style] on over the selection, or off if all of the selected text already has it,
     * as Word does. Markers and line breaks are skipped: a style never covers a checkbox or
     * picture char, so it cannot leak into the next item or show on a marker. A collapsed
     * selection changes nothing. There is no "style the next typed char" state.
     */
    fun toggle(model: EditorModel, style: InlineStyle) = restyle(model) { pieces, runs ->
        val have = runs.getValue(style)
        runs[style] = if (pieces.all { covered(have, it) }) subtract(have, pieces) else have + pieces
    }

    /** Removes every inline style from the selected text. Block types are kept. */
    fun clearFormatting(model: EditorModel) = restyle(model) { pieces, runs ->
        for (s in InlineStyle.entries) runs[s] = subtract(runs.getValue(s), pieces)
    }

    /**
     * Makes every line the selection touches a [type] line, skipping pictures. A selection that
     * ends at the very start of a line does not touch that line. If every touched line already is
     * a [type] line, they all return to paragraphs, so the button is a toggle. A new checklist
     * item starts unticked. An item that already is a checklist keeps its tick.
     *
     * @throws IllegalArgumentException for a picture or drawing type: those are inserted with
     *   [insertPicture], not made out of text.
     */
    fun setType(model: EditorModel, type: BlockType) {
        require(!Markers.isPictureType(type)) { "setType cannot make a $type; use insertPicture" }
        localEdit(model) {
            val text = asCharSequence()
            val lines = lineRanges(text)
            val sel = selection
            val first = lineOf(lines, sel.min)
            var last = lineOf(lines, sel.max)
            if (last > first && lines[last].start == sel.max) last--
            val targets = (first..last).filter { pictureTypeOf(text, lines[it]) == null }
            if (targets.isEmpty()) return@localEdit
            val newType =
                if (targets.all { lineType(text, lines[it]) == type }) BlockType.PARAGRAPH else type
            var start = sel.start
            var end = sel.end
            for (k in targets.asReversed()) {
                val line = lines[k]
                if (lineType(text, line) == newType) continue
                val oldLen = if (hasMarker(text, line)) 1 else 0
                val marker = Markers.of(newType, done = false)?.toString() ?: ""
                replace(line.start, line.start + oldLen, marker)
                // A caret on the line stays on its text, and one before a new marker goes after it.
                fun map(p: Int) =
                    if (p < line.start) p else maxOf(line.start + marker.length, p + marker.length - oldLen)
                start = map(start)
                end = map(end)
            }
            selection = TextRange(start, end)
        }
    }

    /** Ticks or unticks the checklist item on line [lineIndex]. Any other line is left alone. */
    fun toggleCheck(model: EditorModel, lineIndex: Int) {
        val lines = lineRanges(model.state.text)
        require(lineIndex in lines.indices) { "no line $lineIndex" }
        val at = lines[lineIndex].start
        val flipped = when (model.state.text.getOrNull(at)) {
            Markers.CHECK_OPEN -> Markers.CHECK_DONE
            Markers.CHECK_DONE -> Markers.CHECK_OPEN
            else -> return
        }
        localEdit(model) {
            val sel = selection
            replace(at, at + 1, flipped.toString())
            selection = sel
        }
    }

    /**
     * Puts picture or drawing [blockId] (`pic:`/`drw:` plus its record id, see
     * `BlockIds.forPicture`) on its own line at the cursor, replacing any selection:
     * - At the start of a line's text (just after a checkbox counts), it goes on a line above.
     * - At the end of the text, it goes on a line below.
     * - Mid-line, the line is split around it. The first half keeps the block's id. The second is
     *   a new block of the same type (an unticked item for a checklist), as Enter would make it.
     * - An empty paragraph becomes the picture.
     * The caret ends up just after the inserted lines. Removing the selection can join a picture
     * line and a text line. They are first split apart again, so the existing picture keeps its
     * line, id and record, and the text keeps its own line.
     *
     * @throws IllegalArgumentException if [kind] is not a picture type, [blockId] does not match
     *   it, or the block is already shown.
     */
    fun insertPicture(model: EditorModel, blockId: String, kind: BlockType) {
        require(Markers.isPictureType(kind)) { "not a picture type: $kind" }
        val prefix = if (kind == BlockType.PICTURE) "pic:" else "drw:"
        require(blockId.startsWith(prefix) && blockId.length > prefix.length) { "$blockId is not a $kind block id" }
        val marker = Markers.of(kind, done = false)!!
        model.stagePicture(blockId, kind, blockId.removePrefix(prefix))
        localEdit(model) {
            val sel = selection
            var at = sel.min
            if (!sel.collapsed) {
                replace(sel.min, sel.max, "")
                at = splitJoinedPicture(this, at)
            }
            val text = asCharSequence()
            val lines = lineRanges(text)
            val line = lines[lineOf(lines, at)]
            val textStart = if (hasMarker(text, line)) line.start + 1 else line.start
            val (pos, piece) = when {
                line.start == line.end -> line.start to "$marker"
                pictureTypeOf(text, line) != null ->
                    if (at == line.start) line.start to "$marker\n" else line.end to "\n$marker"
                at <= textStart -> line.start to "$marker\n"
                at >= line.end -> line.end to "\n$marker"
                else -> {
                    val head = if (hasMarker(text, line)) text[line.start] else null
                    val tail = if (head == Markers.CHECK_DONE) Markers.CHECK_OPEN else head
                    at to "\n$marker\n" + (tail?.toString() ?: "")
                }
            }
            replace(pos, pos, piece)
            selection = TextRange(pos + piece.length)
        }
    }

    /**
     * Moves the picture or drawing on line [from] to gap [toGap]. Gap `g` is just before line `g`
     * of the current text, and gap `lines` is after the last line. The two gaps beside the picture
     * itself leave it where it is. One picture per edit, deleted and re-inserted in the same edit
     * and not staged. That is how [EditorModel.onEdited] recognises a move and keeps the picture's
     * id. The caret ends up collapsed just after the moved picture's line, not on its marker,
     * so the next typed letter cannot replace the picture.
     *
     * @throws IllegalArgumentException if line [from] is not a picture or [toGap] is out of range.
     */
    fun movePicture(model: EditorModel, from: Int, toGap: Int) {
        val text = model.state.text
        val lines = lineRanges(text)
        require(from in lines.indices && pictureTypeOf(text, lines[from]) != null) { "line $from is not a picture" }
        require(toGap in 0..lines.size) { "no gap $toGap in ${lines.size} lines" }
        if (toGap == from || toGap == from + 1) return
        val line = lines[from]
        val marker = text[line.start]
        localEdit(model) {
            if (from < lines.lastIndex) replace(line.start, line.end + 1, "") else replace(line.start - 1, line.end, "")
            val rest = lineRanges(asCharSequence())
            val target = if (toGap > from) toGap - 1 else toGap
            if (target < rest.size) {
                val at = rest[target].start
                replace(at, at, "$marker\n")
                selection = TextRange(at + 2)
            } else {
                val at = length
                replace(at, at, "\n$marker")
                selection = TextRange(at + 2)
            }
        }
    }

    /**
     * Removes the picture or drawing line showing record [recordId], as one local edit recorded for
     * undo, so Undo puts the same block (id and ref) back. Returns false, changing nothing, when no
     * line shows that record.
     *
     * For a delete made outside the text (the desktop's full-screen viewer): removing the BLOCK,
     * not the record, keeps the body the one authority on which pictures a note has. The save that
     * follows soft-deletes the record (`PictureLifecycle`), and undoing then undeletes it. Deleting
     * the record directly would leave a block the next save brings the record back for.
     *
     * The line goes through [LineEdits.delete], the same removal synced deletes use, which keeps the
     * neighbouring lines' ids where a plain text cut could hand a marker's id to the wrong line.
     */
    fun removePicture(model: EditorModel, recordId: String): Boolean {
        val id = model.refs.entries.firstOrNull { it.value == recordId }?.key ?: return false
        val history = model.history
        val before = model.blocks()
        LineEdits(model).delete(id)
        history?.recordLocal(before, model.blocks())
        return true
    }

    /**
     * Rewrites the field's inline styles with [change] applied to the selected text. [change] gets
     * the selected pieces of each line's text, never a marker or line break, and each style's runs
     * as field offsets. Every inline style is then re-placed per line through [placeStyles], the
     * same segmentation loading uses. The field then shows what [EditorModel.blocks] reads back.
     * Underline and strike stay one combined decoration per segment, never two spans on one char.
     * A span with no inline meaning (none is added today) is left untouched.
     */
    private fun restyle(
        model: EditorModel,
        change: (pieces: List<IntRange>, runs: MutableMap<InlineStyle, List<IntRange>>) -> Unit,
    ) {
        val sel = model.state.selection
        if (sel.collapsed) return
        localEdit(model) {
            val text = asCharSequence()
            val lines = lineRanges(text)
            val parts = lines.map { textPart(text, it) }
            val pieces = parts.mapNotNull { p ->
                val a = maxOf(p.first, sel.min)
                val b = minOf(p.last + 1, sel.max)
                if (a < b) a until b else null
            }
            if (pieces.isEmpty()) return@localEdit
            val placed = getSpanStyles(TextRange(0, length))
                .map { Triple(it, stylesOf(it.spanStyle), it.textRange) }
                .filter { it.second.isNotEmpty() }
            val runs = InlineStyle.entries.associateWith { s ->
                placed.filter { s in it.second && !it.third.collapsed }.map { it.third.min until it.third.max }
            }.toMutableMap()
            change(pieces, runs)
            placed.forEach { removeStyle(it.first) }
            val out = mutableListOf<PlacedStyle>()
            for (p in parts) {
                if (p.isEmpty()) continue
                val spans = runs.flatMap { (style, list) ->
                    list.mapNotNull { r ->
                        val a = maxOf(r.first, p.first)
                        val b = minOf(r.last + 1, p.last + 1)
                        if (a < b) StyleSpan(a - p.first, b - p.first, style) else null
                    }
                }
                placeStyles(normalizeSpans(text.subSequence(p.first, p.last + 1), spans), p.first, out)
            }
            out.forEach { addStyle(it.style, it.start, it.end) }
        }
    }

    /**
     * [EditorModel.edit], recorded in [model]'s history (if any) as one local change. Every
     * command edits through here, so a toolbar press is exactly one undo step.
     */
    private fun localEdit(model: EditorModel, block: TextFieldBuffer.() -> Unit) {
        val history = model.history
        val before = if (history != null) model.blocks() else null
        model.edit(block)
        if (history != null && before != null) history.recordLocal(before, model.blocks())
    }

    /**
     * After a removed selection joined the lines around [at], puts a line break back between a
     * picture marker and text now sharing its line. Returns where [at] is afterwards. Without this,
     * the picture would read back as a paragraph and be dropped. This is a much narrower job than
     * the picture rule in [EditingRules]: a command inserts nothing of the user's, so there is no
     * typed text to move, only the one join point to split.
     */
    private fun splitJoinedPicture(buffer: TextFieldBuffer, at: Int): Int {
        val t = buffer.asCharSequence()
        fun isPicture(c: Char) = c == Markers.PICTURE || c == Markers.DRAWING
        // "<picture>|text": the picture line is now followed by text.
        if (at > 0 && isPicture(t[at - 1]) && (at == 1 || t[at - 2] == '\n') && at < t.length && t[at] != '\n') {
            buffer.replace(at, at, "\n")
            return at + 1
        }
        // "text|<picture>": text now runs into the picture line.
        if (at > 0 && t[at - 1] != '\n' && at < t.length && isPicture(t[at]) && (at + 1 == t.length || t[at + 1] == '\n')) {
            buffer.replace(at, at, "\n")
        }
        return at
    }

    /** The offsets of [line]'s text, without its marker. Empty for a picture line. */
    private fun textPart(text: CharSequence, line: Line): IntRange {
        val start = if (hasMarker(text, line)) line.start + 1 else line.start
        return start until line.end
    }

    private fun hasMarker(text: CharSequence, line: Line) =
        line.end > line.start && Markers.isPrivateUse(text[line.start])

    private fun lineType(text: CharSequence, line: Line) = Markers.typeOf(text.subSequence(line.start, line.end)).first

    /** Whether [runs] together cover every offset of [piece]. */
    private fun covered(runs: List<IntRange>, piece: IntRange): Boolean {
        var at = piece.first
        while (at <= piece.last) {
            val run = runs.firstOrNull { at in it } ?: return false
            at = run.last + 1
        }
        return true
    }

    /** [runs] with every offset of [pieces] taken out. */
    private fun subtract(runs: List<IntRange>, pieces: List<IntRange>): List<IntRange> =
        pieces.fold(runs) { acc, cut ->
            acc.flatMap { r ->
                listOf(r.first until minOf(r.last + 1, cut.first), maxOf(r.first, cut.last + 1)..r.last)
                    .filter { !it.isEmpty() }
            }
        }
}
