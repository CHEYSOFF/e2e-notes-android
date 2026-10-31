package my.cheysoff.desktop.ui.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
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
import my.cheysoff.core_domain.model.NotesSortOrder
import my.cheysoff.core_domain.model.SketchData
import my.cheysoff.core_domain.repository.NotesRepository
import kotlinx.coroutines.flow.first
import my.cheysoff.core_crypto.sync.AccountRootKey
import my.cheysoff.core_sync_codec.RecordCodec
import my.cheysoff.desktop.store.DesktopAttachments
import my.cheysoff.desktop.store.RecordNotesRepository
import my.cheysoff.desktop.store.RecordStore
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
 * A note stored as PLAIN or HTML opens converted and is written as BLOCKS on its first edit.
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

        /**
         * While true, a note flow obtained BEFORE it was set emits nothing: the model's open-note
         * subscription goes stale, as when the store has merged something in that the model has
         * not been told about yet. A flow obtained while it is set (a fresh read) sees the store.
         */
        var holdEmissions = false

        override fun getNoteById(id: String): Flow<Note?> {
            val fresh = holdEmissions
            return inner.getNoteById(id).filter { fresh || !holdEmissions }
        }

        override fun getNotes(sortOrder: NotesSortOrder): Flow<List<Note>> {
            val fresh = holdEmissions
            return inner.getNotes(sortOrder).filter { fresh || !holdEmissions }
        }

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
        )
    }

    @Test
    fun `a title edit of a BLOCKS note keeps its leftover checklist`() = runTest {
        // The real repository, because the rule lives in its save (BlocksSave.Write): a save with
        // no editor base must not clear a checklist whose items are only in the editor's copy.
        val store = RecordStore.inMemory("title-${java.util.UUID.randomUUID()}")
        try {
            val repository = RecordNotesRepository.load(
                store, RecordCodec(AccountRootKey.derive(AccountRootKey.generateArk())), node = "abcd1234",
            ) { 1_000_000L }
            repository.saveNote(note("n1", content = "body", checklist = "0milk"))
            val body = BlocksSave.encodeForSave(listOf(Block("p", BlockType.PARAGRAPH, text = "body")))
            repository.saveNote(note("n1", content = body, format = NoteContentFormat.BLOCKS, checklist = "0milk"))
            val model = model(repository, repository, repository)
            advanceUntilIdle()
            model.selectNote("n1")
            advanceUntilIdle()

            model.setTitle("Renamed")
            advanceUntilIdle()

            val stored = repository.getNoteById("n1").first()!!
            assertEquals("Renamed", stored.title)
            assertEquals("0milk", stored.checklist)
        } finally {
            store.close()
        }
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
        val model = model(RecordingRepository(InMemoryNotesRepository(listOf(note("n1")))), attachments)
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
        val model = model(repository)
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
        val model = model(repository)
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
        val model = model(repository)
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
    fun `a title-only save keeps a body the store merged in after the last emission`() = runTest {
        val body = BlocksSave.encodeForSave(listOf(Block("a", BlockType.PARAGRAPH, text = "one")))
        val repository = RecordingRepository(
            InMemoryNotesRepository(listOf(note("n1", content = body, format = NoteContentFormat.BLOCKS))),
        )
        val model = model(repository)
        advanceUntilIdle()
        model.selectNote("n1")
        advanceUntilIdle()

        // A sync merges a block into the stored row; the model's subscription has not caught up.
        repository.holdEmissions = true
        val merged = BlocksSave.encodeForSave(
            listOf(Block("a", BlockType.PARAGRAPH, text = "one"), Block("b", BlockType.PARAGRAPH, text = "synced")),
        )
        repository.inner.saveNote(note("n1", content = merged, format = NoteContentFormat.BLOCKS), null)
        repository.saves.clear()

        model.setTitle("Renamed")
        advanceUntilIdle()

        val (saved, blocksBase) = repository.saves.single()
        assertEquals("Renamed", saved.title)
        assertEquals("the stored body is kept, not the draft's stale one", merged, saved.content)
        assertNull(blocksBase)
        repository.holdEmissions = false
        val stored = repository.inner.getNoteById("n1").first()!!
        assertEquals("Renamed", stored.title)
        assertEquals(merged, stored.content)
    }

    @Test
    fun `deleting a note right after typing puts what was on screen in Trash`() = runTest {
        val repository = RecordingRepository(InMemoryNotesRepository(listOf(note("n1"))))
        val model = model(repository)
        advanceUntilIdle()
        model.selectNote("n1")
        advanceUntilIdle()
        val base = model.state.value.editor!!.blocks!!
        val edited = base.visible().map { it.copy(text = "typed just now") }

        model.onBlocksChanged("n1", edited, base) // still inside the save debounce
        model.deleteSelectedNote()
        advanceUntilIdle()

        val trashed = repository.inner.getDeletedNotes().first().single()
        assertEquals("n1", trashed.id)
        assertEquals(NoteContentFormat.BLOCKS, trashed.contentFormat)
        assertEquals(BlocksSave.encodeForSave(edited), trashed.content)
    }

    @Test
    fun `an edit for a note that is no longer open is not saved over the open one`() = runTest {
        val repository = RecordingRepository(InMemoryNotesRepository(listOf(note("n1"), note("n2"))))
        val model = model(repository)
        advanceUntilIdle()
        model.selectNote("n2")
        advanceUntilIdle()
        repository.saves.clear()

        model.onBlocksChanged("n1", listOf(Block(id = "x", type = BlockType.PARAGRAPH, text = "stray")), BlockDocument(emptyList()))
        advanceUntilIdle()

        assertTrue(repository.saves.isEmpty())
    }

    @Test
    fun `an unreadable BLOCKS body opens read-only and is never saved`() = runTest {
        val raw = "{\"v\":99,\"future\":true}"
        val repository = RecordingRepository(InMemoryNotesRepository(listOf(note("n1", content = raw, format = NoteContentFormat.BLOCKS))))
        val model = model(repository)
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
        val model = model(RecordingRepository(InMemoryNotesRepository(listOf(note("n1")))), attachments)
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

    /** A repository whose writes take [writeMillis] of virtual time, or never finish when [hang]. */
    private class SlowRepository(
        val inner: InMemoryNotesRepository,
        private val writeMillis: Long = 500L,
        private val hang: Boolean = false,
    ) : NotesRepository by inner {
        val finished = mutableListOf<Pair<Note, String?>>()

        override suspend fun saveNote(note: Note, blocksBase: String?) {
            if (hang) CompletableDeferred<Unit>().await()
            delay(writeMillis)
            inner.saveNote(note, blocksBase)
            finished += note to blocksBase
        }
    }

    @Test
    fun `typing followed by an immediate lock persists the text first`() = runTest {
        val repository = SlowRepository(InMemoryNotesRepository(listOf(note("n1"))))
        val model = model(repository)
        advanceUntilIdle()
        model.selectNote("n1")
        advanceUntilIdle()
        val base = model.state.value.editor!!.blocks!!
        val edited = base.visible().map { it.copy(text = "typed just before locking") }
        var savedAtLock: List<Note>? = null

        model.onBlocksChanged("n1", edited, base) // still inside the 400 ms debounce
        model.flushThen { savedAtLock = repository.finished.map { it.first } }
        advanceUntilIdle()

        val saved = savedAtLock!!.single()
        assertEquals(NoteContentFormat.BLOCKS, saved.contentFormat)
        assertEquals(BlocksSave.encodeForSave(edited), saved.content)
    }

    @Test
    fun `a hung save does not hang the lock past the timeout`() = runTest {
        val repository = SlowRepository(InMemoryNotesRepository(listOf(note("n1"))), hang = true)
        val model = model(repository)
        advanceUntilIdle()
        model.selectNote("n1")
        advanceUntilIdle()
        var lockedAt: Long? = null
        val start = currentTime
        val base = model.state.value.editor!!.blocks!!

        model.onBlocksChanged("n1", base.visible().map { it.copy(text = "typed") }, base)
        model.flushThen { lockedAt = currentTime }
        advanceTimeBy(NotesWorkspaceModel.FLUSH_TIMEOUT_MILLIS + 1)
        runCurrent()

        assertEquals(start + NotesWorkspaceModel.FLUSH_TIMEOUT_MILLIS, lockedAt)
    }

    @Test
    fun `an image paste into a read-only note is refused before any record is written`() = runTest {
        val attachments = FakeAttachments()
        val raw = "{\"v\":99}"
        val model = model(
            RecordingRepository(InMemoryNotesRepository(listOf(note("n1", content = raw, format = NoteContentFormat.BLOCKS)))),
            attachments,
        )
        advanceUntilIdle()
        model.selectNote("n1")
        advanceUntilIdle()
        var placed = false

        val job = model.pasteImage(onAttached = { placed = true }) { DesktopImportResult.Imported(attachment("x", "x")) }
        advanceUntilIdle()

        assertNull(job)
        assertTrue(attachments.rows.value.isEmpty())
        assertFalse(placed)
    }

    @Test
    fun `an image paste before the document is assembled is refused`() = runTest {
        val attachments = FakeAttachments()
        val model = model(RecordingRepository(InMemoryNotesRepository(listOf(note("n1"), note("n2")))), attachments)
        advanceUntilIdle()
        model.selectNote("n1")
        advanceUntilIdle()
        model.selectNote("n2") // the draft exists; its document has not been assembled yet
        assertEquals("n2", model.state.value.editor!!.id)
        assertNull(model.state.value.editor!!.blocks)

        val job = model.pasteImage(onAttached = {}) { DesktopImportResult.Imported(attachment("x", "x")) }
        advanceUntilIdle()

        assertNull(job)
        assertTrue(attachments.rows.value.isEmpty())
    }
}
