package my.cheysoff.feature_notes

import my.cheysoff.feature_notes.model.single.ChecklistItem
import my.cheysoff.feature_notes.model.single.buildNoteShareText
import my.cheysoff.feature_notes.model.single.noteShareTitle
import my.cheysoff.feature_notes.ui.single.duplicateTitle
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pure halves of the editor's overflow menu: the text a note leaves the app as, and the title a
 * "Duplicate" gives its copy. Neither touches Android, so both are checked here rather than on a
 * device; the copy itself is written by the ViewModel (see `SingleNoteViewModelTest`).
 */
class NoteExportTest {

    private fun item(text: String, done: Boolean = false) =
        ChecklistItem(id = "id-$text", text = text, isDone = done)

    // --- share / copy text -------------------------------------------------------------------

    @Test
    fun `all three parts are joined by blank lines`() {
        val text = buildNoteShareText(
            title = "Groceries",
            plainBody = "for the weekend",
            checklist = listOf(item("milk", done = true), item("bread")),
        )

        assertEquals("Groceries\n\nfor the weekend\n\n[x] milk\n[ ] bread", text)
    }

    @Test
    fun `an empty title is dropped instead of leaving a blank first line`() {
        val text = buildNoteShareText(title = "   ", plainBody = "body", checklist = emptyList())

        assertEquals("body", text)
    }

    @Test
    fun `a checklist-only note shares as just the checklist`() {
        val text = buildNoteShareText(title = "", plainBody = "", checklist = listOf(item("one")))

        assertEquals("[ ] one", text)
    }

    @Test
    fun `a body-only note shares as just the body`() {
        val text = buildNoteShareText(title = "", plainBody = "just words", checklist = emptyList())

        assertEquals("just words", text)
    }

    @Test
    fun `blank checklist rows are dropped`() {
        // Tapping the checklist button leaves an empty row behind; "[ ]" on its own is noise to
        // whoever receives the note.
        val text = buildNoteShareText(
            title = "",
            plainBody = "",
            checklist = listOf(item("real"), item("   "), item("")),
        )

        assertEquals("[ ] real", text)
    }

    @Test
    fun `a checklist of only blank rows contributes no block at all`() {
        val text = buildNoteShareText(title = "T", plainBody = "", checklist = listOf(item("")))

        assertEquals("T", text)
    }

    @Test
    fun `a note with nothing in it produces the empty string`() {
        assertEquals("", buildNoteShareText("", "", emptyList()))
    }

    @Test
    fun `body text is passed through verbatim, markup and all`() {
        // buildNoteShareText does no markup handling: the caller is responsible for handing it
        // plain text. This pins that contract so nobody adds a stripper here by surprise.
        val text = buildNoteShareText("", "a < b and 5 > 3", emptyList())

        assertEquals("a < b and 5 > 3", text)
    }

    @Test
    fun `share subject falls back to a label when the note is untitled`() {
        assertEquals("Untitled note", noteShareTitle(""))
        assertEquals("Untitled note", noteShareTitle("  \n "))
        assertEquals("Real title", noteShareTitle("  Real title  "))
    }

    // --- duplicate ---------------------------------------------------------------------------

    @Test
    fun `duplicate title carries the copy suffix`() {
        assertEquals("Notes (copy)", duplicateTitle("Notes"))
        assertEquals("Notes (copy)", duplicateTitle("  Notes  "))
    }

    @Test
    fun `an untitled note duplicates to a findable title`() {
        assertEquals("Untitled (copy)", duplicateTitle(""))
        assertEquals("Untitled (copy)", duplicateTitle("   "))
    }

    @Test
    fun `duplicating a duplicate doubles the suffix rather than guessing a number`() {
        assertEquals("Notes (copy) (copy)", duplicateTitle(duplicateTitle("Notes")))
    }
}
