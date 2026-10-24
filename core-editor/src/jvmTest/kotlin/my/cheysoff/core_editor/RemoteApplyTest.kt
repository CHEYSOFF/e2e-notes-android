package my.cheysoff.core_editor

import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.insert
import androidx.compose.ui.text.TextRange
import my.cheysoff.core_blocks.Block
import my.cheysoff.core_blocks.BlockDocument
import my.cheysoff.core_blocks.BlockType
import my.cheysoff.core_blocks.InlineStyle
import my.cheysoff.core_blocks.StyleSpan
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [RemoteApply] driven as the editor drives it: the note is opened with [RemoteApply.noteLoaded],
 * the user types through [EditingRules] (recorded in a [BlockHistory]), storage emissions arrive
 * through [RemoteApply.offer], and [RemoteApply.tick] runs with the time of the last local edit.
 * Time is a fake clock.
 */
class RemoteApplyTest {

    @BeforeTest
    fun setUp() = EditorFlags.enable()

    private var clock = 10_000L

    private fun p(id: String, text: String, vararg spans: StyleSpan) = Block(id, BlockType.PARAGRAPH, text, spans.toList())
    private fun check(id: String, text: String, done: Boolean = false) = Block(id, BlockType.CHECK, text, done = done)
    private fun pic(record: String) = Block("pic:$record", BlockType.PICTURE, ref = record)

    /** A document in the given order, with positions that sort that way. */
    private fun doc(vararg blocks: Block) = BlockDocument(blocks.mapIndexed { i, b -> b.copy(pos = "a" + ('a' + i)) })

    private class Editor(val model: EditorModel, val history: BlockHistory, val remote: RemoteApply) {
        var steps = 0
        var lastLocalEditAt = Long.MIN_VALUE
    }

    private fun open(vararg blocks: Block): Editor {
        val d = doc(*blocks)
        val model = EditorModel(d)
        val history = BlockHistory(model) { clock }
        val remote = RemoteApply(model, { clock })
        remote.noteLoaded(d)
        return Editor(model, history, remote).also { e -> remote.beforeStep = { e.steps++ } }
    }

    private fun Editor.text() = model.state.text.toString()
    private fun Editor.lineStart(id: String) = lineRanges(model.state.text)[model.ids.indexOf(id)].start
    private fun Editor.lineEnd(id: String) = lineRanges(model.state.text)[model.ids.indexOf(id)].end
    private fun Editor.select(start: Int, end: Int = start) = model.state.edit { selection = TextRange(start, end) }
    private fun Editor.block(id: String) = model.blocks().first { it.id == id }

    /** The user typing [text] at [at], as the field would: buffer change, then the rules. */
    private fun Editor.type(at: Int, text: String) {
        val rules = EditingRules(model)
        model.state.edit {
            insert(at, text)
            selection = TextRange(at + text.length)
            with(rules) { transformInput() }
        }
        lastLocalEditAt = clock
    }

    private fun Editor.receive(d: BlockDocument) {
        remote.offer(d)
        remote.tick(lastLocalEditAt)
    }

    private fun Editor.assertConsistent() {
        assertEquals(text().split('\n').size, model.ids.size, "one id per line in ${text()}")
        assertEquals(model.ids.size, model.ids.toSet().size, "ids are unique: ${model.ids}")
        model.blocks()
    }

    // --- The brief's five --------------------------------------------------------------------------

    @Test
    fun `own save echo makes no edits`() {
        val e = open(check("c", "milk"), p("p", "hello"))
        e.type(e.lineEnd("p"), " world")
        val textBefore = e.model.state.text
        val selectionBefore = e.model.state.selection
        // What the save writes (and storage emits back): the editor's own blocks.
        val echo = BlockDocument(e.model.blocks().mapIndexed { i, b -> b.copy(pos = "b$i") })

        e.receive(echo)
        clock += 5_000
        e.remote.tick(e.lastLocalEditAt)

        assertEquals(0, e.steps, "no edit for our own save")
        assertSame(textBefore, e.model.state.text, "the field's value was not even rewritten")
        assertEquals(selectionBefore, e.model.state.selection)
        assertSame(echo, e.remote.base, "the echo is fully applied, so it is the base")
        assertFalse(e.remote.hasPending)
    }

    @Test
    fun `remote tick in another block applies immediately and keeps the cursor`() {
        val e = open(check("c", "milk"), p("p", "hello"))
        e.type(e.lineEnd("p"), " wor")
        val caret = e.model.state.selection

        e.receive(doc(check("c", "milk", done = true), p("p", "hello")))

        assertTrue(e.block("c").done, "the tick shows at once")
        assertEquals("hello wor", e.block("p").text, "the half-typed word is untouched")
        assertEquals(caret, e.model.state.selection, "the cursor did not move")
        assertFalse(e.remote.hasPending)
        e.assertConsistent()
    }

    @Test
    fun `remote change to the paragraph being typed waits for the pause`() {
        val e = open(check("c", "milk"), p("p", "hello"))
        e.type(e.lineEnd("p"), " wor")
        val caret = e.model.state.selection
        val loaded = e.remote.base
        val incoming = doc(check("c", "milk"), p("p", "HELLO"))

        e.receive(incoming)
        assertEquals("hello wor", e.block("p").text, "not while typing")
        assertEquals(caret, e.model.state.selection)
        assertTrue(e.remote.hasPending)
        assertSame(loaded, e.remote.base, "base stays at the last fully applied doc")

        clock += RemoteApply.QUIET_MS - 1
        e.remote.tick(e.lastLocalEditAt)
        assertEquals("hello wor", e.block("p").text, "still inside the pause")

        clock += 1
        e.remote.tick(e.lastLocalEditAt)
        assertEquals("HELLO", e.block("p").text, "applied after the pause")
        assertFalse(e.remote.hasPending)
        assertSame(incoming, e.remote.base)
        e.assertConsistent()
    }

    @Test
    fun `remote insert above the cursor shifts the cursor by the inserted length`() {
        val e = open(p("a", "first"), p("p", "hello"))
        e.type(e.lineEnd("p"), "!")
        val caret = e.model.state.selection

        e.receive(doc(p("a", "first"), p("new", "inserted"), p("p", "hello")))

        assertEquals(listOf("a", "new", "p"), e.model.ids)
        assertEquals("inserted", e.block("new").text)
        val shift = "inserted\n".length
        assertEquals(TextRange(caret.start + shift, caret.end + shift), e.model.state.selection)
        assertEquals("hello!", e.block("p").text)
        e.assertConsistent()
    }

    @Test
    fun `remote delete of a picture removes its line`() {
        val e = open(p("a", "above"), pic("r1"), p("p", "below"))
        e.select(e.lineEnd("p"))
        val caret = e.model.state.selection
        val pictureLine = e.lineEnd("pic:r1") - e.lineStart("pic:r1") + 1

        e.receive(doc(p("a", "above"), p("p", "below")))

        assertEquals(listOf("a", "p"), e.model.ids)
        assertEquals("above\nbelow", e.text())
        assertEquals(TextRange(caret.start - pictureLine), e.model.state.selection)
        assertTrue(e.model.refs.isEmpty())
        e.assertConsistent()
    }

    // --- Binding obligations -----------------------------------------------------------------------

    @Test
    fun `remote delete of the line being typed in waits for the pause`() {
        val e = open(p("a", "keep"), p("p", "typing"))
        e.type(e.lineEnd("p"), " here")
        val incoming = doc(p("a", "keep"))

        e.receive(incoming)
        assertEquals(listOf("a", "p"), e.model.ids, "the caret's line stays")
        assertEquals("typing here", e.block("p").text)

        clock += RemoteApply.QUIET_MS
        e.remote.tick(e.lastLocalEditAt)
        assertEquals(listOf("a"), e.model.ids)
        assertSame(incoming, e.remote.base)
        e.assertConsistent()
    }

    @Test
    fun `a later offer supersedes a deferred one`() {
        val e = open(p("a", "x"), p("p", "hello"))
        e.type(e.lineEnd("p"), "!")
        e.receive(doc(p("a", "x"), p("p", "first remote")))
        val latest = doc(p("a", "x2"), p("p", "second remote"))
        e.receive(latest)
        assertEquals("x2", e.block("a").text, "other block applied at once")
        assertEquals("hello!", e.block("p").text)

        clock += RemoteApply.QUIET_MS
        e.remote.tick(e.lastLocalEditAt)
        assertEquals("second remote", e.block("p").text, "the latest doc wins")
        assertSame(latest, e.remote.base)
    }

    @Test
    fun `a remote picture whose record is unknown is still inserted`() {
        val e = open(p("a", "text"))
        e.receive(doc(p("a", "text"), pic("not-synced-yet"), Block("drw:d1", BlockType.DRAWING, ref = "d1")))
        assertEquals(listOf("a", "pic:not-synced-yet", "drw:d1"), e.model.ids)
        assertEquals(mapOf("pic:not-synced-yet" to "not-synced-yet", "drw:d1" to "d1"), e.model.refs)
        assertEquals(BlockType.DRAWING, e.block("drw:d1").type)
        e.assertConsistent()
    }

    @Test
    fun `a picture the remote moved is moved, text order is left alone`() {
        val e = open(p("a", "one"), pic("r1"), p("b", "two"), p("c", "three"))
        e.select(e.lineEnd("a"))
        e.receive(
            BlockDocument(
                listOf(
                    p("a", "one").copy(pos = "aa"),
                    p("b", "two").copy(pos = "ab"),
                    p("c", "three").copy(pos = "ac"),
                    pic("r1").copy(pos = "ad"),
                ),
            ),
        )
        assertEquals(listOf("a", "b", "c", "pic:r1"), e.model.ids)
        assertEquals(mapOf("pic:r1" to "r1"), e.model.refs, "the move kept the picture's record")
        assertEquals(TextRange(3), e.model.state.selection)
        e.assertConsistent()
    }

    @Test
    fun `changed text and spans are rewritten in place, other lines untouched`() {
        val e = open(p("a", "plain"), p("b", "other"))
        e.select(e.lineEnd("b"))
        e.receive(doc(p("a", "plain bold", StyleSpan(6, 10, InlineStyle.BOLD)), p("b", "other")))
        assertEquals(listOf(StyleSpan(6, 10, InlineStyle.BOLD)), e.block("a").spans)
        assertEquals("plain bold\nother", e.text())
        assertEquals(TextRange(e.text().length), e.model.state.selection)
    }

    @Test
    fun `remote text goes through the loader's normalisation`() {
        val e = open(p("a", "x"))
        e.receive(doc(p("a", "x"), p("n", "line one\nline two")))
        assertEquals("x\nline one\nline two", e.text(), "split at the line break, private-use char stripped")
        assertEquals(3, e.model.ids.size)
        // The same doc again is now nothing to do.
        val before = e.steps
        e.receive(doc(p("a", "x"), p("n", "line one\nline two")))
        assertEquals(before, e.steps)
    }

    @Test
    fun `a line created locally and not saved yet is not deleted by a remote doc`() {
        val e = open(p("a", "one"), p("b", "two"))
        // A new local line, then the caret moves on to another line.
        e.model.stageText("local")
        e.model.edit { replace(length, length, "\nmine") }
        e.select(e.lineEnd("a"))
        e.receive(doc(p("a", "one"), p("b", "two remote")))
        assertEquals(listOf("a", "b", "local"), e.model.ids)
        assertEquals("two remote", e.block("b").text)
        assertEquals("mine", e.block("local").text)
    }

    @Test
    fun `a local unsaved edit to a block the remote did not change is kept`() {
        val e = open(p("a", "one"), p("b", "two"))
        e.type(e.lineEnd("a"), " local")
        e.select(e.lineEnd("b"))
        clock += RemoteApply.QUIET_MS // paused, but the save of "one local" has not come back yet
        // The stored body still has "one" for a; the other device changed only b.
        e.receive(doc(p("a", "one"), p("b", "two remote")))
        assertEquals("one local", e.block("a").text)
        assertEquals("two remote", e.block("b").text)
    }

    @Test
    fun `remote applies never become undo entries and undo still undoes the local edit`() {
        val e = open(check("c", "milk"), p("p", "hello"))
        e.type(e.lineEnd("p"), "!")
        assertTrue(e.history.canUndo)
        e.receive(doc(check("c", "milk", done = true), p("p", "hello")))
        assertTrue(e.block("c").done)

        assertTrue(e.history.undo())
        assertEquals("hello", e.block("p").text, "the local edit is undone")
        assertTrue(e.block("c").done, "the remote tick stays")
        assertFalse(e.history.canUndo, "the remote apply added no entry")
        assertTrue(e.history.redo())
        assertEquals("hello!", e.block("p").text)
    }

    @Test
    fun `noteLoaded opens the document and makes it the base`() {
        val e = open(p("a", "one"))
        val other = doc(p("x", "fresh"), check("y", "item"))
        e.remote.offer(doc(p("a", "stale")))
        e.remote.noteLoaded(other)
        assertEquals(listOf("x", "y"), e.model.ids)
        assertSame(other, e.remote.base)
        assertFalse(e.remote.hasPending, "a pending doc from the previous note is dropped")
    }

    @Test
    fun `a remote doc that replaces every block leaves exactly its blocks`() {
        val e = open(p("a", "one"), p("b", "two"))
        e.receive(doc(p("x", "new one"), pic("r"), p("y", "new two")))
        assertEquals(listOf("x", "pic:r", "y"), e.model.ids)
        assertEquals("new one\n\nnew two", e.text())
        e.assertConsistent()
    }

    @Test
    fun `remote delete of the first line keeps an empty next line's id`() {
        val e = open(p("a", "gone soon"), check("c", ""), p("b", "rest"))
        e.select(e.lineEnd("b"))
        e.receive(doc(check("c", ""), p("b", "rest")))
        assertEquals(listOf("c", "b"), e.model.ids)
        assertEquals(BlockType.CHECK, e.block("c").type)
        e.assertConsistent()
    }

    @Test
    fun `remote insert after the line holding the caret at its end does not take the caret`() {
        val e = open(p("a", "one"), p("b", "two"))
        e.type(e.lineEnd("a"), "!")
        val caret = e.model.state.selection
        e.receive(doc(p("a", "one"), p("n", "new"), p("b", "two")))
        assertEquals(listOf("a", "n", "b"), e.model.ids)
        assertEquals(caret, e.model.state.selection)

        // And at the very end of the document.
        val e2 = open(p("a", "one"))
        e2.type(e2.lineEnd("a"), "!")
        val caret2 = e2.model.state.selection
        e2.receive(doc(p("a", "one"), p("n", "new")))
        assertEquals("one!\nnew", e2.text())
        assertEquals(caret2, e2.model.state.selection)
    }

    // --- IME composition ---------------------------------------------------------------------------

    @Test
    fun `any programmatic edit ends an active composition, even in another line`() {
        // Characterisation of Compose 1.12, the reason for the composition guard in RemoteApply.
        val state = TextFieldState("hello\nwor")
        composeAsIme(state, 6, 9)
        assertEquals(TextRange(6, 9), state.composition)
        state.edit { replace(0, 1, "H") }
        assertNull(state.composition)
        assertEquals("Hello\nwor", state.text.toString(), "the composing text itself stays")
    }

    @Test
    fun `while the IME is composing, remote changes wait for the pause`() {
        val e = open(check("c", "milk"), p("p", "hello"))
        e.type(e.lineEnd("p"), " wor")
        composeAsIme(e.model.state, e.lineEnd("p") - 3, e.lineEnd("p"))
        val composition = e.model.state.composition
        assertNotNull(composition)

        e.receive(doc(check("c", "milk", done = true), p("p", "hello")))
        assertFalse(e.block("c").done, "deferred: applying would end the composition")
        assertEquals(composition, e.model.state.composition)
        assertEquals(0, e.steps)

        clock += RemoteApply.QUIET_MS
        e.remote.tick(e.lastLocalEditAt)
        assertTrue(e.block("c").done)
    }

    /**
     * Marks [start, end) as the IME's composing region, the way the platform input session does.
     * Compose has no public API for this (only the IME sets a composition), so this reaches the
     * internal `editAsUser`/`setComposition` by reflection. Test-only.
     */
    private fun composeAsIme(state: TextFieldState, start: Int, end: Int) {
        val behavior = Class.forName("androidx.compose.foundation.text.input.internal.undo.TextFieldEditUndoBehavior")
            .enumConstants.first()
        val editAsUser = TextFieldState::class.java.methods.first { it.name == "editAsUser\$foundation" }
        val setComposition = TextFieldBuffer::class.java.methods
            .first { it.name == "setComposition\$foundation" && it.parameterCount == 3 }
        editAsUser.invoke(state, null, false, behavior, { b: TextFieldBuffer -> setComposition.invoke(b, start, end, null) })
    }
}
