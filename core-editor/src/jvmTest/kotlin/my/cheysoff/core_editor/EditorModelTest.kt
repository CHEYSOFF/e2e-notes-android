package my.cheysoff.core_editor

import androidx.compose.foundation.text.input.delete
import androidx.compose.foundation.text.input.insert
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import my.cheysoff.core_blocks.Block
import my.cheysoff.core_blocks.BlockDocument
import my.cheysoff.core_blocks.BlockType
import my.cheysoff.core_blocks.InlineStyle
import my.cheysoff.core_blocks.StyleSpan
import my.cheysoff.core_domain.sync.NameUuid
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
            "\uE011Title\nplain bold under-strike\n\uE002bullet\n\uE003number\n\uE000todo\n\uE001done\n" +
                "\uE004\n\uE005\n\uE013",
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
        assertEquals("one\n\uE004\ntwo", model.text())
        // Cut: the picture line and the break before it.
        model.edit { delete(3, 5) }
        assertEquals(listOf("a", "b"), model.ids)
        // Paste at the end, as a separate edit. Cross-edit identity is the clipboard's job: it
        // remembers what it cut and stages it.
        model.stagePicture("pic:r1", BlockType.PICTURE, "r1")
        model.edit { insert(length, "\n\uE004") }
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
            doc(p("a", "x\uE004y\uF8FFz", StyleSpan(2, 5, InlineStyle.BOLD)), p("b", "next")),
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
        assertEquals("\uE001ab\ncd\n\nend", model.text())
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
        assertEquals("one\ntwo\n\uE004", model.text(), "a picture shown twice is one record, kept once")
        assertEquals("a", model.ids[0])
        assertEquals(NameUuid.v3("a/dup/1".encodeToByteArray()), model.ids[1])
        assertEquals("pic:r1", model.ids[2])
    }

    @Test
    fun `ids minted on load are name-based so two devices agree`() {
        val d = doc(p("x", "one\ntwo\nthree"))
        val a = EditorModel(d)
        val b = EditorModel(d)
        assertEquals(a.ids, b.ids)
        assertEquals(
            listOf("x", NameUuid.v3("x/line/1".encodeToByteArray()), NameUuid.v3("x/line/2".encodeToByteArray())),
            a.ids,
        )
        assertEquals(listOf(NameUuid.v3("empty-document".encodeToByteArray())), EditorModel(BlockDocument(emptyList())).ids)
    }

    // --- Ids follow the text (fix round 1, ruling 1) -----------------------------------------

    @Test
    fun `Enter at the start of a paragraph keeps the id on the text`() {
        val model = EditorModel(doc(p("a", "hello"), p("b", "world")))
        model.edit { insert(6, "\n") }
        assertEquals("hello\n\nworld", model.text())
        assertEquals("a", model.ids[0])
        assertEquals("b", model.ids[2])
        assertFalse(model.ids[1] in setOf("a", "b"))
    }

    @Test
    fun `Enter at the start of a checklist item keeps the id on the item`() {
        val model = EditorModel(doc(Block("c", BlockType.CHECK, "todo")))
        model.edit { insert(0, "\n") }
        assertEquals("\n\uE000todo", model.text())
        assertEquals("c", model.ids[1])
        assertEquals(BlockType.CHECK, model.blocks()[1].type)
    }

    @Test
    fun `an IME replacement across a line break keeps both ids`() {
        val model = EditorModel(doc(p("a", "hello"), p("b", "world")))
        model.edit { replace(3, 9, "LO\nWOR") }
        assertEquals("helLO\nWORld", model.text())
        assertEquals(listOf("a", "b"), model.ids)
    }

    @Test
    fun `replacing a whole line's text keeps its id`() {
        val model = EditorModel(doc(p("a", "hello"), p("b", "world")))
        model.edit { replace(0, 5, "bye") }
        assertEquals(listOf("a", "b"), model.ids)
    }

    @Test
    fun `deleting a whole line hands nothing of it to the next`() {
        val model = EditorModel(doc(p("a", "hello"), p("b", "world")))
        model.edit { delete(0, 6) }
        assertEquals(listOf("b"), model.ids)
    }

    // --- Pictures: staged ids win (ruling 2) -------------------------------------------------

    @Test
    fun `a staged id wins over a picture removed in the same edit`() {
        val model = EditorModel(doc(p("a", "one"), pic("r1")))
        model.stagePicture("pic:r2", BlockType.PICTURE, "r2")
        model.edit { replace(4, 5, "\uE004") } // delete pic:r1's marker, insert a new one
        assertEquals(listOf("a", "pic:r2"), model.ids)
        assertEquals("r2", model.blocks()[1].ref)
        assertEquals(mapOf("pic:r2" to "r2"), model.refs)
    }

    @Test
    fun `a drawing never takes a picture id`() {
        val model = EditorModel(doc(p("a", "one"), pic("r1")))
        // Picture removed and a drawing marker inserted in one edit: not a move.
        assertFailsWith<IllegalStateException> { model.edit { replace(4, 5, "\uE005") } }
        model.stagePicture("pic:r2", BlockType.PICTURE, "r2")
        model.stagePicture("drw:r3", BlockType.DRAWING, "r3")
        model.edit { insert(length, "\n\uE005\n\uE004") }
        assertEquals(listOf("a", "pic:r1", "drw:r3", "pic:r2"), model.ids)
        assertEquals(
            listOf(BlockType.PARAGRAPH, BlockType.PICTURE, BlockType.DRAWING, BlockType.PICTURE),
            model.blocks().map { it.type },
        )
    }

    @Test
    fun `an unstaged marker insert throws and leaves text and ids alone`() {
        val model = EditorModel(doc(p("a", "one")))
        assertFailsWith<IllegalStateException> { model.edit { insert(length, "\n\uE004") } }
        assertEquals("one", model.text())
        assertEquals(listOf("a"), model.ids)
        // A staged id lasts one edit only (and an edit that leaves it unused is itself an error).
        model.stagePicture("pic:r1", BlockType.PICTURE, "r1")
        assertFailsWith<IllegalStateException> { model.edit { insert(0, "x") } }
        assertFailsWith<IllegalStateException> { model.edit { insert(length, "\n\uE004") } }
    }

    // --- Fix round 2 -------------------------------------------------------------------------

    @Test
    fun `insert before an empty line with a chosen id`() {
        val model = EditorModel(doc(p("a", "a"), p("e", ""), p("c", "c")))
        model.stageText("b")
        model.edit { insert(2, "two\n") }
        assertEquals("a\ntwo\n\nc", model.text())
        assertEquals(listOf("a", "b", "e", "c"), model.ids)
    }

    @Test
    fun `Enter at the start of an empty checklist item keeps the id on the item`() {
        val model = EditorModel(doc(p("a", "a"), Block("c", BlockType.CHECK, "")))
        model.edit { insert(2, "\n") }
        assertEquals("a\n\n\uE000", model.text())
        assertEquals("a", model.ids[0])
        assertEquals("c", model.ids[2])
        assertEquals(BlockType.CHECK, model.blocks()[2].type)
    }

    @Test
    fun `a user edit carrying a picture marker loses the marker instead of throwing`() {
        val model = EditorModel(doc(p("a", "one"), pic("r1")))
        // What an IME commitText, drop or autofill of "\n<picture>" would do.
        model.state.edit {
            insert(3, "\n\uE004")
            model.onEdited(this, fromUser = true)
        }
        assertEquals("one\n\n\uE004", model.text())
        assertEquals(1, model.text().count { it == '\uE004' }, "only the real picture's marker")
        assertEquals("a", model.ids[0])
        assertEquals("pic:r1", model.ids[2])
        assertEquals(mapOf("pic:r1" to "r1"), model.refs)
        assertEquals(3, model.ids.toSet().size)
    }

    @Test
    fun `a user edit that is only a picture marker leaves no marker`() {
        val model = EditorModel(doc(p("a", "one")))
        model.state.edit {
            insert(3, "\n\uE005")
            model.onEdited(this, fromUser = true)
        }
        assertEquals("one\n", model.text())
        assertTrue(model.text().none(Markers::isPrivateUse))
        assertEquals("a", model.ids[0])
        assertEquals(2, model.ids.size)
    }

    @Test
    fun `a staged id the edit does not use throws and reverts`() {
        val model = EditorModel(doc(p("a", "one")))
        model.stageText("b")
        assertFailsWith<IllegalStateException> { model.edit { insert(3, "!") } }
        assertEquals("one", model.text())
        assertEquals(listOf("a"), model.ids)
        // Staging was cleared, so the next edit is clean.
        model.edit { insert(3, "!") }
        assertEquals("one!", model.text())
    }

    @Test
    fun `a picture cut and pasted without staging does not come back`() {
        val model = EditorModel(doc(p("a", "one"), pic("r1"), p("b", "two")))
        model.edit { delete(3, 5) }
        assertFailsWith<IllegalStateException> { model.edit { insert(length, "\n\uE004") } }
        assertTrue(model.refs.isEmpty())
    }

    // --- Chosen ids for inserted text lines (ruling 3) ---------------------------------------

    @Test
    fun `a programmatic insert of a text line with a chosen id keeps that id`() {
        val model = EditorModel(doc(p("a", "one"), p("c", "three")))
        model.stageText("b")
        model.edit { insert(3, "\ntwo") }
        assertEquals(listOf("a", "b", "c"), model.ids)
        assertEquals(listOf("a" to "one", "b" to "two", "c" to "three"), model.blocks().map { it.id to it.text })
        // Inserted before a line, the chosen id still goes to the new line, not the old one.
        model.stageText("z")
        model.edit { insert(0, "zero\n") }
        assertEquals(listOf("z", "a", "b", "c"), model.ids)
        assertFailsWith<IllegalArgumentException> { model.stageText("a") }
    }

    // --- Reading back (fix round 1, item 6) --------------------------------------------------

    @Test
    fun `a style spanning several lines is read back per line`() {
        val model = EditorModel(doc(p("a", "ab"), Block("b", BlockType.BULLET, "cd"), p("c", "ef")))
        model.edit { addStyle(SpanStyle(fontWeight = FontWeight.Bold), 1, 8) } // "b\n<bullet>cd\ne"
        assertEquals(
            listOf(
                listOf(StyleSpan(1, 2, InlineStyle.BOLD)),
                listOf(StyleSpan(0, 2, InlineStyle.BOLD)),
                listOf(StyleSpan(0, 1, InlineStyle.BOLD)),
            ),
            model.blocks().map { it.spans },
        )
    }

    @Test
    fun `typing next to a marker never puts a private-use char in block text`() {
        val model = EditorModel(doc(Block("c", BlockType.CHECK, "todo"), pic("r1")))
        model.edit { insert(0, "x") } // before the checkbox marker
        model.edit { insert(length, "y") } // after the picture marker
        val blocks = model.blocks()
        blocks.forEach { b -> assertTrue(b.text.none(Markers::isPrivateUse), "private-use char in ${b.text}") }
        assertEquals(listOf("xtodo", "y"), blocks.map { it.text })
        assertTrue(model.refs.isEmpty(), "a marker no longer alone is no longer a picture")
    }

    @Test
    fun `ids are snapshot state and roll back with a discarded snapshot`() {
        val model = EditorModel(doc(p("a", "hello")))
        val snapshot = Snapshot.takeMutableSnapshot()
        snapshot.enter { model.edit { insert(2, "\n") } }
        assertEquals(listOf("a"), model.ids, "not visible outside the snapshot before apply")
        snapshot.dispose()
        assertEquals(listOf("a"), model.ids)
        assertEquals("hello", model.text())
    }


    // --- Picture edges and programmatic inserts ----------------------------------------------

    @Test
    fun `Enter at the start of a picture line leaves the id on the picture`() {
        val model = EditorModel(doc(p("a", "one"), pic("r1")))
        model.edit { insert(4, "\n") }
        assertEquals("one\n\n\uE004", model.text())
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
            insert(8, "\uE004\n")
        }
        assertEquals("one\ntwo\n\uE004\nthree", model.text())
        assertEquals(listOf("a", "b", "pic:r1", "c"), model.ids)
    }

    @Test
    fun `a staged picture gives a new marker line its id and record`() {
        val model = EditorModel(doc(p("a", "one")))
        model.stagePicture("pic:r9", BlockType.PICTURE, "r9")
        assertFailsWith<IllegalArgumentException> { model.stagePicture("a", BlockType.PICTURE, "r0") }
        model.edit { insert(length, "\n\uE004") }
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
                if (id.startsWith("pic:")) assertEquals("\uE004", text[i], "picture id on a non-picture line")
            }
        }
    }
}
