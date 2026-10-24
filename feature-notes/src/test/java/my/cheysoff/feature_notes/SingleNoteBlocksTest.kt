package my.cheysoff.feature_notes

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import my.cheysoff.core_domain.model.AttachmentData
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NoteContentFormat
import my.cheysoff.core_domain.model.SketchData
import my.cheysoff.core_domain.sketch.Point
import my.cheysoff.core_domain.sketch.Sketch
import my.cheysoff.core_domain.sketch.Stroke
import my.cheysoff.core_domain.sketch.StrokeCodec
import my.cheysoff.feature_notes.model.single.SingleNoteIntent
import my.cheysoff.feature_notes.ui.attachment.ImageImporter
import my.cheysoff.feature_notes.ui.attachment.ImportResult
import my.cheysoff.feature_notes.ui.single.SingleNoteEvent
import my.cheysoff.feature_notes.ui.single.SingleNoteViewModel
import my.cheysoff.feature_notes.ui.single.toPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The phone's block path (Task 15): the note screen's ViewModel assembles the block editor's
 * document, saves the editor's edits as BLOCKS against the editor's base, and flushes on the way
 * out -- the same contract the desktop's `NotesWorkspaceModel` keeps (Task 14).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SingleNoteBlocksTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repo = FakeNotesRepository()
    private val sketchRepo = FakeSketchesRepository()
    private val attachmentRepo = FakeAttachmentsRepository()
    private var imageImporter: ImageImporter = FakeImageImporter()
    private val settingsRepo = FakeSettingsRepository()
    private val logged = mutableListOf<String>()

    private companion object {
        const val NOTE_ID = "n1"
    }

    private fun note(
        title: String = "",
        content: String = "",
        contentFormat: NoteContentFormat = NoteContentFormat.PLAIN,
        checklist: String = "",
    ) = Note(
        id = NOTE_ID,
        title = title,
        content = content,
        contentFormat = contentFormat,
        checklist = checklist,
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    private fun blocksNote(vararg texts: String, title: String = "") = note(
        title = title,
        content = BlocksSave.encodeForSave(texts.mapIndexed { i, t -> Block("b$i", BlockType.PARAGRAPH, text = t) }),
        contentFormat = NoteContentFormat.BLOCKS,
    )

    private fun sketchData(id: String) = SketchData(
        id = id,
        noteId = NOTE_ID,
        anchor = 0,
        order = 0,
        strokes = StrokeCodec.encode(
            Sketch(4096, 4096, listOf(Stroke(0xFF000000L, 8, listOf(Point(0, 0), Point(1, 1))))),
        ),
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    private fun attachmentData(id: String) = AttachmentData(
        id = id,
        noteId = NOTE_ID,
        anchor = 0,
        order = 0,
        mimeType = "image/jpeg",
        width = 100,
        height = 100,
        bytes = ByteArray(1),
        thumbWidth = 10,
        thumbHeight = 10,
        thumbBytes = ByteArray(1),
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    private fun viewModel(enabled: Boolean = true, isNew: Boolean = false): SingleNoteViewModel {
        val args = buildMap<String, Any> {
            put("noteId", NOTE_ID)
            if (isNew) put("isNew", true)
        }
        return SingleNoteViewModel(
            repo, sketchRepo, attachmentRepo, imageImporter, settingsRepo, SavedStateHandle(args),
            blocksWriteEnabled = enabled,
            log = { logged += it },
        )
    }

    private fun TestScope.collectEvents(vm: SingleNoteViewModel): List<SingleNoteEvent> {
        val events = mutableListOf<SingleNoteEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        return events
    }

    private fun SingleNoteViewModel.document(): BlockDocument =
        checkNotNull(state.value.blockDocument) { "no document assembled" }.document

    private fun SingleNoteViewModel.edit(blocks: List<Block>) =
        onIntent(SingleNoteIntent.BlocksChanged(NOTE_ID, blocks, document()))

    // ------------------------------------------------------------------ the brief's three

    @Test
    fun `opening a legacy note yields blocks in order`() = runTest {
        repo.noteById.value = note(
            content = "<p>first</p><p>second</p>",
            contentFormat = NoteContentFormat.HTML,
            checklist = "0milk",
        )
        sketchRepo.sketchesByNote.value = listOf(sketchData("s1"))
        attachmentRepo.attachmentsByNote.value = listOf(attachmentData("a1").toPreview())
        val vm = viewModel()
        runCurrent()

        val opened = assertNotNullAndGet(vm.state.value.blockDocument)
        assertEquals(NOTE_ID, opened.noteId)
        assertNull(opened.unreadableBody)
        val blocks = opened.document.visible()
        assertEquals(
            listOf(BlockType.PARAGRAPH, BlockType.PARAGRAPH, BlockType.CHECK, BlockType.DRAWING, BlockType.PICTURE),
            blocks.map { it.type },
        )
        assertEquals(listOf("first", "second", "milk"), blocks.take(3).map { it.text })
        assertEquals(listOf("s1", "a1"), blocks.drop(3).map { it.ref })
        assertTrue(vm.state.value.blocksEditing)
    }

    @Test
    fun `edits save as BLOCKS when enabled`() = runTest {
        repo.noteById.value = note(content = "hello")
        val vm = viewModel()
        runCurrent()
        val base = vm.document()
        val edited = base.visible() + Block("new", BlockType.CHECK, text = "buy milk")

        vm.edit(edited)
        advanceTimeBy(399)
        runCurrent()
        assertEquals("debounced: nothing written yet", 0, repo.upsertCount())

        advanceTimeBy(2)
        runCurrent()
        val saved = repo.savedNotes.single()
        assertEquals(NoteContentFormat.BLOCKS, saved.contentFormat)
        assertEquals(BlocksSave.encodeForSave(edited), saved.content)
        assertEquals(BlockCodec.encode(base), repo.savedBlocksBases.single())
    }

    @Test
    fun `a remote emission reaches the editor document without a contentRevision bump`() = runTest {
        repo.noteById.value = blocksNote("one")
        val vm = viewModel()
        runCurrent()
        assertEquals(listOf("one"), vm.document().visible().map { it.text })

        repo.noteById.value = blocksNote("one", "two from the laptop")
        runCurrent()

        assertEquals(listOf("one", "two from the laptop"), vm.document().visible().map { it.text })
        assertEquals(0, vm.state.value.contentRevision)
    }

    // ------------------------------------------------------------------ the switch

    @Test
    fun `with the flag off nothing is assembled and an edit is not saved`() = runTest {
        repo.noteById.value = note(content = "hello")
        val vm = viewModel(enabled = false)
        runCurrent()
        assertFalse(vm.state.value.blocksEditing)
        assertNull(vm.state.value.blockDocument)

        vm.onIntent(SingleNoteIntent.BlocksChanged(NOTE_ID, listOf(Block("x", BlockType.PARAGRAPH, text = "x")), BlockDocument(emptyList())))
        advanceUntilIdle()

        assertEquals(0, repo.upsertCount())
        assertTrue(logged.single().contains("WRITE_ENABLED"))
    }

    @Test
    fun `an edit of another note is ignored`() = runTest {
        repo.noteById.value = note(content = "hello")
        val vm = viewModel()
        runCurrent()

        vm.onIntent(SingleNoteIntent.BlocksChanged("someone-else", vm.document().visible(), vm.document()))
        advanceUntilIdle()

        assertEquals(0, repo.upsertCount())
    }

    // ------------------------------------------------------------------ unreadable body

    @Test
    fun `an unreadable BLOCKS body opens read-only, is never saved and refuses pictures`() = runTest {
        repo.noteById.value = note(content = "{\"v\":99,\"future\":true", contentFormat = NoteContentFormat.BLOCKS)
        val vm = viewModel()
        runCurrent()
        assertEquals("{\"v\":99,\"future\":true", vm.state.value.blockDocument?.unreadableBody)

        vm.edit(listOf(Block("x", BlockType.PARAGRAPH, text = "typed anyway")))
        vm.onIntent(SingleNoteIntent.ImportAttachment("content://photo"))
        vm.onIntent(SingleNoteIntent.SketchSaved(null, Sketch(10, 10, emptyList())))
        advanceUntilIdle()

        assertTrue(repo.savedNotes.none { it.contentFormat == NoteContentFormat.BLOCKS && it.content.contains("typed anyway") })
        assertTrue((imageImporter as FakeImageImporter).requested.isEmpty())
        assertTrue(sketchRepo.saved.isEmpty())
    }

    // ------------------------------------------------------------------ flushing

    @Test
    fun `FlushPendingSave writes the debounced edit at once`() = runTest {
        repo.noteById.value = note(content = "hello")
        val vm = viewModel()
        runCurrent()
        val edited = vm.document().visible() + Block("new", BlockType.PARAGRAPH, text = "more")

        vm.edit(edited)
        vm.onIntent(SingleNoteIntent.FlushPendingSave)
        runCurrent()

        assertEquals(BlocksSave.encodeForSave(edited), repo.savedNotes.single().content)
        advanceUntilIdle()
        assertEquals("the debounce does not write it a second time", 1, repo.upsertCount())
    }

    @Test
    fun `FlushPendingSave with nothing pending writes nothing`() = runTest {
        repo.noteById.value = note(content = "hello")
        val vm = viewModel()
        runCurrent()

        vm.onIntent(SingleNoteIntent.FlushPendingSave)
        advanceUntilIdle()

        assertEquals(0, repo.upsertCount())
    }

    @Test
    fun `a flushed save completes even when the ViewModel's scope is cancelled mid-write`() = runTest {
        repo.noteById.value = note(content = "hello")
        val vm = viewModel()
        runCurrent()
        repo.gate("saveNote")
        vm.edit(vm.document().visible() + Block("new", BlockType.PARAGRAPH, text = "more"))
        vm.onIntent(SingleNoteIntent.FlushPendingSave)
        runCurrent()
        assertEquals("the write has started", 1, repo.upsertCount())

        // What a navigation that clears the back stack (the lock on ON_STOP) does to the screen.
        vm.viewModelStore().clear()
        repo.release("saveNote")
        advanceUntilIdle()

        assertEquals(1, repo.completedSaves.size)
    }

    @Test
    fun `a debounced save still pending when the ViewModel is cleared is written`() = runTest {
        repo.noteById.value = note(content = "hello")
        val vm = viewModel()
        runCurrent()
        val edited = vm.document().visible() + Block("new", BlockType.PARAGRAPH, text = "more")
        vm.edit(edited)

        vm.viewModelStore().clear()
        advanceUntilIdle()

        assertEquals(BlocksSave.encodeForSave(edited), repo.completedSaves.single().content)
    }

    @Test
    fun `back flushes the pending block save before navigating`() = runTest {
        repo.noteById.value = note(content = "hello")
        val vm = viewModel()
        val events = collectEvents(vm)
        runCurrent()
        val edited = vm.document().visible() + Block("new", BlockType.PARAGRAPH, text = "more")

        vm.edit(edited)
        vm.onIntent(SingleNoteIntent.BackClicked)
        runCurrent()

        assertEquals(BlocksSave.encodeForSave(edited), repo.savedNotes.single().content)
        assertEquals(listOf(SingleNoteEvent.NavigateBack), events)
    }

    // ------------------------------------------------------------------ title saves

    @Test
    fun `a title edit with no block edit keeps the stored body`() = runTest {
        repo.noteById.value = blocksNote("kept")
        val vm = viewModel()
        runCurrent()

        vm.onIntent(SingleNoteIntent.TitleChanged("A title"))
        advanceUntilIdle()

        val saved = repo.savedNotes.single()
        assertEquals("A title", saved.title)
        assertEquals(blocksNote("kept").content, saved.content)
        assertEquals(NoteContentFormat.BLOCKS, saved.contentFormat)
        assertNull(repo.savedBlocksBases.single())
        assertFalse("the block path keeps no title history", vm.state.value.canUndo)
    }

    @Test
    fun `a title-only save of a BLOCKS note sends its leftover checklist unchanged and no base`() = runTest {
        // With no base the repository keeps the stored checklist (BlocksSave.Write); the ViewModel's
        // part is to send no base and the stored checklist, never an emptied one.
        repo.noteById.value = blocksNote("kept").copy(checklist = "0milk")
        val vm = viewModel()
        runCurrent()

        vm.onIntent(SingleNoteIntent.TitleChanged("Renamed"))
        advanceUntilIdle()

        assertEquals("0milk", repo.savedNotes.single().checklist)
        assertNull(repo.savedBlocksBases.single())
    }

    // ------------------------------------------------------------------ pictures and drawings

    @Test
    fun `back waits for every picture import, not only the last one`() = runTest {
        val gates = mapOf("content://one" to CompletableDeferred<Unit>(), "content://two" to CompletableDeferred())
        val template = (FakeImageImporter().result as ImportResult.Imported).attachment
        imageImporter = object : ImageImporter {
            override suspend fun import(uri: String): ImportResult {
                gates.getValue(uri).await()
                return ImportResult.Imported(template)
            }
        }
        repo.noteById.value = note()
        val vm = viewModel(isNew = true)
        val events = collectEvents(vm)
        runCurrent()

        vm.onIntent(SingleNoteIntent.ImportAttachment("content://one"))
        vm.onIntent(SingleNoteIntent.ImportAttachment("content://two"))
        gates.getValue("content://two").complete(Unit)
        runCurrent()
        vm.onIntent(SingleNoteIntent.BackClicked)
        runCurrent()
        assertTrue("back must still be waiting for the first import", events.isEmpty())

        gates.getValue("content://one").complete(Unit)
        advanceUntilIdle()

        assertEquals(2, attachmentRepo.saved.size)
        assertTrue(repo.callsNamed("purgeNote").isEmpty())
        assertEquals(listOf(SingleNoteEvent.NavigateBack), events)
    }

    @Test
    fun `a drawing refused on a read-only note is reported, not dropped silently`() = runTest {
        repo.noteById.value = note(content = "{\"v\":99", contentFormat = NoteContentFormat.BLOCKS)
        val vm = viewModel()
        val events = collectEvents(vm)
        runCurrent()

        vm.onIntent(SingleNoteIntent.SketchSaved(null, Sketch(10, 10, emptyList())))
        vm.onIntent(SingleNoteIntent.ImportAttachment("content://photo"))
        advanceUntilIdle()

        assertEquals(
            listOf(SingleNoteEvent.PictureRefused(isDrawing = true), SingleNoteEvent.PictureRefused(isDrawing = false)),
            events,
        )
        assertTrue(sketchRepo.saved.isEmpty())
    }

    @Test
    fun `an imported picture stays out of the document until the editor places it`() = runTest {
        repo.noteById.value = note(content = "hello")
        val vm = viewModel()
        runCurrent()

        vm.onIntent(SingleNoteIntent.ImportAttachment("content://photo"))
        advanceUntilIdle()
        val record = attachmentRepo.saved.single()
        // The repository's own echo of the new record.
        attachmentRepo.attachmentsByNote.value = listOf(record.toPreview())
        runCurrent()

        assertEquals(listOf(record.id), vm.state.value.picturesToPlace.map { it.recordId })
        assertEquals(BlockType.PICTURE, vm.state.value.picturesToPlace.single().kind)
        assertTrue(vm.document().visible().none { it.ref == record.id })

        vm.onIntent(SingleNoteIntent.PicturePlaced(record.id))
        runCurrent()

        assertTrue(vm.state.value.picturesToPlace.isEmpty())
        assertEquals(BlockIds.forPicture(record.id), vm.document().visible().last().id)
    }

    @Test
    fun `a new drawing is placed by the editor, a re-edited one is not`() = runTest {
        repo.noteById.value = note(content = "hello")
        sketchRepo.sketchesByNote.value = listOf(sketchData("old"))
        val vm = viewModel()
        runCurrent()

        vm.onIntent(SingleNoteIntent.SketchSaved("old", Sketch(10, 10, emptyList())))
        vm.onIntent(SingleNoteIntent.SketchSaved(null, Sketch(10, 10, emptyList())))
        advanceUntilIdle()

        val fresh = sketchRepo.saved.last()
        assertEquals(listOf(fresh.id), vm.state.value.picturesToPlace.map { it.recordId })
        assertEquals(BlockType.DRAWING, vm.state.value.picturesToPlace.single().kind)
        sketchRepo.sketchesByNote.value = listOf(sketchData("old"), fresh)
        runCurrent()
        assertTrue(vm.document().visible().none { it.ref == fresh.id })
    }

    // ------------------------------------------------------------------ blank-note discard

    @Test
    fun `a new note left blank is purged on back`() = runTest {
        repo.noteById.value = note()
        val vm = viewModel(isNew = true)
        runCurrent()

        vm.edit(listOf(Block("p", BlockType.PARAGRAPH, text = "   ")))
        vm.onIntent(SingleNoteIntent.BackClicked)
        advanceUntilIdle()

        assertEquals(listOf("purgeNote(n1)"), repo.callsNamed("purgeNote"))
        assertEquals(0, repo.upsertCount())
    }

    @Test
    fun `a new note whose only content is a picture block is kept`() = runTest {
        repo.noteById.value = note()
        val vm = viewModel(isNew = true)
        runCurrent()

        vm.edit(listOf(Block(BlockIds.forPicture("a9"), BlockType.PICTURE, ref = "a9")))
        vm.onIntent(SingleNoteIntent.BackClicked)
        advanceUntilIdle()

        assertTrue(repo.callsNamed("purgeNote").isEmpty())
        assertEquals(NoteContentFormat.BLOCKS, repo.savedNotes.single().contentFormat)
    }

    @Test
    fun `a new note with a drawing record and no edit is kept`() = runTest {
        repo.noteById.value = note()
        sketchRepo.sketchesByNote.value = listOf(sketchData("s1"))
        val vm = viewModel(isNew = true)
        runCurrent()

        vm.onIntent(SingleNoteIntent.BackClicked)
        advanceUntilIdle()

        assertTrue(repo.callsNamed("purgeNote").isEmpty())
    }

    private fun <T : Any> assertNotNullAndGet(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}

/** A store owning [this], so a test can clear it the way a back-stack pop does. */
private fun SingleNoteViewModel.viewModelStore(): androidx.lifecycle.ViewModelStore =
    androidx.lifecycle.ViewModelStore().also { it.put("vm", this) }
