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
        // The prior save showed both; this one dropped the picture block and one of the drawings.
        val prior = BlockDocument(listOf(para("p", "a0"), pictureBlock("a-gone", "a1"), drawingBlock("d-kept", "a2"), drawingBlock("d-gone", "a3")))
        val saved = BlockDocument(
            listOf(para("p", "a0"), drawingBlock("d-kept")),
            gone = listOf(Tombstone(BlockIds.forPicture("a-gone"), t2), Tombstone(BlockIds.forDrawing("d-gone"), t2)),
        )

        val changes = PictureLifecycle.reconcile(
            prior,
            saved,
            attachmentsOfNote = listOf(picture("a-gone")),
            sketchesOfNote = listOf(sketch("d-kept"), sketch("d-gone")),
        )

        assertEquals(setOf("a-gone", "d-gone"), changes.delete)
        assertEquals(emptySet(), changes.undelete)
    }

    @Test
    fun `a record the prior document never showed is not deleted`() {
        // It synced in after the editor opened, so the editor could not have shown it.
        val prior = BlockDocument(listOf(para("p", "a0")))
        val saved = BlockDocument(listOf(para("p", "a0")))

        val changes = PictureLifecycle.reconcile(prior, saved, listOf(picture("late")), listOf(sketch("late-d")))

        assertEquals(PictureLifecycle.Changes(emptySet(), emptySet()), changes)
    }

    @Test
    fun `a record whose block the prior document tombstoned is deleted`() {
        // The block was deleted (perhaps on another device, by merge) but the record stayed live.
        val prior = BlockDocument(listOf(para("p", "a0")), gone = listOf(Tombstone(BlockIds.forPicture("a1"), t2)))
        val saved = BlockDocument(listOf(para("p", "a0")), gone = prior.gone)

        val changes = PictureLifecycle.reconcile(prior, saved, listOf(picture("a1")), emptyList())

        assertEquals(setOf("a1"), changes.delete)
    }

    @Test
    fun `the legacy first save deletes a record removed in the editor`() {
        // No prior document: assemble showed every live record, so a missing one was removed.
        val saved = BlockDocument(listOf(para("p", "a0"), pictureBlock("kept", "a1")))

        val changes = PictureLifecycle.reconcile(null, saved, listOf(picture("kept"), picture("removed")), listOf(sketch("d")))

        assertEquals(setOf("removed", "d"), changes.delete)
    }

    @Test
    fun `undo re-reference undeletes`() {
        // The block came back (undo) after an earlier save had tombstoned it and soft-deleted the
        // record. A record already trashed and still unreferenced is left trashed.
        val prior = BlockDocument(emptyList(), gone = listOf(Tombstone(BlockIds.forPicture("a1"), t1)))
        val saved = BlockDocument(listOf(pictureBlock("a1"), drawingBlock("d1")))

        val changes = PictureLifecycle.reconcile(
            prior,
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

        val changes = PictureLifecycle.reconcile(saved, saved, emptyList(), emptyList())

        assertEquals(PictureLifecycle.Changes(emptySet(), emptySet()), changes)
    }

    @Test
    fun `a picture block does not keep a sketch with the same id alive`() {
        // Type matters: a PICTURE block refers to an attachment, never to a sketch.
        val saved = BlockDocument(listOf(pictureBlock("x")))

        val changes = PictureLifecycle.reconcile(null, saved, emptyList(), listOf(sketch("x")))

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

    @Test
    fun `encodeForSave keeps the given order whatever the ids and positions`() {
        val visible = listOf(
            Block("zz", BlockType.PARAGRAPH, text = "first"),
            Block("mm", BlockType.PARAGRAPH, text = "second", pos = "zzz"),
            Block("aa", BlockType.PARAGRAPH, text = "third"),
        )

        val decoded = BlockCodec.decode(BlocksSave.encodeForSave(visible))!!

        assertEquals(listOf("zz", "mm", "aa"), decoded.blocks.map { it.id })
        val stamped = assertIs<BlocksSave.Write>(BlocksSave.prepare(null, "", BlocksSave.encodeForSave(visible), t1))
        assertEquals(listOf("first", "second", "third"), stamped.doc.blocks.map { it.text })
    }
}
