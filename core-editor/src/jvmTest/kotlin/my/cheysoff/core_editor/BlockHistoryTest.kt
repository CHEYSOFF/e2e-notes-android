package my.cheysoff.core_editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.delete
import androidx.compose.foundation.text.input.insert
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.text.TextRange
import my.cheysoff.core_blocks.Block
import my.cheysoff.core_blocks.BlockDocument
import my.cheysoff.core_blocks.BlockType
import my.cheysoff.core_blocks.InlineStyle
import my.cheysoff.core_blocks.StyleSpan
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [BlockHistory] driven the way the editor drives it: user edits through [EditingRules] (a buffer
 * mutation, then the transformation), toolbar presses through [EditorCommands], and remote changes
 * as bare [EditorModel.edit] calls that never reach the history. Time is a fake clock.
 */
class BlockHistoryTest {

    @BeforeTest
    fun setUp() = EditorFlags.enable()

    private var clock = 0L

    private fun doc(vararg blocks: Block) =
        BlockDocument(blocks.mapIndexed { i, b -> b.copy(pos = "a" + ('a' + i)) })

    private fun p(id: String, text: String, vararg spans: StyleSpan) = Block(id, BlockType.PARAGRAPH, text, spans.toList())
    private fun check(id: String, text: String) = Block(id, BlockType.CHECK, text)
    private fun pic(record: String) = Block("pic:$record", BlockType.PICTURE, ref = record)

    private fun setup(vararg blocks: Block): Pair<EditorModel, BlockHistory> {
        val model = EditorModel(doc(*blocks))
        return model to BlockHistory(model) { clock }
    }

    private fun EditorModel.text() = state.text.toString()
    private fun EditorModel.texts() = blocks().map { it.text }
    private fun EditorModel.select(start: Int, end: Int = start) = state.edit { selection = TextRange(start, end) }

    private fun EditorModel.user(edit: TextFieldBuffer.() -> Unit) {
        val rules = EditingRules(this)
        state.edit {
            edit()
            with(rules) { transformInput() }
        }
    }

    private fun EditorModel.commit(at: Int, text: String) = user {
        insert(at, text)
        selection = TextRange(at + text.length)
    }

    private fun EditorModel.deleteRange(start: Int, end: Int) = user {
        delete(start, end)
        selection = TextRange(start)
    }

    /** Types [text] one char at a time at [at], [gap] ms apart. */
    private fun EditorModel.type(at: Int, text: String, gap: Long = 100) {
        text.forEachIndexed { i, c ->
            clock += gap
            commit(at + i, c.toString())
        }
    }

    /** A change synced in from the other device: an edit the history never hears about. */
    private fun EditorModel.remote(edit: TextFieldBuffer.() -> Unit) = edit(edit)

    private fun EditorModel.lineStart(id: String): Int {
        val k = ids.indexOf(id)
        return lineRanges(state.text)[k].start
    }

    private fun EditorModel.lineEnd(id: String): Int {
        val k = ids.indexOf(id)
        return lineRanges(state.text)[k].end
    }

    private fun EditorModel.assertConsistent() {
        assertEquals(text().split('\n').size, ids.size, "one id per line in ${text()}")
        assertEquals(ids.size, ids.toSet().size, "ids are unique: $ids")
        blocks()
    }

    // --- The brief's five ------------------------------------------------------------------------

    @Test
    fun `typing a word then undo removes the word`() {
        val (model, history) = setup(p("a", "Hi "))
        model.type(3, "world")
        assertEquals("Hi world", model.text())
        assertTrue(history.undo())
        assertEquals(listOf("Hi "), model.texts())
        assertEquals(listOf("a"), model.ids)
        assertFalse(history.canUndo)
    }

    @Test
    fun `undo after a remote change elsewhere keeps the remote change`() {
        val (model, history) = setup(p("a", "one"), p("b", "two"))
        model.type(3, "X")
        // Remote: b's text changes and a new block "r" arrives after b.
        model.remote {
            val e = model.lineEnd("b")
            replace(model.lineStart("b"), e, "TWO")
        }
        model.stageText("r")
        model.remote { insert(length, "\nremote") }
        assertTrue(history.undo())
        assertEquals(listOf("one", "TWO", "remote"), model.texts())
        assertEquals(listOf("a", "b", "r"), model.ids)
        model.assertConsistent()
    }

    @Test
    fun `undo of a picture delete brings the picture back with the same id`() {
        val (model, history) = setup(p("a", "x"), pic("r1"), p("b", "y"))
        // Backspace at the start of "y": rule 3 deletes the picture line above it.
        model.deleteRange(3, 4)
        assertEquals(listOf("a", "b"), model.ids)
        assertTrue(history.undo())
        assertEquals(listOf("a", "pic:r1", "b"), model.ids)
        val restored = model.blocks()[1]
        assertEquals(BlockType.PICTURE, restored.type)
        assertEquals("r1", restored.ref)
        assertEquals(mapOf("pic:r1" to "r1"), model.refs)
        model.assertConsistent()
    }

    @Test
    fun `redo reapplies`() {
        val (model, history) = setup(p("a", "x"), pic("r1"), p("b", "y"))
        model.deleteRange(3, 4)
        history.undo()
        assertTrue(history.canRedo)
        assertTrue(history.redo())
        assertEquals(listOf("a", "b"), model.ids)
        assertEquals(listOf("x", "y"), model.texts())
        assertFalse(history.canRedo)
        assertTrue(history.canUndo)
        // And back again: undo after redo works on the same entry.
        assertTrue(history.undo())
        assertEquals(listOf("a", "pic:r1", "b"), model.ids)
    }

    @Test
    fun `a new local edit clears redo`() {
        val (model, history) = setup(p("a", "one"))
        model.type(3, "X")
        history.undo()
        assertTrue(history.canRedo)
        clock += 5_000
        model.type(0, "Y")
        assertFalse(history.canRedo)
        assertFalse(history.redo())
        assertEquals(listOf("Yone"), model.texts())
    }

    // --- Coalescing --------------------------------------------------------------------------------

    @Test
    fun `whitespace ends a typing group`() {
        val (model, history) = setup(p("a", ""))
        model.type(0, "one two")
        history.undo()
        assertEquals(listOf("one "), model.texts())
        history.undo()
        assertEquals(listOf(""), model.texts())
        assertFalse(history.canUndo)
    }

    @Test
    fun `a pause of one second ends a typing group`() {
        val (model, history) = setup(p("a", ""))
        model.type(0, "ab")
        model.type(2, "cd", gap = 1_000)
        history.undo()
        assertEquals(listOf("abc"), model.texts())
        history.undo()
        assertEquals(listOf("ab"), model.texts())
        history.undo()
        assertEquals(listOf(""), model.texts())
    }

    @Test
    fun `typing in another block ends a typing group`() {
        val (model, history) = setup(p("a", "one"), p("b", "two"))
        model.type(3, "X")
        model.type(model.text().length, "Y")
        history.undo()
        assertEquals(listOf("oneX", "two"), model.texts())
        history.undo()
        assertEquals(listOf("one", "two"), model.texts())
    }

    @Test
    fun `undo and redo record nothing new`() {
        val (model, history) = setup(p("a", ""))
        model.type(0, "ab")
        history.undo()
        history.redo()
        history.undo()
        assertEquals(listOf(""), model.texts())
        assertFalse(history.canUndo)
        assertTrue(history.canRedo)
    }

    // --- What gets restored ------------------------------------------------------------------------

    @Test
    fun `undo after a remote change to the same block restores the local before`() {
        val (model, history) = setup(p("a", "one"), p("b", "two"))
        model.type(3, "X")
        model.remote { replace(0, model.lineEnd("a"), "remote") }
        history.undo()
        assertEquals(listOf("one", "two"), model.texts())
        assertEquals(listOf("a", "b"), model.ids)
    }

    @Test
    fun `undo re-inserts a block a remote change deleted, beside its old neighbour`() {
        val (model, history) = setup(p("a", "one"), p("b", "two"), p("c", "three"))
        model.type(model.lineEnd("b"), "X")
        // Remote deletes b.
        model.remote { delete(model.lineEnd("a"), model.lineEnd("b")) }
        assertEquals(listOf("a", "c"), model.ids)
        history.undo()
        assertEquals(listOf("a", "b", "c"), model.ids)
        assertEquals(listOf("one", "two", "three"), model.texts())
        model.assertConsistent()
    }

    @Test
    fun `undo of Enter joins the split back under the old id`() {
        val (model, history) = setup(check("a", "milk"), p("b", "end"))
        model.commit(3, "\n") // "mi" | "lk"
        assertEquals(3, model.ids.size)
        history.undo()
        assertEquals(listOf("a", "b"), model.ids)
        assertEquals(listOf("milk", "end"), model.texts())
        assertEquals(BlockType.CHECK, model.blocks()[0].type)
        model.assertConsistent()
    }

    @Test
    fun `undo of Enter in front of an empty first item drops the new line, not the item`() {
        val (model, history) = setup(check("a", ""))
        model.commit(0, "\n")
        assertEquals(2, model.ids.size)
        assertEquals("a", model.ids[1])
        history.undo()
        assertEquals(listOf("a"), model.ids)
        assertEquals("${Markers.CHECK_OPEN}", model.text())
        model.assertConsistent()
    }

    @Test
    fun `undo of a bold toggle restores the spans`() {
        val (model, history) = setup(p("a", "hello", StyleSpan(0, 2, InlineStyle.ITALIC)))
        model.select(0, 5)
        EditorCommands.toggle(model, InlineStyle.BOLD)
        assertTrue(history.canUndo)
        history.undo()
        assertEquals(listOf(StyleSpan(0, 2, InlineStyle.ITALIC)), model.blocks()[0].spans)
        history.redo()
        assertEquals(
            listOf(StyleSpan(0, 2, InlineStyle.ITALIC), StyleSpan(0, 5, InlineStyle.BOLD)).sortedBy { it.style.ordinal },
            model.blocks()[0].spans.sortedBy { it.style.ordinal },
        )
    }

    @Test
    fun `undo of a type change and a tick`() {
        val (model, history) = setup(p("a", "milk"))
        model.select(1)
        EditorCommands.setType(model, BlockType.CHECK)
        EditorCommands.toggleCheck(model, 0)
        assertTrue(model.blocks()[0].done)
        history.undo()
        assertFalse(model.blocks()[0].done)
        assertEquals(BlockType.CHECK, model.blocks()[0].type)
        history.undo()
        assertEquals(BlockType.PARAGRAPH, model.blocks()[0].type)
        assertEquals(listOf("a"), model.ids)
    }

    @Test
    fun `undo of a picture insert removes it and redo brings it back`() {
        val (model, history) = setup(p("a", "one"), p("b", "two"))
        model.select(3)
        EditorCommands.insertPicture(model, "pic:r9", BlockType.PICTURE)
        assertEquals(listOf("a", "pic:r9", "b"), model.ids)
        history.undo()
        assertEquals(listOf("a", "b"), model.ids)
        assertEquals(listOf("one", "two"), model.texts())
        history.redo()
        assertEquals(listOf("a", "pic:r9", "b"), model.ids)
        assertEquals("r9", model.refs["pic:r9"])
        model.assertConsistent()
    }

    @Test
    fun `undo of a picture move puts it back`() {
        val (model, history) = setup(p("a", "one"), pic("r1"), p("b", "two"), p("c", "three"))
        EditorCommands.movePicture(model, from = 1, toGap = 4)
        assertEquals(listOf("a", "b", "c", "pic:r1"), model.ids)
        history.undo()
        assertEquals(listOf("a", "pic:r1", "b", "c"), model.ids)
        history.redo()
        assertEquals(listOf("a", "b", "c", "pic:r1"), model.ids)
        model.assertConsistent()
    }

    @Test
    fun `undo of a deleted first line restores it above`() {
        val (model, history) = setup(p("a", "one"), p("b", "two"))
        model.deleteRange(0, 4)
        assertEquals(listOf("b"), model.ids)
        history.undo()
        assertEquals(listOf("a", "b"), model.ids)
        assertEquals(listOf("one", "two"), model.texts())
        model.assertConsistent()
    }

    @Test
    fun `nothing to undo returns false`() {
        val (_, history) = setup(p("a", "one"))
        assertFalse(history.undo())
        assertFalse(history.redo())
    }

    @Test
    fun `canUndo and canRedo are snapshot state`() {
        val (model, history) = setup(p("a", ""))
        val can = derivedStateOf { history.canUndo to history.canRedo }
        assertEquals(false to false, can.value)
        model.type(0, "x")
        assertEquals(true to false, can.value)
        history.undo()
        assertEquals(false to true, can.value)
    }

    @OptIn(ExperimentalFoundationApi::class) // undoState
    @Test
    fun `an undo leaves the platform undo with nothing to undo`() {
        val (model, history) = setup(p("a", ""))
        model.type(0, "ab")
        history.undo()
        assertFalse(model.state.undoState.canUndo)
        model.type(0, "c")
        EditorCommands.setType(model, BlockType.H1)
        assertFalse(model.state.undoState.canUndo)
    }

    // --- Failure safety ------------------------------------------------------------------------

    @Test
    fun `an undo that fails midway keeps the stacks and leaves no placeholder`() {
        val (model, history) = setup(check("a", ""))
        model.commit(0, "\n")
        val text = model.text()
        val ids = model.ids
        history.beforeStep = { if (it == "delete") throw IllegalStateException("injected") }
        assertFailsWith<IllegalStateException> { history.undo() }
        assertEquals(text, model.text(), "placeholder taken out again")
        assertEquals(ids, model.ids)
        model.assertConsistent()
        assertTrue(history.canUndo)
        assertFalse(history.canRedo)

        history.beforeStep = null
        assertTrue(history.undo())
        assertEquals(listOf("a"), model.ids)
        assertTrue(history.canRedo)
    }

    @Test
    fun `a redo that fails keeps the entry on the redo stack`() {
        val (model, history) = setup(p("a", "x"), pic("r1"), p("b", "y"))
        model.deleteRange(3, 4)
        history.undo()
        history.beforeStep = { if (it == "delete") throw IllegalStateException("injected") }
        assertFailsWith<IllegalStateException> { history.redo() }
        assertTrue(history.canRedo)
        assertFalse(history.canUndo)
        model.assertConsistent()

        history.beforeStep = null
        assertTrue(history.redo())
        assertEquals(listOf("a", "b"), model.ids)
        assertFalse(history.canRedo)
    }
}
