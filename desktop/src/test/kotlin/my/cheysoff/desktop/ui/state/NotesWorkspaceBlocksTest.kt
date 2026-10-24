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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import my.cheysoff.core_blocks.Block
import my.cheysoff.core_blocks.BlockCodec
import my.cheysoff.core_blocks.BlockDocument
import my.cheysoff.core_blocks.BlockIds
import my.cheysoff.core_blocks.BlockType
import my.cheysoff.core_blocks.BlocksSave
import my.cheysoff.core_blocks.OpenDocument
import my.cheysoff.core_domain.model.AttachmentData
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NoteContentFormat
import my.cheysoff.core_domain.model.SketchData
import my.cheysoff.core_domain.repository.NotesRepository
import my.cheysoff.desktop.store.DesktopAttachments
import my.cheysoff.desktop.store.DesktopSketches
import my.cheysoff.desktop.ui.attachment.DesktopImportResult
import my.cheysoff.desktop.ui.preview.InMemoryNotesRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The desktop's BLOCKS path through [NotesWorkspaceModel]: what document the shared editor is
 * handed for the open note, and what a local edit saves.
 *
 * The write flag is injected (`blocksWriteEnabled`) rather than read from
 * `BlocksFormat.WRITE_ENABLED`, which stays false until Task 16; both settings are exercised so the
 * legacy path is proven untouched while the flag is off.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesWorkspaceBlocksTest {

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun cancelModelScopes() {
        scopes.forEach { it.cancel() }
    }

    /** Records every `saveNote` with its `blocksBase`, and stores the note as given. */
    private class RecordingRepository(
        val inner: InMemoryNotesRepository,
    ) : NotesRepository by inner {
        val saves = mutableListOf<Pair<Note, String?>>()

        override suspend fun saveNote(note: Note, blocksBase: String?) {
            saves += note to blocksBase
            inner.saveNote(note, blocksBase)
        }
    }

    private class FakeAttachments(initial: List<AttachmentData> = emptyList()) : DesktopAttachments {
        val rows = MutableStateFlow(initial)
        override fun getAttachmentsForNote(noteId: String): Flow<List<AttachmentData>> =
            rows.map { all -> all.filter { it.noteId == noteId && !it.isDeleted } }

        override suspend fun deleteAttachment(id: String) {
            rows.value = rows.value.filterNot { it.id == id }
        }

        override suspend fun saveAttachment(attachment: AttachmentData) {
            rows.value = rows.value + attachment
        }
    }

    private class FakeSketches(initial: List<SketchData> = emptyList()) : DesktopSketches {
        val rows = MutableStateFlow(initial)
        override fun getSketchesForNote(noteId: String): Flow<List<SketchData>> =
            rows.map { all -> all.filter { it.noteId == noteId && !it.isDeleted } }

        override suspend fun deleteSketch(id: String) {
            rows.value = rows.value.filterNot { it.id == id }
        }
    }

    private fun note(
        id: String,
        content: String = "Hello",
        format: NoteContentFormat = NoteContentFormat.PLAIN,
        checklist: String = "",
    ) = Note(
        id = id,
        title = "T-$id",
        content = content,
        contentFormat = format,
        checklist = checklist,
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    private fun attachment(id: String, noteId: String) = AttachmentData(
        id = id, noteId = noteId, anchor = 0, order = 0, mimeType = "image/jpeg",
        width = 10, height = 10, bytes = ByteArray(1), thumbWidth = 10, thumbHeight = 10,
        thumbBytes = ByteArray(1), createdAt = 1_000L, updatedAt = 1_000L,
    )

    private fun sketch(id: String, noteId: String) = SketchData(
        id = id, noteId = noteId, anchor = 0, order = 0,
        strokes = "1|10x10|ff000000,4:0,0", createdAt = 1_000L, updatedAt = 1_000L,
    )

    private fun TestScope.model(
        repository: NotesRepository,
        attachments: DesktopAttachments = FakeAttachments(),
        sketches: DesktopSketches = FakeSketches(),
        writeEnabled: Boolean,
    ): NotesWorkspaceModel {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        scopes += scope
        return NotesWorkspaceModel(
            repository = repository,
            scope = scope,
            sketches = sketches,
            attachments = attachments,
            now = { 5_000L },
            encodeDispatcher = StandardTestDispatcher(testScheduler),
            blocksWriteEnabled = writeEnabled,
        )
    }

    @Test
    fun `opening a legacy note shows its body, checklist and pictures as blocks in order`() = runTest {
        val legacy = note("n1", content = "<p>Hello</p><p>World</p>", format = NoteContentFormat.HTML, checklist = "0milk\n1eggs")
        val photo = attachment("a1", "n1")
        val drawing = sketch("s1", "n1")
        val model = model(
            RecordingRepository(InMemoryNotesRepository(listOf(legacy))),
            FakeAttachments(listOf(photo)),
            FakeSketches(listOf(drawing)),
            writeEnabled = true,
        )
        advanceUntilIdle()
        model.selectNote("n1")
        advanceUntilIdle()

        val draft = model.state.value.editor!!
        val doc = draft.blocks
        assertNotNull("the open draft carries its assembled document", doc)
        assertEquals(OpenDocument.assemble(legacy, listOf(photo), listOf(drawing)), doc)
        assertEquals(
            listOf(BlockType.PARAGRAPH, BlockType.PARAGRAPH, BlockType.CHECK, BlockType.CHECK, BlockType.DRAWING, BlockType.PICTURE),
            doc!!.visible().map { it.type },
        )
        assertEquals(listOf("Hello", "World", "milk", "eggs"), doc.visible().take(4).map { it.text })
        assertEquals(listOf("s1", "a1"), doc.visible().drop(4).map { it.ref })
        assertNull(draft.unreadableBody)
    }

    @Test
    fun `a picture that arrives later re-assembles the open document`() = runTest {
        val attachments = FakeAttachments()
        val model = model(RecordingRepository(InMemoryNotesRepository(listOf(note("n1")))), attachments, writeEnabled = true)
        advanceUntilIdle()
        model.selectNote("n1")
        advanceUntilIdle()
        assertEquals(1, model.state.value.editor!!.blocks!!.visible().size)

        attachments.rows.value = listOf(attachment("a1", "n1"))
        advanceUntilIdle()

        val doc = model.state.value.editor!!.blocks!!
        assertEquals(BlockIds.forPicture("a1"), doc.visible().last().id)
    }

    @Test
    fun `edits save as BLOCKS when writing is enabled`() = runTest {
        val repository = RecordingRepository(InMemoryNotesRepository(listOf(note("n1"))))
        val model = model(repository, writeEnabled = true)
        advanceUntilIdle()
        model.selectNote("n1")
        advanceUntilIdle()
        val base = model.state.value.editor!!.blocks!!
        val edited = base.visible().map { it.copy(text = it.text + "!") } +
            Block(id = "new", type = BlockType.CHECK, text = "buy milk")
        repository.saves.clear()

        model.onBlocksChanged("n1", edited, base)
        advanceTimeBy(399)
        runCurrent()
        assertTrue("debounced: nothing written within 400 ms", repository.saves.isEmpty())
        advanceTimeBy(2)
        runCurrent()
        advanceUntilIdle()

        val (saved, blocksBase) = repository.saves.single()
        assertEquals(NoteContentFormat.BLOCKS, saved.contentFormat)
        assertEquals(BlocksSave.encodeForSave(edited), saved.content)
        assertEquals(BlockCodec.encode(base), blocksBase)
        assertEquals("T-n1", saved.title)
    }

    @Test
    fun `a pending block save is flushed when the user switches notes`() = runTest {
        val repository = RecordingRepository(InMemoryNotesRepository(listOf(note("n1"), note("n2"))))
        val model = model(repository, writeEnabled = true)
        advanceUntilIdle()
        model.selectNote("n1")
        advanceUntilIdle()
        val base = model.state.value.editor!!.blocks!!
        val edited = base.visible().map { it.copy(text = "typed") }
        repository.saves.clear()

        model.onBlocksChanged("n1", edited, base)
        model.selectNote("n2")
        advanceUntilIdle()

        val (saved, blocksBase) = repository.saves.single()
        assertEquals("n1", saved.id)
        assertEquals(BlocksSave.encodeForSave(edited), saved.content)
        assertEquals(BlockCodec.encode(base), blocksBase)
        assertEquals("n2", model.state.value.editor!!.id)
    }

    @Test
    fun `a title edit after a saved block edit keeps the stored BLOCKS body and claims no blocks`() = runTest {
        val repository = RecordingRepository(InMemoryNotesRepository(listOf(note("n1"))))
        val model = model(repository, writeEnabled = true)
        advanceUntilIdle()
        model.selectNote("n1")
        advanceUntilIdle()
        val base = model.state.value.editor!!.blocks!!
        val edited = base.visible().map { it.copy(text = "typed") }
        model.onBlocksChanged("n1", edited, base)
        advanceUntilIdle()
        repository.saves.clear()

        model.setTitle("New title")
        advanceUntilIdle()

        // The body sent is the stored one, against no base (so the stored body is the base): the
        // already-written edit is not re-sent, which could claim blocks synced in since.
        val (saved, blocksBase) = repository.saves.single()
        assertEquals("New title", saved.title)
        assertEquals(NoteContentFormat.BLOCKS, saved.contentFormat)
        assertEquals(BlocksSave.encodeForSave(edited), saved.content)
        assertNull(blocksBase)
    }

    @Test
    fun `an edit for a note that is no longer open is not saved over the open one`() = runTest {
        val repository = RecordingRepository(InMemoryNotesRepository(listOf(note("n1"), note("n2"))))
        val model = model(repository, writeEnabled = true)
        advanceUntilIdle()
        model.selectNote("n2")
        advanceUntilIdle()
        repository.saves.clear()

        model.onBlocksChanged("n1", listOf(Block(id = "x", type = BlockType.PARAGRAPH, text = "stray")), BlockDocument(emptyList()))
        advanceUntilIdle()

        assertTrue(repository.saves.isEmpty())
    }

    @Test
    fun `with writing disabled the legacy path stays and block edits are not saved`() = runTest {
        val repository = RecordingRepository(InMemoryNotesRepository(listOf(note("n1"))))
        val model = model(repository, writeEnabled = false)
        advanceUntilIdle()
        model.selectNote("n1")
        advanceUntilIdle()
        assertFalse(model.blocksEditing)
        assertNull("no document is assembled for the legacy editor", model.state.value.editor!!.blocks)
        repository.saves.clear()

        model.onBlocksChanged("n1", listOf(Block(id = "x", type = BlockType.PARAGRAPH, text = "typed")), BlockDocument(emptyList()))
        advanceUntilIdle()

        assertTrue(repository.saves.isEmpty())
    }

    @Test
    fun `an unreadable BLOCKS body opens read-only and is never saved`() = runTest {
        val raw = "{\"v\":99,\"future\":true}"
        val repository = RecordingRepository(InMemoryNotesRepository(listOf(note("n1", content = raw, format = NoteContentFormat.BLOCKS))))
        val model = model(repository, writeEnabled = true)
        advanceUntilIdle()
        model.selectNote("n1")
        advanceUntilIdle()
        val draft = model.state.value.editor!!
        assertEquals(raw, draft.unreadableBody)
        repository.saves.clear()

        model.onBlocksChanged("n1", listOf(Block(id = "x", type = BlockType.PARAGRAPH, text = "typed")), draft.blocks ?: BlockDocument(emptyList()))
        advanceUntilIdle()

        assertTrue(repository.saves.isEmpty())
    }

    @Test
    fun `a pasted image is saved first and then handed to the editor by record id`() = runTest {
        val attachments = FakeAttachments()
        val model = model(RecordingRepository(InMemoryNotesRepository(listOf(note("n1")))), attachments, writeEnabled = true)
        advanceUntilIdle()
        model.selectNote("n1")
        advanceUntilIdle()
        var insertedId: String? = null
        var savedWhenInserted = false

        model.pasteImage(
            onAttached = { id ->
                insertedId = id
                savedWhenInserted = attachments.rows.value.any { it.id == id }
            },
        ) { DesktopImportResult.Imported(attachment("ignored", "ignored")) }
        advanceUntilIdle()

        val saved = attachments.rows.value.single()
        assertEquals(saved.id, insertedId)
        assertTrue("the record exists before the block that points at it", savedWhenInserted)
    }
}
