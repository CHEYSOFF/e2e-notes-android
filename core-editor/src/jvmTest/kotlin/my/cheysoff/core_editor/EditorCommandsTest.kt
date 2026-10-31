package my.cheysoff.core_editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextDecoration
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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EditorCommandsTest {

    @BeforeTest
    fun setUp() = EditorFlags.enable()

    private fun doc(vararg blocks: Block) =
        BlockDocument(blocks.mapIndexed { i, b -> b.copy(pos = "a" + ('a' + i)) })

    private fun p(id: String, text: String, vararg spans: StyleSpan) = Block(id, BlockType.PARAGRAPH, text, spans.toList())
    private fun pic(record: String) = Block("pic:$record", BlockType.PICTURE, ref = record)

    private fun EditorModel.text() = state.text.toString()
    private fun EditorModel.select(start: Int, end: Int = start) = state.edit { selection = TextRange(start, end) }

    private fun EditorModel.assertConsistent() {
        assertEquals(text().split('\n').size, ids.size, "one id per line in ${text()}")
        assertEquals(ids.size, ids.toSet().size, "ids are unique: $ids")
        blocks()
    }

    // --- toggle --------------------------------------------------------------------------------

    @Test
    fun `bold across three paragraphs and a checklist skips markers`() {
        val model = EditorModel(
            doc(p("a", "one"), p("b", "two"), Block("c", BlockType.CHECK, "three"), p("d", "four")),
        )
        model.select(0, model.text().length)
        EditorCommands.toggle(model, InlineStyle.BOLD)
        val bold = model.blocks().map { it.spans }
        assertEquals(
            listOf(
                listOf(StyleSpan(0, 3, InlineStyle.BOLD)),
                listOf(StyleSpan(0, 3, InlineStyle.BOLD)),
                listOf(StyleSpan(0, 5, InlineStyle.BOLD)),
                listOf(StyleSpan(0, 4, InlineStyle.BOLD)),
            ),
            bold,
        )
        val marker = model.text().indexOf(Markers.CHECK_OPEN)
        assertTrue(
            model.state.textStyles.getSpanStyles(TextRange(marker, marker + 1)).none { it.start <= marker && it.end > marker },
            "the checkbox marker carries no style",
        )
        assertEquals(listOf("a", "b", "c", "d"), model.ids)

        // Everything selected is bold, so pressing it again un-bolds.
        EditorCommands.toggle(model, InlineStyle.BOLD)
        assertTrue(model.blocks().all { it.spans.isEmpty() })
    }

    @Test
    fun `toggle on a partly styled selection styles all of it first`() {
        val model = EditorModel(doc(p("a", "hello world", StyleSpan(0, 5, InlineStyle.ITALIC))))
        model.select(3, 8)
        EditorCommands.toggle(model, InlineStyle.ITALIC)
        assertEquals(listOf(StyleSpan(0, 8, InlineStyle.ITALIC)), model.blocks()[0].spans)
        model.select(2, 4)
        EditorCommands.toggle(model, InlineStyle.ITALIC)
        assertEquals(
            listOf(StyleSpan(0, 2, InlineStyle.ITALIC), StyleSpan(4, 8, InlineStyle.ITALIC)),
            model.blocks()[0].spans,
        )
    }

    @Test
    fun `underline and strike overlap without hiding each other`() {
        val model = EditorModel(doc(p("a", "abcdef")))
        model.select(0, 4)
        EditorCommands.toggle(model, InlineStyle.UNDERLINE)
        model.select(2, 6)
        EditorCommands.toggle(model, InlineStyle.STRIKE)
        assertEquals(
            listOf(StyleSpan(0, 4, InlineStyle.UNDERLINE), StyleSpan(2, 6, InlineStyle.STRIKE)),
            model.blocks()[0].spans,
        )
        // Shown as segments, each with one combined decoration, never two decoration spans on a char.
        val deco = model.state.textStyles.getSpanStyles(TextRange(0, 6)).filter { it.item.textDecoration != null }
        for (i in 0 until 6) assertTrue(deco.count { it.start <= i && i < it.end } <= 1, "char $i: $deco")
        val middle = deco.single { it.start <= 2 && 2 < it.end }.item.textDecoration!!
        assertTrue(middle.contains(TextDecoration.Underline) && middle.contains(TextDecoration.LineThrough))
    }

    @Test
    fun `toggle with no selection changes nothing`() {
        val model = EditorModel(doc(p("a", "hello")))
        model.select(2)
        EditorCommands.toggle(model, InlineStyle.BOLD)
        assertTrue(model.blocks()[0].spans.isEmpty())
    }

    @Test
    fun `clearFormatting removes every inline style in the selection only`() {
        val model = EditorModel(
            doc(
                p("a", "abcdef", StyleSpan(0, 6, InlineStyle.BOLD), StyleSpan(0, 6, InlineStyle.UNDERLINE)),
                p("b", "gh", StyleSpan(0, 2, InlineStyle.ITALIC)),
            ),
        )
        model.select(2, 8) // "cdef\ng"
        EditorCommands.clearFormatting(model)
        assertEquals(
            listOf(
                listOf(StyleSpan(0, 2, InlineStyle.BOLD), StyleSpan(0, 2, InlineStyle.UNDERLINE)),
                listOf(StyleSpan(1, 2, InlineStyle.ITALIC)),
            ),
            model.blocks().map { it.spans },
        )
    }

    // --- setType / toggleCheck -------------------------------------------------------------

    @Test
    fun `setType checklist on two lines makes two items`() {
        val model = EditorModel(doc(p("a", "one"), p("b", "two"), p("c", "three")))
        model.select(1, 5) // "ne\nt"
        EditorCommands.setType(model, BlockType.CHECK)
        assertEquals("\uE000one\n\uE000two\nthree", model.text())
        assertEquals(listOf("a", "b", "c"), model.ids)
        assertEquals(listOf(BlockType.CHECK, BlockType.CHECK, BlockType.PARAGRAPH), model.blocks().map { it.type })
        assertEquals(TextRange(2, 7), model.state.selection, "the selection stays on the same text")

        // Picking the same type again returns to paragraphs.
        EditorCommands.setType(model, BlockType.CHECK)
        assertEquals("one\ntwo\nthree", model.text())
        assertEquals(listOf("a", "b", "c"), model.ids)
    }

    @Test
    fun `setType on an empty line puts the caret after the new marker`() {
        val model = EditorModel(doc(p("a", "one"), p("b", "")))
        model.select(4)
        EditorCommands.setType(model, BlockType.BULLET)
        assertEquals("one\n\uE002", model.text())
        assertEquals(listOf("a", "b"), model.ids)
        assertEquals(TextRange(5), model.state.selection)
    }

    @Test
    fun `setType switches between list types and leaves pictures alone`() {
        val model = EditorModel(doc(Block("c", BlockType.CHECK, "one", done = true), pic("r1"), p("b", "two")))
        model.select(0, model.text().length)
        EditorCommands.setType(model, BlockType.H2)
        assertEquals("\uE012one\n\uE004\n\uE012two", model.text())
        assertEquals(listOf("c", "pic:r1", "b"), model.ids)
        assertEquals(mapOf("pic:r1" to "r1"), model.refs)
        assertFailsWith<IllegalArgumentException> { EditorCommands.setType(model, BlockType.PICTURE) }
    }

    @Test
    fun `toggleCheck ticks and unticks one item`() {
        val model = EditorModel(doc(p("a", "one"), Block("c", BlockType.CHECK, "todo")))
        model.select(6)
        EditorCommands.toggleCheck(model, 1)
        assertTrue(model.blocks()[1].done)
        assertEquals(TextRange(6), model.state.selection)
        EditorCommands.toggleCheck(model, 1)
        assertFalse(model.blocks()[1].done)
        EditorCommands.toggleCheck(model, 0) // not an item: nothing happens
        assertEquals("one\n\uE000todo", model.text())
        assertEquals(listOf("a", "c"), model.ids)
    }

    // --- Pictures ------------------------------------------------------------------------------

    @Test
    fun `insertPicture mid-sentence splits the paragraph`() {
        val model = EditorModel(doc(p("a", "hello world")))
        model.select(5)
        EditorCommands.insertPicture(model, "pic:r1", BlockType.PICTURE)
        assertEquals("hello\n\uE004\n world", model.text())
        assertEquals("a", model.ids[0])
        assertEquals("pic:r1", model.ids[1])
        assertNotEquals("a", model.ids[2])
        assertEquals(mapOf("pic:r1" to "r1"), model.refs)
        assertEquals(listOf("hello", "", " world"), model.blocks().map { it.text })
        assertEquals(TextRange(8), model.state.selection, "caret at the start of the second half")
        model.assertConsistent()
    }

    @Test
    fun `insertPicture mid-item keeps both halves in the list`() {
        val model = EditorModel(doc(Block("c", BlockType.CHECK, "abcd", done = true)))
        model.select(3)
        EditorCommands.insertPicture(model, "pic:r1", BlockType.PICTURE)
        assertEquals("\uE001ab\n\uE004\n\uE000cd", model.text())
        assertEquals(listOf(BlockType.CHECK, BlockType.PICTURE, BlockType.CHECK), model.blocks().map { it.type })
        assertEquals(TextRange(7), model.state.selection, "caret at the start of the second half's text")
        model.assertConsistent()
    }

    @Test
    fun `insertPicture at line edges and in an empty line adds only the picture line`() {
        val model = EditorModel(doc(p("a", "one"), Block("c", BlockType.CHECK, "todo"), p("e", "")))
        model.select(3) // end of "one"
        EditorCommands.insertPicture(model, "pic:r1", BlockType.PICTURE)
        assertEquals("one\n\uE004\n\uE000todo\n", model.text())
        model.select(7) // right after the checkbox: the start of the item's text
        EditorCommands.insertPicture(model, "drw:d1", BlockType.DRAWING)
        assertEquals("one\n\uE004\n\uE005\n\uE000todo\n", model.text())
        model.select(model.text().length) // the empty last line becomes the picture
        EditorCommands.insertPicture(model, "pic:r2", BlockType.PICTURE)
        assertEquals("one\n\uE004\n\uE005\n\uE000todo\n\uE004", model.text())
        assertEquals(listOf("a", "pic:r1", "drw:d1", "c", "pic:r2"), model.ids)
        assertEquals(mapOf("pic:r1" to "r1", "drw:d1" to "d1", "pic:r2" to "r2"), model.refs)
        assertFailsWith<IllegalArgumentException> { EditorCommands.insertPicture(model, "pic:r3", BlockType.DRAWING) }
    }

    @Test
    fun `insertPicture replaces a selection`() {
        val model = EditorModel(doc(p("a", "hello"), p("b", "world")))
        model.select(2, 8) // "llo\nwo"
        EditorCommands.insertPicture(model, "pic:r1", BlockType.PICTURE)
        assertEquals("he\n\uE004\nrld", model.text())
        assertEquals("pic:r1", model.ids[1])
        model.assertConsistent()
    }

    @Test
    fun `insertPicture over a selection starting right after a picture keeps that picture`() {
        val p0 = Markers.PICTURE
        val model = EditorModel(doc(pic("r0"), p("x", "xy"), p("z", "z")))
        model.select(1, 3) // "\nx": removing it joins "y" onto the picture line
        EditorCommands.insertPicture(model, "pic:r1", BlockType.PICTURE)
        assertEquals("$p0\n$p0\ny\nz", model.text())
        assertEquals(listOf("pic:r0", "pic:r1", "x", "z"), model.ids)
        assertEquals(mapOf("pic:r0" to "r0", "pic:r1" to "r1"), model.refs)
        assertEquals(BlockType.PARAGRAPH to "y", model.blocks()[2].let { it.type to it.text })
        model.assertConsistent()
    }

    @Test
    fun `insertPicture over a selection ending right before a picture keeps that picture`() {
        val p0 = Markers.PICTURE
        val model = EditorModel(doc(p("a", "ab"), pic("r0")))
        model.select(1, 3) // "b\n": removing it runs "a" into the picture line
        EditorCommands.insertPicture(model, "pic:r1", BlockType.PICTURE)
        assertEquals("a\n$p0\n$p0", model.text())
        assertEquals(listOf("a", "pic:r1", "pic:r0"), model.ids)
        assertEquals(mapOf("pic:r0" to "r0", "pic:r1" to "r1"), model.refs)
    }

    @Test
    fun `insertPicture over a selected picture replaces it`() {
        val model = EditorModel(doc(p("a", "one"), pic("r0"), p("b", "two")))
        model.select(4, 5)
        EditorCommands.insertPicture(model, "pic:r1", BlockType.PICTURE)
        assertEquals("one\n${Markers.PICTURE}\ntwo", model.text())
        assertEquals(listOf("a", "pic:r1", "b"), model.ids)
        assertEquals(mapOf("pic:r1" to "r1"), model.refs)
    }

    @Test
    fun `toggle and clearFormatting over a picture line leave the picture alone`() {
        val model = EditorModel(doc(p("a", "one"), pic("r0"), p("b", "two")))
        model.select(0, model.text().length)
        EditorCommands.toggle(model, InlineStyle.BOLD)
        assertEquals(
            listOf(listOf(StyleSpan(0, 3, InlineStyle.BOLD)), emptyList(), listOf(StyleSpan(0, 3, InlineStyle.BOLD))),
            model.blocks().map { it.spans },
        )
        assertTrue(model.state.textStyles.getSpanStyles(TextRange(4, 5)).none { it.start <= 4 && it.end > 4 })
        EditorCommands.clearFormatting(model)
        assertTrue(model.blocks().all { it.spans.isEmpty() })
        assertEquals(listOf("a", "pic:r0", "b"), model.ids)
        assertEquals(mapOf("pic:r0" to "r0"), model.refs)
        assertEquals(BlockType.PICTURE, model.blocks()[1].type)
    }

    @Test
    fun `movePicture leaves the caret collapsed after the moved picture`() {
        val model = EditorModel(doc(p("a", "one"), p("b", "two"), pic("r1")))
        EditorCommands.movePicture(model, from = 2, toGap = 1)
        assertEquals("one\n${Markers.PICTURE}\ntwo", model.text())
        assertEquals(TextRange(6), model.state.selection, "at the start of the line after it")
        EditorCommands.movePicture(model, from = 1, toGap = 3)
        assertEquals("one\ntwo\n${Markers.PICTURE}", model.text())
        assertEquals(TextRange(model.text().length), model.state.selection, "after it, at the end")
    }

    @Test
    fun `movePicture to gap 0 puts it first`() {
        val model = EditorModel(doc(p("a", "one"), p("b", "two"), pic("r1")))
        EditorCommands.movePicture(model, from = 2, toGap = 0)
        assertEquals("\uE004\none\ntwo", model.text())
        assertEquals(listOf("pic:r1", "a", "b"), model.ids)
        assertEquals(mapOf("pic:r1" to "r1"), model.refs)
    }

    @Test
    fun `movePicture to the end and to its own gaps`() {
        val model = EditorModel(doc(pic("r1"), p("a", "one"), p("b", "")))
        EditorCommands.movePicture(model, from = 0, toGap = 3)
        assertEquals("one\n\n\uE004", model.text())
        assertEquals(listOf("a", "b", "pic:r1"), model.ids)
        EditorCommands.movePicture(model, from = 2, toGap = 2) // before itself: no-op
        EditorCommands.movePicture(model, from = 2, toGap = 3) // after itself: no-op
        assertEquals("one\n\n\uE004", model.text())
        EditorCommands.movePicture(model, from = 2, toGap = 1)
        assertEquals("one\n\uE004\n", model.text())
        assertEquals(listOf("a", "pic:r1", "b"), model.ids)
        assertFailsWith<IllegalArgumentException> { EditorCommands.movePicture(model, from = 0, toGap = 2) }
    }
}
