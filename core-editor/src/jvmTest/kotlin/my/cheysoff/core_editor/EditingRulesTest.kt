package my.cheysoff.core_editor

import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.delete
import androidx.compose.foundation.text.input.insert
import androidx.compose.ui.text.TextRange
import my.cheysoff.core_blocks.Block
import my.cheysoff.core_blocks.BlockDocument
import my.cheysoff.core_blocks.BlockType
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [EditingRules] driven the way the field drives it: a buffer mutation like the one an IME commit
 * or `deleteSurroundingText` makes (a `replace`/`delete` of a range, caret where the IME leaves
 * it), then the transformation on that same buffer. No key events anywhere: Gboard sends none.
 */
class EditingRulesTest {

    @BeforeTest
    fun setUp() = EditorFlags.enable()

    private fun doc(vararg blocks: Block) =
        BlockDocument(blocks.mapIndexed { i, b -> b.copy(pos = "a" + ('a' + i)) })

    private fun p(id: String, text: String) = Block(id, BlockType.PARAGRAPH, text)
    private fun check(id: String, text: String, done: Boolean = false) = Block(id, BlockType.CHECK, text, done = done)
    private fun pic(record: String) = Block("pic:$record", BlockType.PICTURE, ref = record)

    private fun EditorModel.text() = state.text.toString()
    private fun EditorModel.caret() = state.selection

    /** One user edit: [edit] mutates the buffer like an IME would, then the rules run on it. */
    private fun EditorModel.user(edit: TextFieldBuffer.() -> Unit) {
        val rules = EditingRules(this)
        state.edit {
            edit()
            with(rules) { transformInput() }
        }
    }

    /** An IME commit of [text] at [at], caret after it. */
    private fun EditorModel.commit(at: Int, text: String) = user {
        insert(at, text)
        selection = TextRange(at + text.length)
    }

    /** `deleteSurroundingText` of [start, end), caret at [start]. */
    private fun EditorModel.deleteRange(start: Int, end: Int) = user {
        delete(start, end)
        selection = TextRange(start)
    }

    private fun EditorModel.assertConsistent() {
        assertEquals(text().split('\n').size, ids.size, "one id per line in ${text()}")
        assertEquals(ids.size, ids.toSet().size, "ids are unique: $ids")
        blocks() // throws if out of sync
    }

    // --- The brief's six rules ---------------------------------------------------------------

    @Test
    fun `Enter continues a checklist`() {
        val model = EditorModel(doc(check("c", "todo", done = true)))
        model.commit(5, "\n")
        assertEquals("\uE001todo\n\uE000", model.text(), "the new item is unticked")
        assertEquals(TextRange(7), model.caret(), "caret after the new item's marker")
        assertEquals("c", model.ids[0])
        val b = model.blocks()
        assertEquals(BlockType.CHECK, b[1].type)
        assertFalse(b[1].done)
        model.assertConsistent()
    }

    @Test
    fun `Enter continues bullets and numbers too, and a mid-item split makes two items`() {
        val model = EditorModel(doc(Block("b", BlockType.BULLET, "ab"), Block("n", BlockType.NUMBER, "one")))
        model.commit(2, "\n") // between a and b
        assertEquals("\uE002a\n\uE002b\n\uE003one", model.text())
        assertEquals(TextRange(4), model.caret())
        assertEquals("b", model.ids[0])
        model.commit(model.text().length, "\n")
        assertEquals("\uE002a\n\uE002b\n\uE003one\n\uE003", model.text())
        model.assertConsistent()
    }

    @Test
    fun `Enter on an empty item exits the list`() {
        val model = EditorModel(doc(p("a", "one"), check("c", "")))
        model.commit(5, "\n")
        assertEquals("one\n", model.text())
        assertEquals(listOf("a", "c"), model.ids)
        assertEquals(BlockType.PARAGRAPH, model.blocks()[1].type)
        assertEquals(TextRange(4), model.caret())
    }

    @Test
    fun `Enter on an empty item exits even when the IME sends it as a replace`() {
        val model = EditorModel(doc(check("c", "")))
        // Some IMEs re-commit the composing region together with the line break.
        model.user {
            replace(0, 1, "\uE000\n")
            selection = TextRange(2)
        }
        assertEquals("", model.text())
        assertEquals(listOf("c"), model.ids)
    }

    @Test
    fun `IME backspace at item start keeps the text and drops the marker`() {
        val model = EditorModel(doc(p("a", "one"), check("c", "todo")))
        model.deleteRange(4, 5) // the marker: caret was right after it
        assertEquals("one\ntodo", model.text())
        assertEquals(listOf("a", "c"), model.ids)
        val b = model.blocks()[1]
        assertEquals(BlockType.PARAGRAPH, b.type)
        assertEquals("todo", b.text)
    }

    @Test
    fun `joining an item onto the line above leaves no stray marker mid-line`() {
        val model = EditorModel(doc(p("a", "one"), check("c", "todo")))
        model.deleteRange(3, 4) // the line break: caret was before the marker
        assertEquals("onetodo", model.text())
        assertEquals(listOf("a"), model.ids)
        assertEquals(TextRange(3), model.caret())
    }

    @Test
    fun `backspace below a picture removes the picture`() {
        val model = EditorModel(doc(p("a", "one"), pic("r1"), p("b", "two")))
        model.deleteRange(5, 6) // the break before "two"
        assertEquals("one\ntwo", model.text())
        assertEquals(listOf("a", "b"), model.ids)
        assertTrue(model.refs.isEmpty())
        assertEquals(TextRange(4), model.caret(), "caret stays at the start of the text")
    }

    @Test
    fun `forward delete above a picture removes the picture and keeps the text apart`() {
        val model = EditorModel(doc(p("a", "one"), pic("r1"), p("b", "two")))
        model.deleteRange(3, 4) // the break after "one"
        assertEquals("one\ntwo", model.text())
        assertEquals(listOf("a", "b"), model.ids)
        assertTrue(model.refs.isEmpty())
        assertEquals(TextRange(3), model.caret())
    }

    @Test
    fun `Enter after a picture then Backspace keeps the picture`() {
        val model = EditorModel(doc(p("a", "one"), pic("r1")))
        model.commit(5, "\n")
        assertEquals("one\n${Markers.PICTURE}\n", model.text())
        model.deleteRange(5, 6) // Backspace at the start of the new empty line
        assertEquals("one\n${Markers.PICTURE}", model.text())
        assertEquals(listOf("a", "pic:r1"), model.ids)
        assertEquals(mapOf("pic:r1" to "r1"), model.refs)
        assertEquals(TextRange(5), model.caret())
    }

    @Test
    fun `Delete on an empty line above a picture keeps the picture`() {
        val model = EditorModel(doc(p("a", "one"), p("e", ""), pic("r1"), p("b", "two")))
        model.deleteRange(4, 5) // Delete at the end of the empty line
        assertEquals("one\n${Markers.PICTURE}\ntwo", model.text())
        assertEquals(listOf("a", "pic:r1", "b"), model.ids)
        assertEquals(mapOf("pic:r1" to "r1"), model.refs)
        model.assertConsistent()
    }

    @Test
    fun `Backspace on an empty line below a picture keeps the picture when text follows`() {
        val model = EditorModel(doc(pic("r1"), p("e", ""), p("b", "two")))
        model.deleteRange(1, 2)
        assertEquals("${Markers.PICTURE}\ntwo", model.text())
        assertEquals(listOf("pic:r1", "b"), model.ids)
        assertEquals(mapOf("pic:r1" to "r1"), model.refs)
    }

    @Test
    fun `typing over a selected picture replaces it and keeps every letter`() {
        val model = EditorModel(doc(p("a", "one"), pic("r1"), p("b", "two")))
        model.user {
            replace(4, 5, "x") // the picture was selected; an IME commit replaces it
            selection = TextRange(5)
        }
        assertEquals("one\nx\ntwo", model.text())
        assertTrue(model.refs.isEmpty(), "the user selected the picture, so it may go")
        assertEquals("a", model.ids[0])
        assertEquals("b", model.ids[2])
        assertEquals(listOf("one", "x", "two"), model.blocks().map { it.text })
        model.assertConsistent()
    }

    @Test
    fun `typing over a selection spanning a picture keeps the surrounding text`() {
        val model = EditorModel(doc(p("a", "one"), pic("r1"), p("b", "two")))
        model.user {
            replace(2, 7, "Z") // "e\n<picture>\nt"
            selection = TextRange(3)
        }
        assertEquals("onZwo", model.text())
        assertEquals(listOf("a"), model.ids)
        assertTrue(model.refs.isEmpty())
        assertEquals(TextRange(3), model.caret())
    }

    @Test
    fun `typing on a picture line goes to the next line`() {
        val model = EditorModel(doc(p("a", "one"), pic("r1")))
        model.commit(5, "x") // after the marker
        assertEquals("one\n\uE004\nx", model.text())
        assertEquals("pic:r1", model.ids[1])
        assertEquals(mapOf("pic:r1" to "r1"), model.refs)
        assertEquals(TextRange(7), model.caret())
        model.assertConsistent()
    }

    @Test
    fun `typing before a picture marker also goes to the line after it`() {
        val model = EditorModel(doc(p("a", "one"), pic("r1")))
        model.commit(4, "hey") // caret before the marker, an IME commit of a word
        assertEquals("one\n\uE004\nhey", model.text())
        assertEquals(listOf("a", "pic:r1"), model.ids.take(2))
        assertEquals(mapOf("pic:r1" to "r1"), model.refs)
        assertEquals(TextRange(9), model.caret(), "caret at the end of the moved word")
        model.assertConsistent()
    }

    @Test
    fun `text merged onto a picture line by a wider delete is split off, not lost`() {
        val model = EditorModel(doc(p("a", "one"), p("b", "two"), pic("r1")))
        model.deleteRange(6, 8) // "o\n": "tw" now runs into the marker
        assertEquals("one\ntw\n\uE004", model.text())
        assertEquals(listOf("a", "b", "pic:r1"), model.ids)
        assertEquals(mapOf("pic:r1" to "r1"), model.refs)
    }

    @Test
    fun `pasted private-use characters are stripped`() {
        val model = EditorModel(doc(p("a", "one")))
        model.commit(3, "a\uE000b\uE004\nc\uF8FF")
        assertEquals("oneab\nc", model.text())
        assertTrue(model.text().none(Markers::isPrivateUse))
        assertTrue(model.refs.isEmpty())
        assertEquals(TextRange(7), model.caret())
        model.assertConsistent()
    }

    @Test
    fun `a lone private-use commit leaves nothing behind`() {
        val model = EditorModel(doc(p("a", "one")))
        model.commit(0, "\uE000")
        assertEquals("one", model.text())
        assertEquals(BlockType.PARAGRAPH, model.blocks()[0].type)
    }

    @Test
    fun `an IME re-commit that includes an existing marker keeps it`() {
        val model = EditorModel(doc(check("c", "todo")))
        model.user {
            replace(0, 5, "\uE000todos")
            selection = TextRange(6)
        }
        assertEquals("\uE000todos", model.text())
        assertEquals(BlockType.CHECK, model.blocks()[0].type)
        assertEquals(listOf("c"), model.ids)
    }

    // --- Controller rulings ------------------------------------------------------------------

    @Test
    fun `Enter right after the marker of an item with text inserts an empty item above`() {
        val model = EditorModel(doc(check("c", "todo", done = true)))
        model.commit(1, "\n")
        assertEquals("\uE000\n\uE001todo", model.text())
        assertEquals("c", model.ids[1], "the text keeps its id")
        val b = model.blocks()
        assertEquals(BlockType.CHECK, b[0].type)
        assertEquals("", b[0].text)
        assertEquals(BlockType.CHECK, b[1].type)
        assertTrue(b[1].done, "the item keeps its tick")
        assertEquals(TextRange(3), model.caret(), "caret stays with the text")
        model.assertConsistent()
    }

    @Test
    fun `Enter in a heading keeps the heading on its text, and Enter at its end starts a paragraph`() {
        val model = EditorModel(doc(Block("h", BlockType.H1, "Title")))
        model.commit(1, "\n") // right after the marker
        assertEquals("\uE011\n\uE011Title", model.text())
        assertEquals("h", model.ids[1])
        assertEquals(TextRange(3), model.caret())
        model.commit(5, "\n") // mid-heading: "Ti|tle"
        assertEquals("\uE011\n\uE011Ti\n\uE011tle", model.text())
        model.commit(model.text().length, "\n") // at the end
        assertEquals("\uE011\n\uE011Ti\n\uE011tle\n", model.text())
        assertEquals(BlockType.PARAGRAPH, model.blocks().last().type)
        model.assertConsistent()
    }

    @Test
    fun `random user edits never lose text and keep ids consistent`() {
        val rnd = Random(10)
        val pieces = listOf("a", "bc", "\n", "x\ny", "\uE000", "\uE004", "z\uE001")
        repeat(300) {
            val model = EditorModel(
                doc(p("a", "one"), check("c", "todo"), pic("r1"), Block("b", BlockType.BULLET, ""), p("d", "two")),
            )
            repeat(6) {
                val t = model.text()
                val s = rnd.nextInt(t.length + 1)
                val e = (s + rnd.nextInt(3)).coerceAtMost(t.length)
                val ins = if (rnd.nextBoolean()) pieces.random(rnd) else ""
                val before = t.removeRange(s, e) + ins
                model.user {
                    replace(s, e, ins)
                    selection = TextRange(s + ins.length)
                }
                // Typed text and text that survived the edit are all still there, possibly moved
                // past a picture. Only line breaks and markers may be added or removed by a rule.
                fun letters(x: String) = x.filter { it != '\n' && !Markers.isPrivateUse(it) }.toList().sorted()
                assertEquals(letters(before), letters(model.text()), "from '$t' replace($s,$e,'$ins')")
                model.assertConsistent()
                assertTrue(model.text().count { it == Markers.PICTURE } <= 1)
                // The picture goes only when its own marker is deleted, or by rule 3: the edit
                // comes down to deleting the line break beside it. "Comes down to" either with
                // inserted private-use chars stripped, or with a re-inserted marker read as the
                // one already there (the rules trim a re-commit before stripping).
                val m = t.indexOf(Markers.PICTURE)
                if (m >= 0 && m !in s until e) {
                    // Only when the joined line has text (fix round 1: an empty one just goes).
                    fun breakBeside(net: String) =
                        (m > 1 && t[m - 1] == '\n' && t[m - 2] != '\n' && net == t.removeRange(m - 1, m)) ||
                            (m + 2 < t.length && t[m + 1] == '\n' && t[m + 2] != '\n' && net == t.removeRange(m + 1, m + 2))
                    val rule3 = breakBeside(t.substring(0, s) + ins.filter { !Markers.isPrivateUse(it) } + t.substring(e)) ||
                        breakBeside(t.substring(0, s) + ins + t.substring(e))
                    if (!rule3) assertTrue("pic:r1" in model.refs, "picture lost by '$t' replace($s,$e,'$ins') -> '${model.text()}'")
                }
                assertEquals(model.text().contains(Markers.PICTURE), "pic:r1" in model.refs, "marker shown iff picture known")
            }
        }
    }
}
