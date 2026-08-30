package my.cheysoff.feature_notes

import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NoteContentFormat
import my.cheysoff.feature_notes.model.single.ChecklistItem
import my.cheysoff.feature_notes.model.single.SingleNoteScreenState
import my.cheysoff.feature_notes.model.single.normalizeChecklistText
import my.cheysoff.feature_notes.model.single.parseChecklist
import my.cheysoff.feature_notes.model.single.serializeChecklist
import my.cheysoff.feature_notes.ui.single.isDiscardableOnOpen
import my.cheysoff.feature_notes.ui.single.mergeChecklist
import my.cheysoff.feature_notes.ui.single.mergeIncomingNote
import my.cheysoff.feature_notes.ui.single.toEditorBaseline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
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
        contentFormat: NoteContentFormat = NoteContentFormat.PLAIN,
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
        contentFormat = contentFormat,
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
    fun `an unchanged checklist keeps its ids when another field changes externally`() {
        // The test above is characterization only: every field of state and incoming is equal
        // there, so the old row-level "nothing changed at all" shortcut passed it too. Here the
        // checklist is identical but folderId moved (the list screen's MoveNoteToFolder), so the
        // row IS different and only per-field merging can keep the ids alive.
        val items = listOf(item("a", "milk"), item("b", "eggs", done = true))
        val state = SingleNoteScreenState(checklist = items, folderId = null, isLoaded = true)
        val baseline =
            note(checklist = items.serializeChecklist(), folderId = null).toEditorBaseline()

        val merged = mergeIncomingNote(
            state,
            baseline,
            note(checklist = items.serializeChecklist(), folderId = "f2"),
        )

        assertEquals("f2", merged.state.folderId)
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
        // The changed row gets a fresh id: distinct from the one it replaced, distinct from its
        // neighbour's (Compose keys off it), and non-empty.
        assertNotEquals(current[1].id, merged[1].id)
        assertNotEquals(merged[0].id, merged[1].id)
        assertTrue(merged[1].id.isNotEmpty())
    }

    @Test
    fun `a newline pasted into an item survives the echo with its id intact`() {
        // The single-line field can still receive a multi-line paste. serializeChecklist folds the
        // newline to a space, so text held un-normalized in state comes back as different text and
        // the row the user is typing into gets re-identified. Normalizing on the way in fixes it.
        val pasted = "milk\neggs"
        val local = listOf(item("a", normalizeChecklistText(pasted)))

        val merged = mergeChecklist(local, local.serializeChecklist())

        assertEquals(listOf("a"), merged.map { it.id })
        assertSame(local[0], merged[0])

        // Without the normalization the very same echo mints a fresh id — the bug being pinned.
        val unnormalized = listOf(item("a", pasted))
        assertNotSame(
            unnormalized[0],
            mergeChecklist(unnormalized, unnormalized.serializeChecklist())[0],
        )
    }

    @Test
    fun `normalized item text round-trips through serialize and parse unchanged`() {
        val normalized = normalizeChecklistText("line one\nline two")

        assertEquals("line one line two", normalized)
        assertEquals(
            listOf(normalized),
            parseChecklist(listOf(item("a", normalized)).serializeChecklist()).map { it.text },
        )
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
        assertTrue(isDiscardableOnOpen(openedForNewNote = true, note = note()))
    }

    @Test
    fun `a blank note opened without the isNew flag is never discardable`() {
        // Every route into the editor other than the "+" button leaves isNew at its default. An
        // existing note the user emptied out looks exactly like a brand new one on open, and this
        // is the only thing separating them.
        assertFalse(isDiscardableOnOpen(openedForNewNote = false, note = note()))
    }

    @Test
    fun `a legacy row whose createdAt was just backfilled is not discardable`() {
        // The regression: the old test inferred "created for this screen" from timestamps. A legacy
        // pre-migration row stores createdAt = 0, and the upsert rewrites that to now on the first
        // post-migration save — so createdAt != 0, createdAt == updatedAt AND createdAt is inside
        // the grace window all become true for an ordinary note the user has emptied out. It was
        // then hard-deleted, with its pin/favorite/folder, on the next back press.
        val now = 100_000L
        assertFalse(
            isDiscardableOnOpen(
                openedForNewNote = false,
                note = note(createdAt = now, updatedAt = now, isPinned = true, folderId = "f1"),
            )
        )
    }

    @Test
    fun `a note with content is not discardable even when opened as new`() {
        assertFalse(isDiscardableOnOpen(openedForNewNote = true, note = note(title = "hi")))
        assertFalse(isDiscardableOnOpen(openedForNewNote = true, note = note(content = "hi")))
        assertFalse(isDiscardableOnOpen(openedForNewNote = true, note = note(checklist = "0hi")))
    }

    // --- content and its format marker move as one unit ---

    /**
     * The marker says how the stored bytes are to be read, so adopting the body without it (or the
     * other way round) hands a plain note to the HTML reader and truncates it — the exact failure
     * the format column was added to stop. These pin the pairing at the merge, which is where the
     * two changes met.
     */
    @Test
    fun `adopting an external body change adopts its format marker with it`() {
        val baseline = note(content = "plain body").toEditorBaseline()
        val state = SingleNoteScreenState(
            content = "plain body",
            contentFormat = NoteContentFormat.PLAIN,
            isLoaded = true,
        )
        val incoming = note(content = "<p>rich</p>", contentFormat = NoteContentFormat.HTML)

        val merged = mergeIncomingNote(state, baseline, incoming)

        assertEquals("<p>rich</p>", merged.state.content)
        assertEquals(NoteContentFormat.HTML, merged.state.contentFormat)
    }

    @Test
    fun `an echo cannot downgrade the marker of a body the user has just rewritten`() {
        // The user has typed rich text; the echo still carries the old plain row.
        val baseline = note(content = "plain body").toEditorBaseline()
        val state = SingleNoteScreenState(
            content = "<p>typed</p>",
            contentFormat = NoteContentFormat.HTML,
            isLoaded = true,
        )
        val incoming = note(content = "plain body", contentFormat = NoteContentFormat.PLAIN)

        val merged = mergeIncomingNote(state, baseline, incoming)

        assertEquals("<p>typed</p>", merged.state.content)
        assertEquals(NoteContentFormat.HTML, merged.state.contentFormat)
    }

    @Test
    fun `the first row seeds the marker along with the body`() {
        val merged = mergeIncomingNote(
            SingleNoteScreenState(),
            null,
            note(content = "<p>stored</p>", contentFormat = NoteContentFormat.HTML),
        )
        assertEquals(NoteContentFormat.HTML, merged.state.contentFormat)
        assertEquals(NoteContentFormat.HTML, merged.baseline.contentFormat)
    }
}
