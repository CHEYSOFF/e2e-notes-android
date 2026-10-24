package my.cheysoff.core_data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import my.cheysoff.core_blocks.Block
import my.cheysoff.core_blocks.BlockCodec
import my.cheysoff.core_blocks.BlockDocument
import my.cheysoff.core_blocks.BlockIds
import my.cheysoff.core_blocks.BlockType
import my.cheysoff.core_blocks.BlocksSave
import my.cheysoff.core_data.data.RoomNotesRepository
import my.cheysoff.core_data.data.RoomSketchesRepository
import my.cheysoff.core_data.data.local.NoteDatabase
import my.cheysoff.core_data.data.sync.SyncClock
import my.cheysoff.core_domain.model.AttachmentData
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NoteContentFormat
import my.cheysoff.core_domain.model.SketchData
import my.cheysoff.core_domain.sync.FieldClocks
import my.cheysoff.core_domain.sync.Hlc
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Task 7: what `RoomNotesRepository.saveNote` does with a BLOCKS note -- per-block clocks, the
 * folded-in checklist field cleared, and picture/drawing records kept in step with the blocks that
 * show them. `desktop`'s `BlocksSaveTest` is the same file against `RecordNotesRepository`; the two
 * must keep passing the same cases, because a difference is the two devices disagreeing about one
 * note.
 *
 * Seeds through the repositories, like `RoomNotesRepositoryAttachmentTest`, except where the state
 * under test is one the repository refuses to produce (an unreadable stored body).
 */
@RunWith(RobolectricTestRunner::class)
class BlocksSaveTest {

    private lateinit var database: NoteDatabase
    private lateinit var repository: RoomNotesRepository
    private lateinit var sketches: RoomSketchesRepository

    private val clock = SyncClock(node = { "testnode" })

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, NoteDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomNotesRepository(
            database.noteDao, database.folderDao, database.sketchDao, database.attachmentDao, database, clock,
        )
        sketches = RoomSketchesRepository(database.sketchDao, database, clock)
    }

    @After
    fun tearDown() {
        database.close()
    }

    // -- fixtures ----------------------------------------------------------------------------

    /** What the editor hands over: visible blocks in order, clocks left at zero. */
    private fun edited(vararg blocks: Block, checklist: String = "", title: String = "t") = Note(
        id = "n1", title = title, content = BlockCodec.encode(BlockDocument(blocks.toList())),
        contentFormat = NoteContentFormat.BLOCKS, checklist = checklist,
    )

    // `pos` only carries the visible order through the encoding; stamping assigns the real ones.
    private fun para(id: String, text: String, pos: String) = Block(id, BlockType.PARAGRAPH, text = text, pos = pos)

    private fun check(id: String, text: String, done: Boolean, pos: String) =
        Block(id, BlockType.CHECK, text = text, done = done, pos = pos)

    private fun picture(recordId: String, pos: String) =
        Block(BlockIds.forPicture(recordId), BlockType.PICTURE, ref = recordId, pos = pos)

    private fun drawing(recordId: String, pos: String) =
        Block(BlockIds.forDrawing(recordId), BlockType.DRAWING, ref = recordId, pos = pos)

    private fun attachment(id: String) = AttachmentData(
        id = id, noteId = "n1", anchor = 0, order = 0, mimeType = "image/jpeg",
        width = 1, height = 1, bytes = byteArrayOf(1), thumbWidth = 1, thumbHeight = 1,
        thumbBytes = byteArrayOf(2), createdAt = 1_000L, updatedAt = 1_000L,
    )

    private fun sketch(id: String) = SketchData(
        id = id, noteId = "n1", anchor = 0, order = 0, strokes = "1|10x10|ff000000,4:0,0",
        createdAt = 1_000L, updatedAt = 1_000L,
    )

    private suspend fun row() = database.noteDao.noteRow("n1")!!

    private suspend fun storedDoc(): BlockDocument = BlockCodec.decode(row().content)!!

    private suspend fun fieldClock(field: String): Hlc = row().let { FieldClocks.clockOf(field, it.fieldHlc, it.rowHlc()) }

    // -- the brief's cases -------------------------------------------------------------------

    @Test
    fun `saving twice with one paragraph changed only advances that block's clock`() = runTest {
        repository.saveNote(edited(para("p1", "one", "a0"), para("p2", "two", "a1")))
        val first = storedDoc()

        repository.saveNote(edited(para("p1", "one", "a0"), para("p2", "TWO", "a1")))
        val second = storedDoc()

        val (p1Before, p2Before) = first.blocks
        val (p1After, p2After) = second.blocks
        assertEquals("the untouched paragraph keeps its clock", p1Before.clock, p1After.clock)
        assertEquals(p1Before.posClock, p1After.posClock)
        assertTrue("the edited paragraph is newer", p2After.clock > p2Before.clock)
        assertEquals("TWO", p2After.text)
        assertEquals(row().rowHlc(), p2After.clock)
    }

    @Test
    fun `checklist field is cleared once its items are in the body`() = runTest {
        repository.saveNote(Note(id = "n1", title = "t", content = "body", checklist = "0milk\n1eggs"))

        // The editor's Note still carries the old checklist it was opened with; OpenDocument has
        // already folded those items into the body as check blocks.
        val save = edited(
            para("p1", "body", "a0"), check("c1", "milk", false, "a1"), check("c2", "eggs", true, "a2"),
            checklist = "0milk\n1eggs",
        )
        repository.saveNote(save)

        assertEquals("", row().checklist)
        assertEquals("clearing the field is a change, so it claims a clock", row().rowHlc(), fieldClock(FieldClocks.CHECKLIST))
        assertEquals(listOf("body", "milk", "eggs"), storedDoc().blocks.map { it.text })

        val checklistClock = fieldClock(FieldClocks.CHECKLIST)
        val contentClock = fieldClock(FieldClocks.CONTENT)
        repository.saveNote(save)

        assertEquals("an unchanged empty checklist claims nothing", checklistClock, fieldClock(FieldClocks.CHECKLIST))
        assertEquals("an unchanged body claims nothing", contentClock, fieldClock(FieldClocks.CONTENT))
        assertTrue(row().rowHlc() > checklistClock)
    }

    @Test
    fun `removing a picture block soft-deletes the attachment`() = runTest {
        repository.saveAttachment(attachment("a1"))
        repository.saveNote(edited(para("p1", "x", "a0"), picture("a1", "a1")))
        assertNotNull("a referenced picture stays", repository.attachment("a1"))

        repository.saveNote(edited(para("p1", "x", "a0")))

        val stored = database.attachmentDao.attachmentRow("a1")!!
        assertTrue(stored.isDeleted)
        assertTrue("the tombstone is pushed", stored.dirty)
        assertEquals(stored.rowHlc(), FieldClocks.clockOf(FieldClocks.DELETED, stored.fieldHlc, stored.rowHlc()))
        assertTrue("its own clock, minted after the note's", stored.rowHlc() > row().rowHlc())
    }

    // -- the rulings' cases ------------------------------------------------------------------

    @Test
    fun `re-referencing a picture whose block was tombstoned undeletes the attachment`() = runTest {
        repository.saveAttachment(attachment("a1"))
        repository.saveNote(edited(para("p1", "x", "a0"), picture("a1", "a1")))
        repository.saveNote(edited(para("p1", "x", "a0")))
        assertNull(repository.attachment("a1"))
        assertTrue(storedDoc().gone.any { it.id == BlockIds.forPicture("a1") })

        // Undo puts the block back.
        repository.saveNote(edited(para("p1", "x", "a0"), picture("a1", "a1")))

        assertNotNull(repository.attachment("a1"))
        val stored = database.attachmentDao.attachmentRow("a1")!!
        assertFalse(stored.isDeleted)
        assertNull(stored.deletedAt)
        assertTrue(stored.dirty)
        assertEquals(listOf(BlockIds.forPicture("a1")), storedDoc().blocks.filter { it.type == BlockType.PICTURE }.map { it.id })
        // The revived block keeps its tombstone (the merge's canonical form); its newer content
        // clock is what makes it live.
        val revived = storedDoc().blocks.single { it.id == BlockIds.forPicture("a1") }
        assertTrue(storedDoc().gone.filter { it.id == revived.id }.all { it.clock < revived.clock })
    }

    @Test
    fun `a tombstoned picture that stays unreferenced is deleted, even if its record was live`() = runTest {
        // The record is live but the stored body already holds the block's tombstone -- e.g. the
        // tombstone arrived by merge while the attachment did not change.
        repository.saveAttachment(attachment("a1"))
        repository.saveNote(edited(para("p1", "x", "a0"), picture("a1", "a1")))
        repository.saveNote(edited(para("p1", "x", "a0")))
        database.attachmentDao.restoreAttachment("a1", 1, 0, "seed", "")

        repository.saveNote(edited(para("p1", "y", "a0")))

        assertNull(repository.attachment("a1"))
    }

    @Test
    fun `removing a drawing block soft-deletes the sketch`() = runTest {
        sketches.saveSketch(sketch("d1"))
        repository.saveNote(edited(drawing("d1", "a0")))
        assertFalse(database.sketchDao.sketchRow("d1")!!.isDeleted)

        repository.saveNote(edited(para("p1", "", "a0")))

        val stored = database.sketchDao.sketchRow("d1")!!
        assertTrue(stored.isDeleted)
        assertTrue(stored.dirty)
    }

    @Test
    fun `a picture block whose record has not arrived survives the save`() = runTest {
        repository.saveNote(edited(para("p1", "x", "a0"), picture("not-synced", "a1")))

        assertEquals(
            listOf("p1", BlockIds.forPicture("not-synced")),
            storedDoc().blocks.map { it.id },
        )
        assertNull(database.attachmentDao.attachmentRow("not-synced"))
    }

    @Test
    fun `a legacy prior body stamps every block with this save's clock`() = runTest {
        repository.saveNote(Note(id = "n1", title = "t", content = "<p>hi</p>", contentFormat = NoteContentFormat.HTML))

        repository.saveNote(edited(para("p1", "hi", "a0"), para("p2", "there", "a1")))

        val saveClock = row().rowHlc()
        assertEquals(listOf(saveClock, saveClock), storedDoc().blocks.map { it.clock })
        assertEquals(NoteContentFormat.BLOCKS.storageValue, row().contentFormat)
    }

    @Test
    fun `an empty document is stored as an encoded empty document, never the empty string`() = runTest {
        repository.saveNote(edited())
        assertEquals(BlockCodec.encode(BlockDocument(emptyList())), row().content)

        repository.saveNote(edited().copy(content = ""))
        assertEquals(BlockCodec.encode(BlockDocument(emptyList())), row().content)
    }

    @Test
    fun `a stored BLOCKS body that cannot be decoded is not overwritten`() = runTest {
        // Seeded through the DAO: the repository never writes an unreadable BLOCKS body, but a
        // newer build's body (or a damaged one) can still arrive by sync.
        database.noteDao.upsertNote(
            id = "n1", title = "t", content = "{\"v\":99}", contentFormat = NoteContentFormat.BLOCKS.storageValue,
            checklist = "0keep me", isPinned = false, folderId = null, timestamp = 1_000L,
            hlcMs = 1_000L, hlcCounter = 0, hlcNode = "seed", fieldHlc = "",
        )
        repository.saveAttachment(attachment("a1"))
        val contentClock = fieldClock(FieldClocks.CONTENT)

        repository.saveNote(edited(para("p1", "typed over it", "a0"), title = "renamed"))

        assertEquals("{\"v\":99}", row().content)
        assertEquals(NoteContentFormat.BLOCKS.storageValue, row().contentFormat)
        assertEquals("the checklist would be lost with nothing in a body to hold it", "0keep me", row().checklist)
        assertEquals(contentClock, fieldClock(FieldClocks.CONTENT))
        assertEquals("the other fields still save", "renamed", row().title)
        assertNotNull("no picture is reconciled against a body that was not written", repository.attachment("a1"))
    }

    // -- fix round 1: deleting only what the prior document showed, and the editor's order ----

    @Test
    fun `a picture that arrives after the prior save and is absent from the saved doc is not deleted`() = runTest {
        repository.saveNote(edited(para("p1", "x", "a0")))
        // Synced in while the editor was open; the editor never showed it.
        repository.saveAttachment(attachment("late"))

        repository.saveNote(edited(para("p1", "typing", "a0")))

        assertNotNull(repository.attachment("late"))
    }

    @Test
    fun `a picture referenced in the prior doc and removed is deleted`() = runTest {
        repository.saveAttachment(attachment("a1"))
        repository.saveNote(edited(picture("a1", "a0"), para("p1", "x", "a1")))

        repository.saveNote(edited(para("p1", "x", "a1")))

        assertNull(repository.attachment("a1"))
    }

    @Test
    fun `the legacy first save deletes a record that was removed in the editor`() = runTest {
        repository.saveNote(Note(id = "n1", title = "t", content = "legacy"))
        repository.saveAttachment(attachment("kept"))
        repository.saveAttachment(attachment("removed"))

        repository.saveNote(edited(para("p1", "legacy", "a0"), picture("kept", "a1")))

        assertNotNull(repository.attachment("kept"))
        assertNull(repository.attachment("removed"))
    }

    @Test
    fun `blocks built with encodeForSave are stored in the given order`() = runTest {
        val visible = listOf(para("zz", "first", ""), para("mm", "second", ""), para("aa", "third", ""))

        repository.saveNote(edited().copy(content = BlocksSave.encodeForSave(visible)))

        assertEquals(listOf("zz", "mm", "aa"), storedDoc().blocks.map { it.id })
    }

    // -- fix round 2: saving against the editor's base ----------------------------------------

    @Test
    fun `a picture that arrived with its block after the base keeps both`() = runTest {
        repository.saveNote(edited(para("p1", "one", "a0")))
        val base = row().content
        // The other device's picture, block and record, merged in while the editor was open.
        repository.saveAttachment(attachment("X"))
        repository.saveNote(edited(para("p1", "one", "a0"), picture("X", "a1")))

        repository.saveNote(edited(para("p1", "one, edited", "a0")), blocksBase = base)

        assertEquals(listOf("p1", BlockIds.forPicture("X")), storedDoc().blocks.map { it.id })
        assertEquals("one, edited", storedDoc().blocks.first().text)
        assertNotNull(repository.attachment("X"))
    }

    @Test
    fun `a remote paragraph and a remote edit after the base survive an edit to another paragraph`() = runTest {
        repository.saveNote(edited(para("p1", "one", "a0"), para("p2", "two", "a1")))
        val base = row().content
        repository.saveNote(edited(para("p1", "one", "a0"), para("p2", "two, remote", "a1"), para("p3", "three", "a2")))

        repository.saveNote(edited(para("p1", "one, local", "a0"), para("p2", "two", "a1")), blocksBase = base)

        assertEquals(listOf("one, local", "two, remote", "three"), storedDoc().blocks.map { it.text })
    }

    @Test
    fun `a block the user deleted from the base is tombstoned and its record deleted`() = runTest {
        repository.saveAttachment(attachment("a1"))
        repository.saveNote(edited(para("p1", "x", "a0"), picture("a1", "a1")))
        val base = row().content

        repository.saveNote(edited(para("p1", "x", "a0")), blocksBase = base)

        assertEquals(listOf(BlockIds.forPicture("a1")), storedDoc().gone.map { it.id })
        assertNull(repository.attachment("a1"))
    }
}
