package my.cheysoff.feature_notes

import my.cheysoff.core_domain.model.NoteContentFormat
import my.cheysoff.feature_notes.model.single.ChecklistItem
import my.cheysoff.feature_notes.model.single.SingleNoteScreenState
import my.cheysoff.feature_notes.model.single.buildNoteShareText
import my.cheysoff.feature_notes.model.single.noteShareTitle
import my.cheysoff.feature_notes.model.single.parseChecklist
import my.cheysoff.feature_notes.ui.single.buildDuplicate
import my.cheysoff.feature_notes.ui.single.duplicateTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The pure halves of the editor's overflow menu: the text a note leaves the app as, and the row a
 * "Duplicate" writes. Neither touches Android, so both are checked here rather than on a device.
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

    @Test
    fun `the copy carries body, format, checklist and folder`() {
        val state = SingleNoteScreenState(
            title = "Trip",
            content = "<p>packing</p>",
            contentFormat = NoteContentFormat.HTML,
            checklist = listOf(item("socks", done = true), item("passport")),
            folderId = "f1",
        )

        val copy = buildDuplicate(state, newId = "new-id")

        assertEquals("new-id", copy.id)
        assertEquals("Trip (copy)", copy.title)
        assertEquals("<p>packing</p>", copy.content)
        assertEquals(NoteContentFormat.HTML, copy.contentFormat)
        assertEquals("f1", copy.folderId)
        // Round-trips through the same serializer the autosave uses, so the copy's checklist reads
        // back as the same items.
        assertEquals(
            listOf("socks" to true, "passport" to false),
            parseChecklist(copy.checklist).map { it.text to it.isDone },
        )
    }

    @Test
    fun `a plain-text note keeps its plain marker when duplicated`() {
        // The marker must travel with the same bytes it describes: labelling a legacy plain body
        // as HTML would have it parsed on the next open and characters eaten.
        val state = SingleNoteScreenState(
            content = "Email John <john@example.com>",
            contentFormat = NoteContentFormat.PLAIN,
        )

        val copy = buildDuplicate(state, newId = "new-id")

        assertEquals(NoteContentFormat.PLAIN, copy.contentFormat)
        assertEquals("Email John <john@example.com>", copy.content)
    }

    @Test
    fun `the copy is neither pinned nor favorited`() {
        val state = SingleNoteScreenState(title = "T", isPinned = true, isFavorite = true)

        val copy = buildDuplicate(state, newId = "new-id")

        assertFalse(copy.isPinned)
        assertFalse(copy.isFavorite)
    }

    @Test
    fun `the copy never reuses the original's id`() {
        val state = SingleNoteScreenState(title = "T")

        assertNotEquals("original", buildDuplicate(state, newId = "new-id").id)
    }

    @Test
    fun `timestamps are left for the upsert to stamp`() {
        val copy = buildDuplicate(SingleNoteScreenState(title = "T"), newId = "new-id")

        assertEquals(0L, copy.createdAt)
        assertEquals(0L, copy.updatedAt)
    }
}
