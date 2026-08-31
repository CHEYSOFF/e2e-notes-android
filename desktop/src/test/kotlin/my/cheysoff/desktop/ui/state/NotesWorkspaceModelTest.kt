package my.cheysoff.desktop.ui.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import my.cheysoff.core_domain.model.Folder
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NoteContentFormat
import my.cheysoff.core_domain.model.NotesSortOrder
import my.cheysoff.desktop.ui.preview.InMemoryNotesRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotesWorkspaceModelTest {

    private val folders = listOf(
        Folder(id = "work", name = "Work"),
        Folder(id = "home", name = "Personal"),
    )

    private val notes = listOf(
        Note(id = "n1", title = "Standup", content = "blocked", folderId = "work", updatedAt = 300),
        Note(id = "n2", title = "Groceries", content = "milk", folderId = "home", updatedAt = 200),
        Note(id = "n3", title = "Loose", content = "no folder", updatedAt = 100),
    )

    /**
     * Scopes handed to the models under test, cancelled after each case.
     *
     * Deliberately NOT `runTest`'s own `backgroundScope`: work launched there runs only while the
     * test body is suspended, so `advanceUntilIdle()` came back with the model's repository
     * collect never having started and every assertion read an empty, unloaded state. A scope on
     * the test scheduler runs whenever the scheduler is advanced, which is the thing these tests
     * are actually asserting about.
     */
    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun cancelModelScopes() {
        scopes.forEach { it.cancel() }
    }

    private fun TestScope.model(
        repository: InMemoryNotesRepository,
        clock: Long = 1_000L,
    ): NotesWorkspaceModel {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        scopes += scope
        return NotesWorkspaceModel(
            repository = repository,
            scope = scope,
            now = { clock },
            newId = { "generated-id" },
            autosaveDelayMillis = 600L,
        )
    }

    @Test
    fun `the first emission selects a note and builds the chips`() = runTest {
        val repository = InMemoryNotesRepository(notes, folders)
        val model = model(repository)
        advanceUntilIdle()

        val state = model.state.value
        assertTrue(state.loaded)
        // RECENTLY_EDITED, so the newest note is first and is what the editor opens on.
        assertEquals("n1", state.selectedNoteId)
        assertEquals("Standup", state.editor?.title)
        // "All" first, then the folders in the order the repository serves them — by name, so
        // Personal precedes Work regardless of how they were declared.
        assertEquals(listOf(null, "home", "work"), state.chips.map { it.id })
    }

    @Test
    fun `an empty library selects nothing and reports loaded`() = runTest {
        val model = model(InMemoryNotesRepository())
        advanceUntilIdle()

        assertTrue(model.state.value.loaded)
        assertNull(model.state.value.selectedNoteId)
        assertNull(model.state.value.editor)
    }

    @Test
    fun `typing is visible at once and written after the debounce`() = runTest {
        val repository = InMemoryNotesRepository(notes, folders)
        val model = model(repository, clock = 9_999L)
        advanceUntilIdle()

        model.setTitle("Standup — Friday")

        assertEquals("Standup — Friday", model.state.value.editor?.title)
        assertEquals(SaveStatus.Pending, model.state.value.saveStatus)

        advanceUntilIdle()

        val stored = repository.getNotes(NotesSortOrder.RECENTLY_EDITED).first().first { it.id == "n1" }
        assertEquals("Standup — Friday", stored.title)
        assertEquals(9_999L, stored.updatedAt)
        assertEquals(SaveStatus.Saved(9_999L), model.state.value.saveStatus)
    }

    @Test
    fun `the repository echo of an autosave does not reset the draft`() = runTest {
        // The clobbering bug this guards against: the write lands, the repository re-emits, and
        // the keystrokes typed in the meantime are replaced by the version that was saved.
        val repository = InMemoryNotesRepository(notes, folders)
        val model = model(repository)
        advanceUntilIdle()

        model.setTitle("first")
        advanceUntilIdle() // the write and its echo both complete here
        model.setTitle("first second")

        assertEquals("first second", model.state.value.editor?.title)
    }

    @Test
    fun `writing a body promotes the note to HTML and an empty body leaves it plain`() = runTest {
        val repository = InMemoryNotesRepository(notes, folders)
        val model = model(repository)
        advanceUntilIdle()

        model.setContent("<p>now with markup</p>")
        assertEquals(NoteContentFormat.HTML, model.state.value.editor?.contentFormat)

        model.setContent("")
        assertEquals(NoteContentFormat.PLAIN, model.state.value.editor?.contentFormat)
    }

    @Test
    fun `a new note is filed into the folder currently being viewed`() = runTest {
        val repository = InMemoryNotesRepository(notes, folders)
        val model = model(repository)
        advanceUntilIdle()

        model.selectFolder("work")
        val id = model.newNote()
        advanceUntilIdle()

        assertEquals(id, model.state.value.selectedNoteId)
        val stored = repository.getNotes(NotesSortOrder.RECENTLY_EDITED).first().first { it.id == id }
        assertEquals("work", stored.folderId)
        // It must be visible in the list it was created from, or Ctrl+N looks like it did nothing.
        assertTrue(model.state.value.content.all.any { it.id == id })
    }

    @Test
    fun `deleting a note that was never written purges it instead of filling Trash`() = runTest {
        val repository = InMemoryNotesRepository(notes, folders)
        val model = model(repository)
        advanceUntilIdle()

        model.newNote()
        advanceUntilIdle()
        model.deleteSelectedNote()
        advanceUntilIdle()

        assertTrue(repository.getDeletedNotes().first().isEmpty())
        assertEquals(3, repository.getNotes(NotesSortOrder.RECENTLY_EDITED).first().size)
    }

    @Test
    fun `deleting a note with content sends it to Trash where it can be restored`() = runTest {
        val repository = InMemoryNotesRepository(notes, folders)
        val model = model(repository)
        advanceUntilIdle()

        model.deleteSelectedNote()
        advanceUntilIdle()

        assertEquals(listOf("n1"), repository.getDeletedNotes().first().map { it.id })
        assertFalse(model.state.value.content.all.any { it.id == "n1" })
    }

    @Test
    fun `deleting the open note moves the editor to another one rather than leaving it stale`() = runTest {
        val repository = InMemoryNotesRepository(notes, folders)
        val model = model(repository)
        advanceUntilIdle()

        model.deleteSelectedNote()
        advanceUntilIdle()

        assertEquals("n2", model.state.value.selectedNoteId)
        assertEquals("Groceries", model.state.value.editor?.title)
    }

    @Test
    fun `search runs over the whole library, not the filtered list`() = runTest {
        val repository = InMemoryNotesRepository(notes, folders)
        val model = model(repository)
        advanceUntilIdle()

        model.selectFolder("work")
        model.openSearch()
        model.setSearchQuery("groceries")

        assertEquals(listOf("n2"), model.state.value.search.hits.map { it.row.id })
    }

    @Test
    fun `opening a result the folder filter hides clears the filter`() = runTest {
        val repository = InMemoryNotesRepository(notes, folders)
        val model = model(repository)
        advanceUntilIdle()

        model.selectFolder("work")
        model.openSearch()
        model.setSearchQuery("groceries")
        model.openHighlightedSearchHit()

        assertEquals("n2", model.state.value.selectedNoteId)
        assertNull(model.state.value.selectedFolderId)
        assertFalse(model.state.value.search.isOpen)
        // …and the note must actually be in the list the sidebar draws.
        assertTrue(model.state.value.content.all.any { it.id == "n2" })
    }

    @Test
    fun `the arrow keys clamp at the ends of the result list`() = runTest {
        val repository = InMemoryNotesRepository(notes, folders)
        val model = model(repository)
        advanceUntilIdle()

        model.openSearch()
        model.setSearchQuery("o") // matches several notes
        val count = model.state.value.search.hits.size
        assertTrue("expected more than one hit to move between", count > 1)

        model.moveSearchHighlight(-1)
        assertEquals(0, model.state.value.search.highlighted)

        repeat(count + 5) { model.moveSearchHighlight(1) }
        assertEquals(count - 1, model.state.value.search.highlighted)
    }

    @Test
    fun `switching notes flushes the outgoing draft instead of dropping it`() = runTest {
        val repository = InMemoryNotesRepository(notes, folders)
        val model = model(repository, clock = 4_242L)
        advanceUntilIdle()

        model.setTitle("half-typed")
        model.selectNote("n2") // before the debounce would have fired
        advanceUntilIdle()

        val stored = repository.getNotes(NotesSortOrder.RECENTLY_EDITED).first().first { it.id == "n1" }
        assertEquals("half-typed", stored.title)
        assertEquals("n2", model.state.value.selectedNoteId)
    }

    @Test
    fun `a checklist edit survives the round trip through serialization`() = runTest {
        val repository = InMemoryNotesRepository(notes, folders)
        val model = model(repository)
        advanceUntilIdle()

        val itemId = model.addChecklistItem()
        model.setChecklistItemText(itemId, "buy stamps")
        model.toggleChecklistItem(itemId)
        advanceUntilIdle()

        val stored = repository.getNotes(NotesSortOrder.RECENTLY_EDITED).first().first { it.id == "n1" }
        assertEquals("1buy stamps", stored.checklist)
        assertEquals(1 to 1, checklistProgress(stored.checklist))
    }

    @Test
    fun `a newline pasted into a checklist item cannot corrupt the serialized format`() = runTest {
        val repository = InMemoryNotesRepository(notes, folders)
        val model = model(repository)
        advanceUntilIdle()

        val itemId = model.addChecklistItem()
        model.setChecklistItemText(itemId, "line one\nline two")
        advanceUntilIdle()

        val stored = repository.getNotes(NotesSortOrder.RECENTLY_EDITED).first().first { it.id == "n1" }
        // One line, or the parse would come back with a second, phantom item.
        assertEquals("0line one line two", stored.checklist)
        assertEquals(1, parseChecklist(stored.checklist).size)
    }

    @Test
    fun `pinning moves the note into the Pinned section`() = runTest {
        val repository = InMemoryNotesRepository(notes, folders)
        val model = model(repository)
        advanceUntilIdle()

        model.togglePinned()
        advanceUntilIdle()

        assertEquals(listOf("n1"), model.state.value.content.pinned.map { it.id })
        assertNotNull(model.state.value.editor)
        assertTrue(model.state.value.editor!!.isPinned)
    }
}
