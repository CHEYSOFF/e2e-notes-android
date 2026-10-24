package my.cheysoff.core_editor

import androidx.compose.foundation.text.input.delete
import androidx.compose.foundation.text.input.insert
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import my.cheysoff.core_blocks.Block
import my.cheysoff.core_blocks.BlockDocument
import my.cheysoff.core_blocks.BlockType
import my.cheysoff.core_blocks.InlineStyle
import my.cheysoff.core_blocks.StyleSpan
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EditorModelTest {

    @BeforeTest
    fun setUp() = EditorFlags.enable()

    private fun doc(vararg blocks: Block) =
        BlockDocument(blocks.mapIndexed { i, b -> b.copy(pos = "a" + ('a' + i)) })

    private fun p(id: String, text: String, vararg spans: StyleSpan) =
        Block(id, BlockType.PARAGRAPH, text, spans.toList())

    private fun pic(record: String) = Block("pic:$record", BlockType.PICTURE, ref = record)

    private fun EditorModel.text() = state.text.toString()

    /** Every invariant later tasks rely on: one id per line, no id twice. */
    private fun EditorModel.assertConsistent() {
        assertEquals(text().split('\n').size, ids.size, "one id per line")
        assertEquals(ids.size, ids.toSet().size, "ids are unique: $ids")
    }

    // --- The brief's six ---------------------------------------------------------------------

    @Test
    fun `round trip doc to text and back is identity on visible content`() {
        val original = doc(
            Block("h", BlockType.H1, "Title"),
            p(
                "p", "plain bold under-strike",
                StyleSpan(0, 5, InlineStyle.ITALIC),
                StyleSpan(6, 10, InlineStyle.BOLD),
                StyleSpan(11, 23, InlineStyle.UNDERLINE),
                StyleSpan(17, 23, InlineStyle.STRIKE),
            ),
            Block("b", BlockType.BULLET, "bullet"),
            Block("n", BlockType.NUMBER, "number"),
            Block("c1", BlockType.CHECK, "todo", done = false),
            Block("c2", BlockType.CHECK, "done", done = true),
            pic("r1"),
            Block("drw:r2", BlockType.DRAWING, ref = "r2"),
            Block("h3", BlockType.H3, ""),
        )
        val model = EditorModel(original)

        assertEquals(
            "Title\nplain bold under-strike\nbullet\nnumber\ntodo\ndone\n" +
                "\n\n",
            model.text(),
        )
        assertEquals(original.visible().map { it.id }, model.ids)
        assertEquals(mapOf("pic:r1" to "r1", "drw:r2" to "r2"), model.refs)
        val back = BlockDocument(model.blocks())
        assertTrue(original.sameVisibleContent(back), "got ${model.blocks()}")
        // Unchanged blocks come back as the loaded values, pos and clocks included.
        assertEquals(original.visible(), model.blocks())
    }

    @Test
    fun `typing inside a paragraph keeps its id`() {
        val model = EditorModel(doc(p("a", "hello"), p("b", "world")))
        model.edit { insert(2, "XY") }
        assertEquals(listOf("a", "b"), model.ids)
        assertEquals("heXYllo", model.blocks()[0].text)
        model.edit { delete(8, 10) } // "wo" of the second line
        assertEquals(listOf("a", "b"), model.ids)
        assertEquals("rld", model.blocks()[1].text)
    }

    @Test
    fun `Enter in the middle keeps the id on the first half and mints one for the second`() {
        val model = EditorModel(doc(p("a", "hello"), p("b", "world")))
        model.edit { insert(2, "\n") }
        assertEquals(3, model.ids.size)
        assertEquals("a", model.ids[0])
        assertEquals("b", model.ids[2])
        assertFalse(model.ids[1] in setOf("a", "b"))
        assertEquals(listOf("he", "llo", "world"), model.blocks().map { it.text })
        model.assertConsistent()
    }

    @Test
    fun `joining two paragraphs keeps the first id`() {
        val model = EditorModel(doc(p("a", "hello"), p("b", "world"), p("c", "!")))
        model.edit { delete(5, 6) }
        assertEquals(listOf("a", "c"), model.ids)
        assertEquals("helloworld", model.blocks()[0].text)
    }

    @Test
    fun `a picture keeps its id when cut and pasted elsewhere`() {
        val model = EditorModel(doc(p("a", "one"), pic("r1"), p("b", "two")))
        assertEquals("one\n\ntwo", model.text())
        // Cut: the picture line and the break before it.
        model.edit { delete(3, 5) }
        assertEquals(listOf("a", "b"), model.ids)
        // Paste at the end, as a separate edit.
        model.edit { insert(length, "\n") }
        assertEquals(listOf("a", "b", "pic:r1"), model.ids)
        val last = model.blocks().last()
        assertEquals(BlockType.PICTURE, last.type)
        assertEquals("r1", last.ref)
        assertEquals(mapOf("pic:r1" to "r1"), model.refs)
    }

    @Test
    fun `bold over an emoji keeps the pair intact`() {
        val text = "a😀b" // a😀b
        val model = EditorModel(doc(p("a", text, StyleSpan(1, 3, InlineStyle.BOLD))))
        assertEquals(listOf(StyleSpan(1, 3, InlineStyle.BOLD)), model.blocks()[0].spans)

        // A span that would split the pair is widened to cover it, on load...
        val split = EditorModel(doc(p("a", text, StyleSpan(2, 4, InlineStyle.BOLD))))
        assertEquals(listOf(StyleSpan(1, 4, InlineStyle.BOLD)), split.blocks()[0].spans)

        // ...and when read back from a style placed inside the pair by an edit.
        val styled = EditorModel(doc(p("a", text)))
        styled.edit { addStyle(SpanStyle(fontWeight = FontWeight.Bold), 0, 2) }
        assertEquals(listOf(StyleSpan(0, 3, InlineStyle.BOLD)), styled.blocks()[0].spans)

        // Typing in front of the emoji moves the span with it.
        model.edit { insert(0, "xx") }
        assertEquals(listOf(StyleSpan(3, 5, InlineStyle.BOLD)), model.blocks()[0].spans)
    }

    // --- Obligations from earlier reviews ----------------------------------------------------

    @Test
    fun `spans are clamped to the text and empty ones dropped on load`() {
        val model = EditorModel(
            doc(
                p(
                    "a", "hello",
                    StyleSpan(-5, 100, InlineStyle.BOLD),
                    StyleSpan(2, 2, InlineStyle.ITALIC),
                    StyleSpan(4, 1, InlineStyle.STRIKE),
                    StyleSpan(7, 9, InlineStyle.UNDERLINE),
                ),
            ),
        )
        assertEquals(listOf(StyleSpan(0, 5, InlineStyle.BOLD)), model.blocks()[0].spans)
    }

    @Test
    fun `private-use characters in block text are stripped on load`() {
        val model = EditorModel(
            doc(p("a", "xyz", StyleSpan(2, 5, InlineStyle.BOLD)), p("b", "next")),
        )
        assertEquals("xyz\nnext", model.text())
        assertEquals(listOf("a", "b"), model.ids)
        assertTrue(model.refs.isEmpty())
        val first = model.blocks()[0]
        assertEquals(BlockType.PARAGRAPH, first.type)
        assertEquals("xyz", first.text)
        assertEquals(listOf(StyleSpan(1, 3, InlineStyle.BOLD)), first.spans)
    }

    @Test
    fun `a newline inside block text splits it into lines and the first keeps the id`() {
        val model = EditorModel(
            doc(
                Block("c", BlockType.CHECK, "ab\ncd\n", listOf(StyleSpan(1, 4, InlineStyle.BOLD)), done = true),
                p("z", "end"),
            ),
        )
        assertEquals("ab\ncd\n\nend", model.text())
        assertEquals("c", model.ids[0])
        assertEquals("z", model.ids[3])
        model.assertConsistent()
        val blocks = model.blocks()
        assertEquals(
            listOf(BlockType.CHECK, BlockType.PARAGRAPH, BlockType.PARAGRAPH, BlockType.PARAGRAPH),
            blocks.map { it.type },
        )
        assertTrue(blocks[0].done)
        assertEquals(listOf("ab", "cd", "", "end"), blocks.map { it.text })
        assertEquals(listOf(StyleSpan(1, 2, InlineStyle.BOLD)), blocks[0].spans)
        assertEquals(listOf(StyleSpan(0, 1, InlineStyle.BOLD)), blocks[1].spans)
        assertEquals(emptyList(), blocks[2].spans)
    }

    @Test
    fun `an empty document loads as one empty paragraph`() {
        val model = EditorModel(BlockDocument(emptyList()))
        assertEquals("", model.text())
        assertEquals(1, model.ids.size)
        val blocks = model.blocks()
        assertEquals(1, blocks.size)
        assertEquals(model.ids[0], blocks[0].id)
        assertEquals(BlockType.PARAGRAPH, blocks[0].type)
        assertEquals("", blocks[0].text)
    }

    @Test
    fun `duplicate ids in the document never reach the editor twice`() {
        val model = EditorModel(
            BlockDocument(
                listOf(
                    p("a", "one").copy(pos = "a"),
                    p("a", "two").copy(pos = "b"),
                    pic("r1").copy(pos = "c"),
                    pic("r1").copy(pos = "d"),
                ),
            ),
        )
        model.assertConsistent()
        assertEquals("one\ntwo\n", model.text(), "a picture shown twice is one record, kept once")
        assertEquals("a", model.ids[0])
        assertEquals("pic:r1", model.ids[2])
    }

    // --- Picture edges and programmatic inserts ----------------------------------------------

    @Test
    fun `Enter at the start of a picture line leaves the id on the picture`() {
        val model = EditorModel(doc(p("a", "one"), pic("r1")))
        model.edit { insert(4, "\n") }
        assertEquals("one\n\n", model.text())
        assertEquals("a", model.ids[0])
        assertEquals("pic:r1", model.ids[2])
        assertNotEquals("pic:r1", model.ids[1])
        model.assertConsistent()
    }

    @Test
    fun `a picture moved within one edit keeps its id`() {
        val model = EditorModel(doc(p("a", "one"), pic("r1"), p("b", "two"), p("c", "three")))
        // Remove "\n<pic>" after "one", put "<pic>\n" before "three".
        model.edit {
            delete(3, 5)
            insert(8, "\n")
        }
        assertEquals("one\ntwo\n\nthree", model.text())
        assertEquals(listOf("a", "b", "pic:r1", "c"), model.ids)
    }

    @Test
    fun `a staged picture gives a new marker line its id and record`() {
        val model = EditorModel(doc(p("a", "one")))
        model.stagePicture("pic:r9", BlockType.PICTURE, "r9")
        model.edit { insert(length, "\n") }
        assertEquals(listOf("a", "pic:r9"), model.ids)
        assertEquals("r9", model.blocks()[1].ref)
    }

    @Test
    fun `load replaces everything`() {
        val model = EditorModel(doc(p("a", "one", StyleSpan(0, 3, InlineStyle.BOLD)), pic("r1")))
        model.load(doc(p("x", "fresh")))
        assertEquals("fresh", model.text())
        assertEquals(listOf("x"), model.ids)
        assertTrue(model.refs.isEmpty())
        assertEquals(emptyList(), model.blocks()[0].spans)
    }

    @Test
    fun `random edits keep one unique id per line and pictures keep theirs`() {
        val rnd = Random(42)
        val model = EditorModel(
            doc(p("a", "alpha"), pic("r1"), Block("c", BlockType.CHECK, "beta"), pic("r2"), p("d", "gamma")),
        )
        repeat(400) {
            model.edit {
                val len = length
                val start = rnd.nextInt(len + 1)
                val end = (start + rnd.nextInt(4)).coerceAtMost(len)
                val insert = listOf("", "x", "\n", "yz", "\n\n").random(rnd)
                replace(start, end, insert)
            }
            model.assertConsistent()
            val text = model.text().split('\n')
            model.ids.forEachIndexed { i, id ->
                if (id.startsWith("pic:")) assertEquals("", text[i], "picture id on a non-picture line")
            }
        }
    }
}
