package my.cheysoff.core_editor

import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.text.TextRange
import my.cheysoff.core_blocks.Block

/**
 * Whole-block edits made from outside the user's typing: undo/redo ([BlockHistory]) and synced
 * changes ([RemoteApply]). Both need to put a block with a known id into a known state.
 *
 * Each operation is one small [EditorModel.edit] of a shape whose id outcome
 * [EditorModel.onEdited] guarantees: rewrite one line, insert one staged line, remove one line,
 * move one picture. One big edit would also work in principle, but then which line keeps which id
 * would depend on how its changes happen to group, and a wrong guess silently gives a paragraph
 * another paragraph's id. Callers run a batch synchronously, so it still shows as one change.
 *
 * The selection is kept where it was relative to the text around it. A rewrite or a removal
 * leaves that to the buffer's own range tracking. An insertion does not, because the buffer puts
 * text inserted exactly at the caret in front of it, and widens a selection that ends where text
 * is inserted. A line inserted below the caret's line would take the caret along. So inserts and
 * moves set the selection themselves: an offset at the insertion point stays put when the new line
 * goes after the line it belongs to, and moves with its line when the new line goes in front.
 */
internal class LineEdits(private val model: EditorModel) {

    /** Test hook: runs before each edit, named "rewrite", "insert", "move", "placeholder" or "delete". Throwing aborts it. */
    var beforeStep: ((String) -> Unit)? = null

    /**
     * Sets text line [id]'s marker, text and spans to [block]'s. Returns whether anything changed.
     * Only the differing middle of the line is replaced, so a caret in the unchanged start or end
     * of the line (a tick changes only the marker) stays exactly where it was.
     */
    fun rewrite(id: String, block: Block): Boolean {
        val current = model.blocks().first { it.id == id }
        if (sameContent(current, block)) return false
        val k = model.ids.indexOf(id)
        val content = lineContent(block)
        step("rewrite")
        model.edit {
            val line = lineRanges(asCharSequence())[k]
            val old = asCharSequence().subSequence(line.start, line.end)
            var prefix = 0
            while (prefix < old.length && prefix < content.length && old[prefix] == content[prefix]) prefix++
            var suffix = 0
            while (suffix < old.length - prefix && suffix < content.length - prefix &&
                old[old.length - 1 - suffix] == content[content.length - 1 - suffix]
            ) suffix++
            if (prefix + suffix < old.length || prefix + suffix < content.length) {
                replace(line.start + prefix, line.end - suffix, content.substring(prefix, content.length - suffix))
            }
            setSpans(this, line.start + content.length - block.text.length, line.start + content.length, block)
        }
        return true
    }

    /**
     * Inserts [block] as a new line under its own id at the place [where] gives in the current
     * [EditorModel.ids]. A picture or drawing is staged with its record ([Block.ref], else the
     * record named by its id), whether or not that record is known on this device yet.
     */
    fun insert(block: Block, where: (List<String>) -> Anchor) {
        val picture = Markers.isPictureType(block.type)
        step("insert")
        if (picture) {
            model.stagePicture(block.id, block.type, block.ref ?: block.id.substringAfter(':'), block.size)
        } else {
            model.stageText(block.id)
        }
        val content = lineContent(block)
        val anchor = where(model.ids)
        model.edit {
            val sel = selection
            val (start, map) = insertLine(this, anchor, content)
            if (!picture) setSpans(this, start + content.length - block.text.length, start + content.length, block)
            selection = TextRange(map(sel.start), map(sel.end))
        }
    }

    /**
     * Moves picture line [id] to [where], an anchor in the coordinates of the other lines (which
     * are also the lines once this one is out). Removed and re-inserted in one unstaged edit, which
     * [EditorModel.onEdited] reads as a move and so keeps the id and the record. Returns false,
     * making no edit, if the picture is already there.
     */
    fun move(id: String, where: (others: List<String>) -> Anchor): Boolean {
        val ids = model.ids
        val k = ids.indexOf(id)
        val others = ids.filterIndexed { i, _ -> i != k }
        val anchor = where(others)
        // Where it stands now is between others[k - 1] and others[k].
        val alreadyThere = anchor == Anchor(k - 1, after = true) ||
            anchor == Anchor(k, after = false) ||
            (k == 0 && anchor == Anchor(-1, after = false))
        if (alreadyThere) return false
        step("move")
        val content = lineContent(model.blocks()[k])
        model.edit {
            val sel = selection
            val removed = removeLine(this, k)
            val (_, inserted) = insertLine(this, anchor, content)
            selection = TextRange(inserted(removed(sel.start)), inserted(removed(sel.end)))
        }
        return true
    }

    /**
     * Removes line [id]. Deleting the first line takes the break after it, and the next line's id
     * follows its first surviving character. A next line with no character but its marker (an
     * empty item or paragraph) has none, and its id would be lost to the deleted line's. So such a
     * line is first given a placeholder character, which carries its id across, and the
     * placeholder is taken out again after.
     */
    fun delete(id: String) {
        val k = model.ids.indexOf(id)
        if (model.ids.size == 1) {
            // The last line cannot go; a document always has one. Leave it, emptied.
            model.edit { replace(0, length, "") }
            return
        }
        if (k == 0) {
            val text = model.state.text
            val next = lineRanges(text)[1]
            val bare = pictureTypeOf(text, next) == null &&
                (next.start until next.end).all { Markers.isPrivateUse(text[it]) }
            if (bare) {
                val carrier = model.ids[1]
                step("placeholder")
                model.edit { replace(next.end, next.end, PLACEHOLDER) }
                try {
                    step("delete")
                    model.edit { removeLine(this, 0) }
                } finally {
                    // Whether or not the first line went, the placeholder must not stay.
                    val at = model.ids.indexOf(carrier)
                    model.edit {
                        val line = lineRanges(asCharSequence())[at]
                        replace(line.end - PLACEHOLDER.length, line.end, "")
                    }
                }
                return
            }
        }
        step("delete")
        model.edit { removeLine(this, k) }
    }

    private fun step(name: String) {
        beforeStep?.invoke(name)
    }

    /** Where a line goes: after line [line] if [after], else before it. `(-1, false)` is the top. */
    data class Anchor(val line: Int, val after: Boolean)

    private companion object {
        /** Any ordinary char will do; it never stays in the text past one [delete]. */
        const val PLACEHOLDER = "x"
    }
}

/**
 * Inserts a line holding [content] at [where]. Returns the offset the new line starts at, and how
 * an offset of the text before maps into the text after (see [LineEdits] on the selection).
 */
private fun insertLine(buffer: TextFieldBuffer, where: LineEdits.Anchor, content: String): Pair<Int, (Int) -> Int> {
    val lines = lineRanges(buffer.asCharSequence())
    return if (where.after) {
        val at = lines[where.line].end
        buffer.replace(at, at, "\n$content")
        val n = content.length + 1
        (at + 1) to { o: Int -> if (o <= at) o else o + n }
    } else {
        val at = if (where.line < 0) 0 else lines[where.line].start
        buffer.replace(at, at, "$content\n")
        val n = content.length + 1
        at to { o: Int -> if (o < at) o else o + n }
    }
}

/**
 * Removes line [k] and one line break: the one before it, or for the first line the one after.
 * Returns how an offset of the text before maps into the text after.
 */
private fun removeLine(buffer: TextFieldBuffer, k: Int): (Int) -> Int {
    val lines = lineRanges(buffer.asCharSequence())
    val start = if (k > 0) lines[k - 1].end else 0
    val end = if (k > 0) lines[k].end else lines[1].start
    buffer.replace(start, end, "")
    return { o -> if (o <= start) o else if (o >= end) o - (end - start) else start }
}

/**
 * Makes [block]'s spans the only inline styles on its text at [start, end). A style that
 * reaches in from outside is cut back to its outside part, so the rewritten line shows exactly
 * what [EditorModel.blocks] will read back.
 */
private fun setSpans(buffer: TextFieldBuffer, start: Int, end: Int, block: Block) = with(buffer) {
    for (r in getSpanStyles(TextRange(0, length))) {
        val range = r.textRange
        if (range.max <= start || range.min >= end) continue
        val style = r.spanStyle
        removeStyle(r)
        if (range.min < start) addStyle(style, range.min, start)
        if (range.max > end) addStyle(style, end, range.max)
    }
    val out = mutableListOf<PlacedStyle>()
    placeStyles(block.spans, start, out)
    out.forEach { addStyle(it.style, it.start, it.end) }
}

/** The text of [block]'s line: its marker, then its text (a picture line is its marker alone). */
internal fun lineContent(block: Block) = (Markers.of(block.type, block.done)?.toString() ?: "") +
    if (Markers.isPictureType(block.type)) "" else block.text

/** Whether two blocks read the same in the editor; ids, positions and clocks aside. */
internal fun sameContent(x: Block, y: Block) =
    x.type == y.type && x.text == y.text && x.spans == y.spans && x.done == y.done && x.ref == y.ref &&
        x.size == y.size
