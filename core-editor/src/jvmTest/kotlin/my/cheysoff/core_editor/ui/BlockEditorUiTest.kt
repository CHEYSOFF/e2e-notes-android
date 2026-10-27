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
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.IOException
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.asAwtTransferable
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
        val controller = BlockEditorController("n1")
        setContent {
            BlockEditor("n1", doc(text, check), { changes += it }, { _, _ -> }, Modifier.width(400.dp), controller)
        }
        waitForIdle()
        onNodeWithTag("check-c").performClick()
        waitForIdle()
        assertTrue(controller.model.blocks().single { it.id == "c" }.done)
        assertTrue(changes.last().single { it.id == "c" }.done, "onChange got $changes")
        assertTrue(controller.canUndo)
    }

    @Test
    fun `an empty note shows the placeholder until the first character is typed`() = runComposeUiTest {
        EditorFlags.enable()
        val controller = BlockEditorController("n1")
        setContent {
            BlockEditor(
                "n1", BlockDocument(emptyList()), {}, { _, _ -> },
                Modifier.width(400.dp), controller,
                placeholder = { BasicText("Start writing…") },
            )
        }
        waitForIdle()
        onNodeWithText("Start writing…").assertIsDisplayed()
        val field = onNodeWithTag(BlockEditorFieldTag)
        field.requestFocus()
        field.performTextInput("H")
        waitForIdle()
        onNodeWithTag(BlockEditorPlaceholderTag).assertDoesNotExist()
        field.performKeyInput { pressKey(Key.Backspace) }
        waitForIdle()
        onNodeWithText("Start writing…").assertIsDisplayed()
    }

    @Test
    fun `a note with text shows no placeholder`() = runComposeUiTest {
        EditorFlags.enable()
        val controller = BlockEditorController("n1")
        setContent {
            BlockEditor(
                "n1", doc(text), {}, { _, _ -> },
                Modifier.width(400.dp), controller,
                placeholder = { BasicText("Start writing…") },
            )
        }
        waitForIdle()
        onNodeWithTag(BlockEditorPlaceholderTag).assertDoesNotExist()
    }

    @Test
    fun `picture overlay sits on the placeholder line`() = runComposeUiTest {
        EditorFlags.enable()
        val controller = BlockEditorController("n1")
        setContent {
            BlockEditor(
                "n1", doc(text, picture, number), {},
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
        val controller = BlockEditorController("n1")
        setContent {
            BlockEditor(
                "n1", doc(text, gone), { changes += it },
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
        val controller = BlockEditorController("n1")
        setContent {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                BlockEditor("n1", doc(text, check, picture, number), {}, { _, _ -> }, Modifier.width(400.dp), controller)
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
        val controller = BlockEditorController("n1")
        setContent {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                BlockEditor("n1", doc(text, check), { changes += it }, { _, _ -> }, Modifier.width(400.dp), controller)
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
    fun `ParagraphStyle indent still adds a blank line, so the fallback spacing is in use`() = runComposeUiTest {
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
        val controller = BlockEditorController("n1")
        setContent {
            BasicTextField(probe, Modifier.width(400.dp), onTextLayout = { probeLayout = it() })
            BlockEditor(
                "n1", doc(text, check, check.copy(id = "c2", text = "eggs"), Block("b", BlockType.BULLET, "x"), number),
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
        val controller = BlockEditorController("n1")
        var document by mutableStateOf(doc(text, check))
        setContent { BlockEditor("n1", document, { changes += it }, { _, _ -> }, Modifier.width(400.dp), controller) }
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
        val controller = BlockEditorController("n1")
        setContent {
            BlockEditor(
                "n1", doc(text, picture, number), { changes += it },
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
        val controller = BlockEditorController("n1")
        setContent { BlockEditor("n1", doc(text), { changes += it }, { _, _ -> }, Modifier.width(400.dp), controller) }
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

    @Test
    fun `picture content stays composed while another line is typed in`() = runComposeUiTest {
        EditorFlags.enable()
        val identities = mutableSetOf<Any>()
        var disposed = 0
        val controller = BlockEditorController("n1")
        setContent {
            BlockEditor(
                "n1", doc(text, picture, number), {},
                { _, _ ->
                    val identity = remember { Any() }
                    identities += identity
                    DisposableEffect(Unit) { onDispose { disposed++ } }
                    Box(Modifier.fillMaxSize())
                },
                Modifier.width(400.dp), controller,
            )
        }
        waitForIdle()
        controller.model.state.edit { selection = TextRange(5) } // end of "Intro", above the picture
        val field = onNodeWithTag(BlockEditorFieldTag)
        field.requestFocus()
        for (c in "abc") {
            field.performTextInput(c.toString())
            waitForIdle()
        }
        field.performTextInput("\n")
        waitForIdle()
        assertEquals("Introabc", controller.model.blocks().first().text)
        assertEquals(1, identities.size, "picture content was recreated")
        assertEquals(0, disposed, "picture content left composition")
    }

    @Test
    fun `switching the note id gives a fresh controller and Ctrl+Z cannot bring back the old note`() = runComposeUiTest {
        EditorFlags.enable()
        var noteId by mutableStateOf("a")
        var controller: BlockEditorController? = null
        val docs = mapOf("a" to doc(text), "b" to doc(Block("other", BlockType.PARAGRAPH, "Note B")))
        setContent {
            val c = rememberBlockEditorController(noteId)
            controller = c
            BlockEditor(noteId, docs.getValue(noteId), {}, { _, _ -> }, Modifier.width(400.dp), c)
        }
        waitForIdle()
        controller!!.model.state.edit { selection = TextRange(5) }
        onNodeWithTag(BlockEditorFieldTag).requestFocus()
        onNodeWithTag(BlockEditorFieldTag).performTextInput("!")
        waitForIdle()
        assertTrue(controller!!.canUndo)

        noteId = "b"
        waitForIdle()
        val b = controller!!
        assertEquals("b", b.noteId)
        assertFalse(b.canUndo)
        assertEquals("Note B", b.model.state.text.toString())
        val field = onNodeWithTag(BlockEditorFieldTag)
        field.requestFocus()
        field.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }
        waitForIdle()
        assertEquals("Note B", b.model.state.text.toString())
        assertEquals(listOf("other"), b.model.ids)
    }

    @Test
    fun `another note's document is loaded, never offered, even on a hoisted controller`() = runComposeUiTest {
        EditorFlags.enable()
        var noteId by mutableStateOf("a")
        var document by mutableStateOf(doc(text))
        val changes = mutableListOf<List<Block>>()
        val controller = BlockEditorController("a")
        setContent { BlockEditor(noteId, document, { changes += it }, { _, _ -> }, Modifier.width(400.dp), controller) }
        waitForIdle()
        controller.model.state.edit { selection = TextRange(5) }
        onNodeWithTag(BlockEditorFieldTag).requestFocus()
        onNodeWithTag(BlockEditorFieldTag).performTextInput("!")
        waitForIdle()
        assertTrue(controller.canUndo)
        val other = doc(Block("other", BlockType.PARAGRAPH, "Note B"))
        noteId = "b"
        document = other
        waitForIdle()
        assertFalse(controller.canUndo)
        assertFalse(controller.remote.hasPending)
        assertEquals(other, controller.base)
        assertEquals(listOf("other"), controller.model.ids)
        controller.undo()
        waitForIdle()
        assertEquals("Note B", controller.model.state.text.toString())
        assertEquals(1, changes.size, "the reload is not a local edit")
    }

    @Test
    fun `copy of a selection starting mid-line gives that line's tail as a paragraph`() =
        copies(TextRange(2, 15), "tro\n☐ buy milk")

    @Test
    fun `copy of a selection ending mid-line keeps the marker of the line it started`() =
        copies(TextRange(0, 10), "Intro\n☐ buy")

    private fun copies(selection: TextRange, expected: String) = runComposeUiTest {
        EditorFlags.enable()
        val clipboard = FakeClipboard()
        val controller = BlockEditorController("n1")
        setContent {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                BlockEditor("n1", doc(text, check), {}, { _, _ -> }, Modifier.width(400.dp), controller)
            }
        }
        waitForIdle()
        val field = onNodeWithTag(BlockEditorFieldTag)
        field.requestFocus()
        controller.model.state.edit { this.selection = selection }
        field.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.C) } }
        waitForIdle()
        assertEquals(expected, runBlocking { clipboard.entry?.plainText() })
    }

    @Test
    fun `an entry with no readable text is replaced by an empty plain entry`() = runBlocking {
        val target = FakeClipboard()
        val clipboard = EditorClipboard(target)
        val imageOnly = object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor
            override fun getTransferData(flavor: DataFlavor): Any = throw UnsupportedFlavorException(flavor)
        }
        val refusing = object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(DataFlavor.stringFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor) = true
            override fun getTransferData(flavor: DataFlavor): Any = throw IOException("gone")
        }
        for (t in listOf(imageOnly, refusing)) {
            target.entry = null
            clipboard.setClipEntry(ClipEntry(t))
            val written = assertNotNull(target.entry)
            assertEquals("", written.plainText())
            assertFalse(written.asAwtTransferable!!.isDataFlavorSupported(DataFlavor.imageFlavor))
        }
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
