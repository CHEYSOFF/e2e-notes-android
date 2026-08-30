package my.cheysoff.feature_notes

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import my.cheysoff.core_domain.model.Folder
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NoteContentFormat
import my.cheysoff.core_domain.model.TrashPolicy
import my.cheysoff.feature_notes.model.trash.TrashEntryKind
import my.cheysoff.feature_notes.model.trash.TrashIntent
import my.cheysoff.feature_notes.ui.trash.TrashEvent
import my.cheysoff.feature_notes.ui.trash.TrashViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

/**
 * Trash: the 30-day sweep that runs as the screen opens, the flattening of two tombstone lists into
 * one, and the two irreversible-adjacent actions on each row.
 *
 * Like the notes list, this screen maps its rows on `Dispatchers.Default`, so the upstream half runs
 * on real threads and [awaitState] pumps the test dispatcher until the result lands. Every note here
 * is PLAIN for the same reason too: `Note.toUi()` sends an HTML body through `HtmlCompat.fromHtml`,
 * which is a stubbed Android class in a JVM unit test and throws rather than returning text.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrashViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repo = FakeNotesRepository()

    private val day = 24L * 60 * 60 * 1000

    private fun deletedNote(
        id: String,
        title: String = "",
        content: String = "",
        folderId: String? = null,
        deletedAt: Long?,
    ) = Note(
        id = id,
        title = title,
        content = content,
        contentFormat = NoteContentFormat.PLAIN,
        folderId = folderId,
        isDeleted = true,
        deletedAt = deletedAt,
    )

    private fun deletedFolder(
        id: String,
        name: String = "",
        colorArgb: Long? = null,
        deletedAt: Long?,
    ) = Folder(id = id, name = name, colorArgb = colorArgb, isDeleted = true, deletedAt = deletedAt)

    private fun TestScope.collectEvents(vm: TrashViewModel): List<TrashEvent> {
        val received = mutableListOf<TrashEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.events.collect { received += it }
        }
        return received
    }

    private fun TestScope.awaitState(
        reason: String,
        timeoutMillis: Long = 5_000,
        predicate: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (true) {
            advanceUntilIdle()
            if (predicate()) return
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for: $reason")
            Thread.sleep(2)
        }
    }

    // ============================================================================================
    // The sweep
    // ============================================================================================

    @Test
    fun `opening Trash sweeps rows whose retention has run out`() = runTest(mainDispatcherRule.dispatcher) {
        // This is the ONLY place the sweep runs. It is not driven from app start, because the
        // database cannot be opened while the app is locked — so if this call goes away, expired
        // rows are never purged at all, on any path.
        val before = System.currentTimeMillis()
        TrashViewModel(repo)
        advanceUntilIdle()

        assertEquals(1, repo.calls.count { it == "purgeExpiredTrash" })
        val now = repo.purgeExpiredNow
        assertNotNull("purgeExpiredTrash was never given a cutoff", now)
        // It must be handed the CURRENT wall clock. A zero or otherwise stale `now` would make
        // every stamped row look decades old and purge the lot.
        assertTrue(
            "purge cutoff $now is not a current wall clock (expected >= $before)",
            now!! >= before && now <= System.currentTimeMillis(),
        )
    }

    @Test
    fun `the sweep runs before the user can act on anything, and once per screen open`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = TrashViewModel(repo)
            advanceUntilIdle()
            vm.onIntent(TrashIntent.Restore("n1", TrashEntryKind.NOTE))
            advanceUntilIdle()

            assertEquals(listOf("purgeExpiredTrash", "restoreNote(n1)"), repo.calls)
        }

    // ============================================================================================
    // Building the list
    // ============================================================================================

    @Test
    fun `notes and folders are interleaved into one newest-deleted-first list`() =
        runTest(mainDispatcherRule.dispatcher) {
            val now = System.currentTimeMillis()
            repo.deletedNotes.value = listOf(
                deletedNote("n-old", title = "Old note", deletedAt = now - 10 * day),
                deletedNote("n-new", title = "New note", deletedAt = now - 1 * day),
            )
            repo.deletedFolders.value = listOf(
                deletedFolder("f-mid", name = "Mid folder", deletedAt = now - 5 * day),
            )

            val vm = TrashViewModel(repo)
            awaitState("entries") { vm.state.value.entries.size == 3 }

            // Each source query is already sorted, but a merge of two sorted lists is not, so the
            // combined list is re-sorted here. The user deleted these in one sequence, and showing
            // them in that sequence is what makes "the thing I just deleted" the first row —
            // whichever table it came out of.
            assertEquals(listOf("n-new", "f-mid", "n-old"), vm.state.value.entries.map { it.id })
            assertEquals(
                listOf(TrashEntryKind.NOTE, TrashEntryKind.FOLDER, TrashEntryKind.NOTE),
                vm.state.value.entries.map { it.kind },
            )
            assertFalse(vm.state.value.isLoading)
        }

    @Test
    fun `a tombstone with no stamp sorts last rather than first`() = runTest(mainDispatcherRule.dispatcher) {
        val now = System.currentTimeMillis()
        repo.deletedNotes.value = listOf(
            deletedNote("unstamped", deletedAt = null),
            deletedNote("stamped", deletedAt = now - day),
        )

        val vm = TrashViewModel(repo)
        awaitState("entries") { vm.state.value.entries.size == 2 }

        // Matches what the SQL does with NULL under DESC. Sorting it first would put a row whose
        // age is unknown at the top of a list ordered by recency.
        assertEquals(listOf("stamped", "unstamped"), vm.state.value.entries.map { it.id })
        // TrashPolicy refuses to age a row it cannot date, so the card shows no countdown at all.
        assertNull(vm.state.value.entries.last().daysRemaining)
    }

    @Test
    fun `rows deleted in the same millisecond are ordered by id, so the list cannot shuffle`() =
        runTest(mainDispatcherRule.dispatcher) {
            val at = System.currentTimeMillis() - day
            repo.deletedNotes.value = listOf(deletedNote("b", deletedAt = at))
            repo.deletedFolders.value = listOf(
                deletedFolder("c", deletedAt = at),
                deletedFolder("a", deletedAt = at),
            )

            val vm = TrashViewModel(repo)
            awaitState("entries") { vm.state.value.entries.size == 3 }

            // Without a total order, two rows sharing a timestamp could swap places between
            // emissions of otherwise-unchanged data, which reads on screen as the list twitching.
            assertEquals(listOf("a", "b", "c"), vm.state.value.entries.map { it.id })
        }

    @Test
    fun `each row reports the whole days it has left, rounded up`() = runTest(mainDispatcherRule.dispatcher) {
        val now = System.currentTimeMillis()
        repo.deletedNotes.value = listOf(
            deletedNote("fresh", deletedAt = now),
            deletedNote("halfway", deletedAt = now - 5 * day),
            deletedNote("expired", deletedAt = now - (TrashPolicy.RETENTION_DAYS + 1) * day),
        )

        val vm = TrashViewModel(repo)
        awaitState("entries") { vm.state.value.entries.size == 3 }

        val byId = vm.state.value.entries.associateBy { it.id }
        assertEquals(TrashPolicy.RETENTION_DAYS, byId.getValue("fresh").daysRemaining)
        assertEquals(TrashPolicy.RETENTION_DAYS - 5, byId.getValue("halfway").daysRemaining)
        // Expired but still listed: it is awaiting the next sweep, not already gone.
        assertEquals(0, byId.getValue("expired").daysRemaining)
    }

    @Test
    fun `a deleted note keeps its folder accent even when that folder is in Trash too`() =
        runTest(mainDispatcherRule.dispatcher) {
            val now = System.currentTimeMillis()
            repo.folders.value = listOf(Folder(id = "live", name = "Work", colorArgb = 0x11L))
            repo.deletedNotes.value = listOf(
                deletedNote("n-live", folderId = "live", deletedAt = now - day),
                deletedNote("n-dead", folderId = "dead", deletedAt = now - 2 * day),
                deletedNote("n-none", folderId = null, deletedAt = now - 3 * day),
            )
            repo.deletedFolders.value = listOf(
                deletedFolder("dead", name = "Archive", colorArgb = 0x22L, deletedAt = now - 4 * day),
            )

            val vm = TrashViewModel(repo)
            awaitState("entries") { vm.state.value.entries.size == 4 }

            val byId = vm.state.value.entries.associateBy { it.id }
            // The LIVE folder list is combined in for its colors, not its rows: a deleted note
            // keeps its folderId and that folder is usually still alive.
            assertEquals(0x11L, byId.getValue("n-live").folderColorArgb)
            // ...and a folder deleted after the note still lends the card its accent.
            assertEquals(0x22L, byId.getValue("n-dead").folderColorArgb)
            assertNull(byId.getValue("n-none").folderColorArgb)
            // A folder row wears its own color.
            assertEquals(0x22L, byId.getValue("dead").folderColorArgb)
        }

    @Test
    fun `a folder row carries its name as the title and has no snippet`() =
        runTest(mainDispatcherRule.dispatcher) {
            val now = System.currentTimeMillis()
            repo.deletedNotes.value = listOf(
                deletedNote("n", title = "Note title", content = "  body text  ", deletedAt = now),
            )
            repo.deletedFolders.value = listOf(deletedFolder("f", name = "Folder name", deletedAt = now - day))

            val vm = TrashViewModel(repo)
            awaitState("entries") { vm.state.value.entries.size == 2 }

            val byId = vm.state.value.entries.associateBy { it.id }
            assertEquals("Note title", byId.getValue("n").title)
            assertEquals("body text", byId.getValue("n").snippet)
            assertEquals("Folder name", byId.getValue("f").title)
            assertEquals("", byId.getValue("f").snippet)
        }

    @Test
    fun `an empty Trash is only reported as empty once it has actually been checked`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = TrashViewModel(repo)
            // "Trash is empty" is a claim, so the screen holds isLoading until the first emission
            // rather than flashing the empty state for a frame.
            assertTrue(vm.state.value.isLoading)

            awaitState("the first emission") { !vm.state.value.isLoading }
            assertEquals(emptyList<Any>(), vm.state.value.entries)
        }

    // ============================================================================================
    // Actions
    // ============================================================================================

    @Test
    fun `restore dispatches on the kind of row rather than guessing`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = TrashViewModel(repo)
            advanceUntilIdle()

            vm.onIntent(TrashIntent.Restore("n1", TrashEntryKind.NOTE))
            vm.onIntent(TrashIntent.Restore("f1", TrashEntryKind.FOLDER))
            advanceUntilIdle()

            assertEquals(listOf("restoreNote(n1)"), repo.callsNamed("restoreNote"))
            assertEquals(listOf("restoreFolder(f1)"), repo.callsNamed("restoreFolder"))
        }

    @Test
    fun `delete forever purges rather than soft-deletes, and dispatches on the kind of row`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = TrashViewModel(repo)
            advanceUntilIdle()

            vm.onIntent(TrashIntent.DeleteForever("n1", TrashEntryKind.NOTE))
            vm.onIntent(TrashIntent.DeleteForever("f1", TrashEntryKind.FOLDER))
            advanceUntilIdle()

            assertEquals(listOf("purgeNote(n1)"), repo.callsNamed("purgeNote"))
            assertEquals(listOf("purgeFolder(f1)"), repo.callsNamed("purgeFolder"))
            // Routing this through the soft delete would leave the row sitting in Trash after the
            // user confirmed "delete forever" — the one action with nothing behind it.
            assertEquals(emptyList<String>(), repo.callsNamed("deleteNote"))
            assertEquals(emptyList<String>(), repo.callsNamed("deleteFolder"))
        }

    @Test
    fun `back emits a navigation event and touches nothing`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = TrashViewModel(repo)
        val events = collectEvents(vm)
        advanceUntilIdle()
        val callsBefore = repo.calls.toList()

        vm.onIntent(TrashIntent.BackClicked)
        advanceUntilIdle()

        assertEquals(listOf(TrashEvent.NavigateBack), events)
        assertEquals(callsBefore, repo.calls)
    }
}
