package my.cheysoff.feature_notes

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import my.cheysoff.core_domain.model.Folder
import my.cheysoff.core_domain.model.HeaderSettings
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NoteContentFormat
import my.cheysoff.core_domain.model.NotesSortOrder
import my.cheysoff.feature_notes.model.list.BottomBarItem
import my.cheysoff.feature_notes.model.list.NotesListIntent
import my.cheysoff.feature_notes.ui.list.NotesListEvent
import my.cheysoff.feature_notes.ui.list.NotesListViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

/**
 * The notes list: folder filtering, the sort-order re-subscription, the search pipeline, and the
 * navigation events.
 *
 * ## Two things about how these tests wait
 *
 * The list and search pipelines both end in `.flowOn(Dispatchers.Default)`, which is production
 * behaviour worth keeping — `Note.toUi()` parses every note's HTML and would jank the frame if it
 * ran on the main thread. It does mean the upstream half of each pipeline runs on the REAL
 * background pool, on REAL time, and is therefore not reachable by the virtual clock:
 *
 *  - [awaitState] pumps the test dispatcher (so the downstream `onEach` that writes `_state` gets
 *    to run) and sleeps a little real time in between, until the assertion's precondition holds.
 *    A timeout turns a pipeline that never delivers into a named failure rather than a hang.
 *  - [pumpRealMillis] does the same for a fixed stretch of real time. It is how "nothing has
 *    happened yet" is asserted — the search debounce is 300 ms of real time for the same reason,
 *    so pumping for a small fraction of that and finding no results is a genuine observation.
 *
 * ## Why every note here is PLAIN
 *
 * `Note.toUi()` sends an HTML-format body through `HtmlCompat.fromHtml`, which is `android.text.Html`
 * underneath — a stubbed class in a JVM unit test that throws rather than returning a value. PLAIN
 * bodies take the other branch and are passed through verbatim, so the whole list pipeline runs
 * for real. Covering the HTML branch needs Robolectric or an instrumented test; it is not covered
 * here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesListViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val notesRepo = FakeNotesRepository()
    private val settingsRepo = FakeSettingsRepository()

    private fun note(
        id: String,
        title: String = "",
        content: String = "",
        isPinned: Boolean = false,
        isFavorite: Boolean = false,
        folderId: String? = null,
        checklist: String = "",
        updatedAt: Long = 0L,
    ) = Note(
        id = id,
        title = title,
        content = content,
        // See the class comment: PLAIN keeps HtmlCompat out of a JVM unit test.
        contentFormat = NoteContentFormat.PLAIN,
        checklist = checklist,
        isPinned = isPinned,
        isFavorite = isFavorite,
        folderId = folderId,
        updatedAt = updatedAt,
    )

    private fun viewModel() = NotesListViewModel(notesRepo, settingsRepo)

    /** Notes visible under the order the settings repository currently reports. */
    private fun currentNotes(vararg notes: Note) {
        notesRepo.notesFor(settingsRepo.notesSortOrder.value).value = notes.toList()
    }

    private fun TestScope.collectEvents(vm: NotesListViewModel): List<NotesListEvent> {
        val received = mutableListOf<NotesListEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.events.collect { received += it }
        }
        return received
    }

    /**
     * Runs the test dispatcher and a little real time, alternately, until [predicate] holds.
     * Fails with [reason] rather than hanging if the pipeline never gets there.
     */
    private fun TestScope.awaitState(reason: String, timeoutMillis: Long = 5_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (true) {
            advanceUntilIdle()
            if (predicate()) return
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for: $reason")
            Thread.sleep(2)
        }
    }

    /** Lets the pipelines run for [millis] of REAL time, pumping the test dispatcher throughout. */
    private fun TestScope.pumpRealMillis(millis: Long) {
        val deadline = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < deadline) {
            advanceUntilIdle()
            Thread.sleep(2)
        }
        advanceUntilIdle()
    }

    // ============================================================================================
    // The list itself
    // ============================================================================================

    @Test
    fun `notes arrive split into the pinned pager and the recent grid`() = runTest(mainDispatcherRule.dispatcher) {
        currentNotes(
            note("a", title = "Alpha", isPinned = true),
            note("b", title = "Beta"),
            note("c", title = "Gamma", isPinned = true),
        )
        val vm = viewModel()
        awaitState("the first list emission") { !vm.state.value.isLoading }

        assertEquals(listOf("a", "c"), vm.state.value.pinnedPreviews.map { it.id })
        assertEquals(listOf("b"), vm.state.value.notePreviews.map { it.id })
    }

    @Test
    fun `folder chips carry the number of notes filed in each`() = runTest(mainDispatcherRule.dispatcher) {
        notesRepo.folders.value = listOf(
            Folder(id = "work", name = "Work"),
            Folder(id = "home", name = "Home"),
        )
        currentNotes(
            note("a", folderId = "work"),
            note("b", folderId = "work"),
            note("c", folderId = null),
        )
        val vm = viewModel()
        awaitState("folder previews") { vm.state.value.folderPreviews.size == 2 }

        assertEquals(
            listOf("Work" to 2, "Home" to 0),
            vm.state.value.folderPreviews.map { it.name to it.notesAmount },
        )
    }

    @Test
    fun `the stats line counts notes and pins, and disappears when the setting is off`() =
        runTest(mainDispatcherRule.dispatcher) {
            currentNotes(note("a", isPinned = true), note("b"), note("c"))
            val vm = viewModel()
            awaitState("stats line") { vm.state.value.statsLine != null }
            assertEquals("3 notes · 1 pinned", vm.state.value.statsLine)

            settingsRepo.headerSettings.value = HeaderSettings(showStats = false)
            awaitState("stats line cleared") { vm.state.value.statsLine == null }
        }

    @Test
    fun `the header line is dropped entirely when both of its sources are switched off`() =
        runTest(mainDispatcherRule.dispatcher) {
            settingsRepo.headerSettings.value =
                HeaderSettings(showGreetings = false, showDailyPhrases = false)
            val vm = viewModel()
            advanceUntilIdle()

            // The screen falls back to the small "Mañana" wordmark when there is no line at all.
            assertNull(vm.state.value.headerLine)
        }

    @Test
    fun `with only greetings enabled the header line is always a greeting`() =
        runTest(mainDispatcherRule.dispatcher) {
            // The two sources are picked between at random, so the only deterministic assertion is
            // the one where a single source is enabled.
            settingsRepo.headerSettings.value =
                HeaderSettings(showGreetings = true, showDailyPhrases = false)
            val vm = viewModel()
            advanceUntilIdle()

            val line = vm.state.value.headerLine
            assertNotNull(line)
            assertEquals("Good", line!!.prefix)
            assertTrue(
                "greeting was '${line.accent}'",
                line.accent in setOf("morning.", "afternoon.", "evening.", "night."),
            )
        }

    // ============================================================================================
    // Folder filtering
    // ============================================================================================

    @Test
    fun `tapping a folder chip filters the list down to that folder`() = runTest(mainDispatcherRule.dispatcher) {
        notesRepo.folders.value = listOf(Folder(id = "work", name = "Work"))
        currentNotes(
            note("a", folderId = "work", isPinned = true),
            note("b", folderId = "work"),
            note("c", folderId = null, isPinned = true),
            note("d", folderId = null),
        )
        val vm = viewModel()
        awaitState("the first list emission") { !vm.state.value.isLoading }

        vm.onIntent(NotesListIntent.FolderClicked("work"))
        advanceUntilIdle()

        // The filter applies to both halves of the screen; a pinned note outside the folder must
        // not keep floating at the top of a filtered view.
        assertEquals("work", vm.state.value.selectedFolderId)
        assertEquals(listOf("a"), vm.state.value.pinnedPreviews.map { it.id })
        assertEquals(listOf("b"), vm.state.value.notePreviews.map { it.id })
    }

    @Test
    fun `tapping the active folder chip again clears the filter`() = runTest(mainDispatcherRule.dispatcher) {
        notesRepo.folders.value = listOf(Folder(id = "work", name = "Work"))
        currentNotes(note("a", folderId = "work"), note("b", folderId = null))
        val vm = viewModel()
        awaitState("the first list emission") { !vm.state.value.isLoading }

        vm.onIntent(NotesListIntent.FolderClicked("work"))
        vm.onIntent(NotesListIntent.FolderClicked("work"))
        advanceUntilIdle()

        assertNull(vm.state.value.selectedFolderId)
        assertEquals(listOf("a", "b"), vm.state.value.notePreviews.map { it.id })
    }

    @Test
    fun `deleting the folder that is being filtered on falls back to All at once`() =
        runTest(mainDispatcherRule.dispatcher) {
            notesRepo.folders.value = listOf(Folder(id = "work", name = "Work"))
            currentNotes(note("a", folderId = "work"), note("b", folderId = null))
            val vm = viewModel()
            awaitState("the first list emission") { !vm.state.value.isLoading }
            vm.onIntent(NotesListIntent.FolderClicked("work"))
            advanceUntilIdle()

            vm.onIntent(NotesListIntent.DeleteFolder("work"))
            advanceUntilIdle()

            // Reset now rather than waiting for the unfiling to come back through the notes flow:
            // a chip selection pointing at a folder that no longer exists shows an empty list with
            // no visible way out of it.
            assertNull(vm.state.value.selectedFolderId)
            assertEquals(listOf("a", "b"), vm.state.value.notePreviews.map { it.id })
            assertEquals(listOf("deleteFolder(work)"), notesRepo.callsNamed("deleteFolder"))
        }

    @Test
    fun `deleting some other folder leaves the active filter alone`() = runTest(mainDispatcherRule.dispatcher) {
        notesRepo.folders.value =
            listOf(Folder(id = "work", name = "Work"), Folder(id = "home", name = "Home"))
        currentNotes(note("a", folderId = "work"), note("b", folderId = "home"))
        val vm = viewModel()
        awaitState("the first list emission") { !vm.state.value.isLoading }
        vm.onIntent(NotesListIntent.FolderClicked("work"))
        advanceUntilIdle()

        vm.onIntent(NotesListIntent.DeleteFolder("home"))
        advanceUntilIdle()

        assertEquals("work", vm.state.value.selectedFolderId)
        assertEquals(listOf("a"), vm.state.value.notePreviews.map { it.id })
    }

    // ============================================================================================
    // Sort order
    // ============================================================================================

    @Test
    fun `changing the sort order re-subscribes the notes query rather than re-sorting in memory`() =
        runTest(mainDispatcherRule.dispatcher) {
            // Each order is a separate verified @Query in the DAO, so the ordering lives in SQL and
            // a change has to drop the old subscription and open a new one. The two orders are given
            // deliberately different content here, which only a genuine re-subscription can deliver.
            notesRepo.notesFor(NotesSortOrder.RECENTLY_EDITED).value =
                listOf(note("recent1"), note("recent2"))
            notesRepo.notesFor(NotesSortOrder.TITLE_ASC).value =
                listOf(note("alpha"), note("beta"))

            val vm = viewModel()
            awaitState("the first list emission") { !vm.state.value.isLoading }
            assertEquals(listOf("recent1", "recent2"), vm.state.value.notePreviews.map { it.id })
            assertEquals(listOf(NotesSortOrder.RECENTLY_EDITED), notesRepo.ordersSubscribed)

            settingsRepo.notesSortOrder.value = NotesSortOrder.TITLE_ASC
            awaitState("the re-subscribed list") {
                vm.state.value.notePreviews.map { it.id } == listOf("alpha", "beta")
            }

            assertEquals(
                listOf(NotesSortOrder.RECENTLY_EDITED, NotesSortOrder.TITLE_ASC),
                notesRepo.ordersSubscribed,
            )
            // The picker echoes the order the list was actually built from, carried alongside the
            // notes rather than read from a second flow that could disagree with them.
            assertEquals(NotesSortOrder.TITLE_ASC, vm.state.value.sortOrder)
        }

    @Test
    fun `selecting a sort order only persists it and never guesses at the result`() =
        runTest(mainDispatcherRule.dispatcher) {
            currentNotes(note("a"))
            val vm = viewModel()
            awaitState("the first list emission") { !vm.state.value.isLoading }

            vm.onIntent(NotesListIntent.SortOrderSelected(NotesSortOrder.NEWEST_CREATED))
            advanceUntilIdle()

            assertEquals(listOf("setNotesSortOrder(newest_created)"), settingsRepo.calls)
            awaitState("the order to come back through settings") {
                vm.state.value.sortOrder == NotesSortOrder.NEWEST_CREATED
            }
        }

    // ============================================================================================
    // Search
    // ============================================================================================

    @Test
    fun `the search field echoes every keystroke but the query itself is debounced`() =
        runTest(mainDispatcherRule.dispatcher) {
            currentNotes(note("a", title = "Milk run"), note("b", title = "Taxes"))
            val vm = viewModel()
            awaitState("the first list emission") { !vm.state.value.isLoading }

            vm.onIntent(NotesListIntent.SearchQueryChanged("mil"))
            advanceUntilIdle()
            // The field is state, so it has to move on the keystroke or typing feels dropped.
            assertEquals("mil", vm.state.value.searchQuery)

            // A fraction of the 300 ms window, but two orders of magnitude more than the pipeline
            // needs once it is let go — so finding no results here is a real observation, not luck.
            pumpRealMillis(120)
            assertEquals("", vm.state.value.searchResultsQuery)
            assertEquals(emptyList<Any>(), vm.state.value.searchResults)

            awaitState("the debounced search to settle") { vm.state.value.searchResultsQuery == "mil" }
            assertEquals(listOf("a"), vm.state.value.searchResults.map { it.preview.id })
        }

    @Test
    fun `search spans every folder, ignoring the chip the list is filtered by`() =
        runTest(mainDispatcherRule.dispatcher) {
            notesRepo.folders.value = listOf(Folder(id = "work", name = "Work"))
            currentNotes(
                note("a", title = "Milk run", folderId = "work"),
                note("b", title = "Milk order", folderId = null),
            )
            val vm = viewModel()
            awaitState("the first list emission") { !vm.state.value.isLoading }
            vm.onIntent(NotesListIntent.FolderClicked("work"))
            advanceUntilIdle()

            vm.onIntent(NotesListIntent.SearchQueryChanged("milk"))
            awaitState("results") { vm.state.value.searchResultsQuery == "milk" }

            // The chip is a view filter on the list; search reads the unfiltered previews.
            assertEquals(listOf("a"), vm.state.value.notePreviews.map { it.id })
            assertEquals(setOf("a", "b"), vm.state.value.searchResults.map { it.preview.id }.toSet())
        }

    @Test
    fun `an edit elsewhere re-runs the open query without waiting out another debounce`() =
        runTest(mainDispatcherRule.dispatcher) {
            currentNotes(note("a", title = "Milk run"))
            val vm = viewModel()
            awaitState("the first list emission") { !vm.state.value.isLoading }

            vm.onIntent(NotesListIntent.SearchQueryChanged("milk"))
            awaitState("results") { vm.state.value.searchResults.size == 1 }

            // A second note that matches arrives from the database while the results are on screen.
            currentNotes(note("a", title = "Milk run"), note("b", title = "Milk order"))
            awaitState("the results to pick up the new note") { vm.state.value.searchResults.size == 2 }
        }

    @Test
    fun `leaving the Search tab drops the query and its results`() = runTest(mainDispatcherRule.dispatcher) {
        currentNotes(note("a", title = "Milk run"))
        val vm = viewModel()
        awaitState("the first list emission") { !vm.state.value.isLoading }
        vm.onIntent(NotesListIntent.SearchClicked)
        vm.onIntent(NotesListIntent.SearchQueryChanged("milk"))
        awaitState("results") { vm.state.value.searchResults.size == 1 }

        vm.onIntent(NotesListIntent.AllNotesClicked)
        advanceUntilIdle()

        assertEquals(BottomBarItem.ALL_NOTES, vm.state.value.selectedBottomBarItem)
        assertEquals("", vm.state.value.searchQuery)
        assertEquals("", vm.state.value.searchResultsQuery)
        assertEquals(emptyList<Any>(), vm.state.value.searchResults)

        // ...and the pipeline is genuinely idle afterwards, rather than re-matching on every note
        // change while a different tab is on screen.
        currentNotes(note("a", title = "Milk run"), note("b", title = "Milk order"))
        pumpRealMillis(400)
        assertEquals(emptyList<Any>(), vm.state.value.searchResults)
    }

    @Test
    fun `opening a note from a result keeps the query, because that is a navigation not a tab switch`() =
        runTest(mainDispatcherRule.dispatcher) {
            currentNotes(note("a", title = "Milk run"))
            val vm = viewModel()
            val events = collectEvents(vm)
            awaitState("the first list emission") { !vm.state.value.isLoading }
            vm.onIntent(NotesListIntent.SearchClicked)
            vm.onIntent(NotesListIntent.SearchQueryChanged("milk"))
            awaitState("results") { vm.state.value.searchResults.size == 1 }

            vm.onIntent(NotesListIntent.NoteClicked("a"))
            advanceUntilIdle()

            assertEquals(listOf(NotesListEvent.NavigateToNote("a", isNew = false)), events)
            assertEquals("milk", vm.state.value.searchQuery)
            assertEquals(1, vm.state.value.searchResults.size)
            assertEquals(BottomBarItem.SEARCH, vm.state.value.selectedBottomBarItem)
        }

    // ============================================================================================
    // Navigation and writes
    // ============================================================================================

    @Test
    fun `the plus button inserts a blank row and is the only route that sets isNew`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = viewModel()
            val events = collectEvents(vm)
            advanceUntilIdle()

            vm.onIntent(NotesListIntent.AddNoteClicked)
            advanceUntilIdle()

            val inserted = notesRepo.savedNotes.single()
            assertEquals("", inserted.title)
            assertEquals("", inserted.content)
            // isNew is the editor's sole licence to auto-discard the row on back, so it may only be
            // set for an id this screen minted itself moments ago.
            assertEquals(listOf(NotesListEvent.NavigateToNote(inserted.id, isNew = true)), events)
        }

    @Test
    fun `tapping an existing note navigates without isNew`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        val events = collectEvents(vm)
        advanceUntilIdle()

        vm.onIntent(NotesListIntent.NoteClicked("existing"))
        advanceUntilIdle()

        // Setting isNew here would make any note deletable by emptying it and backing out.
        assertEquals(listOf(NotesListEvent.NavigateToNote("existing", isNew = false)), events)
        assertEquals(0, notesRepo.savedNotes.size)
    }

    @Test
    fun `the Profile tab navigates without lighting up as a selected tab`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = viewModel()
            val events = collectEvents(vm)
            advanceUntilIdle()

            vm.onIntent(NotesListIntent.ProfileClicked)
            advanceUntilIdle()

            assertEquals(listOf(NotesListEvent.NavigateToProfile), events)
            // Settings is pushed on top of this screen; marking it selected would leave the person
            // icon lit once the user backs out and is looking at the list again.
            assertEquals(BottomBarItem.ALL_NOTES, vm.state.value.selectedBottomBarItem)
        }

    @Test
    fun `Trash is reached by an event, not by a bottom-bar slot`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        val events = collectEvents(vm)
        advanceUntilIdle()

        vm.onIntent(NotesListIntent.TrashClicked)
        advanceUntilIdle()

        assertEquals(listOf(NotesListEvent.NavigateToTrash), events)
        assertEquals(BottomBarItem.ALL_NOTES, vm.state.value.selectedBottomBarItem)
    }

    @Test
    fun `creating a folder normalizes the name and refuses one that is only whitespace`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()

            vm.onIntent(NotesListIntent.CreateFolder(name = "   ", colorArgb = null))
            vm.onIntent(NotesListIntent.CreateFolder(name = "  Work  ", colorArgb = 0xFF00FF00))
            advanceUntilIdle()

            val folder = notesRepo.savedFolders.single()
            assertEquals("Work", folder.name)
            assertEquals(0xFF00FF00, folder.colorArgb)
        }

    @Test
    fun `renaming a folder keeps its id and refuses a blank name`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onIntent(NotesListIntent.UpdateFolder(id = "work", name = " ", colorArgb = null))
        vm.onIntent(NotesListIntent.UpdateFolder(id = "work", name = " Office ", colorArgb = 1L))
        advanceUntilIdle()

        val folder = notesRepo.savedFolders.single()
        assertEquals("work", folder.id)
        assertEquals("Office", folder.name)
    }

    @Test
    fun `moving a note into a folder goes straight to the targeted update`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()

            vm.onIntent(NotesListIntent.MoveNoteToFolder(noteId = "a", folderId = "work"))
            vm.onIntent(NotesListIntent.MoveNoteToFolder(noteId = "a", folderId = null))
            advanceUntilIdle()

            assertEquals(
                listOf("setNoteFolder(a, work)", "setNoteFolder(a, null)"),
                notesRepo.callsNamed("setNoteFolder"),
            )
            // Never an upsert: a move is not an edit and must not restamp updatedAt.
            assertEquals(0, notesRepo.upsertCount())
        }
}
