package my.cheysoff.feature_notes

import my.cheysoff.core_domain.model.Note
import my.cheysoff.feature_notes.model.single.ChecklistItem
import my.cheysoff.feature_notes.model.single.SingleNoteScreenState
import my.cheysoff.feature_notes.model.single.serializeChecklist
import my.cheysoff.feature_notes.ui.single.NEW_NOTE_GRACE_MS
import my.cheysoff.feature_notes.ui.single.isFreshlyCreatedBlankNote
import my.cheysoff.feature_notes.ui.single.mergeChecklist
import my.cheysoff.feature_notes.ui.single.mergeIncomingNote
import my.cheysoff.feature_notes.ui.single.toEditorBaseline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Room re-emits the note row after every write, so the editor is constantly handed echoes of its
 * own saves. These cover the rule that keeps such an echo from rolling the user's edits back.
 */
class SingleNoteMergeTest {

    private fun note(
        title: String = "",
        content: String = "",
        checklist: String = "",
        isPinned: Boolean = false,
        isFavorite: Boolean = false,
        folderId: String? = null,
        createdAt: Long = 1_000L,
        updatedAt: Long = 1_000L,
    ) = Note(
        id = "n1",
        title = title,
        content = content,
        checklist = checklist,
        isPinned = isPinned,
        isFavorite = isFavorite,
        folderId = folderId,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun item(id: String, text: String, done: Boolean = false) =
        ChecklistItem(id = id, text = text, isDone = done)

    // --- seeding -----------------------------------------------------------------------------

    @Test
    fun `first emission seeds every field from the stored row`() {
        val stored = note(
            title = "hello",
            content = "<p>body</p>",
            checklist = "0buy milk",
            isPinned = true,
            isFavorite = true,
            folderId = "f1",
            updatedAt = 42L,
        )

        val merged = mergeIncomingNote(SingleNoteScreenState(), baseline = null, incoming = stored)

        assertEquals("hello", merged.state.title)
        assertEquals("<p>body</p>", merged.state.content)
        assertEquals(listOf("buy milk" to false), merged.state.checklist.map { it.text to it.isDone })
        assertTrue(merged.state.isPinned)
        assertTrue(merged.state.isFavorite)
        assertEquals("f1", merged.state.folderId)
        assertEquals(42L, merged.state.updatedAt)
        assertTrue(merged.state.isLoaded)
        assertEquals(stored.toEditorBaseline(), merged.baseline)
    }

    // --- symptom 1: typed characters must not be rolled back ---------------------------------

    @Test
    fun `an echo of an older save does not revert typing that happened after it`() {
        // The user typed "hello", the autosave wrote it (so the baseline holds it), then typed "!"
        // before Room delivered the row for that write.
        val state = SingleNoteScreenState(title = "hello!", isLoaded = true)
        val baseline = note(title = "hello").toEditorBaseline()

        val merged = mergeIncomingNote(state, baseline, note(title = "hello", updatedAt = 7L))

        assertEquals("hello!", merged.state.title)
        // updatedAt is still adopted so the "Edited … ago" line stays honest.
        assertEquals(7L, merged.state.updatedAt)
    }

    @Test
    fun `an echo does not resurrect text the user has just deleted`() {
        // Reverting to an earlier value is still an edit: local "hell" differs from the last
        // written value "hello", so the echo of "hello" must not be adopted.
        val state = SingleNoteScreenState(title = "hell", isLoaded = true)
        val baseline = note(title = "hello").toEditorBaseline()

        val merged = mergeIncomingNote(state, baseline, note(title = "hello"))

        assertEquals("hell", merged.state.title)
    }

    @Test
    fun `an echo that matches the last write leaves the editor untouched`() {
        val state = SingleNoteScreenState(title = "hello", content = "body", isLoaded = true)
        val baseline = note(title = "hello", content = "body").toEditorBaseline()

        val merged = mergeIncomingNote(state, baseline, note(title = "hello", content = "body", updatedAt = 9L))

        assertEquals(state.copy(updatedAt = 9L), merged.state)
    }

    // --- symptom 2: metadata toggles must not flip back ---------------------------------------

    @Test
    fun `a favorite toggle survives an emission that still reports the old value`() {
        val state = SingleNoteScreenState(isFavorite = true, isLoaded = true)
        val baseline = note(isFavorite = false).toEditorBaseline()

        val merged = mergeIncomingNote(state, baseline, note(isFavorite = false))

        assertTrue(merged.state.isFavorite)
    }

    @Test
    fun `a pin toggle survives an emission that still reports the old value`() {
        val state = SingleNoteScreenState(isPinned = true, isLoaded = true)
        val baseline = note(isPinned = false).toEditorBaseline()

        assertTrue(mergeIncomingNote(state, baseline, note(isPinned = false)).state.isPinned)
    }

    // --- external changes must still land ------------------------------------------------------

    @Test
    fun `an external folder move is adopted`() {
        val state = SingleNoteScreenState(title = "hello", folderId = null, isLoaded = true)
        val baseline = note(title = "hello", folderId = null).toEditorBaseline()

        val merged = mergeIncomingNote(state, baseline, note(title = "hello", folderId = "f2"))

        assertEquals("f2", merged.state.folderId)
    }

    @Test
    fun `an external folder move lands even while the user is mid-word in the title`() {
        // The rule is per field: an untouched folderId is adopted, a touched title is not clobbered.
        val state = SingleNoteScreenState(title = "hello!", folderId = null, isLoaded = true)
        val baseline = note(title = "hello", folderId = null).toEditorBaseline()

        val merged = mergeIncomingNote(state, baseline, note(title = "hello", folderId = "f2"))

        assertEquals("hello!", merged.state.title)
        assertEquals("f2", merged.state.folderId)
    }

    @Test
    fun `the row just received always becomes the new baseline`() {
        val state = SingleNoteScreenState(title = "hello!", isLoaded = true)
        val incoming = note(title = "hello", folderId = "f2")

        val merged = mergeIncomingNote(state, note(title = "older").toEditorBaseline(), incoming)

        assertEquals(incoming.toEditorBaseline(), merged.baseline)
    }

    // --- symptom 3: checklist identity ---------------------------------------------------------

    @Test
    fun `an echo of the same checklist keeps the item ids`() {
        // Fresh ids would strand in-flight ChecklistItemTextChanged intents and steal focus.
        val items = listOf(item("a", "milk"), item("b", "eggs", done = true))
        val state = SingleNoteScreenState(checklist = items, isLoaded = true)
        val baseline = note(checklist = items.serializeChecklist()).toEditorBaseline()

        val merged = mergeIncomingNote(state, baseline, note(checklist = items.serializeChecklist()))

        assertEquals(listOf("a", "b"), merged.state.checklist.map { it.id })
        assertSame(items, merged.state.checklist)
    }

    @Test
    fun `a checklist keystroke is not rolled back by the echo of the previous save`() {
        val local = listOf(item("a", "milkk"))
        val state = SingleNoteScreenState(checklist = local, isLoaded = true)
        val baseline = note(checklist = listOf(item("a", "milk")).serializeChecklist()).toEditorBaseline()

        val merged = mergeIncomingNote(state, baseline, note(checklist = "0milk"))

        assertEquals(listOf("milkk"), merged.state.checklist.map { it.text })
        assertSame(local, merged.state.checklist)
    }

    // --- mergeChecklist ------------------------------------------------------------------------

    @Test
    fun `mergeChecklist returns the same instance when nothing changed`() {
        val current = listOf(item("a", "milk"), item("b", "eggs", done = true))
        assertSame(current, mergeChecklist(current, current.serializeChecklist()))
    }

    @Test
    fun `mergeChecklist keeps ids of untouched rows and re-ids only what changed`() {
        val current = listOf(item("a", "milk"), item("b", "eggs"))
        val merged = mergeChecklist(current, "0milk\n1eggs")

        assertSame(current[0], merged[0])
        assertEquals("eggs" to true, merged[1].text to merged[1].isDone)
        // The changed row gets a fresh id; it must at least still be a distinct, non-empty id.
        assertTrue(merged[1].id.isNotEmpty())
    }

    @Test
    fun `mergeChecklist adopts appended rows while keeping the existing ones`() {
        val current = listOf(item("a", "milk"))
        val merged = mergeChecklist(current, "0milk\n0eggs")

        assertEquals(2, merged.size)
        assertSame(current[0], merged[0])
        assertEquals("eggs", merged[1].text)
    }

    @Test
    fun `mergeChecklist adopts an emptied checklist`() {
        assertEquals(emptyList<ChecklistItem>(), mergeChecklist(listOf(item("a", "milk")), ""))
    }

    // --- auto-discard of a freshly created blank note -------------------------------------------

    @Test
    fun `the blank row the plus button just inserted is discardable`() {
        val openedAt = 100_000L
        assertTrue(
            isFreshlyCreatedBlankNote(
                note(createdAt = openedAt - 20L, updatedAt = openedAt - 20L),
                openedAt,
            )
        )
    }

    @Test
    fun `an existing note emptied in an earlier session is never discardable`() {
        // The bug: it is blank on open, but emptying it went through a save that bumped updatedAt.
        val openedAt = 100_000L
        assertFalse(
            isFreshlyCreatedBlankNote(
                note(createdAt = openedAt - 20L, updatedAt = openedAt - 10L),
                openedAt,
            )
        )
    }

    @Test
    fun `a blank note created in an earlier session is not discardable`() {
        val openedAt = 100_000L
        val createdAt = openedAt - NEW_NOTE_GRACE_MS - 1L
        assertFalse(isFreshlyCreatedBlankNote(note(createdAt = createdAt, updatedAt = createdAt), openedAt))
    }

    @Test
    fun `a legacy row with no timestamps is not discardable`() {
        assertFalse(isFreshlyCreatedBlankNote(note(createdAt = 0L, updatedAt = 0L), 100_000L))
    }

    @Test
    fun `a note with content is not discardable`() {
        val openedAt = 100_000L
        val stamp = openedAt - 20L
        assertFalse(isFreshlyCreatedBlankNote(note(title = "hi", createdAt = stamp, updatedAt = stamp), openedAt))
        assertFalse(isFreshlyCreatedBlankNote(note(content = "hi", createdAt = stamp, updatedAt = stamp), openedAt))
        assertFalse(isFreshlyCreatedBlankNote(note(checklist = "0hi", createdAt = stamp, updatedAt = stamp), openedAt))
    }
}
