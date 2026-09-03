package my.cheysoff.feature_notes

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import my.cheysoff.core_domain.sync.SyncPassState
import my.cheysoff.core_domain.sync.SyncPassSummary
import my.cheysoff.core_domain.sync.SyncTrigger
import my.cheysoff.feature_notes.model.list.NotesListIntent
import my.cheysoff.feature_notes.ui.list.NotesListViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Pull-to-refresh: the one sync a person can ask for on purpose.
 *
 * The controller is a fake, deliberately. What is under test is the gesture's contract with the
 * screen — that it runs a pass, that it waits for it, that it does not hide the list while it
 * waits, and that what it then says is what the pass reported and nothing more. Whether the pass
 * itself works is `TwoDeviceSyncTest`'s question, one module down.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesListRefreshTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val notesRepo = FakeNotesRepository()
    private val settingsRepo = FakeSettingsRepository()
    private val syncController = FakeSyncController()

    private fun viewModel() = NotesListViewModel(notesRepo, settingsRepo, syncController)

    @Test
    fun `pulling down runs a pass, labelled as the manual one`() = runTest {
        val vm = viewModel()
        vm.onIntent(NotesListIntent.RefreshRequested)
        advanceUntilIdle()

        assertEquals(listOf(SyncTrigger.MANUAL_REFRESH), syncController.triggers)
    }

    /**
     * A pull-to-refresh must not clear a halt.
     *
     * Every halt is a condition the engine cannot repair, and stopping was the correct response --
     * so the only thing allowed to clear one is a person who went to Settings and pressed the
     * control that says so. A gesture people make absent-mindedly, on a list, while looking for
     * something else, is the opposite of that: it would turn every deliberate stop into a
     * halt-resume loop against the very server the engine refused to trust.
     */
    @Test
    fun `pulling down never clears a halt`() = runTest {
        syncController.answerWith(SyncPassState.Halted("The server was rolled back."))
        val vm = viewModel()

        vm.onIntent(NotesListIntent.RefreshRequested)
        advanceUntilIdle()
        vm.onIntent(NotesListIntent.RefreshRequested)
        advanceUntilIdle()

        assertEquals("a refresh asked for an ordinary pass, twice", 2, syncController.triggers.size)
        assertEquals("and cleared nothing", 0, syncController.haltsCleared)
    }

    /**
     * The list is a Room `Flow` and stays on screen throughout: `refreshing` drives a spinner over
     * a usable list, and `isLoading` — which gates the whole screen — must not be touched. Taking
     * someone's notes away for the duration of a network call would be the worse trade.
     */
    @Test
    fun `a refresh does not blank the list`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        val loadingBefore = vm.state.value.isLoading

        vm.onIntent(NotesListIntent.RefreshRequested)
        assertTrue("the indicator should be up while the pass runs", vm.state.value.refreshing)
        assertEquals("isLoading is not the refresh indicator", loadingBefore, vm.state.value.isLoading)

        advanceUntilIdle()
        assertFalse(vm.state.value.refreshing)
    }

    @Test
    fun `a pass that moved something reports what it moved`() = runTest {
        syncController.answerWith(
            SyncPassState.Completed(SyncPassSummary(pushed = 2, applied = 1, received = 1)),
        )
        val vm = viewModel()
        vm.onIntent(NotesListIntent.RefreshRequested)
        advanceUntilIdle()

        assertEquals("Sent 2, applied 1.", vm.state.value.syncNotice)
    }

    /**
     * "Nothing new" and not "up to date". A pass that found nothing proves this device and the
     * server agreed at that moment; it says nothing about the other device that has not synced yet,
     * and the difference is the whole of the rule this app's sync copy lives under.
     */
    @Test
    fun `a pass that moved nothing says nothing new, and claims no more`() = runTest {
        syncController.answerWith(SyncPassState.Completed(SyncPassSummary()))
        val vm = viewModel()
        vm.onIntent(NotesListIntent.RefreshRequested)
        advanceUntilIdle()

        val notice = vm.state.value.syncNotice!!
        assertEquals("Nothing new.", notice)
        listOf("synced", "up to date", "backed up").forEach {
            assertFalse("$notice claims \"$it\"", notice.lowercase().contains(it))
        }
    }

    @Test
    fun `a pass that could not run says which piece is missing`() = runTest {
        syncController.answerWith(SyncPassState.Unavailable("Pair this device first."))
        val vm = viewModel()
        vm.onIntent(NotesListIntent.RefreshRequested)
        advanceUntilIdle()

        assertEquals("Pair this device first.", vm.state.value.syncNotice)
    }

    @Test
    fun `a halted engine says so on the list, not only in settings`() = runTest {
        syncController.answerWith(SyncPassState.Halted("The server's history is older."))
        val vm = viewModel()
        vm.onIntent(NotesListIntent.RefreshRequested)
        advanceUntilIdle()

        assertEquals("The server's history is older.", vm.state.value.syncNotice)
    }

    /** A second pull replaces the first pull's answer rather than stacking on it. */
    @Test
    fun `the previous notice is cleared while the next pass runs`() = runTest {
        syncController.answerWith(SyncPassState.Deferred("Couldn't reach the server."))
        val vm = viewModel()
        vm.onIntent(NotesListIntent.RefreshRequested)
        advanceUntilIdle()
        assertEquals("Couldn't reach the server.", vm.state.value.syncNotice)

        vm.onIntent(NotesListIntent.RefreshRequested)
        assertNull("a stale answer must not sit under a running pass", vm.state.value.syncNotice)
    }
}
