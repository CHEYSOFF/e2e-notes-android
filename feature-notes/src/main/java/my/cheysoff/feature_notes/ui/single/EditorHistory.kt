package my.cheysoff.feature_notes.ui.single

import my.cheysoff.core_domain.model.NoteContentFormat
import my.cheysoff.feature_notes.model.single.ChecklistItem

/**
 * The value of one editable slice of the editor at a point in time — what an undo puts back.
 *
 * Entries are per slice rather than whole-editor snapshots so that undoing a title edit restores
 * only the title. That matters because the editor's fields do not only move under the user's hand:
 * `mergeIncomingNote` can adopt an external change (today: a folder move from the list screen, and
 * in principle any field the user has not touched) into a field while the user edits another one.
 * A whole-editor snapshot taken before that adoption would drag the adopted field back with it.
 *
 * It does NOT make undo safe against an external change to the *same* slice: undoing a local
 * checklist edit restores the items as this editor last saw them, discarding anything another
 * writer put there in the meantime. Nothing in the app writes a note's checklist behind an open
 * editor today.
 */
internal sealed interface EditorRevision {
    data class Title(val text: String) : EditorRevision

    /**
     * The body and its format marker, always together: the marker says how those exact bytes are
     * to be read, so restoring one without the other hands a plain body to the HTML reader.
     */
    data class Body(val content: String, val format: NoteContentFormat) : EditorRevision

    /**
     * The checklist as a whole, holding the very [ChecklistItem] instances that were in state — so
     * an undo puts back the original ids, not fresh ones. Ids back `remember(item.id)` focus
     * requesters in the UI and address the text/toggle/remove intents, so re-minting them on undo
     * would steal focus and strand any in-flight keystroke.
     */
    data class Checklist(val items: List<ChecklistItem>) : EditorRevision
}

/**
 * What an edit is "about", used to decide whether it continues the previous one or starts a new
 * undo step. Two consecutive edits fold into a single step only when they share a group and land
 * within [EDITOR_COALESCE_WINDOW_MS] of each other, so undo steps back by a burst of typing
 * rather than by one character — and switching field always begins a new step.
 */
internal sealed interface EditGroup {
    data object Title : EditGroup
    data object Body : EditGroup

    /** Typing into one checklist row. Keyed by item id, so moving to another row breaks the run. */
    data class ChecklistItemText(val itemId: String) : EditGroup

    /**
     * Adding, removing or toggling a checklist item. Each such edit is a discrete action rather
     * than a keystroke, so it never folds — not even into another structural edit.
     */
    data object Structural : EditGroup
}

/** Undo steps kept per editor session; the oldest is dropped once the stack is full. */
internal const val EDITOR_HISTORY_LIMIT = 50

/**
 * Idle gap that ends a run of same-field edits. Wider than the screen's 300 ms content-serialize
 * debounce, so a pause in typing that only flushes the body doesn't split it into two steps.
 */
internal const val EDITOR_COALESCE_WINDOW_MS = 600L

/**
 * One undo/redo stack for the whole editor: title, body and checklist edits are recorded here in
 * the order they reach the ViewModel, so the top-bar buttons step back through the user's edits
 * whatever field each one touched.
 *
 * The stack stores the value a slice held *before* an edit; the value it holds *after* lives in the
 * editor state itself and is supplied by the caller at undo/redo time (the `currentOf` lambda).
 *
 * Not thread-safe: every caller is the ViewModel's intent handling, which runs on the main
 * dispatcher.
 *
 * [now] defaults to wall-clock time, which can jump (NTP, user changing the clock). The only thing
 * that rides on it is the grouping window, so a jump can merge two undo steps into one or split one
 * into two — it cannot lose or corrupt an entry.
 */
internal class EditorHistory(
    private val limit: Int = EDITOR_HISTORY_LIMIT,
    private val coalesceWindowMs: Long = EDITOR_COALESCE_WINDOW_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    // Newest entry last. ArrayDeque so the oldest can be dropped from the front in O(1) once the
    // stack reaches [limit].
    private val undoStack = ArrayDeque<EditorRevision>()
    private val redoStack = ArrayDeque<EditorRevision>()

    // The group and timestamp of the last recorded edit — the whole of the coalescing state. Null
    // after an undo or redo, which makes the next edit start a fresh step.
    private var lastGroup: EditGroup? = null
    private var lastRecordedAt = 0L

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /**
     * Records that the slice described by [group] is changing, and that it held [before] until now.
     *
     * Call this once per *actual* change: a caller that records a no-op edit puts an undo step on
     * the stack that appears to do nothing when taken.
     *
     * Any recorded edit drops the redo stack — once the user edits after undoing, the branch that
     * redo would have replayed is gone.
     */
    fun record(before: EditorRevision, group: EditGroup) {
        val at = now()
        redoStack.clear()
        // Structural edits never fold, not even into each other, so they are excluded here rather
        // than relying on group equality (EditGroup.Structural does equal itself).
        val continuesLastStep = group != EditGroup.Structural &&
                group == lastGroup &&
                undoStack.isNotEmpty() &&
                at - lastRecordedAt < coalesceWindowMs
        // Moved even when folding, so a continuous burst keeps folding: the window measures the
        // gap since the previous keystroke, not since the step began.
        lastGroup = group
        lastRecordedAt = at
        // The step already on the stack holds the value from before the whole burst, which is
        // exactly what this edit's undo should restore — so there is nothing to push.
        if (continuesLastStep) return
        undoStack.addLast(before)
        if (undoStack.size > limit) undoStack.removeFirst()
    }

    /**
     * Pops the newest step and returns the value to restore, or null when there is nothing to undo.
     *
     * [currentOf] is handed the popped revision and must return the value the *same* slice holds
     * right now; that value goes on the redo stack, so redo can put it back.
     */
    fun undo(currentOf: (EditorRevision) -> EditorRevision): EditorRevision? =
        move(from = undoStack, to = redoStack, currentOf = currentOf)

    /** The mirror of [undo]: replays the step undo last took back. Null when there is none. */
    fun redo(currentOf: (EditorRevision) -> EditorRevision): EditorRevision? =
        move(from = redoStack, to = undoStack, currentOf = currentOf)

    private fun move(
        from: ArrayDeque<EditorRevision>,
        to: ArrayDeque<EditorRevision>,
        currentOf: (EditorRevision) -> EditorRevision,
    ): EditorRevision? {
        val restore = from.removeLastOrNull() ?: return null
        // No [limit] trim needed on the way back: a redo can only exist while no edit has been
        // recorded since the undo (record() clears the redo stack), and every entry moved here came
        // off this same stack — so it cannot grow past the size it already had.
        to.addLast(currentOf(restore))
        // A barrier: the next edit must start its own step rather than folding into the one now on
        // top, or a second undo would step back past the edit the user just made.
        lastGroup = null
        return restore
    }
}
