package my.cheysoff.desktop.ui.notes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import my.cheysoff.core_blocks.Block
import my.cheysoff.core_blocks.BlockDocument
import my.cheysoff.core_blocks.BlockType
import my.cheysoff.core_domain.model.AttachmentData
import my.cheysoff.core_domain.model.NoteContentFormat
import my.cheysoff.core_editor.EditorFlags
import my.cheysoff.core_editor.ui.BlockEditorController
import my.cheysoff.desktop.ui.state.EditorDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The block body's own wiring around the shared editor: a click on a picture opens the full-screen
 * viewer, a drag of it does not, and pictures are refused until the editor can take them. Driven by
 * Compose's test input inside the test's own composition, never the real screen.
 */
@OptIn(ExperimentalTestApi::class)
class BlockNoteBodyTest {

    private val photo = AttachmentData(
        id = "r1", noteId = "n1", anchor = 0, order = 0, mimeType = "image/jpeg",
        width = 10, height = 10, bytes = ByteArray(1), thumbWidth = 10, thumbHeight = 10,
        thumbBytes = ByteArray(1), createdAt = 1L, updatedAt = 1L,
    )

    private val document = BlockDocument(
        listOf(
            Block("t", BlockType.PARAGRAPH, text = "before", pos = "a"),
            Block("pic:r1", BlockType.PICTURE, ref = "r1", pos = "b"),
            Block("u", BlockType.PARAGRAPH, text = "after", pos = "c"),
        ),
    )

    private fun draft(blocks: BlockDocument? = document, unreadable: String? = null) = EditorDraft(
        id = "n1", title = "", content = "", contentFormat = NoteContentFormat.BLOCKS, checklist = emptyList(),
        isPinned = false, isFavorite = false, folderId = null, createdAt = 0L, updatedAt = 0L,
        blocks = blocks, unreadableBody = unreadable,
    )

    private fun androidx.compose.ui.test.ComposeUiTest.showBody(opened: MutableList<String>): BlockEditorController {
        EditorFlags.enable()
        val controller = BlockEditorController("n1")
        setContent {
            Column(Modifier.width(400.dp)) {
                BlockNoteBody(
                    draft = draft(),
                    controller = controller,
                    accent = Color.Blue,
                    attachments = listOf(photo),
                    sketches = emptyList(),
                    pasteFailure = null,
                    onDismissPasteFailure = {},
                    onBlocksChange = { _, _, _ -> },
                    onInsertPicture = {},
                    onOpenPicture = { opened += it.id },
                )
            }
        }
        waitForIdle()
        return controller
    }

    @Test
    fun `clicking a picture opens the viewer for its record`() = runComposeUiTest {
        val opened = mutableListOf<String>()
        showBody(opened)

        onNodeWithTag("picture-pic:r1").performMouseInput { click(center) }
        waitForIdle()

        assertEquals(listOf("r1"), opened)
    }

    @Test
    fun `dragging a picture does not open the viewer`() = runComposeUiTest {
        val opened = mutableListOf<String>()
        showBody(opened)
        val overlay = onNodeWithTag("picture-pic:r1")
        val h = overlay.getBoundsInRoot().let { it.bottom - it.top }

        overlay.performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(0f, 10f))
            moveBy(Offset(0f, (h.value * density) * 0.9f))
            release()
        }
        waitForIdle()

        assertTrue(opened.isEmpty())
    }

    @Test
    fun `pictures are refused until the editor has loaded an editable note`() = runComposeUiTest {
        val controller = BlockEditorController("n1")
        assertFalse("not loaded yet", controller.canPlacePicture(draft()))

        val loaded = showBody(mutableListOf())
        assertTrue(loaded.canPlacePicture(draft()))
        assertFalse(loaded.canPlacePicture(draft(unreadable = "{\"v\":99}")))
        assertFalse(loaded.canPlacePicture(draft(blocks = null)))
    }
}
