package my.cheysoff.core_editor.ui

import my.cheysoff.core_blocks.Block
import my.cheysoff.core_blocks.BlockDocument
import my.cheysoff.core_blocks.BlockType
import my.cheysoff.core_editor.EditorFlags
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [BlockEditorController.removePicture]: a delete of a picture made outside the text, as a local, undoable edit. */
class RemovePictureTest {

    @BeforeTest
    fun setUp() = EditorFlags.enable()

    private val text = Block("t", BlockType.PARAGRAPH, "before", pos = "a")
    private val picture = Block("pic:r1", BlockType.PICTURE, ref = "r1", pos = "b")
    private val drawing = Block("drw:s1", BlockType.DRAWING, ref = "s1", pos = "c")
    private val after = Block("u", BlockType.PARAGRAPH, "after", pos = "d")

    private fun loaded(vararg blocks: Block): Pair<BlockEditorController, MutableList<List<Block>>> {
        val controller = BlockEditorController("n1")
        val changes = mutableListOf<List<Block>>()
        controller.onChange = { changes += it }
        controller.receive("n1", BlockDocument(blocks.toList()))
        return controller to changes
    }

    @Test
    fun `removePicture removes the line, fires onChange, and undo restores the same id and ref`() {
        val (controller, changes) = loaded(text, picture, after)

        assertTrue(controller.removePicture("r1"))

        assertEquals(listOf("t", "u"), controller.model.ids)
        assertEquals(listOf("t", "u"), changes.single().map { it.id })
        assertTrue(controller.canUndo)

        controller.undo()

        assertEquals(listOf("t", "pic:r1", "u"), controller.model.ids)
        val restored = controller.model.blocks().single { it.id == "pic:r1" }
        assertEquals(BlockType.PICTURE, restored.type)
        assertEquals("r1", restored.ref)
        assertEquals(listOf("t", "pic:r1", "u"), changes.last().map { it.id })
    }

    @Test
    fun `removePicture finds a drawing by its record, even as the first line`() {
        val (controller, changes) = loaded(drawing, after)

        assertTrue(controller.removePicture("s1"))

        assertEquals(listOf("u"), controller.model.ids)
        assertEquals("after", controller.model.blocks().single().text)
        assertEquals(1, changes.size)
    }

    @Test
    fun `removing a picture that is the only line leaves a paragraph with a fresh id`() {
        for (only in listOf(picture, drawing)) {
            val (controller, changes) = loaded(only)

            assertTrue(controller.removePicture(only.ref!!))

            val left = controller.model.blocks().single()
            assertEquals(BlockType.PARAGRAPH, left.type)
            assertEquals("", left.text)
            assertFalse(left.id.startsWith("pic:") || left.id.startsWith("drw:"), "left line carries ${left.id}")
            assertTrue(controller.model.refs.isEmpty())
            assertFalse(changes.last().any { it.id.startsWith("pic:") || it.id.startsWith("drw:") })

            controller.undo()
            assertEquals(listOf(only.id), controller.model.ids, "undo brings the picture back")
            assertEquals(only.type, controller.model.blocks().single().type)
        }
    }

    @Test
    fun `removePicture finds a picture or drawing block that has no ref by its id`() {
        val refLessPicture = Block("pic:r9", BlockType.PICTURE, pos = "b")
        val refLessDrawing = Block("drw:s9", BlockType.DRAWING, pos = "c")
        val (controller, changes) = loaded(text, refLessPicture, refLessDrawing, after)

        assertTrue(controller.removePicture("r9"))
        assertEquals(listOf("t", "drw:s9", "u"), controller.model.ids)
        assertTrue(controller.removePicture("s9"))
        assertEquals(listOf("t", "u"), controller.model.ids)
        assertEquals(2, changes.size)
        assertFalse(controller.removePicture("t"), "a text block is never a picture")
    }

    @Test
    fun `removePicture of a record no line shows changes nothing`() {
        val (controller, changes) = loaded(text, picture)

        assertFalse(controller.removePicture("other"))

        assertEquals(listOf("t", "pic:r1"), controller.model.ids)
        assertTrue(changes.isEmpty())
        assertFalse(controller.canUndo)
    }

    @Test
    fun `removePicture before the note is loaded does nothing`() {
        assertFalse(BlockEditorController("n1").removePicture("r1"))
    }
}
