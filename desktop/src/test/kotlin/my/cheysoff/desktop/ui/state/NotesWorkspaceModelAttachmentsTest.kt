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
import my.cheysoff.core_domain.model.AttachmentData
import my.cheysoff.core_domain.model.Note
import my.cheysoff.desktop.store.DesktopAttachments
import my.cheysoff.desktop.ui.preview.InMemoryNotesRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plumbing between [DesktopAttachments] and [WorkspaceUiState.attachments]: the exact mirror of
 * [NotesWorkspaceModelSketchesTest], for attachments instead of sketches. Does selecting a note
 * subscribe to its attachments, does switching notes drop the previous subscription rather than
 * merge with it, and does deleting one remove it from the state immediately rather than waiting on
 * the round trip.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesWorkspaceModelAttachmentsTest {

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun cancelModelScopes() {
        scopes.forEach { it.cancel() }
    }

    /** One attachment per note, keyed by which note it is anchored under, live and mutable. */
    private class FakeAttachments(initial: Map<String, List<AttachmentData>> = emptyMap()) : DesktopAttachments {
        private val state = MutableStateFlow(initial)
        var deletedIds = listOf<String>(); private set

        override fun getAttachmentsForNote(noteId: String): Flow<List<AttachmentData>> =
            state.map { it[noteId].orEmpty() }

        override suspend fun deleteAttachment(id: String) {
            deletedIds = deletedIds + id
            state.value = state.value.mapValues { (_, list) -> list.filterNot { it.id == id } }
        }
    }

    private fun attachment(id: String, noteId: String, anchor: Int = 0) = AttachmentData(
        id = id,
        noteId = noteId,
        anchor = anchor,
        order = 0,
        mimeType = "image/jpeg",
        width = 10,
        height = 10,
        bytes = ByteArray(1),
        thumbWidth = 10,
        thumbHeight = 10,
        thumbBytes = ByteArray(1),
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    private fun TestScope.model(
        repository: InMemoryNotesRepository,
        attachments: DesktopAttachments,
    ): NotesWorkspaceModel {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        scopes += scope
        return NotesWorkspaceModel(repository = repository, scope = scope, attachments = attachments)
    }

    @Test
    fun `selecting a note streams its attachments`() = runTest {
        val repository = InMemoryNotesRepository(notes = listOf(Note(id = "n1", title = "t", content = "")))
        val fake = FakeAttachments(mapOf("n1" to listOf(attachment("a", "n1"), attachment("b", "n1"))))
        val model = model(repository, fake)
        advanceUntilIdle()

        model.selectNote("n1")
        advanceUntilIdle()

        assertEquals(setOf("a", "b"), model.state.value.attachments.map { it.id }.toSet())
    }

    @Test
    fun `switching notes replaces the attachment list rather than appending to it`() = runTest {
        val repository = InMemoryNotesRepository(
            notes = listOf(Note(id = "n1", title = "t1", content = ""), Note(id = "n2", title = "t2", content = "")),
        )
        val fake = FakeAttachments(
            mapOf("n1" to listOf(attachment("a1", "n1")), "n2" to listOf(attachment("a2", "n2"))),
        )
        val model = model(repository, fake)
        advanceUntilIdle()

        model.selectNote("n1")
        advanceUntilIdle()
        model.selectNote("n2")
        advanceUntilIdle()

        assertEquals(listOf("a2"), model.state.value.attachments.map { it.id })
    }

    @Test
    fun `deleting the selected note clears its attachments`() = runTest {
        val repository = InMemoryNotesRepository(notes = listOf(Note(id = "n1", title = "t", content = "")))
        val fake = FakeAttachments(mapOf("n1" to listOf(attachment("a1", "n1"))))
        val model = model(repository, fake)
        advanceUntilIdle()

        model.selectNote("n1")
        advanceUntilIdle()
        model.deleteSelectedNote()
        advanceUntilIdle()

        assertTrue(model.state.value.attachments.isEmpty())
    }

    @Test
    fun `deleteAttachment drops the row immediately and calls through to the port`() = runTest {
        val repository = InMemoryNotesRepository(notes = listOf(Note(id = "n1", title = "t", content = "")))
        val fake = FakeAttachments(mapOf("n1" to listOf(attachment("a1", "n1"), attachment("a2", "n1"))))
        val model = model(repository, fake)
        advanceUntilIdle()
        model.selectNote("n1")
        advanceUntilIdle()

        model.deleteAttachment("a1")

        assertEquals(
            "removed from state without waiting on the coroutine",
            listOf("a2"),
            model.state.value.attachments.map { it.id },
        )
        advanceUntilIdle()
        assertEquals(listOf("a1"), fake.deletedIds)
    }
}
