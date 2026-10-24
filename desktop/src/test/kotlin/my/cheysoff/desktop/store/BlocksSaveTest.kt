package my.cheysoff.desktop.store

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import my.cheysoff.core_blocks.Block
import my.cheysoff.core_blocks.BlockCodec
import my.cheysoff.core_blocks.BlockDocument
import my.cheysoff.core_blocks.BlockIds
import my.cheysoff.core_blocks.BlockType
import my.cheysoff.core_blocks.BlocksSave
import my.cheysoff.core_crypto.sync.AccountRootKey
import my.cheysoff.core_domain.model.AttachmentData
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NoteContentFormat
import my.cheysoff.core_domain.model.SketchData
import my.cheysoff.core_domain.sync.FieldClocks
import my.cheysoff.core_domain.sync.Hlc
import my.cheysoff.core_sync_codec.AttachmentRecords
import my.cheysoff.core_sync_codec.NoteRecords
import my.cheysoff.core_sync_codec.NoteRow
import my.cheysoff.core_sync_codec.OpenResult
import my.cheysoff.core_sync_codec.RecordCodec
import my.cheysoff.core_sync_codec.RecordPayload
import my.cheysoff.core_sync_codec.SketchRecords
import my.cheysoff.core_sync_codec.SketchRow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Task 7: what `RecordNotesRepository.saveNote` does with a BLOCKS note. The same cases as
 * `core-data`'s `BlocksSaveTest` against `RoomNotesRepository`, kept case for case: the two
 * repositories disagreeing about one save is the two devices disagreeing about one note.
 */
class BlocksSaveTest {

    private val keys = AccountRootKey.derive(AccountRootKey.generateArk())
    private val codec = RecordCodec(keys)
    private lateinit var store: RecordStore
    private var now = 1_000_000L

    private lateinit var repository: RecordNotesRepository

    @Before
    fun setUp() {
        store = RecordStore.inMemory("blocks-${UUID.randomUUID()}")
        repository = reload()
    }

    @After
    fun tearDown() {
        store.close()
    }

    private fun reload() = RecordNotesRepository.load(store, codec, node = "abcd1234") { now }

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
        thumbBytes = byteArrayOf(2), createdAt = 0L, updatedAt = 0L,
    )

    /** The desktop never creates a sketch, so one is sealed straight into the store and loaded. */
    private fun seedSketch(id: String) {
        val row = SketchRow(
            sketch = SketchData(
                id = id, noteId = "n1", anchor = 0, order = 0, strokes = "1|10x10|ff000000,4:0,0",
                createdAt = 1_000L, updatedAt = 1_000L,
            ),
            rowClock = Hlc(1_000L, 0, "abcd1234"),
            clocks = emptyMap(),
            dirty = false,
            lastSyncedSeq = 0L,
        )
        val sealed = codec.seal(SketchRecords.toPayload(row, createdAt = row.sketch.createdAt))
        store.put(sealed.blindedId, sealed.envelope)
        repository = reload()
    }

    private suspend fun stored(): Note = repository.getNoteById("n1").first()!!

    private suspend fun storedDoc(): BlockDocument = BlockCodec.decode(stored().content)!!

    private fun payload(id: String): RecordPayload = store.readAll()
        .mapNotNull { (codec.open(it.blindedId, it.envelope) as? OpenResult.Ok)?.payload }
        .single { it.uuid == id }

    private fun rowClock(): Hlc = payload("n1").rowClock

    private fun fieldClock(field: String): Hlc = payload("n1").let { it.clocks[field] ?: it.rowClock }

    private fun tick() {
        now += 1_000
    }

    // -- the brief's cases -------------------------------------------------------------------

    @Test
    fun `saving twice with one paragraph changed only advances that block's clock`() = runTest {
        repository.saveNote(edited(para("p1", "one", "a0"), para("p2", "two", "a1")))
        val first = storedDoc()
        tick()

        repository.saveNote(edited(para("p1", "one", "a0"), para("p2", "TWO", "a1")))
        val second = storedDoc()

        val (p1Before, p2Before) = first.blocks
        val (p1After, p2After) = second.blocks
        assertEquals("the untouched paragraph keeps its clock", p1Before.clock, p1After.clock)
        assertEquals(p1Before.posClock, p1After.posClock)
        assertTrue("the edited paragraph is newer", p2After.clock > p2Before.clock)
        assertEquals("TWO", p2After.text)
        assertEquals(rowClock(), p2After.clock)
    }

    @Test
    fun `checklist field is cleared once its items are in the body`() = runTest {
        repository.saveNote(Note(id = "n1", title = "t", content = "body", checklist = "0milk\n1eggs"))
        tick()

        // The editor's Note still carries the old checklist it was opened with; OpenDocument has
        // already folded those items into the body as check blocks.
        val opened = listOf(para("p1", "body", "a0"), check("c1", "milk", false, "a1"), check("c2", "eggs", true, "a2"))
        val save = edited(*opened.toTypedArray(), checklist = "0milk\n1eggs")
        // An editor save carries the editor's base: the document the items were folded into. Only
        // such a save clears the field (see BlocksSave.Write).
        val base = BlockCodec.encode(BlockDocument(opened))
        repository.saveNote(save, base)

        assertEquals("", stored().checklist)
        assertEquals("clearing the field is a change, so it claims a clock", rowClock(), fieldClock(FieldClocks.CHECKLIST))
        assertEquals(listOf("body", "milk", "eggs"), storedDoc().blocks.map { it.text })

        val checklistClock = fieldClock(FieldClocks.CHECKLIST)
        val contentClock = fieldClock(FieldClocks.CONTENT)
        tick()
        // The editor's base after the first save's echo: the stored document, clocks and all.
        repository.saveNote(save, BlockCodec.encode(storedDoc()))

        assertEquals("an unchanged empty checklist claims nothing", checklistClock, fieldClock(FieldClocks.CHECKLIST))
        assertEquals("an unchanged body claims nothing", contentClock, fieldClock(FieldClocks.CONTENT))
        assertTrue(rowClock() > checklistClock)
    }

    @Test
    fun `a save without the editor's base keeps a leftover checklist`() = runTest {
        repository.saveNote(Note(id = "n1", title = "t", content = "body", checklist = "0milk"))
        tick()
        val body = BlocksSave.encodeForSave(listOf(para("p1", "body", "")))
        // No base: a title-only save, or one made before the editor loaded. The checklist items are
        // only in the editor's assembled copy, never in this body, so clearing the field loses them.
        repository.saveNote(Note(id = "n1", title = "renamed", content = body, contentFormat = NoteContentFormat.BLOCKS, checklist = "0milk"))
        assertEquals("0milk", stored().checklist)
        tick()
        // A second title-only save of the now-BLOCKS note keeps it too.
        repository.saveNote(Note(id = "n1", title = "again", content = stored().content, contentFormat = NoteContentFormat.BLOCKS, checklist = "0milk"))
        assertEquals("again", stored().title)
        assertEquals("0milk", stored().checklist)
    }

    @Test
    fun `removing a picture block soft-deletes the attachment`() = runTest {
        repository.saveAttachment(attachment("a1"))
        repository.saveNote(edited(para("p1", "x", "a0"), picture("a1", "a1")))
        assertFalse("a referenced picture stays", repository.attachmentRows().getValue("a1").attachment.isDeleted)
        tick()

        repository.saveNote(edited(para("p1", "x", "a0")))

        val row = repository.attachmentRows().getValue("a1")
        assertTrue(row.attachment.isDeleted)
        assertEquals(now, row.attachment.deletedAt)
        assertEquals(row.rowClock, row.clocks[FieldClocks.DELETED] ?: row.rowClock)
        assertTrue("its own clock, minted after the note's", row.rowClock > rowClock())
        assertTrue("the tombstone reached the store", reload().attachmentRows().getValue("a1").attachment.isDeleted)
    }

    // -- the rulings' cases ------------------------------------------------------------------

    @Test
    fun `re-referencing a picture whose block was tombstoned undeletes the attachment`() = runTest {
        repository.saveAttachment(attachment("a1"))
        repository.saveNote(edited(para("p1", "x", "a0"), picture("a1", "a1")))
        tick()
        repository.saveNote(edited(para("p1", "x", "a0")))
        assertTrue(repository.attachmentRows().getValue("a1").attachment.isDeleted)
        assertTrue(storedDoc().gone.any { it.id == BlockIds.forPicture("a1") })
        tick()

        // Undo puts the block back.
        repository.saveNote(edited(para("p1", "x", "a0"), picture("a1", "a1")))

        val row = repository.attachmentRows().getValue("a1")
        assertFalse(row.attachment.isDeleted)
        assertNull(row.attachment.deletedAt)
        assertEquals(listOf(BlockIds.forPicture("a1")), storedDoc().blocks.filter { it.type == BlockType.PICTURE }.map { it.id })
        // The revived block keeps its tombstone (the merge's canonical form); its newer content
        // clock is what makes it live.
        val revived = storedDoc().blocks.single { it.id == BlockIds.forPicture("a1") }
        assertTrue(storedDoc().gone.filter { it.id == revived.id }.all { it.clock < revived.clock })
        assertFalse(reload().attachmentRows().getValue("a1").attachment.isDeleted)
    }

    @Test
    fun `removing a drawing block soft-deletes the sketch`() = runTest {
        seedSketch("d1")
        repository.saveNote(edited(drawing("d1", "a0")))
        assertFalse(repository.sketchRows().getValue("d1").sketch.isDeleted)
        tick()

        repository.saveNote(edited(para("p1", "", "a0")))

        assertTrue(repository.sketchRows().getValue("d1").sketch.isDeleted)
        assertTrue(reload().sketchRows().getValue("d1").sketch.isDeleted)
    }

    @Test
    fun `a picture block whose record has not arrived survives the save`() = runTest {
        repository.saveNote(edited(para("p1", "x", "a0"), picture("not-synced", "a1")))

        assertEquals(listOf("p1", BlockIds.forPicture("not-synced")), storedDoc().blocks.map { it.id })
        assertTrue(repository.attachmentRows().isEmpty())
    }

    @Test
    fun `a legacy prior body stamps every block with this save's clock`() = runTest {
        repository.saveNote(Note(id = "n1", title = "t", content = "<p>hi</p>", contentFormat = NoteContentFormat.HTML))
        tick()

        repository.saveNote(edited(para("p1", "hi", "a0"), para("p2", "there", "a1")))

        val saveClock = rowClock()
        assertEquals(listOf(saveClock, saveClock), storedDoc().blocks.map { it.clock })
        assertEquals(NoteContentFormat.BLOCKS, stored().contentFormat)
    }

    @Test
    fun `an empty document is stored as an encoded empty document, never the empty string`() = runTest {
        repository.saveNote(edited())
        assertEquals(BlockCodec.encode(BlockDocument(emptyList())), stored().content)

        repository.saveNote(edited().copy(content = ""))
        assertEquals(BlockCodec.encode(BlockDocument(emptyList())), stored().content)
    }

    @Test
    fun `a stored BLOCKS body that cannot be decoded is not overwritten`() = runTest {
        // Sealed straight into the store: the repository never writes an unreadable BLOCKS body,
        // but a newer build's body (or a damaged one) can still arrive by sync.
        val seeded = NoteRow(
            note = Note(
                id = "n1", title = "t", content = "{\"v\":99}", contentFormat = NoteContentFormat.BLOCKS,
                checklist = "0keep me", createdAt = 1_000L, updatedAt = 1_000L,
            ),
            rowClock = Hlc(1_000L, 0, "seed"),
            clocks = emptyMap(),
        )
        val sealed = codec.seal(NoteRecords.toPayload(seeded))
        store.put(sealed.blindedId, sealed.envelope)
        repository = reload()
        repository.saveAttachment(attachment("a1"))
        val contentClock = fieldClock(FieldClocks.CONTENT)
        tick()

        repository.saveNote(edited(para("p1", "typed over it", "a0"), title = "renamed"))

        assertEquals("{\"v\":99}", stored().content)
        assertEquals(NoteContentFormat.BLOCKS, stored().contentFormat)
        assertEquals("the checklist would be lost with nothing in a body to hold it", "0keep me", stored().checklist)
        assertEquals(contentClock, fieldClock(FieldClocks.CONTENT))
        assertEquals("the other fields still save", "renamed", stored().title)
        assertNotNull(repository.attachmentRows()["a1"])
        assertFalse(
            "no picture is reconciled against a body that was not written",
            repository.attachmentRows().getValue("a1").attachment.isDeleted,
        )
    }

    // -- fix round 1: deleting only what the prior document showed, and the editor's order ----

    private fun isDeleted(id: String) = repository.attachmentRows().getValue(id).attachment.isDeleted

    @Test
    fun `a picture that arrives after the prior save and is absent from the saved doc is not deleted`() = runTest {
        repository.saveNote(edited(para("p1", "x", "a0")))
        // Synced in while the editor was open; the editor never showed it.
        repository.saveAttachment(attachment("late"))
        tick()

        repository.saveNote(edited(para("p1", "typing", "a0")))

        assertFalse(isDeleted("late"))
    }

    @Test
    fun `a picture referenced in the prior doc and removed is deleted`() = runTest {
        repository.saveAttachment(attachment("a1"))
        repository.saveNote(edited(picture("a1", "a0"), para("p1", "x", "a1")))
        tick()

        repository.saveNote(edited(para("p1", "x", "a1")))

        assertTrue(isDeleted("a1"))
    }

    @Test
    fun `the legacy first save deletes a record that was removed in the editor`() = runTest {
        repository.saveNote(Note(id = "n1", title = "t", content = "legacy"))
        repository.saveAttachment(attachment("kept"))
        repository.saveAttachment(attachment("removed"))
        tick()

        repository.saveNote(edited(para("p1", "legacy", "a0"), picture("kept", "a1")))

        assertFalse(isDeleted("kept"))
        assertTrue(isDeleted("removed"))
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
        val base = stored().content
        tick()
        // The other device's picture, block and record, merged in while the editor was open.
        repository.saveAttachment(attachment("X"))
        repository.saveNote(edited(para("p1", "one", "a0"), picture("X", "a1")))

        repository.saveNote(edited(para("p1", "one, edited", "a0")), blocksBase = base)

        assertEquals(listOf("p1", BlockIds.forPicture("X")), storedDoc().blocks.map { it.id })
        assertEquals("one, edited", storedDoc().blocks.first().text)
        assertFalse(isDeleted("X"))
    }

    @Test
    fun `a remote paragraph and a remote edit after the base survive an edit to another paragraph`() = runTest {
        repository.saveNote(edited(para("p1", "one", "a0"), para("p2", "two", "a1")))
        val base = stored().content
        tick()
        repository.saveNote(edited(para("p1", "one", "a0"), para("p2", "two, remote", "a1"), para("p3", "three", "a2")))

        repository.saveNote(edited(para("p1", "one, local", "a0"), para("p2", "two", "a1")), blocksBase = base)

        assertEquals(listOf("one, local", "two, remote", "three"), storedDoc().blocks.map { it.text })
    }

    @Test
    fun `a block the user deleted from the base is tombstoned and its record deleted`() = runTest {
        repository.saveAttachment(attachment("a1"))
        repository.saveNote(edited(para("p1", "x", "a0"), picture("a1", "a1")))
        val base = stored().content
        tick()

        repository.saveNote(edited(para("p1", "x", "a0")), blocksBase = base)

        assertEquals(listOf(BlockIds.forPicture("a1")), storedDoc().gone.map { it.id })
        assertTrue(isDeleted("a1"))
    }

    @Test
    fun `a tombstoned picture that stays unreferenced is deleted, even if its record was live`() = runTest {
        // The record is live but the stored body already holds the block's tombstone -- e.g. the
        // tombstone arrived by merge while the attachment did not change.
        repository.saveAttachment(attachment("a1"))
        repository.saveNote(edited(para("p1", "x", "a0"), picture("a1", "a1")))
        tick()
        repository.saveNote(edited(para("p1", "x", "a0")))
        val trashed = repository.attachmentRows().getValue("a1")
        val revived = trashed.copy(
            attachment = trashed.attachment.copy(isDeleted = false, deletedAt = null),
            rowClock = Hlc(1, 0, "seed"),
        )
        val sealed = codec.seal(AttachmentRecords.toPayload(revived, createdAt = revived.attachment.createdAt))
        store.put(sealed.blindedId, sealed.envelope)
        repository = reload()
        assertFalse(isDeleted("a1"))
        tick()

        repository.saveNote(edited(para("p1", "y", "a0")))

        assertTrue(isDeleted("a1"))
    }
}
