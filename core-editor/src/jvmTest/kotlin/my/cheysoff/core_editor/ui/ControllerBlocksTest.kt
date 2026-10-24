package my.cheysoff.core_editor.ui

import my.cheysoff.core_blocks.Block
import my.cheysoff.core_blocks.BlockDocument
import my.cheysoff.core_blocks.BlockType
import my.cheysoff.core_editor.EditorFlags
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [BlockEditorController.blocks]: what the editor shows, for readers outside it (share, word count, a title save). */
class ControllerBlocksTest {

    @BeforeTest
    fun setUp() = EditorFlags.enable()

    @Test
    fun `blocks is empty before the note is loaded`() {
        assertTrue(BlockEditorController("n1").blocks.isEmpty())
    }

    @Test
    fun `blocks follows the loaded document and local edits`() {
        val controller = BlockEditorController("n1")
        controller.receive(
            "n1",
            BlockDocument(
                listOf(
                    Block("t", BlockType.PARAGRAPH, "before", pos = "a"),
                    Block("pic:r1", BlockType.PICTURE, ref = "r1", pos = "b"),
                ),
            ),
        )
        assertEquals(listOf("t", "pic:r1"), controller.blocks.map { it.id })

        controller.removePicture("r1")

        assertEquals(listOf("before"), controller.blocks.map { it.text })
    }
}
