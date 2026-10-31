package my.cheysoff.notes.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.cheysoff.core_blocks.Block
import my.cheysoff.core_blocks.BlockDocument
import my.cheysoff.core_blocks.BlockType
import my.cheysoff.core_editor.EditorFlags
import my.cheysoff.core_editor.ui.BlockEditor
import my.cheysoff.core_editor.ui.BlockEditorFieldTag
import my.cheysoff.core_editor.ui.rememberBlockEditorController
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A finger tap on a checklist item's box, on a real Android touch screen: it ticks the item and
 * does not start editing the item's text. Laid out as the note screen does (a scrolling column).
 */
@RunWith(AndroidJUnit4::class)
class ChecklistTapTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun tappingTheBoxTicksTheItemWithoutFocusingTheText() = tapBox(editing = false)

    @Test
    fun tappingTheBoxWhileTypingTicksTheItemAndLeavesTheCursor() = tapBox(editing = true)

    private fun tapBox(editing: Boolean) {
        EditorFlags.enable()
        val changes = mutableListOf<List<Block>>()
        val doc = BlockDocument(
            listOf(
                Block("t", BlockType.PARAGRAPH, "Shopping", pos = "a"),
                Block("c", BlockType.CHECK, "buy milk", pos = "b"),
            ),
        )
        rule.setContent {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                BlockEditor(
                    noteId = "n1",
                    document = doc,
                    onChange = { changes += it },
                    pictureContent = { _, _ -> },
                    controller = rememberBlockEditorController("n1"),
                )
            }
        }
        rule.waitForIdle()

        val field = rule.onNodeWithTag(BlockEditorFieldTag)
        if (editing) {
            field.performClick()
            field.performTextInputSelection(TextRange(3)) // in "Shopping"
            rule.waitForIdle()
        }

        // The visible box: CheckBoxSize (18dp) at the line's start, 9dp in from the left.
        rule.onNodeWithTag("check-c").performTouchInput { click(Offset(17.dp.toPx(), centerY)) }
        rule.waitForIdle()

        assertTrue("the tap ticked the item: $changes", changes.lastOrNull()?.single { it.id == "c" }?.done == true)
        if (editing) {
            field.assertIsFocused()
            field.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(3)))
        } else {
            field.assertIsNotFocused()
        }
    }
}
