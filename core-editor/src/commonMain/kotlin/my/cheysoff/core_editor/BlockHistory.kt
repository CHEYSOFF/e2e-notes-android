package my.cheysoff.core_editor

import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import my.cheysoff.core_blocks.Block

/**
 * Undo/redo for one [EditorModel], per block.
 *
 * Why not the text field's own undo: a note is edited on two devices, and remote changes are
 * applied into the open editor as ordinary text edits. A text-level undo would step back through
 * those too, or (undoing a local edit made before them) restore a range of text that now overlaps
 * them, reverting the other device's work. Here an entry holds only the blocks a local edit
 * touched, by id: their content and their neighbours before and after. Undo puts exactly those
 * blocks back the way they were. Every other block, whatever a remote change did to it since,
 * stays as it is.
 *
 * What is restored, per touched block:
 * - Present now, present before: its content (type, text, spans, done) is set back. A remote
 *   change to that same block since the edit is overwritten by the local "before"; accepted by
 *   the plan, and the alternative (refusing to undo) is worse.
 * - Present now, absent before (the edit created it): it is removed.
 * - Absent now, present before (the edit deleted it, or a remote change deleted it since): it is
 *   re-inserted under its old id, next to its old previous block, else in front of its old next
 *   block, else at the end. Picture and drawing blocks get their record back with it; the record
 *   itself is undeleted on save by `PictureLifecycle`.
 * - A picture the edit moved goes back beside its old neighbour. Text lines are never moved by
 *   an edit (a moved line of text is a new block), so only their content is restored.
 * Redo is the same with "after" for "before".
 *
 * Recording: [recordLocal] is called after every LOCAL change only, by [EditingRules] (typing,
 * IME, paste, cut) and [EditorCommands] (toolbar). Remote apply never calls it, and neither does
 * [undo]/[redo]: they edit through [EditorModel.edit], which records nothing, and move the entry
 * between the two stacks instead. A new local change clears the redo stack.
 *
 * Coalescing: a run of typing in one block is one entry, as in Word. A text-only change to the
 * same single block extends the open entry unless 1 s ([IDLE_MS], by [now]) has passed since the
 * entry's last change. A change that inserts whitespace still joins the entry but closes it, so
 * "one two" undoes as "two", then "one ". Any other change (Enter, a second block, a type,
 * style or tick change, a picture) is an entry of its own, closed.
 *
 * The built-in undo must stay out of the way: its Ctrl+Z replays text into the field without
 * going through the InputTransformation or [EditorModel.onEdited], which leaves the model's ids
 * out of step with the lines, and [EditorModel.blocks] then throws. Compose 1.12 has no switch to
 * turn it off (`TextFieldState` takes no undo setting, and its undo manager is internal), and in
 * 1.12 the field's key handler is the only caller of it on either platform. So
 * [blockHistoryKeys] takes the undo/redo keys before the field sees them, and the platform
 * history is also cleared after every edit (in [EditorModel.edit] and at the start of each user
 * edit in [EditingRules]) so there is never more than the latest user edit in it to replay.
 *
 * Main thread only, like [EditorModel]. Attaching is one-to-one: constructing a history attaches
 * it to [model], where the rules and commands find it, and [EditorModel.load] clears it.
 */
class BlockHistory(private val model: EditorModel, private val now: () -> Long) {

    /** A block as one side of an edit saw it, with its neighbours there, for re-inserting it. */
    private class Snap(val block: Block, val index: Int, val prev: String?, val next: String?)

    private class Change(val id: String, val before: Snap?, var after: Snap?, val moved: Boolean)

    private class Entry(val changes: List<Change>, var at: Long, var open: Boolean)

    private val undoStack = ArrayList<Entry>()
    private val redoStack = ArrayList<Entry>()

    /** Whether [undo] would do anything. Snapshot state, so a toolbar button can bind to it. */
    var canUndo by mutableStateOf(false)
        private set

    /** Whether [redo] would do anything. Snapshot state, like [canUndo]. */
    var canRedo by mutableStateOf(false)
        private set

    init {
        check(model.history == null) { "this EditorModel already has a BlockHistory" }
        model.history = this
    }

    /**
     * Records a local change from [before] to [after] (both the whole document, as
     * [EditorModel.blocks] reads it). Only blocks that differ become part of the entry, so a
     * change that differs nowhere records nothing. Clears the redo stack.
     */
    fun recordLocal(before: List<Block>, after: List<Block>) {
        val changes = diff(before, after)
        if (changes.isEmpty()) return
        redoStack.clear()
        val t = now()
        val single = changes.singleOrNull()?.takeIf { isTyping(it) }
        val top = undoStack.lastOrNull()
        val topChange = top?.changes?.singleOrNull()
        if (single != null && top != null && top.open && t - top.at < IDLE_MS && topChange?.id == single.id) {
            topChange.after = single.after
            top.at = t
            top.open = !insertsWhitespace(single)
        } else {
            undoStack += Entry(changes, t, open = single != null && !insertsWhitespace(single))
            if (undoStack.size > MAX_ENTRIES) undoStack.removeAt(0)
        }
        refresh()
    }

    /** Steps back one entry. Returns false if there was nothing to undo. */
    fun undo(): Boolean {
        val entry = undoStack.removeLastOrNull() ?: return false
        entry.open = false
        apply(entry.changes.map { Target(it.id, it.before, it.moved) })
        redoStack += entry
        refresh()
        return true
    }

    /** Re-applies the last undone entry. Returns false if there was nothing to redo. */
    fun redo(): Boolean {
        val entry = redoStack.removeLastOrNull() ?: return false
        apply(entry.changes.map { Target(it.id, it.after, it.moved) })
        undoStack += entry
        refresh()
        return true
    }

    /** Forgets everything; for a freshly loaded document, whose blocks the entries no longer describe. */
    fun clear() {
        undoStack.clear()
        redoStack.clear()
        refresh()
    }

    private fun refresh() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    // --- Recording --------------------------------------------------------------------------------

    private fun diff(before: List<Block>, after: List<Block>): List<Change> {
        val b = snaps(before)
        val a = snaps(after)
        val moved = moved(before, after, a)
        val out = mutableListOf<Change>()
        for (blk in before) {
            val x = b.getValue(blk.id)
            val y = a[blk.id]
            if (y == null || !sameContent(x.block, y.block) || blk.id in moved) out += Change(blk.id, x, y, blk.id in moved)
        }
        for (blk in after) if (blk.id !in b) out += Change(blk.id, null, a.getValue(blk.id), false)
        return out
    }

    private fun snaps(list: List<Block>): Map<String, Snap> {
        val out = HashMap<String, Snap>(list.size * 2)
        list.forEachIndexed { i, blk -> out[blk.id] = Snap(blk, i, list.getOrNull(i - 1)?.id, list.getOrNull(i + 1)?.id) }
        return out
    }

    /**
     * Ids present on both sides whose order relative to the other common ids changed: those not
     * on a longest increasing run of after-positions. Blocks inserted or deleted around a block
     * do not make it "moved"; only reordering does.
     */
    private fun moved(before: List<Block>, after: List<Block>, a: Map<String, Snap>): Set<String> {
        val common = before.filter { it.id in a }
        val pos = common.map { a.getValue(it.id).index }
        if (pos.zipWithNext().all { (x, y) -> x < y }) return emptySet()
        // Patience LIS over pos.
        val tails = IntArray(pos.size)
        val prevOf = IntArray(pos.size)
        var len = 0
        for (i in pos.indices) {
            var lo = 0
            var hi = len
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (pos[tails[mid]] < pos[i]) lo = mid + 1 else hi = mid
            }
            prevOf[i] = if (lo > 0) tails[lo - 1] else -1
            tails[lo] = i
            if (lo == len) len++
        }
        val keep = HashSet<String>()
        var k = if (len > 0) tails[len - 1] else -1
        while (k >= 0) {
            keep += common[k].id
            k = prevOf[k]
        }
        return common.map { it.id }.filterTo(HashSet()) { it !in keep }
    }

    /** A change that only edits the text (and its styles) of one existing block, as typing does. */
    private fun isTyping(c: Change): Boolean {
        val x = c.before?.block ?: return false
        val y = c.after?.block ?: return false
        return !c.moved && !Markers.isPictureType(x.type) && x.type == y.type && x.done == y.done && x.text != y.text
    }

    private fun insertsWhitespace(c: Change): Boolean {
        val x = c.before?.block?.text ?: return false
        val y = c.after?.block?.text ?: return false
        var p = 0
        while (p < x.length && p < y.length && x[p] == y[p]) p++
        var s = 0
        while (s < x.length - p && s < y.length - p && x[x.length - 1 - s] == y[y.length - 1 - s]) s++
        return (p until y.length - s).any { y[it].isWhitespace() }
    }

    // --- Applying ---------------------------------------------------------------------------------

    /** Block [id] should end up as [snap] (or gone, if null). */
    private class Target(val id: String, val snap: Snap?, val moved: Boolean)

    /**
     * Brings every target block to its state. One small [EditorModel.edit] per block, each of a
     * shape whose id outcome [EditorModel.onEdited] guarantees (rewrite one line, insert one
     * staged line, remove one line), rather than one big edit whose id derivation would depend on
     * how the changes happen to group. All of it runs synchronously, so it shows as one change.
     * Restores and inserts come first and removals last, so the document never runs out of lines.
     */
    private fun apply(targets: List<Target>) {
        var caretAfter: String? = null
        for (t in targets) {
            val snap = t.snap ?: continue
            if (t.id in model.ids && !Markers.isPictureType(snap.block.type)) {
                if (rewrite(t.id, snap.block)) caretAfter = t.id
            }
        }
        for (t in targets.filter { it.snap != null }.sortedBy { it.snap!!.index }) {
            val snap = t.snap!!
            if (t.id !in model.ids) {
                insert(snap)
                caretAfter = t.id
            } else if (t.moved && Markers.isPictureType(snap.block.type)) {
                move(snap)
                caretAfter = t.id
            }
        }
        for (t in targets) if (t.snap == null && t.id in model.ids) delete(t.id)
        val at = caretAfter?.let { id -> model.ids.indexOf(id).takeIf { it >= 0 } }
        if (at != null) model.edit { selection = TextRange(lineRanges(asCharSequence())[at].end) }
    }

    /** Sets line [id]'s marker, text and spans to [block]'s. Returns whether anything changed. */
    private fun rewrite(id: String, block: Block): Boolean {
        val current = model.blocks().first { it.id == id }
        if (sameContent(current, block)) return false
        val k = model.ids.indexOf(id)
        val content = lineContent(block)
        model.edit {
            val line = lineRanges(asCharSequence())[k]
            if (asCharSequence().subSequence(line.start, line.end).toString() != content) {
                replace(line.start, line.end, content)
            }
            setSpans(this, line.start + content.length - block.text.length, line.start + content.length, block)
        }
        return true
    }

    /** Re-inserts [snap]'s block under its old id, beside its old neighbour. */
    private fun insert(snap: Snap) {
        val block = snap.block
        val picture = Markers.isPictureType(block.type)
        if (picture) {
            model.stagePicture(block.id, block.type, block.ref ?: block.id.substringAfter(':'))
        } else {
            model.stageText(block.id)
        }
        val content = lineContent(block)
        model.edit {
            val start = insertLine(this, anchor(snap, model.ids), content)
            if (!picture) setSpans(this, start + content.length - block.text.length, start + content.length, block)
        }
    }

    /**
     * Moves picture [snap] back beside its old neighbour. Removed and re-inserted in one unstaged
     * edit, which [EditorModel.onEdited] reads as a move and so keeps the id and record.
     */
    private fun move(snap: Snap) {
        val ids = model.ids
        val k = ids.indexOf(snap.block.id)
        // Anchors are in the coordinates of the other lines, which are also the lines once this
        // one is removed. Where it stands now is between others[k - 1] and others[k].
        val others = ids.filterIndexed { i, _ -> i != k }
        val where = anchor(snap, others)
        val alreadyThere = where == Anchor(k - 1, after = true) ||
            where == Anchor(k, after = false) ||
            (k == 0 && where == Anchor(-1, after = false))
        if (alreadyThere) return
        model.edit {
            removeLine(this, k)
            insertLine(this, where, lineContent(snap.block))
        }
    }

    /**
     * Removes line [id]. Deleting the first line takes the break after it, and the next line's id
     * follows its first surviving character. A next line with no character but its marker (an
     * empty item or paragraph) has none, and its id would be lost to the deleted line's. So such a
     * line is first given a placeholder character, which carries its id across, and the
     * placeholder is taken out again after.
     */
    private fun delete(id: String) {
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
                model.edit { replace(next.end, next.end, PLACEHOLDER) }
                model.edit { removeLine(this, 0) }
                model.edit {
                    val line = lineRanges(asCharSequence())[0]
                    replace(line.end - PLACEHOLDER.length, line.end, "")
                }
                return
            }
        }
        model.edit { removeLine(this, k) }
    }

    /** Where a line goes: after line [line] if [after], else before it. `(-1, false)` is the top. */
    private data class Anchor(val line: Int, val after: Boolean)

    private fun anchor(snap: Snap, ids: List<String>): Anchor {
        snap.prev?.let { p -> ids.indexOf(p).takeIf { it >= 0 }?.let { return Anchor(it, after = true) } }
        snap.next?.let { n -> ids.indexOf(n).takeIf { it >= 0 }?.let { return Anchor(it, after = false) } }
        return if (snap.index == 0 || ids.isEmpty()) Anchor(-1, after = false) else Anchor(ids.lastIndex, after = true)
    }

    /** Inserts a line holding [content] at [where]. Returns the offset the new line starts at. */
    private fun insertLine(buffer: TextFieldBuffer, where: Anchor, content: String): Int {
        val lines = lineRanges(buffer.asCharSequence())
        return if (where.after) {
            val at = lines[where.line].end
            buffer.replace(at, at, "\n$content")
            at + 1
        } else {
            val at = if (where.line < 0) 0 else lines[where.line].start
            buffer.replace(at, at, "$content\n")
            at
        }
    }

    /** Removes line [k] and one line break: the one before it, or for the first line the one after. */
    private fun removeLine(buffer: TextFieldBuffer, k: Int) {
        val lines = lineRanges(buffer.asCharSequence())
        if (k > 0) buffer.replace(lines[k - 1].end, lines[k].end, "") else buffer.replace(0, lines[1].start, "")
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

    private fun lineContent(block: Block) = (Markers.of(block.type, block.done)?.toString() ?: "") +
        if (Markers.isPictureType(block.type)) "" else block.text

    private fun sameContent(x: Block, y: Block) =
        x.type == y.type && x.text == y.text && x.spans == y.spans && x.done == y.done && x.ref == y.ref

    companion object {
        /** Typing pauses longer than this start a new undo entry. */
        const val IDLE_MS = 1_000L

        /** Oldest entries are dropped beyond this, to bound memory in a long session. */
        const val MAX_ENTRIES = 200

        /** Any ordinary char will do; it never stays in the text past one [delete]. */
        private const val PLACEHOLDER = "x"
    }
}

/**
 * Sends Ctrl+Z to [history]'s undo and Ctrl+Shift+Z / Ctrl+Y to its redo (Cmd instead of Ctrl on
 * macOS), and consumes them, before the text field's own key handling sees them. Put it on the
 * editor's `BasicTextField`. Without it the field's built-in undo would replay text behind the
 * [EditorModel]'s back; see [BlockHistory]. Key-ups of these chords are consumed too, so nothing
 * downstream sees half a shortcut.
 */
fun Modifier.blockHistoryKeys(history: BlockHistory): Modifier = onPreviewKeyEvent { e ->
    if (!(e.isCtrlPressed || e.isMetaPressed)) return@onPreviewKeyEvent false
    val redo = (e.key == Key.Z && e.isShiftPressed) || e.key == Key.Y
    val undo = e.key == Key.Z && !e.isShiftPressed
    if (!redo && !undo) return@onPreviewKeyEvent false
    if (e.type == KeyEventType.KeyDown) {
        if (redo) history.redo() else history.undo()
    }
    true
}
