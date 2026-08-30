package my.cheysoff.feature_notes

import my.cheysoff.core_domain.model.NoteContentFormat
import my.cheysoff.feature_notes.model.single.ChecklistItem
import my.cheysoff.feature_notes.ui.single.EditGroup
import my.cheysoff.feature_notes.ui.single.EditorHistory
import my.cheysoff.feature_notes.ui.single.EditorRevision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The editor's undo/redo stack, exercised as the pure component it is: one stack for title, body
 * and checklist, coalescing bursts of typing into single steps.
 *
 * Time is injected, so "the user paused" is a variable rather than a sleep.
 */
class EditorHistoryTest {

    /** Advances only when a test says so, so every coalescing decision here is deterministic. */
    private class FakeClock(var nowMs: Long = 1_000L) : () -> Long {
        override fun invoke(): Long = nowMs
    }

    private val clock = FakeClock()

    private fun history(limit: Int = 50, window: Long = 600L) =
        EditorHistory(limit = limit, coalesceWindowMs = window, now = clock)

    private fun title(text: String) = EditorRevision.Title(text)

    private fun body(content: String) =
        EditorRevision.Body(content, NoteContentFormat.HTML)

    private fun items(vararg pairs: Pair<String, String>) =
        pairs.map { (id, text) -> ChecklistItem(id = id, text = text, isDone = false) }

    /**
     * Stands in for the editor state: undo/redo hand back the value to restore and are given the
     * value the same slice holds right now, so a test drives them through one mutable "current".
     */
    private class Editor(var current: EditorRevision) {
        fun currentOf(like: EditorRevision): EditorRevision {
            // The production caller reads the slice matching `like`; a test only ever drives one
            // slice through a given Editor, so this asserts the kinds line up rather than mapping.
            assertEquals(like::class, current::class)
            return current
        }

        /** Applies one undo/redo result the way the ViewModel does. */
        fun apply(restored: EditorRevision?): EditorRevision? {
            if (restored != null) current = restored
            return restored
        }
    }

    // --- an empty history offers nothing -------------------------------------------------------

    @Test
    fun `a fresh history can neither undo nor redo`() {
        val h = history()

        assertFalse(h.canUndo)
        assertFalse(h.canRedo)
        assertNull(h.undo { it })
        assertNull(h.redo { it })
    }

    // --- one stack across all three fields -----------------------------------------------------

    @Test
    fun `undo steps back through title, body and checklist in the order they were edited`() {
        val h = history()
        val list = items("a" to "milk")

        // Each edit is far enough apart that nothing coalesces, and each is a different field
        // anyway.
        h.record(title(""), EditGroup.Title)
        clock.nowMs += 5_000
        h.record(body("<p></p>"), EditGroup.Body)
        clock.nowMs += 5_000
        h.record(EditorRevision.Checklist(list), EditGroup.Structural)

        assertEquals(EditorRevision.Checklist(list), h.undo { it })
        assertEquals(body("<p></p>"), h.undo { it })
        assertEquals(title(""), h.undo { it })
        assertNull(h.undo { it })
        assertFalse(h.canUndo)
    }

    @Test
    fun `a title edit is undone and then redone`() {
        val h = history()
        val editor = Editor(title("hello"))

        h.record(title(""), EditGroup.Title)
        editor.current = title("hello")

        editor.apply(h.undo(editor::currentOf))
        assertEquals(title(""), editor.current)
        assertFalse(h.canUndo)
        assertTrue(h.canRedo)

        editor.apply(h.redo(editor::currentOf))
        assertEquals(title("hello"), editor.current)
        assertTrue(h.canUndo)
        assertFalse(h.canRedo)
    }

    // --- coalescing ----------------------------------------------------------------------------

    @Test
    fun `a burst of typing in one field is a single undo step`() {
        val h = history(window = 600L)

        // "" -> "h" -> "he" -> "hel", 100 ms apart: one step back to "".
        h.record(title(""), EditGroup.Title)
        clock.nowMs += 100
        h.record(title("h"), EditGroup.Title)
        clock.nowMs += 100
        h.record(title("he"), EditGroup.Title)

        assertEquals(title(""), h.undo { it })
        assertFalse(h.canUndo)
    }

    @Test
    fun `typing that keeps going past the window still folds while the gaps stay short`() {
        val h = history(window = 600L)

        // The window measures the gap since the previous keystroke, not since the step began, so
        // 10 keystrokes 500 ms apart (5 s in total) are still one step.
        h.record(title(""), EditGroup.Title)
        repeat(9) {
            clock.nowMs += 500
            h.record(title("x".repeat(it + 1)), EditGroup.Title)
        }

        assertEquals(title(""), h.undo { it })
        assertFalse(h.canUndo)
    }

    @Test
    fun `a pause longer than the window starts a new undo step`() {
        val h = history(window = 600L)

        h.record(title(""), EditGroup.Title)
        clock.nowMs += 601
        h.record(title("hello"), EditGroup.Title)

        assertEquals(title("hello"), h.undo { it })
        assertEquals(title(""), h.undo { it })
    }

    @Test
    fun `switching field starts a new step even with no pause at all`() {
        val h = history(window = 600L)

        h.record(title(""), EditGroup.Title)
        h.record(body("<p></p>"), EditGroup.Body)

        assertEquals(body("<p></p>"), h.undo { it })
        assertEquals(title(""), h.undo { it })
    }

    @Test
    fun `typing into two different checklist rows makes two steps`() {
        val h = history(window = 600L)
        val before = items("a" to "", "b" to "")
        val afterFirstRow = items("a" to "milk", "b" to "")

        h.record(EditorRevision.Checklist(before), EditGroup.ChecklistItemText("a"))
        h.record(EditorRevision.Checklist(afterFirstRow), EditGroup.ChecklistItemText("b"))

        assertEquals(EditorRevision.Checklist(afterFirstRow), h.undo { it })
        assertEquals(EditorRevision.Checklist(before), h.undo { it })
    }

    @Test
    fun `structural checklist edits never fold, not even back to back`() {
        val h = history(window = 600L)
        val empty = items()
        val one = items("a" to "")

        // Two "add item" taps in the same instant: two steps, because each is a discrete action.
        h.record(EditorRevision.Checklist(empty), EditGroup.Structural)
        h.record(EditorRevision.Checklist(one), EditGroup.Structural)

        assertEquals(EditorRevision.Checklist(one), h.undo { it })
        assertEquals(EditorRevision.Checklist(empty), h.undo { it })
    }

    // --- redo is a branch that a new edit destroys ---------------------------------------------

    @Test
    fun `a new edit after an undo clears redo`() {
        val h = history()
        val editor = Editor(title("hello"))

        h.record(title(""), EditGroup.Title)
        editor.apply(h.undo(editor::currentOf))
        assertTrue(h.canRedo)

        clock.nowMs += 5_000
        h.record(title(""), EditGroup.Title)

        assertFalse(h.canRedo)
        assertNull(h.redo { it })
    }

    @Test
    fun `an undo is a barrier, so the next edit cannot fold into the step below it`() {
        val h = history(window = 600L)
        val editor = Editor(title("live"))

        h.record(title("A"), EditGroup.Title)
        clock.nowMs += 5_000
        h.record(title("B"), EditGroup.Title)

        editor.apply(h.undo(editor::currentOf))
        assertEquals(title("B"), editor.current)

        // Same field, no pause since the last record: were the undo not a barrier, this edit would
        // fold into the "A" step still on the stack and the undo below would jump straight past
        // "B", losing a step the user could see.
        h.record(title("B"), EditGroup.Title)
        editor.current = title("Bx")

        editor.apply(h.undo(editor::currentOf))
        assertEquals(title("B"), editor.current)
        editor.apply(h.undo(editor::currentOf))
        assertEquals(title("A"), editor.current)
        assertFalse(h.canUndo)
    }

    @Test
    fun `redo replays the steps in the order they were undone`() {
        val h = history()
        val editor = Editor(title("ab"))

        h.record(title(""), EditGroup.Title)
        clock.nowMs += 5_000
        h.record(title("a"), EditGroup.Title)

        editor.apply(h.undo(editor::currentOf))
        assertEquals(title("a"), editor.current)
        editor.apply(h.undo(editor::currentOf))
        assertEquals(title(""), editor.current)

        editor.apply(h.redo(editor::currentOf))
        assertEquals(title("a"), editor.current)
        editor.apply(h.redo(editor::currentOf))
        assertEquals(title("ab"), editor.current)
        assertFalse(h.canRedo)
    }

    // --- checklist identity --------------------------------------------------------------------

    @Test
    fun `undo puts back the very ChecklistItem instances that were in state`() {
        // Ids back the UI's focus requesters and address every checklist intent, so an undo that
        // minted fresh ones would steal focus and strand an in-flight keystroke.
        val h = history()
        val before = items("a" to "milk", "b" to "eggs")

        h.record(EditorRevision.Checklist(before), EditGroup.Structural)
        val restored = h.undo { it } as EditorRevision.Checklist

        assertEquals(listOf("a", "b"), restored.items.map { it.id })
        assertSame(before, restored.items)
        assertSame(before[0], restored.items[0])
    }

    @Test
    fun `undoing a removal restores the removed row with its original id`() {
        val h = history()
        val before = items("a" to "milk", "b" to "eggs")
        val after = listOf(before[0])
        val editor = Editor(EditorRevision.Checklist(after))

        h.record(EditorRevision.Checklist(before), EditGroup.Structural)
        editor.apply(h.undo(editor::currentOf))

        assertEquals(listOf("a", "b"), (editor.current as EditorRevision.Checklist).items.map { it.id })

        // And redo takes it away again, leaving the surviving row's id untouched.
        editor.apply(h.redo(editor::currentOf))
        assertEquals(listOf("a"), (editor.current as EditorRevision.Checklist).items.map { it.id })
    }

    // --- the stack is bounded ------------------------------------------------------------------

    @Test
    fun `the stack keeps the newest entries and drops the oldest past the limit`() {
        val h = history(limit = 3)

        // Five separate steps (each past the window), of which only the last three survive.
        repeat(5) { i ->
            h.record(title("step$i"), EditGroup.Title)
            clock.nowMs += 5_000
        }

        assertEquals(title("step4"), h.undo { it })
        assertEquals(title("step3"), h.undo { it })
        assertEquals(title("step2"), h.undo { it })
        assertNull(h.undo { it })
    }

    @Test
    fun `a full stack still redoes everything it took back`() {
        val h = history(limit = 3)
        // "live" is the value left after the fifth edit — the one no step holds, because the stack
        // holds the value from *before* each edit.
        val editor = Editor(title("live"))

        repeat(5) { i ->
            h.record(title("step$i"), EditGroup.Title)
            clock.nowMs += 5_000
        }

        repeat(3) { editor.apply(h.undo(editor::currentOf)) }
        assertEquals(title("step2"), editor.current)
        assertFalse(h.canUndo)

        repeat(3) { editor.apply(h.redo(editor::currentOf)) }
        assertEquals(title("live"), editor.current)
        assertFalse(h.canRedo)
    }

    // --- body and its format marker move together -----------------------------------------------

    @Test
    fun `undoing the body restores its format marker with it`() {
        // A legacy plain-text note whose first edit flips it to HTML: the undo has to put the PLAIN
        // marker back too, or the screen would feed plain text to the HTML reader.
        val h = history()
        val plain = EditorRevision.Body("a < b", NoteContentFormat.PLAIN)
        val editor = Editor(EditorRevision.Body("<p>a &lt; b!</p>", NoteContentFormat.HTML))

        h.record(plain, EditGroup.Body)
        editor.apply(h.undo(editor::currentOf))

        assertEquals(plain, editor.current)
    }
}
