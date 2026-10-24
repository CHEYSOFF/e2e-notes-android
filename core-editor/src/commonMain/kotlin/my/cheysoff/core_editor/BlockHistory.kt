package my.cheysoff.core_editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import my.cheysoff.core_blocks.Block
import my.cheysoff.core_editor.LineEdits.Anchor

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

    /**
     * Steps back one entry. Returns false if there was nothing to undo.
     *
     * The entry moves to the redo stack only once it has been applied. If applying throws (a bug:
     * every step is checked by [EditorModel.onEdited]), the stacks are left as they were, so the
     * entry is not lost or duplicated. Steps already made stay made; each left the model
     * consistent, since a failing [EditorModel.edit] reverts itself.
     */
    fun undo(): Boolean {
        val entry = undoStack.lastOrNull() ?: return false
        apply(entry.changes.map { Target(it.id, it.before, it.moved) })
        undoStack.removeAt(undoStack.lastIndex)
        entry.open = false
        redoStack += entry
        refresh()
        return true
    }

    /** Re-applies the last undone entry. Returns false if there was nothing to redo. Failure as in [undo]. */
    fun redo(): Boolean {
        val entry = redoStack.lastOrNull() ?: return false
        apply(entry.changes.map { Target(it.id, it.after, it.moved) })
        redoStack.removeAt(redoStack.lastIndex)
        undoStack += entry
        refresh()
        return true
    }

    /**
     * Empties the text field's own undo history. Called by [blockHistoryKeys] on every key press,
     * before the field handles it, so no key mapping of the field's (Ctrl+Z, Alt+Backspace on
     * Windows, whatever a future version adds) finds anything to replay.
     */
    @OptIn(ExperimentalFoundationApi::class) // undoState
    internal fun clearPlatformUndo() = model.state.undoState.clearHistory()

    private val edits = LineEdits(model)

    /** Test hook: runs before each step of an undo/redo, named as in [LineEdits.beforeStep]. Throwing aborts it. */
    internal var beforeStep: ((String) -> Unit)?
        get() = edits.beforeStep
        set(value) {
            edits.beforeStep = value
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
     * Brings every target block to its state, through [LineEdits] (one small edit per block, see
     * there for why). All of it runs synchronously, so it shows as one change. Restores and
     * inserts come first and removals last, so the document never runs out of lines.
     */
    private fun apply(targets: List<Target>) {
        var caretAfter: String? = null
        for (t in targets) {
            val snap = t.snap ?: continue
            if (t.id in model.ids && !Markers.isPictureType(snap.block.type)) {
                if (edits.rewrite(t.id, snap.block)) caretAfter = t.id
            }
        }
        for (t in targets.filter { it.snap != null }.sortedBy { it.snap!!.index }) {
            val snap = t.snap!!
            if (t.id !in model.ids) {
                edits.insert(snap.block) { ids -> anchor(snap, ids) }
                caretAfter = t.id
            } else if (t.moved && Markers.isPictureType(snap.block.type)) {
                edits.move(t.id) { others -> anchor(snap, others) }
                caretAfter = t.id
            }
        }
        for (t in targets) if (t.snap == null && t.id in model.ids) edits.delete(t.id)
        val at = caretAfter?.let { id -> model.ids.indexOf(id).takeIf { it >= 0 } }
        if (at != null) model.edit { selection = TextRange(lineRanges(asCharSequence())[at].end) }
    }

    /** Next to its old previous block, else in front of its old next block, else at its old end. */
    private fun anchor(snap: Snap, ids: List<String>): Anchor {
        snap.prev?.let { p -> ids.indexOf(p).takeIf { it >= 0 }?.let { return Anchor(it, after = true) } }
        snap.next?.let { n -> ids.indexOf(n).takeIf { it >= 0 }?.let { return Anchor(it, after = false) } }
        return if (snap.index == 0 || ids.isEmpty()) Anchor(-1, after = false) else Anchor(ids.lastIndex, after = true)
    }

    companion object {
        /** Typing pauses longer than this start a new undo entry. */
        const val IDLE_MS = 1_000L

        /** Oldest entries are dropped beyond this, to bound memory in a long session. */
        const val MAX_ENTRIES = 200
    }
}

/**
 * Sends the undo keys to [history] and keeps the text field's own undo from ever running. Put it
 * on the editor's `BasicTextField`. The field's built-in undo would replay text behind the
 * [EditorModel]'s back (see [BlockHistory]), and the field maps more keys to it than Ctrl+Z: on
 * Windows/Linux desktop, Alt+Backspace too. So:
 * - Every key press first empties the field's undo history, in this preview pass, before the field
 *   sees the key. Whatever the field's key mapping calls undo then finds nothing to replay.
 * - Ctrl+Z (Cmd+Z), and Alt+Backspace where the field treats it as undo ([altBackspaceIsUndo]: not
 *   on macOS, where it deletes a word), go to [BlockHistory.undo]. Ctrl+Shift+Z (Cmd+Shift+Z) and
 *   Ctrl+Y (Cmd+Y) go to [BlockHistory.redo]. These are consumed, key-ups included, so nothing
 *   downstream sees half a shortcut.
 */
fun Modifier.blockHistoryKeys(history: BlockHistory): Modifier = onPreviewKeyEvent { e ->
    if (e.type == KeyEventType.KeyDown) history.clearPlatformUndo()
    val command = e.isCtrlPressed || e.isMetaPressed
    val redo = command && ((e.key == Key.Z && e.isShiftPressed) || e.key == Key.Y)
    val undo = (command && e.key == Key.Z && !e.isShiftPressed) ||
        (altBackspaceIsUndo && e.isAltPressed && !command && e.key == Key.Backspace)
    if (!redo && !undo) return@onPreviewKeyEvent false
    if (e.type == KeyEventType.KeyDown) {
        if (redo) history.redo() else history.undo()
    }
    true
}
