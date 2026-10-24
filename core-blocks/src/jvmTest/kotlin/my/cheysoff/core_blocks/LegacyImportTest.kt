package my.cheysoff.core_blocks

import my.cheysoff.core_domain.model.NoteContentFormat
import my.cheysoff.core_domain.sync.Hlc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Legacy bodies must open as blocks with nothing lost. The sample bodies are copied from the
 * deleted instrumented `RichEditorHtmlRoundTripTest` (the pre-blocks editor's own): rc14 stores
 * entity-escaped text, 1.1.0 stores it literally, and both must import to the same blocks.
 */
class LegacyImportTest {

    private fun html(body: String, checklist: String = "") =
        LegacyImport.toBlocks("n1", body, NoteContentFormat.HTML, checklist)

    /** (type, text, spans) -- the part of a block a person can see. */
    private fun shape(blocks: List<Block>) = blocks.map { Triple(it.type, it.text, it.spans) }

    private fun p(text: String, spans: List<StyleSpan> = emptyList()) = Triple(BlockType.PARAGRAPH, text, spans)

    private val russianSource =
        "<h1>Планы на неделю</h1>" +
            "<p>Купить <b>молоко</b> и хлеб.</p>" +
            "<ul><li>Позвонить маме</li>" +
            "<li>Оплатить счёт</li></ul>"

    private val rc14Russian =
        "<h1>&Pcy;&lcy;&acy;&ncy;&ycy; &ncy;&acy; &ncy;&iecy;&dcy;&iecy;&lcy;&yucy;</h1>" +
            "<p>&Kcy;&ucy;&pcy;&icy;&tcy;&softcy; <b>&mcy;&ocy;&lcy;&ocy;&kcy;&ocy;</b> " +
            "&icy; &khcy;&lcy;&iecy;&bcy;&period;</p>" +
            "<ul><li>&Pcy;&ocy;&zcy;&vcy;&ocy;&ncy;&icy;&tcy;&softcy; &mcy;&acy;&mcy;&iecy;</li>" +
            "<li>&Ocy;&pcy;&lcy;&acy;&tcy;&icy;&tcy;&softcy; &scy;&chcy;&iocy;&tcy;</li></ul>"

    private val rc14BoldCyrillic =
        "<p><b>&Zcy;&acy;&gcy;&ocy;&lcy;&ocy;&vcy;&ocy;&kcy;</b> &icy; " +
            "&ocy;&bcy;&ycy;&chcy;&ncy;&ycy;&jcy; &tcy;&iecy;&kcy;&scy;&tcy;</p>"

    /** Each sample as (rc14 body, 1.1.0 body, blocks both must import to). */
    private val samples: List<Triple<String, String, List<Triple<BlockType, String, List<StyleSpan>>>>> = listOf(
        Triple("<p>First&period;</p><p>Second&period;</p>", "<p>First.</p><p>Second.</p>", listOf(p("First."), p("Second."))),
        Triple(
            "<p>&Pcy;&rcy;&icy;&vcy;&iecy;&tcy;&comma; &mcy;&icy;&rcy; &mdash; " +
                "&ecy;&tcy;&ocy; &zcy;&acy;&mcy;&iecy;&tcy;&kcy;&acy;&period;</p>",
            "<p>Привет, мир — это заметка.</p>",
            listOf(p("Привет, мир — это заметка.")),
        ),
        Triple(
            rc14BoldCyrillic,
            "<p><b>Заголовок</b> и обычный текст</p>",
            listOf(p("Заголовок и обычный текст", listOf(StyleSpan(0, 9, InlineStyle.BOLD)))),
        ),
        Triple(
            "<p>He said &quot;hi&quot; &amp; left</p>",
            "<p>He said \"hi\" &amp; left</p>",
            listOf(p("He said \"hi\" & left")),
        ),
        Triple(
            "<p>ASCII&comma; &Kcy;&icy;&rcy;&icy;&lcy;&lcy;&icy;&tscy;&acy;&comma; 日本語&comma; emoji 🚀</p>",
            "<p>ASCII, Кириллица, 日本語, emoji 🚀</p>",
            listOf(p("ASCII, Кириллица, 日本語, emoji 🚀")),
        ),
        Triple(
            "<p>Email John &lt;john&commat;example&period;com&gt; about Q3</p>",
            "<p>Email John &lt;john@example.com&gt; about Q3</p>",
            listOf(p("Email John <john@example.com> about Q3")),
        ),
        Triple(
            rc14Russian,
            russianSource,
            listOf(
                Triple(BlockType.H1, "Планы на неделю", emptyList()),
                p("Купить молоко и хлеб.", listOf(StyleSpan(7, 13, InlineStyle.BOLD))),
                Triple(BlockType.BULLET, "Позвонить маме", emptyList()),
                Triple(BlockType.BULLET, "Оплатить счёт", emptyList()),
            ),
        ),
    )

    @Test
    fun `rc14 and 1_1_0 sample bodies import to the expected blocks`() {
        for ((rc14, v110, expected) in samples) {
            assertEquals(expected, shape(html(rc14)), "rc14: $rc14")
            assertEquals(expected, shape(html(v110)), "1.1.0: $v110")
        }
    }

    @Test
    fun `plain text keeps angle brackets literal`() {
        val blocks = LegacyImport.toBlocks("n1", "Email John <john@example.com>", NoteContentFormat.PLAIN, "")
        assertEquals(listOf(p("Email John <john@example.com>")), shape(blocks))
    }

    @Test
    fun `plain text is one paragraph per line and keeps empty lines`() {
        val blocks = LegacyImport.toBlocks("n1", "a\n\nb\r\nc", NoteContentFormat.PLAIN, "")
        assertEquals(listOf(p("a"), p(""), p("b"), p("c")), shape(blocks))
    }

    @Test
    fun `plain text is not entity decoded`() {
        val blocks = LegacyImport.toBlocks("n1", "Tom &amp; Jerry", NoteContentFormat.PLAIN, "")
        assertEquals(listOf(p("Tom &amp; Jerry")), shape(blocks))
    }

    @Test
    fun `unknown tag keeps its text`() {
        assertEquals(listOf(p("a x b")), shape(html("<p>a <mark>x</mark> b</p>")))
        assertEquals(listOf(p("x")), shape(html("<mark>x</mark>")))
    }

    @Test
    fun `h4 to h6 become H3 and h2 h3 are kept`() {
        val blocks = html("<h2>a</h2><h3>b</h3><h4>c</h4><h6>d</h6>")
        assertEquals(listOf(BlockType.H2, BlockType.H3, BlockType.H3, BlockType.H3), blocks.map { it.type })
    }

    @Test
    fun `ordered list items become number blocks`() {
        val blocks = html("<ol><li>one</li><li>two</li></ol><p>after</p>")
        assertEquals(
            listOf(
                Triple(BlockType.NUMBER, "one", emptyList<StyleSpan>()),
                Triple(BlockType.NUMBER, "two", emptyList()),
                p("after"),
            ),
            shape(blocks),
        )
    }

    @Test
    fun `br inside a paragraph splits it`() {
        assertEquals(listOf(p("first"), p("second")), shape(html("<p>first<br>second</p>")))
        assertEquals(listOf(p("first")), shape(html("<p>first<br></p>")))
        assertEquals(listOf(p("")), shape(html("<p><br></p>")))
    }

    @Test
    fun `empty paragraphs are kept`() {
        assertEquals(listOf(p("a"), p(""), p("b")), shape(html("<p>a</p><p></p><p>b</p>")))
    }

    @Test
    fun `nested styles produce overlapping spans`() {
        val b = html("<p><strong>bold and <em>also italic</em></strong> then</p>").single()
        assertEquals("bold and also italic then", b.text)
        assertEquals(
            setOf(StyleSpan(0, 20, InlineStyle.BOLD), StyleSpan(9, 20, InlineStyle.ITALIC)),
            b.spans.toSet(),
        )
    }

    @Test
    fun `strike aliases and underline are recognised`() {
        for (tag in listOf("s", "strike", "del")) {
            assertEquals(listOf(StyleSpan(0, 1, InlineStyle.STRIKE)), html("<p><$tag>x</$tag></p>").single().spans, tag)
        }
        assertEquals(listOf(StyleSpan(0, 1, InlineStyle.UNDERLINE)), html("<p><u>x</u></p>").single().spans)
    }

    @Test
    fun `span covering a surrogate pair keeps UTF-16 offsets`() {
        val b = html("<p>a<b>🚀</b>c</p>").single()
        assertEquals("a🚀c", b.text)
        assertEquals(listOf(StyleSpan(1, 3, InlineStyle.BOLD)), b.spans)
    }

    @Test
    fun `style across a br continues in the next paragraph`() {
        val bold = listOf(StyleSpan(0, 1, InlineStyle.BOLD))
        assertEquals(listOf(p("a", bold), p("b", bold)), shape(html("<p><b>a<br>b</b></p>")))
    }

    @Test
    fun `malformed html does not throw and keeps text`() {
        assertEquals(listOf(p("a < b")), shape(html("<p>a < b")))
        assertEquals(listOf(p("bare text")), shape(html("bare text")))
        assertEquals(listOf(p("x</p")), shape(html("<p>x</p")))
    }

    @Test
    fun `unterminated tags keep all their text`() {
        assertEquals(listOf(p("a <b then")), shape(html("<p>a <b then</p>")))
        assertEquals(listOf(p("x<y")), shape(html("x<y")))
        assertEquals(listOf(p("x<y z")), shape(html("<p>x<y z")))
        assertEquals(listOf(p("a <!-- b")), shape(html("<p>a <!-- b</p>")))
    }

    @Test
    fun `top-level br splits without an empty paragraph`() {
        assertEquals(listOf(p("text"), p("more")), shape(html("text<br>more")))
        assertEquals(listOf(p("text"), p("more")), shape(html("text<br/>more")))
        assertEquals(listOf(p("a"), p("b")), shape(html("<p>a<br />b</p>")))
    }

    @Test
    fun `uppercase tags and attributes are understood`() {
        val bold = listOf(StyleSpan(0, 1, InlineStyle.BOLD))
        assertEquals(listOf(p("x", bold)), shape(html("<P><B>x</B></P>")))
        assertEquals(listOf(p("x", bold)), shape(html("<p class=\"a\"><b id=1>x</b></p>")))
        assertEquals(listOf(Triple(BlockType.H1, "t", emptyList<StyleSpan>())), shape(html("<h1 id=\"x\">t</h1>")))
    }

    @Test
    fun `nested list keeps both items as bullets`() {
        assertEquals(
            listOf(
                Triple(BlockType.BULLET, "a", emptyList<StyleSpan>()),
                Triple(BlockType.BULLET, "b", emptyList()),
            ),
            shape(html("<ul><li>a<ul><li>b</li></ul></li></ul>")),
        )
    }

    @Test
    fun `checklist items are appended as check blocks`() {
        val blocks = html("<p>body</p>", checklist = "1milk\n0bread")
        assertEquals(
            listOf(
                p("body"),
                Triple(BlockType.CHECK, "milk", emptyList()),
                Triple(BlockType.CHECK, "bread", emptyList()),
            ),
            shape(blocks),
        )
        assertEquals(listOf(true, false), blocks.drop(1).map { it.done })
    }

    @Test
    fun `positions ascend and clocks are zero`() {
        val blocks = html("<p>a</p><p>b</p><p>c</p>", checklist = "0d")
        assertEquals(blocks.map { it.pos }.sorted(), blocks.map { it.pos })
        assertEquals(blocks.size, blocks.map { it.pos }.toSet().size)
        assertTrue(blocks.all { it.clock == Hlc.ZERO && it.posClock == Hlc.ZERO })
    }

    @Test
    fun `same note converts to identical ids twice`() {
        val a = html(russianSource, "0x")
        val b = html(russianSource, "0x")
        assertEquals(a, b)
        assertEquals(a.size, a.map { it.id }.toSet().size)
        val other = LegacyImport.toBlocks("n2", russianSource, NoteContentFormat.HTML, "0x")
        assertTrue(a.map { it.id }.intersect(other.map { it.id }.toSet()).isEmpty())
    }

    @Test
    fun `empty body yields no blocks`() {
        assertEquals(emptyList(), html(""))
        assertEquals(emptyList(), LegacyImport.toBlocks("n1", "", NoteContentFormat.PLAIN, ""))
    }

    @Test
    fun `legacy checklist parser matches the storage format`() {
        assertEquals(emptyList(), LegacyChecklist.parse(""))
        assertEquals(listOf("a" to true, "b" to false, "" to false), LegacyChecklist.parse("1a\n0b\n0"))
    }

    /**
     * The 30 round-trip inputs of the deleted `RichEditorHtmlRoundTripTest` (the shapes the
     * pre-blocks editor wrote and read back), each with the blocks it must open as. Its stored-output
     * samples are in [samples] above; these cover the remaining tags, entities and whitespace.
     */
    @Test
    fun `the pre-blocks editor's round-trip inputs import to the expected blocks`() {
        fun t(type: BlockType, text: String, spans: List<StyleSpan> = emptyList()) = Triple(type, text, spans)
        val b = InlineStyle.BOLD
        val i = InlineStyle.ITALIC
        val u = InlineStyle.UNDERLINE
        val long = "word ".repeat(200).trim()
        val cases = listOf(
            "" to emptyList(),
            "<p>Hello world</p>" to listOf(p("Hello world")),
            "<p>First.</p><p>Second.</p>" to listOf(p("First."), p("Second.")),
            "<p><b>bold</b></p>" to listOf(p("bold", listOf(StyleSpan(0, 4, b)))),
            "<p><strong>bold</strong></p>" to listOf(p("bold", listOf(StyleSpan(0, 4, b)))),
            "<p><i>italic</i></p>" to listOf(p("italic", listOf(StyleSpan(0, 6, i)))),
            "<p><em>italic</em></p>" to listOf(p("italic", listOf(StyleSpan(0, 6, i)))),
            "<p><u>underlined</u></p>" to listOf(p("underlined", listOf(StyleSpan(0, 10, u)))),
            "<h1>Heading one</h1>" to listOf(t(BlockType.H1, "Heading one")),
            "<h2>Heading two</h2>" to listOf(t(BlockType.H2, "Heading two")),
            "<h3>Heading three</h3>" to listOf(t(BlockType.H3, "Heading three")),
            "<h2>Title</h2><p>Body text.</p>" to listOf(t(BlockType.H2, "Title"), p("Body text.")),
            "<ul><li>alpha</li><li>beta</li></ul>" to listOf(t(BlockType.BULLET, "alpha"), t(BlockType.BULLET, "beta")),
            "<ol><li>one</li><li>two</li></ol>" to listOf(t(BlockType.NUMBER, "one"), t(BlockType.NUMBER, "two")),
            "<ul><li>item</li></ul><p>after</p>" to listOf(t(BlockType.BULLET, "item"), p("after")),
            "<p><b>bold and <i>also italic</i></b> then plain</p>" to
                listOf(p("bold and also italic then plain", listOf(StyleSpan(0, 20, b), StyleSpan(9, 20, i)))),
            "<p><b><i><u>all three</u></i></b></p>" to
                listOf(p("all three", listOf(StyleSpan(0, 9, b), StyleSpan(0, 9, i), StyleSpan(0, 9, u)))),
            "<p>Привет, мир — это заметка.</p>" to listOf(p("Привет, мир — это заметка.")),
            "<p><b>Заголовок</b> и обычный текст</p>" to listOf(p("Заголовок и обычный текст", listOf(StyleSpan(0, 9, b)))),
            "<p>ASCII, Кириллица, 日本語, emoji 🚀</p>" to listOf(p("ASCII, Кириллица, 日本語, emoji 🚀")),
            "<p>a &lt; b &amp; c &gt; d</p>" to listOf(p("a < b & c > d")),
            "<p>Tom &amp; Jerry</p>" to listOf(p("Tom & Jerry")),
            "<p>He said &quot;hi&quot; &amp; left</p>" to listOf(p("He said \"hi\" & left")),
            "<p>first<br>second</p>" to listOf(p("first"), p("second")),
            "<p><span style=\"font-weight: bold;\">styled</span></p>" to listOf(p("styled")),
            "<p>$long</p>" to listOf(p(long)),
            "<p>a    b\tc</p>" to listOf(p("a    b\tc")),
            "<p> padded </p>" to listOf(p(" padded ")),
            "<p>Email John &lt;john@example.com&gt; about Q3</p>" to listOf(p("Email John <john@example.com> about Q3")),
            russianSource to listOf(
                t(BlockType.H1, "Планы на неделю"),
                p("Купить молоко и хлеб.", listOf(StyleSpan(7, 13, b))),
                t(BlockType.BULLET, "Позвонить маме"),
                t(BlockType.BULLET, "Оплатить счёт"),
            ),
        )
        assertEquals(30, cases.size)
        for ((body, expected) in cases) assertEquals(expected, shape(html(body)), body)
    }
}
