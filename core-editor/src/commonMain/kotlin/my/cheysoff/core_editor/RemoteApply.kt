package my.cheysoff.core_editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import my.cheysoff.core_blocks.Block
import my.cheysoff.core_blocks.BlockDocument
import my.cheysoff.core_blocks.FractionalIndex
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
 * incoming document, the editor, and [base], the last document applied. An incoming block that
 * equals its [base] version was not changed remotely, so a local edit to it that has not been
 * saved yet is kept, not reverted to the stored text. Likewise a line the user just created (not
 * in [base]) is not deleted for being absent from the incoming document, and a block the user
 * just deleted (in [base]) is not put back. Otherwise the incoming side wins, as the merge would.
 *
 * **Where.** An added block goes right after the nearest block that precedes it in the incoming
 * order and is in the editor, else at the top. Text lines are never reordered: no local edit
 * moves one either, and moving a line of text under the user's eyes is worse than leaving an
 * order the other device chose for a later save to settle. Pictures and drawings the remote moved
 * (their `pos` differs from [base]) are moved, one per edit, so each keeps its id and record.
 *
 * **The block being typed in waits.** While the user typed within the last [quietMs], a change
 * to a line holding the selection, the caret or the IME composition (an edit, or a delete) is
 * deferred, so it cannot move the caret or eat keystrokes; every other block is applied at once.
 * The deferred document stays pending, a later [offer] replaces it (the latest document wins), and
 * the first [tick] after the pause applies it. Call [tick] again when [hasPending] and the pause
 * may have passed.
 *
 * **IME composition.** In Compose 1.12 every programmatic edit that changes text ends an active
 * composition, wherever the edit is: `TextFieldState.edit` rebuilds its buffer without the
 * composing region (the text stays; it is committed) and, on Android, restarts the IME's input
 * connection. There is no public way to edit around a composition. So while the IME is composing
 * and the user typed within [quietMs], nothing is applied at all; the pending document waits for
 * the pause like a change to the caret's block. Without a composition (hardware keyboards,
 * desktop typing outside an IME), other blocks apply at once as above.
 *
 * **Undo.** Remote edits never reach [BlockHistory.recordLocal]: they go through
 * [EditorModel.edit], which records nothing and clears the field's own undo after each edit. The
 * undo stacks therefore hold only local changes, and undoing one leaves remote changes alone.
 *
 * **Base.** [base] is the last incoming document whose changes are fully applied (or the one
 * opened by [noteLoaded]). While a change is deferred it stays at the previous one. Saves must be
 * stamped against it: Tasks 14 and 15 pass `BlockCodec.encode(remoteApply.base)` as `blocksBase`
 * on every `saveNote(note, blocksBase)` of a BLOCKS note. Stamping against the stored body instead
 * would treat every block this editor has not shown yet as deleted by the user; stamping against
 * [base] claims a new clock only for what the user changed since the editor last caught up. A
 * block the editor applied from a newer, partly deferred document reads as a local change against
 * the older [base] and gets this save's clock, with the same content, which is harmless.
 *
 * Main thread only, like [EditorModel].
 */
class RemoteApply(
    private val model: EditorModel,
    private val now: () -> Long,
    private val quietMs: Long = QUIET_MS,
) {

    /** See the class doc. Snapshot state, so a save path reading it in composition stays current. */
    var base: BlockDocument by mutableStateOf(provisionalBase())
        private set

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
        base = doc
        pending = null
        hasPending = false
    }

    /** Stores [doc], the latest from storage, to be applied by the next [tick]. Replaces any earlier pending one. */
    fun offer(doc: BlockDocument) {
        pending = doc
        hasPending = true
    }

    /**
     * Applies the pending document as far as the user's typing allows; see the class doc.
     * [lastLocalEditAt] is the [now] time of the last local edit (any very old value if none).
     * Makes no edit when the document reads the same as the editor.
     */
    fun tick(lastLocalEditAt: Long) {
        val doc = pending ?: return
        val typing = lastLocalEditAt > now() - quietMs
        if (typing && model.state.composition != null) return

        val incoming = model.project(doc).map { it.block }
        val incomingById = incoming.associateBy { it.id }
        val baseById = model.project(base).associateBy { it.block.id }
        val rawBase = base.blocks.associateBy { it.id }
        val rawIncoming = doc.blocks.associateBy { it.id }
        val held = if (typing) heldIds() else emptySet()
        var deferred = false

        /** Whether the other side changed [id] since [base]: content, presence or (for a picture) position. */
        fun remoteChanged(id: String): Boolean {
            val was = baseById[id]?.block ?: return true
            val now = incomingById[id] ?: return true
            return !sameContent(was, now)
        }

        // 1. Rewrite changed lines in place.
        val local = model.blocks().associateBy { it.id }
        for (b in incoming) {
            val mine = local[b.id] ?: continue
            if (sameContent(mine, b) || !remoteChanged(b.id)) continue
            // A text line turned picture or back under the same id cannot happen (picture ids are
            // "pic:"/"drw:" + record), and has no edit whose id outcome is certain. Left as is.
            if (Markers.isPictureType(mine.type) || Markers.isPictureType(b.type)) continue
            if (b.id in held) deferred = true else edits.rewrite(b.id, b)
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
            if (b.id in held) {
                deferred = true
                return@forEachIndexed
            }
            edits.move(b.id) { others -> anchorAfterPredecessor(incoming, i, others) }
        }

        // 4. Delete blocks the other side deleted. Last, so the document never runs out of lines.
        for (id in model.ids.toList()) {
            if (id in incomingById || id !in baseById) continue // still there, or new here
            if (id in held) deferred = true else edits.delete(id)
        }

        if (!deferred) {
            base = doc
            pending = null
            hasPending = false
        }
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

    /**
     * Until [noteLoaded], the base is what the editor shows, in order, with no clocks. Then a save
     * stamps exactly what changed after the editor was set up, and its unchanged blocks lose to
     * the stored ones' real clocks in the merge. Callers should not rely on it: open a note with
     * [noteLoaded].
     */
    private fun provisionalBase(): BlockDocument {
        var previous: String? = null
        return BlockDocument(
            model.blocks().map { b ->
                val pos = FractionalIndex.between(previous, null)
                previous = pos
                b.copy(pos = pos)
            },
        )
    }

    companion object {
        /** How long after the last local edit a change to the block being typed in waits. */
        const val QUIET_MS = 1_500L
    }
}
