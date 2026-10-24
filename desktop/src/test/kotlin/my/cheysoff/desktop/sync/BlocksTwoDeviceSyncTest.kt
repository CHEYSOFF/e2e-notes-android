package my.cheysoff.desktop.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import my.cheysoff.core_blocks.Block
import my.cheysoff.core_blocks.BlockCodec
import my.cheysoff.core_blocks.BlockDocument
import my.cheysoff.core_blocks.BlockIds
import my.cheysoff.core_blocks.BlockMerge
import my.cheysoff.core_blocks.BlockType
import my.cheysoff.core_blocks.BlocksSave
import my.cheysoff.core_blocks.OpenDocument
import my.cheysoff.core_crypto.sync.AccountRootKey
import my.cheysoff.core_domain.model.AttachmentData
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NoteContentFormat
import my.cheysoff.core_domain.model.NotesSortOrder
import my.cheysoff.core_domain.sync.SyncRecord
import my.cheysoff.core_editor.EditorFlags
import my.cheysoff.core_editor.EditorModel
import my.cheysoff.core_editor.RemoteApply
import my.cheysoff.core_sync_codec.RecordCodec
import my.cheysoff.core_sync_engine.ChangePage
import my.cheysoff.core_sync_engine.IncomingRecord
import my.cheysoff.core_sync_engine.PushAck
import my.cheysoff.core_sync_engine.PushRequest
import my.cheysoff.core_sync_engine.PushResponse
import my.cheysoff.core_sync_engine.SyncEngine
import my.cheysoff.core_sync_engine.SyncOutcome
import my.cheysoff.core_sync_engine.SyncTransport
import my.cheysoff.desktop.store.RecordNotesRepository
import my.cheysoff.desktop.store.RecordStore
import my.cheysoff.desktop.ui.state.NotesWorkspaceModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Two replicas exchanging BLOCKS notes over the real desktop stack and one fake server, with the
 * shipped write setting ([my.cheysoff.core_blocks.BlocksFormat.WRITE_ENABLED]; nothing is injected).
 *
 * The laptop is driven through [NotesWorkspaceModel] as the window drives it: the document it hands
 * the editor, and `onBlocksChanged` with the editor's base. The phone stands in for the Android
 * editor's save: it opens the note with [OpenDocument.assemble], as the phone does, and saves the
 * edited blocks with [BlocksSave.encodeForSave] against that document as `blocksBase`. Both run the
 * real repositories, the real sync engine and the real block merge, so these are the end-to-end
 * proofs of "a tick and an edit both survive, with no conflict copy".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BlocksTwoDeviceSyncTest {

    private class Server : SyncTransport {
        private val rows = LinkedHashMap<String, Pair<Long, SyncRecord>>()
        private var seq = 0L
        override suspend fun changesSince(since: Long, limit: Int): ChangePage {
            val page = rows.values.filter { it.first > since }.sortedBy { it.first }.take(limit)
            return ChangePage(page.map { IncomingRecord.Opened(seq = it.first, record = it.second, createdAt = null) }, page.size == limit)
        }
        override suspend fun push(items: List<PushRequest>): PushResponse = PushResponse(items.map { item ->
            val key = "${item.type.wireKey}:${item.uuid}"
            val current = rows[key]
            if ((current?.first ?: 0L) != item.baseSeq) {
                PushAck.Conflicted(item.type, item.uuid, current!!.second, current.first)
            } else {
                seq += 1
                rows[key] = seq to item.record
                PushAck.Accepted(item.type, item.uuid, seq)
            }
        })
    }

    private var wall = 1_000_000L
    private val codec = RecordCodec(AccountRootKey.derive(AccountRootKey.generateArk()))
    private val server = Server()
    private val laptopStore = RecordStore.inMemory("laptop-" + UUID.randomUUID())
    private val phoneStore = RecordStore.inMemory("phone-" + UUID.randomUUID())
    private val laptop = RecordNotesRepository.load(laptopStore, codec, "laptop") { wall }
    private val phone = RecordNotesRepository.load(phoneStore, codec, "phone") { wall }
    // BlockMerge as both apps wire it (DesktopSyncService, DefaultSyncController): without it a
    // BLOCKS body is field-LWW like any other and one device's edit is lost.
    private val laptopEngine = SyncEngine(RecordSyncStore(laptopStore, codec, "acct"), server, laptop.clockObserver, contentMerger = BlockMerge)
    private val phoneEngine = SyncEngine(RecordSyncStore(phoneStore, codec, "acct"), server, phone.clockObserver, contentMerger = BlockMerge)
    private val scopes = mutableListOf<CoroutineScope>()

    @Before fun enableStyledText() = EditorFlags.enable()

    @After fun tearDown() { scopes.forEach { it.cancel() } }

    private suspend fun syncLaptop() { wall += 1000; assertTrue(laptopEngine.runPass() is SyncOutcome.Completed); laptop.refreshFromStore() }
    private suspend fun syncPhone() { wall += 1000; assertTrue(phoneEngine.runPass() is SyncOutcome.Completed); phone.refreshFromStore() }

    /** Both directions, twice, so a push that conflicted on the first pass is merged and re-sent. */
    private suspend fun syncBoth() { syncPhone(); syncLaptop(); syncPhone(); syncLaptop() }

    private fun TestScope.model(): NotesWorkspaceModel {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        scopes += scope
        return NotesWorkspaceModel(
            repository = laptop,
            sketches = laptop,
            attachments = laptop,
            scope = scope,
            now = { wall },
            newId = { UUID.randomUUID().toString() },
        )
    }

    private fun TestScope.settle() { advanceTimeBy(1_000); advanceUntilIdle(); wall += 1000 }

    private fun p(id: String, text: String) = Block(id, BlockType.PARAGRAPH, text = text)
    private fun check(id: String, text: String) = Block(id, BlockType.CHECK, text = text)

    private suspend fun phoneCreates(id: String, vararg blocks: Block) {
        phone.saveNote(
            Note(id = id, title = "T", content = BlocksSave.encodeForSave(blocks.toList()), contentFormat = NoteContentFormat.BLOCKS),
        )
    }

    /** The phone's editor: open the note as the phone does, edit its blocks, save against what it opened. */
    private suspend fun phoneEdits(id: String, edit: (List<Block>) -> List<Block>) {
        val note = phone.getNoteById(id).first()!!
        val base = OpenDocument.assemble(note, phone.getAttachmentsForNote(id).first(), phone.getSketchesForNote(id).first())
        phone.saveNote(
            note.copy(content = BlocksSave.encodeForSave(edit(base.visible())), contentFormat = NoteContentFormat.BLOCKS, updatedAt = wall),
            BlockCodec.encode(base),
        )
    }

    /** The laptop's editor, with nothing synced in since it opened: its base is the document it was handed. */
    private fun TestScope.laptopEdits(model: NotesWorkspaceModel, id: String, edit: (List<Block>) -> List<Block>) {
        val base = model.state.value.editor!!.blocks!!
        model.onBlocksChanged(id, edit(base.visible()), base)
        settle()
    }

    private suspend fun RecordNotesRepository.doc(id: String): List<Block> {
        val note = getNoteById(id).first()!!
        assertEquals(NoteContentFormat.BLOCKS, note.contentFormat)
        return BlockCodec.decode(note.content)!!.visible()
    }

    private suspend fun RecordNotesRepository.noteCount() = getNotes(NotesSortOrder.DEFAULT).first().size

    private fun List<Block>.shown() = map { "${it.type.wire}:${it.text}:${it.done}:${it.ref}" }

    @Test
    fun `tick on the phone and typing on the laptop in the same note both survive, no conflict copy`() = runTest {
        phoneCreates("n", p("intro", "Shopping"), check("milk", "milk"))
        syncPhone(); syncLaptop()
        val model = model(); settle()
        model.selectNote("n"); settle()

        phoneEdits("n") { blocks -> blocks.map { if (it.id == "milk") it.copy(done = true) else it } }
        laptopEdits(model, "n") { blocks -> blocks.map { if (it.id == "intro") it.copy(text = "Shopping list") else it } }
        syncBoth()

        val expected = listOf("p:Shopping list:false:null", "check:milk:true:null")
        assertEquals(expected, phone.doc("n").shown())
        assertEquals(expected, laptop.doc("n").shown())
        assertEquals(1, phone.noteCount())
        assertEquals(1, laptop.noteCount())
    }

    @Test
    fun `a picture moved on one side and deleted on the other ends deleted on both`() = runTest {
        phone.saveAttachment(
            AttachmentData(
                id = "r1", noteId = "n", anchor = 0, order = 0, mimeType = "image/jpeg",
                width = 10, height = 10, bytes = ByteArray(1), thumbWidth = 10, thumbHeight = 10,
                thumbBytes = ByteArray(1), createdAt = wall, updatedAt = wall,
            ),
        )
        val picture = Block(BlockIds.forPicture("r1"), BlockType.PICTURE, ref = "r1")
        phoneCreates("n", p("a", "above"), picture, p("b", "below"))
        syncPhone(); syncLaptop()
        val model = model(); settle()
        model.selectNote("n"); settle()
        assertEquals(listOf("a", picture.id, "b"), model.state.value.editor!!.blocks!!.visible().map { it.id })

        laptopEdits(model, "n") { blocks -> blocks.filterNot { it.id == picture.id } + blocks.first { it.id == picture.id } }
        phoneEdits("n") { blocks -> blocks.filterNot { it.id == picture.id } }
        syncBoth()

        val expected = listOf("p:above:false:null", "p:below:false:null")
        assertEquals(expected, phone.doc("n").shown())
        assertEquals(expected, laptop.doc("n").shown())
        assertTrue("the record is deleted on the phone", phone.getAttachmentsForNote("n").first().isEmpty())
        assertTrue("the record is deleted on the laptop", laptop.getAttachmentsForNote("n").first().isEmpty())
        assertEquals(1, phone.noteCount())
        assertEquals(1, laptop.noteCount())
    }

    @Test
    fun `a legacy note first edited on both devices merges by block`() = runTest {
        phone.saveNote(Note(id = "n", title = "T", content = "<p>one</p><p>two</p>", contentFormat = NoteContentFormat.HTML))
        syncPhone(); syncLaptop()
        val model = model(); settle()
        model.selectNote("n"); settle()
        val laptopIds = model.state.value.editor!!.blocks!!.visible().map { it.id }
        val phoneIds = OpenDocument.assemble(phone.getNoteById("n").first()!!, emptyList(), emptyList()).visible().map { it.id }
        assertEquals("both devices convert to the same block ids", phoneIds, laptopIds)

        laptopEdits(model, "n") { blocks -> blocks.mapIndexed { i, b -> if (i == 0) b.copy(text = "one, from the laptop") else b } }
        phoneEdits("n") { blocks -> blocks.mapIndexed { i, b -> if (i == 1) b.copy(text = "two, from the phone") else b } }
        syncBoth()

        val expected = listOf("p:one, from the laptop:false:null", "p:two, from the phone:false:null")
        assertEquals(expected, phone.doc("n").shown())
        assertEquals(expected, laptop.doc("n").shown())
        assertEquals(laptopIds, phone.doc("n").map { it.id })
        assertEquals(1, phone.noteCount())
        assertEquals(1, laptop.noteCount())
    }

    @Test
    fun `the laptop's open editor shows the phone's tick`() = runTest {
        phoneCreates("n", p("intro", "Shopping"), check("milk", "milk"))
        syncPhone(); syncLaptop()
        val model = model(); settle()
        model.selectNote("n"); settle()

        // The editor as BlockEditor wires it: the first document opens the note, every later store
        // emission for it is offered to RemoteApply and applied (nothing typed, so nothing waits).
        val editor = EditorModel(BlockDocument(emptyList()))
        val remote = RemoteApply(editor, now = { wall })
        var loaded = false
        val collector = CoroutineScope(StandardTestDispatcher(testScheduler) + Job()).also { scopes += it }
        collector.launch {
            model.state.mapNotNull { s -> s.editor?.takeIf { it.id == "n" }?.blocks }.distinctUntilChanged().collect { doc ->
                if (!loaded) {
                    remote.noteLoaded(doc)
                    loaded = true
                } else {
                    remote.offer(doc)
                    remote.tick(lastLocalEditAt = Long.MIN_VALUE)
                }
            }
        }
        settle()
        assertTrue(loaded)
        assertEquals(false, editor.blocks().first { it.id == "milk" }.done)

        phoneEdits("n") { blocks -> blocks.map { if (it.id == "milk") it.copy(done = true) else it } }
        syncPhone(); syncLaptop(); settle()

        assertEquals(true, editor.blocks().first { it.id == "milk" }.done)
        assertTrue(!remote.hasPending)

        // Typing after the tick, saved against the editor's base, keeps the tick on both devices.
        model.onBlocksChanged("n", editor.blocks().map { if (it.id == "intro") it.copy(text = "Shopping list") else it }, remote.base)
        settle()
        syncLaptop(); syncPhone()
        val expected = listOf("p:Shopping list:false:null", "check:milk:true:null")
        assertEquals(expected, phone.doc("n").shown())
        assertEquals(expected, laptop.doc("n").shown())
        assertNull(model.state.value.editor!!.unreadableBody)
    }
}
