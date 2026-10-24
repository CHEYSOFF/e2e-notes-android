package my.cheysoff.core_blocks

import my.cheysoff.core_domain.model.AttachmentData
import my.cheysoff.core_domain.model.NoteContentFormat
import my.cheysoff.core_domain.model.SketchData
import my.cheysoff.core_domain.sync.Hlc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PictureLifecycleTest {

    private val t1 = Hlc(100, 0, "a")
    private val t2 = Hlc(200, 0, "a")

    private fun picture(id: String, deleted: Boolean = false) = AttachmentData(
        id = id, noteId = "n1", anchor = 0, order = 0, mimeType = "image/jpeg",
        width = 1, height = 1, bytes = ByteArray(0), thumbWidth = 1, thumbHeight = 1,
        thumbBytes = ByteArray(0), createdAt = 0, updatedAt = 0, isDeleted = deleted,
    )

    private fun sketch(id: String, deleted: Boolean = false) = SketchData(
        id = id, noteId = "n1", anchor = 0, order = 0, strokes = "",
        createdAt = 0, updatedAt = 0, isDeleted = deleted,
    )

    private fun pictureBlock(recordId: String, pos: String = "a0") =
        Block(BlockIds.forPicture(recordId), BlockType.PICTURE, ref = recordId, pos = pos, clock = t1, posClock = t1)

    private fun drawingBlock(recordId: String, pos: String = "a1") =
        Block(BlockIds.forDrawing(recordId), BlockType.DRAWING, ref = recordId, pos = pos, clock = t1, posClock = t1)

    private fun para(id: String, pos: String) = Block(id, BlockType.PARAGRAPH, text = id, pos = pos, clock = t1, posClock = t1)

    @Test
    fun `removed picture is deleted`() {
        val saved = BlockDocument(
            listOf(para("p", "a0"), drawingBlock("d-kept")),
            gone = listOf(Tombstone(BlockIds.forPicture("a-gone"), t2)),
        )

        val changes = PictureLifecycle.reconcile(
            saved,
            attachmentsOfNote = listOf(picture("a-gone")),
            sketchesOfNote = listOf(sketch("d-kept"), sketch("d-gone")),
        )

        assertEquals(setOf("a-gone", "d-gone"), changes.delete)
        assertEquals(emptySet(), changes.undelete)
    }

    @Test
    fun `undo re-reference undeletes`() {
        // The block came back (undo) after an earlier save had tombstoned it and soft-deleted the
        // record. A record already trashed and still unreferenced is left trashed.
        val saved = BlockDocument(listOf(pictureBlock("a1"), drawingBlock("d1")))

        val changes = PictureLifecycle.reconcile(
            saved,
            attachmentsOfNote = listOf(picture("a1", deleted = true), picture("a-old", deleted = true)),
            sketchesOfNote = listOf(sketch("d1", deleted = true)),
        )

        assertEquals(setOf("a1", "d1"), changes.undelete)
        assertEquals(emptySet(), changes.delete)
    }

    @Test
    fun `picture whose record has not arrived is left alone`() {
        val saved = BlockDocument(listOf(pictureBlock("not-synced-yet"), drawingBlock("also-not-yet")))

        val changes = PictureLifecycle.reconcile(saved, emptyList(), emptyList())

        assertEquals(PictureLifecycle.Changes(emptySet(), emptySet()), changes)
    }

    @Test
    fun `a picture block does not keep a sketch with the same id alive`() {
        // Type matters: a PICTURE block refers to an attachment, never to a sketch.
        val saved = BlockDocument(listOf(pictureBlock("x")))

        val changes = PictureLifecycle.reconcile(saved, emptyList(), listOf(sketch("x")))

        assertEquals(setOf("x"), changes.delete)
    }

    // -- BlocksSave: the half of a BLOCKS save both repositories share ----------------------

    private val empty = BlockCodec.encode(BlockDocument(emptyList()))

    @Test
    fun `a legacy prior stamps every block with this save's clock`() {
        val incoming = BlockCodec.encode(BlockDocument(listOf(para("p1", "a0"), para("p2", "a1"))))

        val out = BlocksSave.prepare(NoteContentFormat.PLAIN, "old text", incoming, t2)

        val write = assertIs<BlocksSave.Write>(out)
        assertEquals(listOf(t2, t2), write.doc.blocks.map { it.clock })
        assertEquals(BlockCodec.encode(write.doc), write.content)
    }

    @Test
    fun `an empty editor body is stored as an encoded empty document`() {
        val fromEmptyDoc = assertIs<BlocksSave.Write>(BlocksSave.prepare(null, "", empty, t1))
        val fromBlank = assertIs<BlocksSave.Write>(BlocksSave.prepare(null, "", "", t1))

        assertEquals(empty, fromEmptyDoc.content)
        assertEquals(empty, fromBlank.content)
    }

    @Test
    fun `an unreadable stored BLOCKS body is kept`() {
        val out = BlocksSave.prepare(NoteContentFormat.BLOCKS, "{not json", empty, t1)

        assertIs<BlocksSave.Keep>(out)
    }

    @Test
    fun `an unreadable editor body never replaces a stored one`() {
        val stored = BlockCodec.encode(BlockDocument(listOf(para("p1", "a0"))))

        assertIs<BlocksSave.Keep>(BlocksSave.prepare(NoteContentFormat.BLOCKS, stored, "{garbage", t2))
        // With nothing stored there is nothing to keep, and "" is never a BLOCKS body.
        val fresh = assertIs<BlocksSave.Write>(BlocksSave.prepare(null, "", "{garbage", t2))
        assertEquals(empty, fresh.content)
        assertTrue(fresh.doc.blocks.isEmpty())
    }
}
