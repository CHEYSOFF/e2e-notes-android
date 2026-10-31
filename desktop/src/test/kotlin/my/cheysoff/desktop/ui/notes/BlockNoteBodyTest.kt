package my.cheysoff.desktop.ui.notes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithText
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

    private fun androidx.compose.ui.test.ComposeUiTest.showBody(
        opened: MutableList<String>,
        changes: MutableList<List<Block>> = mutableListOf(),
        blocks: BlockDocument = document,
    ): BlockEditorController {
        EditorFlags.enable()
        val controller = BlockEditorController("n1")
        setContent {
            Column(Modifier.width(400.dp)) {
                BlockNoteBody(
                    draft = draft(blocks),
                    controller = controller,
                    accent = Color.Blue,
                    attachments = listOf(photo),
                    sketches = emptyList(),
                    pasteFailure = null,
                    onDismissPasteFailure = {},
                    onBlocksChange = { _, blocks, _ -> changes += blocks },
                    onInsertPicture = {},
                    onOpenPicture = { opened += it.id },
                )
            }
        }
        waitForIdle()
        return controller
    }

    @Test
    fun `a new note shows the Start writing hint, and a note with text does not`() = runComposeUiTest {
        var blocks by mutableStateOf(BlockDocument(emptyList()))
        EditorFlags.enable()
        val controller = BlockEditorController("n1")
        setContent {
            Column(Modifier.width(400.dp)) {
                BlockNoteBody(
                    draft = draft(blocks), controller = controller, accent = Color.Blue,
                    attachments = emptyList(), sketches = emptyList(), pasteFailure = null,
                    onDismissPasteFailure = {}, onBlocksChange = { _, _, _ -> }, onInsertPicture = {},
                    onOpenPicture = {},
                )
            }
        }
        waitForIdle()
        onNodeWithText("Start writing…").assertIsDisplayed()

        blocks = document
        waitForIdle()
        onNodeWithText("Start writing…").assertDoesNotExist()
    }

    @Test
    fun `the first click on a picture selects it, showing its handle, and the next opens it`() = runComposeUiTest {
        val opened = mutableListOf<String>()
        showBody(opened)

        onNodeWithTag("picture-pic:r1").performMouseInput { click(center) }
        waitForIdle()
        assertTrue("selecting is not opening", opened.isEmpty())
        onNodeWithTag("picture-resize-pic:r1").assertIsDisplayed()

        onNodeWithTag("picture-pic:r1").performMouseInput { click(center) }
        waitForIdle()
        assertEquals(listOf("r1"), opened)
    }

    @Test
    fun `dragging a picture does not open the viewer`() = runComposeUiTest {
        val opened = mutableListOf<String>()
        showBody(opened)
        val overlay = onNodeWithTag("picture-pic:r1")

        // The whole drag stays inside the picture and goes well past touch slop, so only the drag
        // detection (not leaving the bounds) can be what keeps the click from firing.
        overlay.performMouseInput {
            val start = Offset(centerX, height * 0.1f)
            val end = Offset(centerX, height * 0.8f)
            assertTrue("drag must pass touch slop", end.y - start.y > viewConfiguration.touchSlop * 2)
            moveTo(start)
            press()
            moveBy(Offset(0f, viewConfiguration.touchSlop + 1f))
            moveTo(end)
            assertTrue("release is inside the picture", end.y < height)
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

    @Test
    fun `the viewer's Delete removes the block, undoably`() = runComposeUiTest {
        // The record is left to the save's PictureLifecycle: deletePictureFromViewer has no way to
        // delete one (it takes only the controller), so that half is enforced by its signature.
        val changes = mutableListOf<List<Block>>()
        val controller = showBody(mutableListOf(), changes)

        runOnIdle { deletePictureFromViewer("r1", controller) }
        waitForIdle()

        assertEquals(listOf("t", "u"), changes.last().map { it.id })
        onNodeWithTag("picture-pic:r1").assertDoesNotExist()
        assertTrue("undoable", controller.canUndo)
    }
}
