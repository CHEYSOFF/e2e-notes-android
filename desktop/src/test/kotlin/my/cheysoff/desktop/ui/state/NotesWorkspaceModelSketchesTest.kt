package my.cheysoff.desktop.ui.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.SketchData
import my.cheysoff.desktop.store.DesktopSketches
import my.cheysoff.desktop.ui.preview.InMemoryNotesRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plumbing between [DesktopSketches] and [WorkspaceUiState.sketches]: does selecting a note
 * subscribe to its sketches, does switching notes drop the previous subscription rather than merge
 * with it, and does deleting one remove it from the state immediately rather than waiting on the
 * round trip. [SketchDisplayTest] covers the ordering and decode-failure rules this state is built
 * from; this file is only about whether the model wires that pure logic up correctly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesWorkspaceModelSketchesTest {

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun cancelModelScopes() {
        scopes.forEach { it.cancel() }
    }

    /** One sketch per note, keyed by which note it is anchored under, live and mutable. */
    private class FakeSketches(initial: Map<String, List<SketchData>> = emptyMap()) : DesktopSketches {
        private val state = MutableStateFlow(initial)
        var deletedIds = listOf<String>(); private set

        override fun getSketchesForNote(noteId: String): Flow<List<SketchData>> =
            state.map { it[noteId].orEmpty() }

        override suspend fun deleteSketch(id: String) {
            deletedIds = deletedIds + id
            state.value = state.value.mapValues { (_, list) -> list.filterNot { it.id == id } }
        }
    }

    private fun sketch(id: String, noteId: String) = SketchData(
        id = id,
        noteId = noteId,
        anchor = 0,
        order = 0,
        strokes = "1|10x10|ff000000,4:0,0",
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    private fun TestScope.model(repository: InMemoryNotesRepository, sketches: DesktopSketches): NotesWorkspaceModel {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        scopes += scope
        return NotesWorkspaceModel(repository = repository, scope = scope, sketches = sketches)
    }

    @Test
    fun `selecting a note streams its sketches, decoded and ordered`() = runTest {
        val repository = InMemoryNotesRepository(notes = listOf(Note(id = "n1", title = "t", content = "")))
        val fake = FakeSketches(mapOf("n1" to listOf(sketch("b", "n1"), sketch("a", "n1"))))
        val model = model(repository, fake)
        advanceUntilIdle()

        model.selectNote("n1")
        advanceUntilIdle()

        assertEquals(listOf("a", "b"), model.state.value.sketches.map { it.id })
    }

    @Test
    fun `switching notes replaces the sketch list rather than appending to it`() = runTest {
        val repository = InMemoryNotesRepository(
            notes = listOf(Note(id = "n1", title = "t1", content = ""), Note(id = "n2", title = "t2", content = "")),
        )
        val fake = FakeSketches(
            mapOf("n1" to listOf(sketch("s1", "n1")), "n2" to listOf(sketch("s2", "n2"))),
        )
        val model = model(repository, fake)
        advanceUntilIdle()

        model.selectNote("n1")
        advanceUntilIdle()
        model.selectNote("n2")
        advanceUntilIdle()

        assertEquals(listOf("s2"), model.state.value.sketches.map { it.id })
    }

    /**
     * `selectNote(null)` alone does not prove this -- [resolveSelection] auto-selects the first
     * remaining note when nothing else claims the id, so a model with `n1` still in its list simply
     * reselects it. Deleting the selected note is the real path to "nothing is open" while other
     * notes exist, and is what [my.cheysoff.desktop.ui.notes.NoteEditorPane]'s delete button drives.
     */
    @Test
    fun `deleting the selected note clears its sketches`() = runTest {
        val repository = InMemoryNotesRepository(notes = listOf(Note(id = "n1", title = "t", content = "")))
        val fake = FakeSketches(mapOf("n1" to listOf(sketch("s1", "n1"))))
        val model = model(repository, fake)
        advanceUntilIdle()

        model.selectNote("n1")
        advanceUntilIdle()
        model.deleteSelectedNote()
        advanceUntilIdle()

        assertTrue(model.state.value.sketches.isEmpty())
    }

    @Test
    fun `deleteSketch drops the row immediately and calls through to the port`() = runTest {
        val repository = InMemoryNotesRepository(notes = listOf(Note(id = "n1", title = "t", content = "")))
        val fake = FakeSketches(mapOf("n1" to listOf(sketch("s1", "n1"), sketch("s2", "n1"))))
        val model = model(repository, fake)
        advanceUntilIdle()
        model.selectNote("n1")
        advanceUntilIdle()

        model.deleteSketch("s1")

        assertEquals("removed from state without waiting on the coroutine", listOf("s2"), model.state.value.sketches.map { it.id })
        advanceUntilIdle()
        assertEquals(listOf("s1"), fake.deletedIds)
    }
}
