package my.cheysoff.desktop.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onRoot
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
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test

/** Not a test: renders the real body with a real photo to PNGs for a look. Run with -Dprobe.out=dir. */
@OptIn(ExperimentalTestApi::class)
class PictureLookProbe {
    @Test
    fun render() = runComposeUiTest {
        val out = System.getProperty("manana.probe.out") ?: return@runComposeUiTest
        val img = BufferedImage(1200, 800, BufferedImage.TYPE_INT_RGB)
        img.createGraphics().apply { color = java.awt.Color(200, 90, 40); fillRect(0, 0, 1200, 800); color = java.awt.Color.WHITE; fillOval(300, 200, 600, 400) }
        val bytes = ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
        val photo = AttachmentData("r1", "n1", 0, 0, "image/png", 1200, 800, bytes, 1200, 800, bytes, 1L, 1L)
        val doc = BlockDocument(listOf(
            Block("t", BlockType.PARAGRAPH, text = "before ".repeat(60), pos = "a"),
            Block("pic:r1", BlockType.PICTURE, ref = "r1", pos = "b"),
            Block("u", BlockType.PARAGRAPH, text = "after", pos = "c"),
        ))
        EditorFlags.enable()
        val controller = BlockEditorController("n1")
        setContent {
            Column(Modifier.width(900.dp).background(Color(0xFF121216))) {
                BlockNoteBody(
                    draft = EditorDraft("n1", "", "", NoteContentFormat.BLOCKS, emptyList(), false, false, null, 0L, 0L, blocks = doc),
                    controller = controller, accent = Color(0xFF7C5CFF), attachments = listOf(photo), sketches = emptyList(),
                    pasteFailure = null, onDismissPasteFailure = {}, onBlocksChange = { _, _, _ -> }, onInsertPicture = {}, onOpenPicture = {},
                )
            }
        }
        waitForIdle()
        fun shot(name: String) = ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "$name.png"))
        shot("1-plain")
        onNodeWithTag("picture-pic:r1").performMouseInput { click(Offset(50f, 50f)) }
        waitForIdle()
        shot("2-selected")
        onNodeWithTag("picture-resize-pic:r1").performMouseInput {
            moveTo(center); press(); moveBy(Offset(-10f, 0f)); moveBy(Offset(-250f, 0f)); release()
        }
        waitForIdle()
        shot("3-resized")
    }
}
