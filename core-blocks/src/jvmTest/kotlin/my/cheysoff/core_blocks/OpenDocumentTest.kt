package my.cheysoff.core_blocks

import my.cheysoff.core_domain.model.AttachmentData
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NoteContentFormat
import my.cheysoff.core_domain.model.SketchData
import my.cheysoff.core_domain.sync.Hlc
import my.cheysoff.core_domain.sync.NameUuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenDocumentTest {

    private val t1 = Hlc(100, 0, "a")

    private fun blocksNote(doc: BlockDocument, checklist: String = "") = Note(
        id = "n1", title = "t", content = BlockCodec.encode(doc),
        contentFormat = NoteContentFormat.BLOCKS, checklist = checklist,
    )

    private fun body(vararg blocks: Block) = BlockDocument(blocks.toList())

    private fun para(id: String, text: String, pos: String) =
        Block(id, BlockType.PARAGRAPH, text = text, pos = pos, clock = t1, posClock = t1)

    private fun picture(id: String, noteId: String = "n1", anchor: Int = 0, order: Int = 0, deleted: Boolean = false) =
        AttachmentData(
            id = id, noteId = noteId, anchor = anchor, order = order, mimeType = "image/jpeg",
            width = 1, height = 1, bytes = ByteArray(0), thumbWidth = 1, thumbHeight = 1,
            thumbBytes = ByteArray(0), createdAt = 0, updatedAt = 0, isDeleted = deleted,
        )

    private fun sketch(id: String, anchor: Int = 0, order: Int = 0, deleted: Boolean = false) =
        SketchData(
            id = id, noteId = "n1", anchor = anchor, order = order, strokes = "",
            createdAt = 0, updatedAt = 0, isDeleted = deleted,
        )

    private fun checklistId(i: Int) = NameUuid.v3("n1/checklist/$i".encodeToByteArray())

    @Test
    fun `leftover checklist is appended after the body`() {
        val doc = body(para("a", "hello", "V"))
        val out = OpenDocument.assemble(blocksNote(doc, "1milk\n0bread"), emptyList(), emptyList())
        assertEquals(listOf("hello", "milk", "bread"), out.blocks.map { it.text })
        assertEquals(listOf(BlockType.PARAGRAPH, BlockType.CHECK, BlockType.CHECK), out.blocks.map { it.type })
        assertEquals(listOf(true, false), out.blocks.drop(1).map { it.done })
        assertEquals(listOf("a", checklistId(0), checklistId(1)), out.blocks.map { it.id })
    }

    @Test
    fun `unreferenced picture appears at the end`() {
        val doc = body(para("a", "hello", "V"))
        val out = OpenDocument.assemble(blocksNote(doc), listOf(picture("p1")), listOf(sketch("s1", anchor = 1)))
        assertEquals(listOf("a", BlockIds.forDrawing("s1"), BlockIds.forPicture("p1")), out.blocks.map { it.id })
        assertEquals(listOf(BlockType.PARAGRAPH, BlockType.DRAWING, BlockType.PICTURE), out.blocks.map { it.type })
        assertEquals(listOf(null, "s1", "p1"), out.blocks.map { it.ref })
        assertTrue(out.blocks.drop(1).all { it.clock == Hlc.ZERO })
    }

    @Test
    fun `attachments are appended in anchor order`() {
        val out = OpenDocument.assemble(
            blocksNote(body()), listOf(picture("late", anchor = 3), picture("early", anchor = 1)), emptyList(),
        )
        assertEquals(listOf(BlockIds.forPicture("early"), BlockIds.forPicture("late")), out.blocks.map { it.id })
    }

    @Test
    fun `same-anchor pictures follow id order not the reserved order field`() {
        val out = OpenDocument.assemble(
            blocksNote(body()),
            listOf(picture("a", order = 2), picture("b", order = 1)),
            emptyList(),
        )
        assertEquals(listOf(BlockIds.forPicture("a"), BlockIds.forPicture("b")), out.blocks.map { it.id })
    }

    @Test
    fun `drawings come before pictures as the apps render them`() {
        val out = OpenDocument.assemble(
            blocksNote(body()),
            listOf(picture("p1", anchor = 0)),
            listOf(sketch("s2", anchor = 5), sketch("s1", anchor = 5, order = 9)),
        )
        assertEquals(
            listOf(BlockIds.forDrawing("s1"), BlockIds.forDrawing("s2"), BlockIds.forPicture("p1")),
            out.blocks.map { it.id },
        )
    }

    @Test
    fun `referenced picture is not duplicated`() {
        val pic = Block(BlockIds.forPicture("p1"), BlockType.PICTURE, ref = "p1", pos = "V", clock = t1, posClock = t1)
        val out = OpenDocument.assemble(blocksNote(body(pic)), listOf(picture("p1")), emptyList())
        assertEquals(listOf(BlockIds.forPicture("p1")), out.blocks.map { it.id })
    }

    @Test
    fun `picture of another note is ignored`() {
        val out = OpenDocument.assemble(blocksNote(body()), listOf(picture("p1", noteId = "other")), emptyList())
        assertEquals(emptyList(), out.blocks)
    }

    @Test
    fun `deleted attachment is not shown`() {
        val out = OpenDocument.assemble(
            blocksNote(body()), listOf(picture("p1", deleted = true)), listOf(sketch("s1", deleted = true)),
        )
        assertEquals(emptyList(), out.blocks)
    }

    @Test
    fun `corrupt BLOCKS body opens as one paragraph with its raw text`() {
        val note = Note(id = "n1", title = "t", content = "{not json", contentFormat = NoteContentFormat.BLOCKS)
        val out = OpenDocument.assemble(note, emptyList(), emptyList())
        assertEquals(listOf(BlockType.PARAGRAPH), out.blocks.map { it.type })
        assertEquals("{not json", out.blocks.single().text)
    }

    @Test
    fun `legacy note converts then appends pictures after its checklist`() {
        val note = Note(
            id = "n1", title = "t", content = "<p>hi</p>", contentFormat = NoteContentFormat.HTML,
            checklist = "0x",
        )
        val out = OpenDocument.assemble(note, listOf(picture("p1")), emptyList())
        assertEquals(listOf(BlockType.PARAGRAPH, BlockType.CHECK, BlockType.PICTURE), out.blocks.map { it.type })
        assertEquals(LegacyImport.toBlocks("n1", "<p>hi</p>", NoteContentFormat.HTML, "0x"), out.blocks.take(2))
    }

    @Test
    fun `assembling twice gives the same document`() {
        val note = blocksNote(body(para("a", "x", "V")), "0c")
        val pics = listOf(picture("p1"), picture("p2", anchor = 2))
        assertEquals(OpenDocument.assemble(note, pics, emptyList()), OpenDocument.assemble(note, pics, emptyList()))
    }
}
