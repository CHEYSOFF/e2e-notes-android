package my.cheysoff.core_editor

import my.cheysoff.core_blocks.Block
import my.cheysoff.core_blocks.BlockDocument
import my.cheysoff.core_blocks.BlockType
import my.cheysoff.core_editor.ui.BlockEditorController
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A picture's or drawing's size: loaded, read back, resized as one undo step, and synced. */
class PictureSizeTest {

    @BeforeTest
    fun setUp() = EditorFlags.enable()

    private var clock = 10_000L

    private fun doc(vararg blocks: Block) = BlockDocument(blocks.mapIndexed { i, b -> b.copy(pos = "a" + ('a' + i)) })

    private val text = Block("t", BlockType.PARAGRAPH, "Intro")
    private val pic = Block("pic:r1", BlockType.PICTURE, ref = "r1")
    private val drawing = Block("drw:s1", BlockType.DRAWING, ref = "s1")

    private fun open(vararg blocks: Block): Pair<BlockEditorController, MutableList<List<Block>>> {
        val changes = mutableListOf<List<Block>>()
        val c = BlockEditorController("n1", now = { clock })
        c.onChange = { changes += it }
        c.receive("n1", doc(*blocks))
        return c to changes
    }

    private fun BlockEditorController.size(id: String) = blocks.first { it.id == id }.size

    @Test
    fun `a loaded size reads back, clamped, with full size as none`() {
        val (c, _) = open(pic.copy(size = 50), drawing.copy(size = 10), Block("pic:r2", BlockType.PICTURE, ref = "r2", size = 100))
        assertEquals(50, c.size("pic:r1"))
        assertEquals(MIN_PICTURE_SIZE, c.size("drw:s1"))
        assertNull(c.size("pic:r2"))
    }

    @Test
    fun `a resize is reported, undone and redone as one step`() {
        val (c, changes) = open(text, pic)
        c.resizePicture("pic:r1", 50)
        assertEquals(50, changes.last().first { it.id == "pic:r1" }.size)
        c.undo()
        assertNull(c.size("pic:r1"))
        assertNull(changes.last().first { it.id == "pic:r1" }.size)
        c.redo()
        assertEquals(50, c.size("pic:r1"))
    }

    @Test
    fun `a dragged resize is one step back to where the drag began`() {
        val (c, changes) = open(text, drawing.copy(size = 75))
        c.previewPictureSize("drw:s1", 60)
        c.previewPictureSize("drw:s1", 40)
        assertEquals(0, changes.size, "a preview is not an edit")
        c.resizePicture("drw:s1", 40, from = 75)
        assertEquals(1, changes.size)
        c.undo()
        assertEquals(75, c.size("drw:s1"))
        c.redo()
        assertEquals(40, c.size("drw:s1"))
    }

    @Test
    fun `a size changed on the other device is applied, and an echo makes no edit`() {
        val (c, changes) = open(text, pic)
        c.receive("n1", doc(text, pic.copy(size = 25)))
        assertEquals(25, c.size("pic:r1"))
        assertEquals(0, changes.size, "a synced change is not a local edit")
        c.receive("n1", doc(text, pic.copy(size = 25)))
        assertEquals(25, c.size("pic:r1"))
        // Not part of the local undo history either.
        c.undo()
        assertEquals(25, c.size("pic:r1"))
    }

    @Test
    fun `a moved picture keeps its size`() {
        val (c, _) = open(text, pic.copy(size = 50), Block("u", BlockType.PARAGRAPH, "after"))
        c.movePicture(1, 3)
        assertEquals(listOf("t", "u", "pic:r1"), c.model.ids)
        assertEquals(50, c.size("pic:r1"))
    }
}
