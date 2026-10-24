package my.cheysoff.core_editor.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.runBlocking
import my.cheysoff.core_blocks.Block
import my.cheysoff.core_blocks.BlockDocument
import my.cheysoff.core_blocks.BlockType
import my.cheysoff.core_editor.EditorFlags
import my.cheysoff.core_editor.Markers
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The composed [BlockEditor]: overlays over their marker lines, the clipboard, local vs remote
 * edits, picture drag. Input goes through the Compose test API only, never the OS.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class BlockEditorUiTest {

    private fun doc(vararg blocks: Block) =
        BlockDocument(blocks.mapIndexed { i, b -> b.copy(pos = ('a' + i).toString()) })

    private val text = Block("t", BlockType.PARAGRAPH, "Intro")
    private val check = Block("c", BlockType.CHECK, "buy milk")
    private val picture = Block("pic:r1", BlockType.PICTURE, ref = "r1")
    private val number = Block("n", BlockType.NUMBER, "one")

    /** Records what the editor puts on the clipboard. */
    private class FakeClipboard : Clipboard {
        var entry: ClipEntry? = null
        override suspend fun getClipEntry(): ClipEntry? = entry
        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            entry = clipEntry
        }
        @Suppress("OVERRIDE_DEPRECATION")
        override val nativeClipboard get() = error("not used")
    }

    @Test
    fun `clicking a checkbox ticks the item and calls onChange`() = runComposeUiTest {
        EditorFlags.enable()
        val changes = mutableListOf<List<Block>>()
        val controller = BlockEditorController()
        setContent {
            BlockEditor(doc(text, check), { changes += it }, { _, _ -> }, Modifier.width(400.dp), controller)
        }
        waitForIdle()
        onNodeWithTag("check-c").performClick()
        waitForIdle()
        assertTrue(controller.model.blocks().single { it.id == "c" }.done)
        assertTrue(changes.last().single { it.id == "c" }.done, "onChange got $changes")
        assertTrue(controller.canUndo)
    }

    @Test
    fun `picture overlay sits on the placeholder line`() = runComposeUiTest {
        EditorFlags.enable()
        val controller = BlockEditorController()
        setContent {
            BlockEditor(
                doc(text, picture, number), {},
                { id, _ -> Box(Modifier.fillMaxSize().testTag("content-$id")) },
                Modifier.width(400.dp).testTag("editor"), controller,
            )
        }
        waitForIdle()
        val layout = assertNotNull(controller.layout)
        val line = layout.getLineForOffset(lineStart(controller, 1))
        val top = layout.getLineTop(line)
        val bottom = layout.getLineBottom(line)
        val editor = onNodeWithTag("editor").getBoundsInRoot()
        val overlay = onNodeWithTag("picture-pic:r1").getBoundsInRoot()
        val px = density.density
        assertClose(editor.top + (top / px).dp, overlay.top, "overlay top")
        assertClose(editor.top + (bottom / px).dp, overlay.bottom, "overlay bottom")
        assertTrue(bottom - top > 100 * px, "the picture line is tall: ${bottom - top}")
        onNodeWithTag("content-r1", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `missing record shows the placeholder and the block survives onChange`() = runComposeUiTest {
        EditorFlags.enable()
        val gone = Block("pic:gone", BlockType.PICTURE, ref = "gone")
        val changes = mutableListOf<List<Block>>()
        val controller = BlockEditorController()
        setContent {
            BlockEditor(
                doc(text, gone), { changes += it },
                { id, _ -> if (id == "known") Box(Modifier.fillMaxSize()) else BasicText("Picture not synced yet") },
                Modifier.width(400.dp).testTag("editor"), controller,
            )
        }
        waitForIdle()
        onNodeWithText("Picture not synced yet").assertIsDisplayed()
        controller.model.state.edit { selection = TextRange(5) } // end of "Intro"
        val field = onNodeWithTag(BlockEditorFieldTag)
        field.requestFocus()
        field.performTextInput("!")
        waitForIdle()
        val last = changes.last()
        assertEquals("Intro!", last.first().text)
        assertEquals(Block("pic:gone", BlockType.PICTURE, ref = "gone", pos = "b"), last.single { it.id == "pic:gone" })
    }

    @Test
    fun `copy of a selection with a picture and a checklist gives clean text`() = runComposeUiTest {
        EditorFlags.enable()
        val clipboard = FakeClipboard()
        val controller = BlockEditorController()
        setContent {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                BlockEditor(doc(text, check, picture, number), {}, { _, _ -> }, Modifier.width(400.dp), controller)
            }
        }
        waitForIdle()
        val field = onNodeWithTag(BlockEditorFieldTag)
        field.requestFocus()
        controller.model.state.edit { selection = TextRange(0, length) }
        field.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.C) } }
        waitForIdle()
        val copied = assertNotNull(runBlocking { clipboard.entry?.plainText() })
        assertEquals("Intro\n☐ buy milk\n1. one", copied)
        assertFalse(copied.any { Markers.isPrivateUse(it) })
        // Copy leaves the note alone.
        assertEquals(4, controller.model.ids.size)
    }

    @Test
    fun `cut puts clean text on the clipboard and removes the selection`() = runComposeUiTest {
        EditorFlags.enable()
        val clipboard = FakeClipboard()
        val changes = mutableListOf<List<Block>>()
        val controller = BlockEditorController()
        setContent {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                BlockEditor(doc(text, check), { changes += it }, { _, _ -> }, Modifier.width(400.dp), controller)
            }
        }
        waitForIdle()
        val field = onNodeWithTag(BlockEditorFieldTag)
        field.requestFocus()
        // From the start of the checklist line, marker included, to its end.
        controller.model.state.edit { selection = TextRange(6, length) }
        field.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.X) } }
        waitForIdle()
        assertEquals("☐ buy milk", runBlocking { clipboard.entry?.plainText() })
        assertEquals("Intro\n", controller.model.state.text.toString())
        assertTrue(changes.isNotEmpty())
    }

    @Test
    fun `ParagraphStyle indent adds no blank line`() = runComposeUiTest {
        EditorFlags.enable()
        // The probe: a hanging-indent ParagraphStyle on list lines, in the buffer as the plan
        // first proposed. Compose 1.12 still lays out an extra empty line at each styled line
        // break, so the editor uses the fallback (no hanging indent, spacing on the line break).
        val probe = TextFieldState("${Markers.CHECK_OPEN}one\n${Markers.CHECK_OPEN}two\nthree")
        probe.edit {
            val indent = ParagraphStyle(textIndent = TextIndent(firstLine = 0.sp, restLine = 24.sp))
            addStyle(indent, 0, 4)
            addStyle(indent, 5, 9)
        }
        var probeLayout: TextLayoutResult? = null
        val controller = BlockEditorController()
        setContent {
            BasicTextField(probe, Modifier.width(400.dp), onTextLayout = { probeLayout = it() })
            BlockEditor(
                doc(text, check, check.copy(id = "c2", text = "eggs"), Block("b", BlockType.BULLET, "x"), number),
                {}, { _, _ -> }, Modifier.width(400.dp), controller,
            )
        }
        waitForIdle()
        assertTrue(probeLayout!!.lineCount > 3, "ParagraphStyle no longer adds blank lines: revisit the fallback")
        // The editor's own layout: exactly one visual line per block.
        assertEquals(5, controller.layout!!.lineCount)
    }

    @Test
    fun `a remote document applies without calling onChange and becomes the base`() = runComposeUiTest {
        EditorFlags.enable()
        val changes = mutableListOf<List<Block>>()
        val controller = BlockEditorController()
        var document by mutableStateOf(doc(text, check))
        setContent { BlockEditor(document, { changes += it }, { _, _ -> }, Modifier.width(400.dp), controller) }
        waitForIdle()
        val remote = doc(text, check.copy(done = true))
        document = remote
        waitForIdle()
        assertTrue(controller.model.blocks().single { it.id == "c" }.done)
        assertEquals(remote, controller.base)
        assertTrue(changes.isEmpty(), "onChange after a remote apply: $changes")
    }

    @Test
    fun `dragging a picture moves it below the next line and can be undone`() = runComposeUiTest {
        EditorFlags.enable()
        val changes = mutableListOf<List<Block>>()
        val controller = BlockEditorController()
        setContent {
            BlockEditor(
                doc(text, picture, number), { changes += it },
                { _, _ -> Box(Modifier.fillMaxSize()) }, Modifier.width(400.dp), controller,
            )
        }
        waitForIdle()
        val overlay = onNodeWithTag("picture-pic:r1")
        val h = overlay.getBoundsInRoot().let { it.bottom - it.top }
        overlay.performMouseInput {
            moveTo(center)
            press()
            moveBy(androidx.compose.ui.geometry.Offset(0f, 10f))
            moveBy(androidx.compose.ui.geometry.Offset(0f, (h.value * density) * 0.9f))
            release()
        }
        waitForIdle()
        assertEquals(listOf("t", "n", "pic:r1"), controller.model.ids)
        assertEquals(listOf("t", "n", "pic:r1"), changes.last().map { it.id })
        controller.undo()
        waitForIdle()
        assertEquals(listOf("t", "pic:r1", "n"), controller.model.ids)
    }

    @Test
    fun `typing and Ctrl+Z are local edits reported through onChange`() = runComposeUiTest {
        EditorFlags.enable()
        val changes = mutableListOf<List<Block>>()
        val controller = BlockEditorController()
        setContent { BlockEditor(doc(text), { changes += it }, { _, _ -> }, Modifier.width(400.dp), controller) }
        waitForIdle()
        controller.model.state.edit { selection = TextRange(5) }
        val field = onNodeWithTag(BlockEditorFieldTag)
        field.requestFocus()
        field.performTextInput("!")
        waitForIdle()
        assertEquals("Intro!", changes.last().single().text)
        field.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }
        waitForIdle()
        assertEquals("Intro", controller.model.state.text.toString())
        assertEquals("Intro", changes.last().single().text)
        assertEquals(2, changes.size)
    }

    private fun lineStart(controller: BlockEditorController, line: Int): Int {
        val t = controller.model.state.text
        var k = 0
        for (i in t.indices) {
            if (k == line) return i
            if (t[i] == '\n') k++
        }
        return t.length
    }

    private fun assertClose(expected: Dp, actual: Dp, what: String) {
        assertTrue(abs(expected.value - actual.value) <= 1f, "$what: expected $expected, was $actual")
    }
}
