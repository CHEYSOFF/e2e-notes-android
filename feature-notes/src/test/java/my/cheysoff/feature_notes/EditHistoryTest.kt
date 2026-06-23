package my.cheysoff.feature_notes

import my.cheysoff.feature_notes.ui.single.EditHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditHistoryTest {
    @Test fun `starts with nothing to undo or redo`() {
        val h = EditHistory("a")
        assertFalse(h.canUndo); assertFalse(h.canRedo)
        assertNull(h.undo()); assertNull(h.redo())
    }

    @Test fun `record enables undo and undo-redo round trips`() {
        val h = EditHistory("a")
        h.record("ab"); h.record("abc")
        assertTrue(h.canUndo); assertFalse(h.canRedo)
        assertEquals("ab", h.undo())
        assertEquals("a", h.undo())
        assertFalse(h.canUndo); assertTrue(h.canRedo)
        assertEquals("ab", h.redo())
        assertEquals("abc", h.redo())
        assertFalse(h.canRedo)
    }

    @Test fun `record dedupes the current state (loop guard)`() {
        val h = EditHistory("a")
        h.record("ab")
        h.record("ab") // no-op
        assertEquals("a", h.undo())
        assertFalse(h.canUndo)
    }

    @Test fun `recording after undo drops the redo branch`() {
        val h = EditHistory("a")
        h.record("ab"); h.record("abc")
        h.undo() // at "ab"
        h.record("abX")
        assertFalse(h.canRedo)
        assertEquals("ab", h.undo())
    }

    @Test fun `reset clears history to a new baseline`() {
        val h = EditHistory("a")
        h.record("ab")
        h.reset("z")
        assertFalse(h.canUndo); assertFalse(h.canRedo)
    }
}
