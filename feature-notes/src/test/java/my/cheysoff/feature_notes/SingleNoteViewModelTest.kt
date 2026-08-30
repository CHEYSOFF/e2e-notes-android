package my.cheysoff.feature_notes

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import my.cheysoff.core_domain.model.Folder
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NoteContentFormat
import my.cheysoff.feature_notes.model.single.SingleNoteIntent
import my.cheysoff.feature_notes.ui.single.SingleNoteEvent
import my.cheysoff.feature_notes.ui.single.SingleNoteViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The stateful half of [SingleNoteViewModel]: what it writes, when it writes it, and what it does
 * with the row Room hands back afterwards.
 *
 * `SingleNoteMergeTest` covers the pure functions this class is built out of (`mergeIncomingNote`,
 * `mergeChecklist`, `isDiscardableOnOpen`). Those were always testable. What was not, until
 * `kotlinx-coroutines-test` became resolvable, is everything that needs a clock and a dispatcher:
 * the 300 ms autosave debounce, the mutex that serializes writes, the baseline that has to move
 * with every one of them, and the latch that decides whether backing out of a note DESTROYS it.
 * Every data-loss bug this file guards against lives in that half.
 *
 * Conventions used throughout:
 *  - the repository never echoes a write back by itself. Room does, and the test says exactly when
 *    and with exactly which row, because "the echo arrived while another write was still queued"
 *    is the shape of the bugs being pinned here.
 *  - `advanceUntilIdle()` means "let everything that can run, run"; `runCurrent()` means "run only
 *    what is scheduled for right now", which is how an un-debounced write is told from a debounced
 *    one; `advanceTimeBy(n)` runs tasks scheduled strictly before `now + n`, so a task scheduled at
 *    exactly +300 needs `advanceTimeBy(301)`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SingleNoteViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repo = FakeNotesRepository()

    private companion object {
        const val NOTE_ID = "n1"
    }

    private fun note(
        id: String = NOTE_ID,
        title: String = "",
        content: String = "",
        contentFormat: NoteContentFormat = NoteContentFormat.PLAIN,
        checklist: String = "",
        isPinned: Boolean = false,
        isFavorite: Boolean = false,
        folderId: String? = null,
        updatedAt: Long = 1_000L,
    ) = Note(
        id = id,
        title = title,
        content = content,
        contentFormat = contentFormat,
        checklist = checklist,
        isPinned = isPinned,
        isFavorite = isFavorite,
        folderId = folderId,
        createdAt = 1_000L,
        updatedAt = updatedAt,
    )

    /**
     * Builds the ViewModel the way the nav graph does. [isNew] is written into the handle only when
     * true, because every route other than the "+" button omits the argument entirely — and the
     * difference between "absent" and "false" is worth exercising as it actually occurs.
     */
    private fun viewModel(noteId: String? = NOTE_ID, isNew: Boolean = false): SingleNoteViewModel {
        val map = buildMap<String, Any> {
            if (noteId != null) put("noteId", noteId)
            if (isNew) put("isNew", true)
        }
        return SingleNoteViewModel(repo, SavedStateHandle(map))
    }

    /**
     * Starts draining [SingleNoteViewModel.events] into the returned (live) list.
     *
     * The collector runs on an [UnconfinedTestDispatcher] sharing the test's scheduler, which is
     * the recipe kotlinx-coroutines-test documents for exactly this: an unconfined coroutine begins
     * executing at the point it is launched, so the collector is already subscribed to the channel
     * before the test sends its first intent. Launched on the standard dispatcher it would instead
     * sit in the queue, and `runTest` does not schedule `backgroundScope` work with the same
     * eagerness it gives the test body — the events then never arrive.
     *
     * It stays on [TestScope.backgroundScope] so the collection is cancelled when the test ends
     * rather than keeping `runTest` waiting on a flow that never completes.
     */
    private fun TestScope.collectEvents(vm: SingleNoteViewModel): List<SingleNoteEvent> {
        val received = mutableListOf<SingleNoteEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.events.collect { received += it }
        }
        return received
    }

    // ============================================================================================
    // Loading
    // ============================================================================================

    @Test
    fun `the first row seeds every editor field and flips isLoaded`() = runTest(mainDispatcherRule.dispatcher) {
        repo.noteById.value = note(
            title = "Trip",
            content = "<p>body</p>",
            contentFormat = NoteContentFormat.HTML,
            checklist = "0milk\n1eggs",
            isPinned = true,
            isFavorite = true,
            folderId = "f1",
            updatedAt = 77L,
        )
        val vm = viewModel()
        advanceUntilIdle()

        val s = vm.state.value
        assertTrue(s.isLoaded)
        assertEquals("Trip", s.title)
        assertEquals("<p>body</p>", s.content)
        assertEquals(NoteContentFormat.HTML, s.contentFormat)
        assertEquals(listOf("milk" to false, "eggs" to true), s.checklist.map { it.text to it.isDone })
        assertTrue(s.isPinned)
        assertTrue(s.isFavorite)
        assertEquals("f1", s.folderId)
        assertEquals(77L, s.updatedAt)
    }

    @Test
    fun `the folder list is collected independently of the note`() = runTest(mainDispatcherRule.dispatcher) {
        // No note row at all: the folder picker still has to be populated, because a screen whose
        // note never loads is a screen the user can still back out of.
        repo.folders.value = listOf(Folder(id = "f1", name = "Work"), Folder(id = "f2", name = "Home"))
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(listOf("Work", "Home"), vm.state.value.folders.map { it.name })
        assertFalse(vm.state.value.isLoaded)
    }

    // ============================================================================================
    // The baseline moves with every write
    //
    // The baseline is "what the stored row is known to hold". It is what tells an echo of our own
    // save apart from someone else's change, and it is what `hasUnsavedContent()` compares against.
    // It is private, so these tests reach it the only honest way: through behaviour that can only
    // be right if the baseline moved.
    // ============================================================================================

    @Test
    fun `an autosave folds its own write into the baseline, so backing out does not rewrite the row`() =
        runTest(mainDispatcherRule.dispatcher) {
            repo.noteById.value = note(title = "a")
            val vm = viewModel()
            advanceUntilIdle()

            vm.onIntent(SingleNoteIntent.TitleChanged("b"))
            advanceUntilIdle()
            assertEquals(1, repo.upsertCount())

            // Room has NOT echoed the write back yet — which is the whole point. If the write did
            // not move the baseline itself, the only thing that could is that echo, and until it
            // arrives the editor would believe "b" is unsaved and upsert it a second time. That
            // second upsert is not harmless: it restamps updatedAt and reorders the notes list for
            // a note the user only read on the way out.
            vm.onIntent(SingleNoteIntent.BackClicked)
            advanceUntilIdle()

            assertEquals(1, repo.upsertCount())
        }

    @Test
    fun `backing out of a note that was only read writes nothing`() = runTest(mainDispatcherRule.dispatcher) {
        repo.noteById.value = note(title = "a", content = "body")
        val vm = viewModel()
        val events = collectEvents(vm)
        advanceUntilIdle()

        vm.onIntent(SingleNoteIntent.BackClicked)
        advanceUntilIdle()

        assertEquals(0, repo.upsertCount())
        assertEquals(listOf(SingleNoteEvent.NavigateBack), events)
    }

    @Test
    fun `a body whose format marker changed but whose bytes did not still counts as unsaved`() =
        runTest(mainDispatcherRule.dispatcher) {
            // A legacy PLAIN row whose bytes happen to survive the rich-text round trip. The editor
            // reports the same characters back, so only `contentFormat` moves — and the row must
            // still be rewritten, or it keeps a PLAIN marker for a body the editor now treats as
            // HTML and is re-parsed with the wrong reader on the next open.
            repo.noteById.value = note(content = "hello", contentFormat = NoteContentFormat.PLAIN)
            val vm = viewModel()
            val events = collectEvents(vm)
            advanceUntilIdle()

            vm.onIntent(SingleNoteIntent.ContentChanged("hello"))
            assertEquals(NoteContentFormat.HTML, vm.state.value.contentFormat)

            vm.onIntent(SingleNoteIntent.BackClicked)
            // runCurrent, NOT advanceUntilIdle. The ContentChanged above already queued a
            // debounced autosave, so advancing the clock would produce the write whether or not
            // back flushed anything — and the assertion below would then hold for a reason that
            // has nothing to do with hasUnsavedContent(). (Written that way first; the mutation
            // that strips the contentFormat comparison out of hasUnsavedContent() survived it.)
            //
            // Holding the clock still leaves exactly one thing that can produce a write here: the
            // immediate flush back is supposed to perform before it navigates. Which is also the
            // real requirement — popping the screen cancels viewModelScope, so a row left to the
            // debounce is a row that keeps a PLAIN marker for a body the editor now treats as
            // HTML, and gets re-parsed with the wrong reader on the next open.
            runCurrent()

            assertEquals(1, repo.upsertCount())
            assertEquals(NoteContentFormat.HTML, repo.savedNotes.single().contentFormat)
            assertEquals(listOf(SingleNoteEvent.NavigateBack), events)
        }

    // --- writeMeta must claim ONLY the field its UPDATE actually wrote ---------------------------
    //
    // Each of the three tests below holds one targeted UPDATE open, lets its sibling finish, and
    // then delivers the row the database genuinely holds at that instant — i.e. carrying the
    // finished field's new value and the in-flight field's OLD one.
    //
    // If the finished write's `record` lambda claims the in-flight field too, the baseline now says
    // "the database holds the new value" for a column the database has not been told about. The
    // very next emission reports the old value, the merge sees local == baseline and classifies it
    // as an external change, and adopts it — silently undoing the toggle the user just made.
    //
    // Note there is no matching test for the opposite mistake (a `record` that claims too LITTLE).
    // That one has no symptom: the merge self-heals, because a baseline that lags behind makes
    // `local != baseline`, which keeps the local value and then adopts the incoming row as the new
    // baseline anyway. Over-claiming is the only direction that loses data, so it is the only
    // direction with a test.

    @Test
    fun `an in-flight pin write is not reverted by the echo of a finished favorite write`() =
        runTest(mainDispatcherRule.dispatcher) {
            repo.noteById.value = note(isPinned = false, isFavorite = false)
            val vm = viewModel()
            advanceUntilIdle()

            repo.gate("setNotePinned")
            vm.onIntent(SingleNoteIntent.ToggleFavorite) // finishes
            vm.onIntent(SingleNoteIntent.TogglePin)      // parks inside its UPDATE
            advanceUntilIdle()

            assertEquals(listOf("setNoteFavorite($NOTE_ID, true)"), repo.callsNamed("setNoteFavorite"))
            assertEquals(listOf("setNotePinned($NOTE_ID, true)"), repo.callsNamed("setNotePinned"))

            // The row as the database actually stands: favorite committed, pin not yet.
            repo.noteById.value = note(isPinned = false, isFavorite = true, updatedAt = 2_000L)
            advanceUntilIdle()

            assertTrue("the queued pin must survive the favorite write's echo", vm.state.value.isPinned)
            assertTrue(vm.state.value.isFavorite)

            repo.releaseAll()
            advanceUntilIdle()
        }

    @Test
    fun `an in-flight favorite write is not reverted by the echo of a finished pin write`() =
        runTest(mainDispatcherRule.dispatcher) {
            repo.noteById.value = note(isPinned = false, isFavorite = false)
            val vm = viewModel()
            advanceUntilIdle()

            repo.gate("setNoteFavorite")
            vm.onIntent(SingleNoteIntent.TogglePin)      // finishes
            vm.onIntent(SingleNoteIntent.ToggleFavorite) // parks inside its UPDATE
            advanceUntilIdle()

            repo.noteById.value = note(isPinned = true, isFavorite = false, updatedAt = 2_000L)
            advanceUntilIdle()

            assertTrue("the queued favorite must survive the pin write's echo", vm.state.value.isFavorite)
            assertTrue(vm.state.value.isPinned)

            repo.releaseAll()
            advanceUntilIdle()
        }

    @Test
    fun `an in-flight folder write is not reverted by the echo of a finished favorite write`() =
        runTest(mainDispatcherRule.dispatcher) {
            repo.noteById.value = note(folderId = null, isFavorite = false)
            val vm = viewModel()
            advanceUntilIdle()

            repo.gate("setNoteFolder")
            vm.onIntent(SingleNoteIntent.ToggleFavorite)  // finishes
            vm.onIntent(SingleNoteIntent.SetFolder("f1")) // parks inside its UPDATE
            advanceUntilIdle()

            repo.noteById.value = note(folderId = null, isFavorite = true, updatedAt = 2_000L)
            advanceUntilIdle()

            assertEquals("f1", vm.state.value.folderId)
            assertTrue(vm.state.value.isFavorite)

            repo.releaseAll()
            advanceUntilIdle()
        }

    // ============================================================================================
    // createdBlankNote — the latch in front of a hard, undoable DELETE
    // ============================================================================================

    @Test
    fun `a note opened as new and left blank is purged on back`() = runTest(mainDispatcherRule.dispatcher) {
        repo.noteById.value = note()
        val vm = viewModel(isNew = true)
        val events = collectEvents(vm)
        advanceUntilIdle()

        vm.onIntent(SingleNoteIntent.BackClicked)
        advanceUntilIdle()

        assertEquals(listOf("purgeNote($NOTE_ID)"), repo.callsNamed("purgeNote"))
        // A purge, never a soft delete: an abandoned "+" tap must not land in Trash for the user
        // to clear out by hand.
        assertEquals(emptyList<String>(), repo.callsNamed("deleteNote"))
        assertEquals(0, repo.upsertCount())
        assertEquals(listOf(SingleNoteEvent.NavigateBack), events)
    }

    @Test
    fun `a note opened as new and typed into is saved, not purged`() = runTest(mainDispatcherRule.dispatcher) {
        repo.noteById.value = note()
        val vm = viewModel(isNew = true)
        advanceUntilIdle()

        vm.onIntent(SingleNoteIntent.TitleChanged("kept"))
        vm.onIntent(SingleNoteIntent.BackClicked)
        advanceUntilIdle()

        assertEquals(emptyList<String>(), repo.callsNamed("purgeNote"))
        assertEquals("kept", repo.savedNotes.last().title)
    }

    @Test
    fun `a blank note NOT opened as new is never purged, however empty it is`() =
        runTest(mainDispatcherRule.dispatcher) {
            // An existing note the user emptied out looks identical to a brand-new one on open.
            // The nav argument is the only thing separating them, and getting this wrong destroys
            // a real note with no undo behind it.
            repo.noteById.value = note()
            val vm = viewModel(isNew = false)
            advanceUntilIdle()

            vm.onIntent(SingleNoteIntent.BackClicked)
            advanceUntilIdle()

            assertEquals(emptyList<String>(), repo.callsNamed("purgeNote"))
            assertEquals(0, repo.upsertCount())
        }

    @Test
    fun `the latch survives a ViewModel rebuild - isNew comes back, but the row is no longer blank`() =
        runTest(mainDispatcherRule.dispatcher) {
            // Rotation / process death rebuilds the ViewModel for the same back-stack entry, and
            // SavedStateHandle hands `isNew = true` back verbatim however long ago the "+" was
            // tapped. By then the user may have typed, so the nav argument alone is not enough —
            // the FIRST row this instance sees still has to be blank.
            repo.noteById.value = note(title = "typed before rotation")
            val vm = viewModel(isNew = true)
            advanceUntilIdle()

            // ...and now the user selects all and deletes, then backs out. The note is blank in
            // every field, and `isNew` is still true, and it must still survive.
            vm.onIntent(SingleNoteIntent.TitleChanged(""))
            vm.onIntent(SingleNoteIntent.BackClicked)
            advanceUntilIdle()

            assertEquals(emptyList<String>(), repo.callsNamed("purgeNote"))
            assertEquals("", repo.savedNotes.last().title)
        }

    @Test
    fun `the latch is decided from the first row only and is not re-evaluated on later emissions`() =
        runTest(mainDispatcherRule.dispatcher) {
            repo.noteById.value = note(title = "existing")
            val vm = viewModel(isNew = true)
            advanceUntilIdle()

            // A later emission that happens to be blank must not re-open the discard window: by
            // then the screen has been live for a while and "blank right now" says nothing about
            // whether this row was created for this screen.
            repo.noteById.value = note(title = "", updatedAt = 2_000L)
            advanceUntilIdle()
            assertEquals("", vm.state.value.title)

            vm.onIntent(SingleNoteIntent.BackClicked)
            advanceUntilIdle()

            assertEquals(emptyList<String>(), repo.callsNamed("purgeNote"))
        }

    @Test
    fun `a note opened as new, typed into and then emptied again is still discarded`() =
        runTest(mainDispatcherRule.dispatcher) {
            // The latch stays true for the whole session, so a "+" tap the user changed their mind
            // about does not leave an empty card at the top of the newest-first list. This is the
            // deliberate flip side of the test above; both behaviours follow from latching on the
            // first row.
            repo.noteById.value = note()
            val vm = viewModel(isNew = true)
            advanceUntilIdle()

            vm.onIntent(SingleNoteIntent.TitleChanged("oops"))
            advanceUntilIdle()
            vm.onIntent(SingleNoteIntent.TitleChanged(""))
            vm.onIntent(SingleNoteIntent.BackClicked)
            advanceUntilIdle()

            assertEquals(listOf("purgeNote($NOTE_ID)"), repo.callsNamed("purgeNote"))
        }

    // ============================================================================================
    // Autosave: debounce, cancellation, flushing
    // ============================================================================================

    @Test
    fun `a typed edit is not written until the debounce window has elapsed`() =
        runTest(mainDispatcherRule.dispatcher) {
            repo.noteById.value = note(title = "a")
            val vm = viewModel()
            advanceUntilIdle()

            vm.onIntent(SingleNoteIntent.TitleChanged("ab"))
            advanceTimeBy(300) // strictly before +300: the save is scheduled AT +300
            assertEquals(0, repo.upsertCount())

            advanceTimeBy(1)
            assertEquals(1, repo.upsertCount())
            assertEquals("ab", repo.savedNotes.single().title)
        }

    @Test
    fun `a delayed save is cancelled by a newer edit and never overwrites it`() =
        runTest(mainDispatcherRule.dispatcher) {
            repo.noteById.value = note(title = "a")
            val vm = viewModel()
            advanceUntilIdle()

            vm.onIntent(SingleNoteIntent.TitleChanged("ab"))
            advanceTimeBy(200) // still inside the first window
            vm.onIntent(SingleNoteIntent.TitleChanged("abc"))
            advanceUntilIdle()

            // Exactly one write, carrying the newest text. Two things are being pinned at once:
            // the older job is cancelled rather than left to fire, and the surviving job reads
            // state AFTER its delay rather than capturing it before.
            assertEquals(1, repo.upsertCount())
            assertEquals("abc", repo.savedNotes.single().title)
        }

    @Test
    fun `back flushes a still-debouncing edit exactly once before navigating`() =
        runTest(mainDispatcherRule.dispatcher) {
            repo.noteById.value = note(title = "a")
            val vm = viewModel()
            val events = collectEvents(vm)
            advanceUntilIdle()

            vm.onIntent(SingleNoteIntent.TitleChanged("ab"))
            advanceTimeBy(50) // nowhere near the 300 ms window
            vm.onIntent(SingleNoteIntent.BackClicked)
            advanceUntilIdle()

            assertEquals(1, repo.upsertCount())
            assertEquals("ab", repo.savedNotes.single().title)
            assertEquals(listOf(SingleNoteEvent.NavigateBack), events)
        }

    @Test
    fun `back waits for an in-flight metadata write before it navigates`() =
        runTest(mainDispatcherRule.dispatcher) {
            repo.noteById.value = note()
            val vm = viewModel()
            val events = collectEvents(vm)
            advanceUntilIdle()

            repo.gate("setNoteFolder")
            vm.onIntent(SingleNoteIntent.SetFolder("f1"))
            advanceUntilIdle()
            assertEquals(listOf("setNoteFolder($NOTE_ID, f1)"), repo.callsNamed("setNoteFolder"))

            vm.onIntent(SingleNoteIntent.BackClicked)
            advanceUntilIdle()

            // Navigating now would pop the screen, cancel viewModelScope and drop the UPDATE that
            // is still running — the folder move would be lost with no sign of it.
            assertEquals(emptyList<SingleNoteEvent>(), events)

            repo.release("setNoteFolder")
            advanceUntilIdle()
            assertEquals(listOf(SingleNoteEvent.NavigateBack), events)
        }

    @Test
    fun `deleting the note cancels the pending autosave instead of restamping it`() =
        runTest(mainDispatcherRule.dispatcher) {
            repo.noteById.value = note(title = "a")
            val vm = viewModel()
            val events = collectEvents(vm)
            advanceUntilIdle()

            vm.onIntent(SingleNoteIntent.TitleChanged("ab"))
            advanceTimeBy(100)
            vm.onIntent(SingleNoteIntent.DeleteNote)
            advanceUntilIdle()

            // The pending save is for an edit the user has just thrown away. It could not resurrect
            // the note (the upsert leaves a tombstone alone), but it would still bump updatedAt.
            assertEquals(0, repo.upsertCount())
            assertEquals(listOf("deleteNote($NOTE_ID)"), repo.callsNamed("deleteNote"))
            // Soft, so Restore is lossless — the discard path is the only one that purges.
            assertEquals(emptyList<String>(), repo.callsNamed("purgeNote"))
            assertEquals(listOf(SingleNoteEvent.NavigateBack), events)
        }

    // ============================================================================================
    // Undo / redo
    // ============================================================================================

    @Test
    fun `undo restores the previous title and persists it without waiting out the debounce`() =
        runTest(mainDispatcherRule.dispatcher) {
            repo.noteById.value = note(title = "a")
            val vm = viewModel()
            advanceUntilIdle()

            vm.onIntent(SingleNoteIntent.TitleChanged("ab"))
            advanceUntilIdle()
            assertEquals(1, repo.upsertCount())
            assertTrue(vm.state.value.canUndo)
            assertFalse(vm.state.value.canRedo)

            vm.onIntent(SingleNoteIntent.Undo)
            // runCurrent, not advanceUntilIdle: pressing undo is a discrete action, so its write
            // must be scheduled for now. A debounced write would still be 300 ms away here.
            runCurrent()

            assertEquals("a", vm.state.value.title)
            assertFalse(vm.state.value.canUndo)
            assertTrue(vm.state.value.canRedo)
            assertEquals(2, repo.upsertCount())
            assertEquals("a", repo.savedNotes.last().title)
        }

    @Test
    fun `redo replays the undone edit and persists it`() = runTest(mainDispatcherRule.dispatcher) {
        repo.noteById.value = note(title = "a")
        val vm = viewModel()
        advanceUntilIdle()

        vm.onIntent(SingleNoteIntent.TitleChanged("ab"))
        advanceUntilIdle()
        vm.onIntent(SingleNoteIntent.Undo)
        advanceUntilIdle()
        vm.onIntent(SingleNoteIntent.Redo)
        runCurrent()

        assertEquals("ab", vm.state.value.title)
        assertTrue(vm.state.value.canUndo)
        assertFalse(vm.state.value.canRedo)
        assertEquals(3, repo.upsertCount())
        assertEquals("ab", repo.savedNotes.last().title)
    }

    @Test
    fun `undo and redo on an empty stack do nothing at all`() = runTest(mainDispatcherRule.dispatcher) {
        repo.noteById.value = note(title = "a")
        val vm = viewModel()
        advanceUntilIdle()

        vm.onIntent(SingleNoteIntent.Undo)
        vm.onIntent(SingleNoteIntent.Redo)
        advanceUntilIdle()

        assertEquals("a", vm.state.value.title)
        assertEquals(0, repo.upsertCount())
    }

    @Test
    fun `undoing a body edit restores the bytes and the format marker together`() =
        runTest(mainDispatcherRule.dispatcher) {
            // The marker says how the bytes are read. Restoring one without the other hands a
            // plain body to the HTML reader, which swallows anything that looks like a tag —
            // silent, permanent truncation, and the reason the format column exists at all.
            repo.noteById.value = note(content = "a < b and c > d", contentFormat = NoteContentFormat.PLAIN)
            val vm = viewModel()
            advanceUntilIdle()

            vm.onIntent(SingleNoteIntent.ContentChanged("<p>rewritten</p>"))
            advanceUntilIdle()
            assertEquals(NoteContentFormat.HTML, vm.state.value.contentFormat)

            vm.onIntent(SingleNoteIntent.Undo)
            runCurrent()

            assertEquals("a < b and c > d", vm.state.value.content)
            assertEquals(NoteContentFormat.PLAIN, vm.state.value.contentFormat)
            // ...and the pair reaches the database as a pair, too.
            val written = repo.savedNotes.last()
            assertEquals("a < b and c > d", written.content)
            assertEquals(NoteContentFormat.PLAIN, written.contentFormat)
        }

    @Test
    fun `undoing a body edit bumps contentRevision so the editor re-seeds`() =
        runTest(mainDispatcherRule.dispatcher) {
            repo.noteById.value = note(content = "one")
            val vm = viewModel()
            advanceUntilIdle()

            vm.onIntent(SingleNoteIntent.ContentChanged("<p>two</p>"))
            advanceUntilIdle()
            assertEquals(0, vm.state.value.contentRevision)

            vm.onIntent(SingleNoteIntent.Undo)
            runCurrent()
            assertEquals(1, vm.state.value.contentRevision)

            vm.onIntent(SingleNoteIntent.Redo)
            runCurrent()
            assertEquals(2, vm.state.value.contentRevision)
        }

    @Test
    fun `ordinary typing never bumps contentRevision`() = runTest(mainDispatcherRule.dispatcher) {
        // A bump re-seeds RichTextState from `content`, which moves the caret. The body changes on
        // every debounced flush while the user types, so a bump on this path would yank the cursor
        // to the end of the note mid-sentence.
        repo.noteById.value = note(content = "one")
        val vm = viewModel()
        advanceUntilIdle()

        vm.onIntent(SingleNoteIntent.ContentChanged("<p>two</p>"))
        vm.onIntent(SingleNoteIntent.ContentChanged("<p>three</p>"))
        vm.onIntent(SingleNoteIntent.TitleChanged("t"))
        vm.onIntent(SingleNoteIntent.ChecklistItemAdded(newId = "i1", afterId = null))
        advanceUntilIdle()

        assertEquals(0, vm.state.value.contentRevision)
    }

    @Test
    fun `undoing a checklist edit puts back the original item instances, ids and all`() =
        runTest(mainDispatcherRule.dispatcher) {
            repo.noteById.value = note(checklist = "0milk")
            val vm = viewModel()
            advanceUntilIdle()
            val original = vm.state.value.checklist

            vm.onIntent(SingleNoteIntent.ChecklistItemAdded(newId = "i2", afterId = null))
            advanceUntilIdle()
            assertEquals(2, vm.state.value.checklist.size)

            vm.onIntent(SingleNoteIntent.Undo)
            runCurrent()

            // Same ids, so nothing loses focus and no in-flight keystroke is stranded.
            assertEquals(original.map { it.id }, vm.state.value.checklist.map { it.id })
            assertEquals(listOf("milk"), vm.state.value.checklist.map { it.text })
        }

    // ============================================================================================
    // What each write path actually writes
    // ============================================================================================

    @Test
    fun `a title-only edit leaves a legacy plain body labelled PLAIN`() =
        runTest(mainDispatcherRule.dispatcher) {
            // The one place a note earns the HTML label is the rich-text editor reporting a body.
            // Labelling it anywhere else relabels an untouched legacy plain-text body, and the next
            // open parses "Email John <john@example.com>" as markup and eats the address.
            repo.noteById.value = note(
                title = "a",
                content = "Email John <john@example.com>",
                contentFormat = NoteContentFormat.PLAIN,
            )
            val vm = viewModel()
            advanceUntilIdle()

            vm.onIntent(SingleNoteIntent.TitleChanged("ab"))
            advanceUntilIdle()

            val written = repo.savedNotes.single()
            assertEquals("ab", written.title)
            assertEquals("Email John <john@example.com>", written.content)
            assertEquals(NoteContentFormat.PLAIN, written.contentFormat)
        }

    @Test
    fun `a body edit writes the new bytes and the HTML marker together`() =
        runTest(mainDispatcherRule.dispatcher) {
            repo.noteById.value = note(content = "plain", contentFormat = NoteContentFormat.PLAIN)
            val vm = viewModel()
            advanceUntilIdle()

            vm.onIntent(SingleNoteIntent.ContentChanged("<p>rich</p>"))
            advanceUntilIdle()

            val written = repo.savedNotes.single()
            assertEquals("<p>rich</p>", written.content)
            assertEquals(NoteContentFormat.HTML, written.contentFormat)
        }

    @Test
    fun `the upsert never writes isFavorite`() = runTest(mainDispatcherRule.dispatcher) {
        // isFavorite is owned exclusively by the targeted UPDATE. If the upsert carried it, a note
        // favorited from the list screen while this editor is open would be un-favorited by the
        // editor's next autosave.
        repo.noteById.value = note(isFavorite = false)
        val vm = viewModel()
        advanceUntilIdle()

        vm.onIntent(SingleNoteIntent.ToggleFavorite)
        advanceUntilIdle()
        vm.onIntent(SingleNoteIntent.TitleChanged("t"))
        advanceUntilIdle()

        assertTrue(vm.state.value.isFavorite)
        assertFalse(repo.savedNotes.single().isFavorite)
    }

    @Test
    fun `pin, favorite and folder each persist through a targeted update, never an upsert`() =
        runTest(mainDispatcherRule.dispatcher) {
            // A full upsert would restamp updatedAt, and updatedAt is what orders the notes list —
            // so pinning a note from the editor would silently jump it to the top of "recently
            // edited" for a change that is not an edit.
            repo.noteById.value = note()
            val vm = viewModel()
            advanceUntilIdle()

            vm.onIntent(SingleNoteIntent.TogglePin)
            vm.onIntent(SingleNoteIntent.ToggleFavorite)
            vm.onIntent(SingleNoteIntent.SetFolder("f1"))
            advanceUntilIdle()

            assertEquals(0, repo.upsertCount())
            assertEquals(
                listOf(
                    "setNotePinned($NOTE_ID, true)",
                    "setNoteFavorite($NOTE_ID, true)",
                    "setNoteFolder($NOTE_ID, f1)",
                ),
                repo.calls,
            )
            assertTrue(vm.state.value.isPinned)
            assertTrue(vm.state.value.isFavorite)
            assertEquals("f1", vm.state.value.folderId)
        }

    @Test
    fun `a structural checklist change is written immediately, typing into an item is debounced`() =
        runTest(mainDispatcherRule.dispatcher) {
            repo.noteById.value = note()
            val vm = viewModel()
            advanceUntilIdle()

            vm.onIntent(SingleNoteIntent.ChecklistItemAdded(newId = "i1", afterId = null))
            runCurrent()
            assertEquals(1, repo.upsertCount())

            vm.onIntent(SingleNoteIntent.ChecklistItemTextChanged(id = "i1", text = "milk"))
            runCurrent()
            assertEquals("typing into an item must wait out the debounce", 1, repo.upsertCount())

            advanceUntilIdle()
            assertEquals(2, repo.upsertCount())
            assertEquals("0milk", repo.savedNotes.last().checklist)
        }

    @Test
    fun `a checklist intent naming an item that is already gone is dropped`() =
        runTest(mainDispatcherRule.dispatcher) {
            // A keystroke can be in flight when the row it addresses is removed. Acting on it would
            // record an undo step that appears to do nothing when taken.
            repo.noteById.value = note(checklist = "0milk")
            val vm = viewModel()
            advanceUntilIdle()

            vm.onIntent(SingleNoteIntent.ChecklistItemTextChanged(id = "ghost", text = "x"))
            vm.onIntent(SingleNoteIntent.ChecklistItemToggled(id = "ghost"))
            vm.onIntent(SingleNoteIntent.ChecklistItemRemoved(id = "ghost"))
            advanceUntilIdle()

            assertEquals(0, repo.upsertCount())
            assertFalse(vm.state.value.canUndo)
            assertEquals(listOf("milk"), vm.state.value.checklist.map { it.text })
        }

    // ============================================================================================
    // Duplicate
    // ============================================================================================

    @Test
    fun `duplicate writes a new row and leaves the editor on the original`() =
        runTest(mainDispatcherRule.dispatcher) {
            repo.noteById.value = note(
                title = "Trip",
                content = "<p>body</p>",
                contentFormat = NoteContentFormat.HTML,
                checklist = "0milk",
                isPinned = true,
                isFavorite = true,
                folderId = "f1",
            )
            val vm = viewModel()
            val events = collectEvents(vm)
            advanceUntilIdle()

            vm.onIntent(SingleNoteIntent.DuplicateNote)
            advanceUntilIdle()

            // Nothing was pending, so the original is not rewritten: duplicating a note the user
            // only read must not restamp it and reorder the list.
            val copy = repo.savedNotes.single()
            assertNotEquals(NOTE_ID, copy.id)
            assertEquals("Trip (copy)", copy.title)
            assertEquals("<p>body</p>", copy.content)
            assertEquals(NoteContentFormat.HTML, copy.contentFormat)
            assertEquals("0milk", copy.checklist)
            assertEquals("f1", copy.folderId)
            // A second pinned card with a near-identical title reads as a glitch in the pager.
            assertFalse(copy.isPinned)

            assertEquals(listOf(SingleNoteEvent.NoteDuplicated("Trip (copy)")), events)
            // The editor did not move.
            assertEquals("Trip", vm.state.value.title)
        }

    @Test
    fun `duplicate flushes the original first, so the copy is never ahead of it`() =
        runTest(mainDispatcherRule.dispatcher) {
            repo.noteById.value = note(title = "Trip")
            val vm = viewModel()
            advanceUntilIdle()

            vm.onIntent(SingleNoteIntent.TitleChanged("Trip 2"))
            advanceTimeBy(50) // the autosave is still inside its window
            vm.onIntent(SingleNoteIntent.DuplicateNote)
            advanceUntilIdle()

            assertEquals(2, repo.upsertCount())
            assertEquals("Trip 2", repo.savedNotes[0].title)
            assertEquals(NOTE_ID, repo.savedNotes[0].id)
            assertEquals("Trip 2 (copy)", repo.savedNotes[1].title)
        }

    @Test
    fun `a second duplicate tap while the first insert is in flight is ignored`() =
        runTest(mainDispatcherRule.dispatcher) {
            repo.noteById.value = note(title = "Trip")
            val vm = viewModel()
            val events = collectEvents(vm)
            advanceUntilIdle()

            repo.gate("saveNote")
            vm.onIntent(SingleNoteIntent.DuplicateNote)
            advanceUntilIdle()
            vm.onIntent(SingleNoteIntent.DuplicateNote)
            advanceUntilIdle()

            repo.release("saveNote")
            advanceUntilIdle()

            // Asserted only after the gate is lifted: while the first insert holds saveMutex a
            // second one would simply be queued behind it, so counting before the release would
            // pass with or without the guard.
            assertEquals(1, repo.upsertCount())
            assertEquals(1, events.count { it is SingleNoteEvent.NoteDuplicated })
        }

    // ============================================================================================
    // A screen opened without a note
    // ============================================================================================

    @Test
    fun `a screen opened without a noteId writes nothing and still navigates back`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = viewModel(noteId = null)
            val events = collectEvents(vm)
            advanceUntilIdle()

            vm.onIntent(SingleNoteIntent.TitleChanged("orphan"))
            vm.onIntent(SingleNoteIntent.TogglePin)
            vm.onIntent(SingleNoteIntent.DuplicateNote)
            vm.onIntent(SingleNoteIntent.DeleteNote)
            vm.onIntent(SingleNoteIntent.BackClicked)
            advanceUntilIdle()

            assertEquals(emptyList<String>(), repo.calls)
            assertEquals(listOf(SingleNoteEvent.NavigateBack), events)
        }
    // ============================================================================================
    // Checklist editing
    // ============================================================================================

    @Test
    fun `a new item is inserted after the one it was added from, not appended`() =
        runTest(mainDispatcherRule.dispatcher) {
            repo.noteById.value = note(checklist = "0milk\n0eggs")
            val vm = viewModel()
            advanceUntilIdle()
            val milkId = vm.state.value.checklist.first().id

            vm.onIntent(SingleNoteIntent.ChecklistItemAdded(newId = "new", afterId = milkId))
            advanceUntilIdle()

            // Pressing enter on a row opens the next line under THAT row; appending would send the
            // caret to the bottom of the list instead.
            assertEquals(listOf("milk", "", "eggs"), vm.state.value.checklist.map { it.text })
            assertEquals("new", vm.state.value.checklist[1].id)
        }

    @Test
    fun `an add whose anchor row is already gone appends instead of failing`() =
        runTest(mainDispatcherRule.dispatcher) {
            // The anchor id comes from the UI, so it can name a row that a concurrent removal has
            // just taken out. Appending is the safe reading of "after nothing in particular".
            repo.noteById.value = note(checklist = "0milk")
            val vm = viewModel()
            advanceUntilIdle()

            vm.onIntent(SingleNoteIntent.ChecklistItemAdded(newId = "new", afterId = "ghost"))
            advanceUntilIdle()

            assertEquals(listOf("milk", ""), vm.state.value.checklist.map { it.text })
        }

    @Test
    fun `toggling an item flips only that row and is written at once`() =
        runTest(mainDispatcherRule.dispatcher) {
            repo.noteById.value = note(checklist = "0milk\n0eggs")
            val vm = viewModel()
            advanceUntilIdle()
            val eggsId = vm.state.value.checklist[1].id

            vm.onIntent(SingleNoteIntent.ChecklistItemToggled(eggsId))
            runCurrent()

            assertEquals(listOf(false, true), vm.state.value.checklist.map { it.isDone })
            assertEquals("0milk\n1eggs", repo.savedNotes.single().checklist)
        }

    @Test
    fun `removing an item drops only that row and is written at once`() =
        runTest(mainDispatcherRule.dispatcher) {
            repo.noteById.value = note(checklist = "0milk\n0eggs\n0bread")
            val vm = viewModel()
            advanceUntilIdle()
            val eggsId = vm.state.value.checklist[1].id

            vm.onIntent(SingleNoteIntent.ChecklistItemRemoved(eggsId))
            runCurrent()

            assertEquals(listOf("milk", "bread"), vm.state.value.checklist.map { it.text })
            assertEquals("0milk\n0bread", repo.savedNotes.single().checklist)
        }

    @Test
    fun `typing into two different checklist rows makes two undo steps, not one`() =
        runTest(mainDispatcherRule.dispatcher) {
            // Undo steps are grouped by item id, so a burst within one row folds into a single
            // step but moving to another row starts a new one. Sharing a group across rows would
            // make one undo wipe both edits.
            repo.noteById.value = note(checklist = "0milk\n0eggs")
            val vm = viewModel()
            advanceUntilIdle()
            val (milkId, eggsId) = vm.state.value.checklist.map { it.id }

            vm.onIntent(SingleNoteIntent.ChecklistItemTextChanged(milkId, "milkk"))
            vm.onIntent(SingleNoteIntent.ChecklistItemTextChanged(eggsId, "eggss"))
            advanceUntilIdle()
            assertEquals(listOf("milkk", "eggss"), vm.state.value.checklist.map { it.text })

            vm.onIntent(SingleNoteIntent.Undo)
            advanceUntilIdle()
            assertEquals(listOf("milkk", "eggs"), vm.state.value.checklist.map { it.text })

            vm.onIntent(SingleNoteIntent.Undo)
            advanceUntilIdle()
            assertEquals(listOf("milk", "eggs"), vm.state.value.checklist.map { it.text })
        }

    @Test
    fun `undo steps back through edits to different fields in the order they were made`() =
        runTest(mainDispatcherRule.dispatcher) {
            // One stack for the whole editor: the top-bar button steps back through the user's
            // edits whichever field each one touched.
            repo.noteById.value = note(title = "t0", content = "b0")
            val vm = viewModel()
            advanceUntilIdle()

            vm.onIntent(SingleNoteIntent.TitleChanged("t1"))
            advanceUntilIdle()
            vm.onIntent(SingleNoteIntent.ContentChanged("<p>b1</p>"))
            advanceUntilIdle()

            vm.onIntent(SingleNoteIntent.Undo)
            advanceUntilIdle()
            // The body edit was last, so it is the first to come back — and the title edit is
            // untouched by it, which is the point of recording per slice rather than per screen.
            assertEquals("b0", vm.state.value.content)
            assertEquals("t1", vm.state.value.title)

            vm.onIntent(SingleNoteIntent.Undo)
            advanceUntilIdle()
            assertEquals("t0", vm.state.value.title)
            assertEquals("b0", vm.state.value.content)
        }

    // ============================================================================================
    // Live merging of database emissions
    // ============================================================================================

    @Test
    fun `a folder move made from the list screen lands while the user is mid-word`() =
        runTest(mainDispatcherRule.dispatcher) {
            // The merge is per field, not per row. A row-level "keep everything local" would
            // swallow this move; a row-level "take everything from the database" is the bug the
            // merge exists to stop.
            repo.noteById.value = note(title = "a", folderId = null)
            val vm = viewModel()
            advanceUntilIdle()

            vm.onIntent(SingleNoteIntent.TitleChanged("ab"))
            // The row still carries the old title (this screen's write has not been echoed yet)
            // but a different writer has moved the note into a folder.
            repo.noteById.value = note(title = "a", folderId = "f2", updatedAt = 2_000L)
            advanceUntilIdle()

            assertEquals("ab", vm.state.value.title)
            assertEquals("f2", vm.state.value.folderId)
            // Not editable, so always adopted — this is what keeps "Edited … ago" honest.
            assertEquals(2_000L, vm.state.value.updatedAt)
        }

    @Test
    fun `the echo of our own checklist write leaves the row ids alone`() =
        runTest(mainDispatcherRule.dispatcher) {
            // Ids back the focus requesters and address the text/toggle/remove intents. Re-minting
            // them on every emission steals focus from the row being typed into and strands any
            // keystroke already in flight for it.
            repo.noteById.value = note(checklist = "0milk")
            val vm = viewModel()
            advanceUntilIdle()
            val milkId = vm.state.value.checklist.single().id

            vm.onIntent(SingleNoteIntent.ChecklistItemTextChanged(milkId, "milkk"))
            advanceUntilIdle()
            assertEquals("0milkk", repo.savedNotes.single().checklist)

            repo.noteById.value = note(checklist = "0milkk", updatedAt = 2_000L)
            advanceUntilIdle()

            assertEquals(milkId, vm.state.value.checklist.single().id)
            assertEquals("milkk", vm.state.value.checklist.single().text)
        }

    @Test
    fun `a duplicate of an untitled note is still findable in the list`() =
        runTest(mainDispatcherRule.dispatcher) {
            // A card reading "(copy)" says less than one reading "Untitled (copy)".
            repo.noteById.value = note(title = "   ", content = "body")
            val vm = viewModel()
            advanceUntilIdle()

            vm.onIntent(SingleNoteIntent.DuplicateNote)
            advanceUntilIdle()

            assertEquals("Untitled (copy)", repo.savedNotes.single().title)
        }
}
