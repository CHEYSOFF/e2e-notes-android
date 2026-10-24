package my.cheysoff.core_editor

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.test.onNodeWithTag
import my.cheysoff.core_blocks.Block
import my.cheysoff.core_blocks.BlockDocument
import my.cheysoff.core_blocks.BlockType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y (and the Cmd forms) in a real composed field must reach
 * [BlockHistory], not the field's own undo, which would change the text behind [EditorModel]'s
 * back and leave its ids out of step. Keys are injected through the Compose test API only.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class BlockHistoryKeysTest {

    private fun setUp(): Pair<EditorModel, BlockHistory> {
        EditorFlags.enable()
        val model = EditorModel(
            BlockDocument(listOf(Block("a", BlockType.PARAGRAPH, "one", pos = "a"), Block("b", BlockType.PARAGRAPH, "two", pos = "b"))),
        )
        var clock = 0L
        return model to BlockHistory(model) { clock += 2_000; clock }
    }

    @Test
    fun `Ctrl+Z undoes through the block history and Ctrl+Shift+Z and Ctrl+Y redo`() = runComposeUiTest {
        val (model, history) = setUp()
        setContent {
            BasicTextField(
                model.state,
                inputTransformation = EditingRules(model),
                modifier = Modifier.blockHistoryKeys(history).testTag("field"),
            )
        }
        val field = onNodeWithTag("field")
        field.requestFocus()
        field.performTextInput("X")
        waitForIdle()
        val typed = model.state.text.toString()
        assertTrue(typed.contains('X'), typed)
        assertTrue(history.canUndo)

        field.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }
        waitForIdle()
        assertEquals("one\ntwo", model.state.text.toString())
        assertEquals(listOf("a", "b"), model.ids)
        model.blocks() // in step

        field.performKeyInput { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.Z) } } }
        waitForIdle()
        assertEquals(typed, model.state.text.toString())

        field.performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.Z) } }
        waitForIdle()
        assertEquals("one\ntwo", model.state.text.toString())

        field.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Y) } }
        waitForIdle()
        assertEquals(typed, model.state.text.toString())
        model.blocks()
    }

    @Test
    fun `Ctrl+Z with nothing to undo leaves the field alone`() = runComposeUiTest {
        val (model, history) = setUp()
        setContent {
            BasicTextField(
                model.state,
                inputTransformation = EditingRules(model),
                modifier = Modifier.blockHistoryKeys(history).testTag("field"),
            )
        }
        val field = onNodeWithTag("field")
        field.requestFocus()
        field.performTextInput("X")
        waitForIdle()
        val typed = model.state.text.toString()
        // Our history is empty, but the field's may still hold the typing; it must not run.
        history.clear()
        field.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }
        waitForIdle()
        assertEquals(typed, model.state.text.toString())
        model.blocks()
    }
}
