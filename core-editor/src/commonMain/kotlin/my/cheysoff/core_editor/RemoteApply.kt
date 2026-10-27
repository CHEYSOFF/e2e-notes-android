package my.cheysoff.core_editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import my.cheysoff.core_blocks.Block
import my.cheysoff.core_blocks.BlockDocument
import my.cheysoff.core_editor.LineEdits.Anchor

/**
 * Brings changes synced from another device into the open editor while the user may be typing in
 * it. A tick made on the phone shows on the laptop within seconds, and the cursor, a half-typed
 * word and the undo history stay as they were.
 *
 * Why not reload the document: a reload replaces the whole text, which loses the cursor, ends the
 * IME's composition and empties the undo history. And every save of our own comes back from
 * storage too, so the note would reload under the user's fingers each time they paused.
 *
 * **How.** Each storage emission is [offer]ed; [tick] diffs it, block by block, against what the
 * editor shows ([EditorModel.blocks]) and makes only the needed edits through [LineEdits]: rewrite
 * a changed line in place, insert an added block under its own id, delete a removed one, move a
 * picture or drawing the other device moved. Incoming blocks are first put through the loader's
 * normalisation ([EditorModel.project]), so they land exactly as they would on opening the note
 * and a block that reads the same counts as unchanged. Echoes of our own saves therefore make no
 * edit at all.
 *
 * **Which changes.** Only what the other side changed. A block is compared three ways: the
 * incoming document, the editor, and [base], what the editor last caught up with. An incoming
 * block that equals its [base] version was not changed remotely, so a local edit to it that has
 * not been saved yet is kept, not reverted to the stored text. Likewise a line the user just
 * created (not in [base]) is not deleted for being absent from the incoming document, and a block
 * the user just deleted (in [base]) is not put back.
 *
 * **A block changed on both sides is held, however long that takes.** If the editor's version
 * and the incoming one both differ from [base], the editor holds text the merge has not seen yet:
 * the debounced save has not run, has failed or was skipped, or its echo has not come back (an
 * older emission can arrive after the caret has moved on). Applying the incoming version, or its delete, would wipe that
 * text, so the block keeps showing the user's version, typing or not, and [base] keeps its old
 * version (which makes the next save claim it). The hold ends only when:
 * - an incoming document has the block exactly as the editor shows it (the echo of our save, or
 *   the two sides converging), or
 * - [base] catches up with the editor's version, or
 * - the user undoes or retypes the block back to its [base] version; then the incoming change is
 *   an ordinary remote edit again.
 * Because [base] keeps the old version, every save re-claims the block, so while the note is open
 * local wins for a block typed on both devices at once: the other device's simultaneous edit to
 * that block is overwritten (accepted: no local words are lost, and the devices still converge on
 * one version). The [quietMs] pause below governs only the caret's
 * block when the editor still shows its [base] version.
 *
 * **Where.** An added block goes right after the nearest block that precedes it in the incoming
 * order and is in the editor, else at the top. Text lines are never reordered: no local edit
 * moves one either, and moving a line of text under the user's eyes is worse than leaving an
 * order the other device chose. Pictures and drawings the remote moved (their `pos` differs from
 * [base]) are moved, one per edit, so each keeps its id and record.
 *
 * **The block being typed in waits.** While the user typed within the last [quietMs], a remote
 * change to a line holding the selection, the caret or the IME composition (an edit, or a delete) is
 * deferred, so it cannot move the caret or eat keystrokes; every other block is applied at once.
 * The deferred document stays pending, a later [offer] replaces it (the latest document wins), and
 * the first [tick] after the pause applies it. Call [tick] again when [hasPending] and the pause
 * may have passed.
 *
 * **IME composition.** In Compose 1.12 every programmatic edit that changes text ends an active
 * composition, wherever the edit is: `TextFieldState.edit` rebuilds its buffer without the
 * composing region (the text stays; it is committed) and, on Android, restarts the IME's input
 * connection. There is no public way to edit around a composition. So while the IME is composing
 * and the user typed within [quietMs], nothing is applied at all. Known limitation: with an IME
 * that keeps a composition up while the user types on (Gboard composes each word), changes wait
 * for a [quietMs] pause in typing, not for the gap between words; there is no cap on the wait.
 * Without a composition (hardware keyboards, desktop typing outside an IME), other blocks apply
 * at once as above.
 *
 * **Undo.** Remote edits never reach [BlockHistory.recordLocal]: they go through
 * [EditorModel.edit], which records nothing and clears the field's own undo after each edit. The
 * undo stacks therefore hold only local changes, and undoing one leaves remote changes alone.
 *
 * **Base.** [base] is what the editor has caught up with, block by block, and saves are stamped
 * against it: Tasks 14 and 15 pass `BlockCodec.encode(remoteApply.base)` as `blocksBase` on every
 * `saveNote(note, blocksBase)` of a BLOCKS note. Stamping against the stored body instead would
 * treat every block this editor has not shown yet as deleted by the user; stamping against [base]
 * claims a new clock only for what the user changed. After every [tick] it is a composite:
 * - an applied or already-equal block is the incoming block, clocks and `pos` included, so a save
 *   that leaves it alone does not claim it with a newer clock;
 * - a deferred block (edit, move or delete) is the previous base's version, since the editor
 *   still shows that one; a deferred block the previous base lacked is left out;
 * - a text block whose `pos` the other side changed keeps the previous base's `pos` and position
 *   clock: text reorders from the other side are not shown, and a save stamped against the reordered positions would read the
 *   editor's unchanged order as a fresh move and undo the other device's reorder everywhere;
 * - tombstones are the incoming ones, minus those of deferred deletes.
 * With nothing deferred and no text reorder it is the incoming document itself. Reading it before
 * [noteLoaded] throws: a save stamped against a guessed base could claim or delete blocks it never
 * saw.
 *
 * Main thread only, like [EditorModel].
 */
class RemoteApply(
    private val model: EditorModel,
    private val now: () -> Long,
    private val quietMs: Long = QUIET_MS,
) {

    private var loadedBase: BlockDocument? by mutableStateOf(null)

    /**
     * See the class doc. Snapshot state, so a save path reading it in composition stays current.
     *
     * @throws IllegalStateException before [noteLoaded].
     */
    val base: BlockDocument
        get() = checkNotNull(loadedBase) { "RemoteApply.base read before noteLoaded" }

    /** Whether an offered document is still waiting to be (fully) applied. Snapshot state. */
    var hasPending: Boolean by mutableStateOf(false)
        private set

    private var pending: BlockDocument? = null

    private val edits = LineEdits(model)

    /** Test hook: runs before each edit, named as in [LineEdits.beforeStep]. */
    internal var beforeStep: ((String) -> Unit)?
        get() = edits.beforeStep
        set(value) {
            edits.beforeStep = value
        }

    /**
     * Opens [doc] in the editor ([EditorModel.load]: cursor at the start, undo history cleared)
     * and makes it the [base]. One call for both, so the editor and its base cannot disagree.
     * Drops any pending document, which belonged to what was open before.
     */
    fun noteLoaded(doc: BlockDocument) {
        model.load(doc)
        loadedBase = doc
        pending = null
        hasPending = false
    }

    /** Stores [doc], the latest from storage, to be applied by the next [tick]. Replaces any earlier pending one. */
    fun offer(doc: BlockDocument) {
        pending = doc
        hasPending = true
    }

    /**
     * Applies the pending document as far as the user's typing allows, then updates [base]; see
     * the class doc. [lastLocalEditAt] is the [now] time of the last local edit (any very old
     * value if none). Makes no edit when the document reads the same as the editor.
     *
     * @throws IllegalStateException before [noteLoaded].
     */
    fun tick(lastLocalEditAt: Long) {
        val doc = pending ?: return
        val previous = base
        val typing = lastLocalEditAt > now() - quietMs
        if (typing && model.state.composition != null) return

        val incomingLines = model.project(doc)
        val baseLines = model.project(previous)
        val incoming = incomingLines.map { it.block }
        val incomingById = incoming.associateBy { it.id }
        val baseById = baseLines.associate { it.block.id to it.block }
        val rawBase = previous.blocks.associateBy { it.id }
        val rawIncoming = doc.blocks.associateBy { it.id }
        val local = model.blocks().associateBy { it.id }
        val caretLines = if (typing) heldIds() else emptySet()
        val deferred = HashSet<String>() // line ids

        /** Whether the other side changed line [id] since [base]: content or presence. */
        fun remoteChanged(id: String): Boolean {
            val was = baseById[id] ?: return id in incomingById
            val now = incomingById[id] ?: return true
            return !sameContent(was, now)
        }

        /** Whether the editor's line [id] differs from [base]: unsaved, or saved but not echoed back. */
        fun localChanged(id: String): Boolean {
            val was = baseById[id] ?: return id in local
            val mine = local[id] ?: return true
            return !sameContent(was, mine)
        }

        // Not time-based: see "A block changed on both sides is held" in the class doc.
        fun held(id: String) = id in caretLines || (localChanged(id) && remoteChanged(id))

        // 1. Rewrite changed lines in place.
        for (b in incoming) {
            val mine = local[b.id] ?: continue
            if (sameContent(mine, b) || !remoteChanged(b.id)) continue
            // A text line turned picture or back under the same id cannot happen (picture ids are
            // "pic:"/"drw:" + record), and has no edit whose id outcome is certain. Left as is.
            if (Markers.isPictureType(mine.type) || Markers.isPictureType(b.type)) continue
            if (held(b.id)) deferred += b.id else edits.rewrite(b.id, b)
        }

        // 2. Insert blocks the other side added, in incoming order.
        incoming.forEachIndexed { i, b ->
            if (b.id in model.ids || b.id in baseById) return@forEachIndexed // present, or deleted here
            edits.insert(b) { ids -> anchorAfterPredecessor(incoming, i, ids) }
        }

        // 3. Move pictures and drawings the other side moved.
        incoming.forEachIndexed { i, b ->
            if (!Markers.isPictureType(b.type) || b.id !in model.ids) return@forEachIndexed
            val was = rawBase[b.id] ?: return@forEachIndexed
            val moved = rawIncoming[b.id]?.let { it.pos != was.pos } ?: false
            if (!moved) return@forEachIndexed
            if (b.id in caretLines) {
                deferred += b.id
                return@forEachIndexed
            }
            edits.move(b.id) { others -> anchorAfterPredecessor(incoming, i, others) }
        }

        // 4. Delete blocks the other side deleted. Last, so the document never runs out of lines.
        for (id in model.ids.toList()) {
            if (id in incomingById || id !in baseById) continue // still there, or new here
            if (held(id)) deferred += id else edits.delete(id)
        }

        val origin = HashMap<String, String?>()
        baseLines.forEach { origin[it.block.id] = it.originId }
        incomingLines.forEach { origin[it.block.id] = it.originId }
        val composite = composite(doc, previous, deferred.mapNotNullTo(HashSet()) { origin[it] })
        loadedBase = if (composite == doc) doc else composite
        if (deferred.isEmpty()) {
            pending = null
            hasPending = false
        }
    }

    /** The new [base] after a tick of [doc] over [previous] that deferred blocks [deferred]; see the class doc. */
    private fun composite(doc: BlockDocument, previous: BlockDocument, deferred: Set<String>): BlockDocument {
        val before = previous.blocks.associateBy { it.id }
        val out = ArrayList<Block>(doc.blocks.size)
        for (b in doc.blocks) {
            val old = before[b.id]
            if (b.id in deferred) {
                old?.let { out += it }
                continue
            }
            // A text block the other side moved: the editor did not move it, so neither does the base.
            val unshownMove = old != null && old.pos != b.pos &&
                !Markers.isPictureType(b.type) && !Markers.isPictureType(old.type)
            out += if (unshownMove) b.copy(pos = old!!.pos, posClock = old.posClock) else b
        }
        val present = doc.blocks.mapTo(HashSet()) { it.id }
        for (id in deferred) if (id !in present) before[id]?.let { out += it } // a deferred delete
        return BlockDocument(out, doc.gone.filter { it.id !in deferred })
    }

    /**
     * Where incoming block [i] goes: after the nearest block before it in [incoming] that is among
     * [ids], else in front of the first line (the top). A block [ids] holds that [incoming] lacks (a
     * deferred delete, a new local line) is passed over, so it does not decide the place.
     */
    private fun anchorAfterPredecessor(incoming: List<Block>, i: Int, ids: List<String>): Anchor {
        for (j in i - 1 downTo 0) {
            val at = ids.indexOf(incoming[j].id)
            if (at >= 0) return Anchor(at, after = true)
        }
        return Anchor(-1, after = false)
    }

    /** Ids of the lines the selection or the IME composition touches. */
    private fun heldIds(): Set<String> {
        val text = model.state.text
        val lines = lineRanges(text)
        val out = HashSet<String>()
        fun hold(start: Int, end: Int) {
            for (k in lineOf(lines, start)..lineOf(lines, end)) out += model.ids[k]
        }
        val sel = model.state.selection
        hold(sel.min, sel.max)
        model.state.composition?.let { hold(it.min, it.max) }
        return out
    }

    companion object {
        /** How long after the last local edit a change to the block being typed in waits. */
        const val QUIET_MS = 1_500L
    }
}
