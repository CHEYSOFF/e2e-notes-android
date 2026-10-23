package my.cheysoff.core_blocks

import my.cheysoff.core_domain.sync.Hlc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlockCodecTest {

    private fun hlc(ms: Long, counter: Int = 0, node: String = "n1") = Hlc(ms, counter, node)

    @Test
    fun `round trip keeps every field`() {
        val blocks = BlockType.entries.mapIndexed { i, type ->
            Block(
                id = "id$i",
                type = type,
                text = "text \"$i\"\né",
                spans = InlineStyle.entries.map { StyleSpan(0, 2, it) },
                done = type == BlockType.CHECK,
                ref = if (type == BlockType.PICTURE) "rec-1" else null,
                pos = "a$i",
                clock = hlc(100L + i, i),
                posClock = hlc(200L + i, i, "n2"),
            )
        }
        val doc = BlockDocument(blocks, listOf(Tombstone("dead", hlc(300L, 1))))

        val decoded = BlockCodec.decode(BlockCodec.encode(doc))

        assertEquals(doc, decoded)
    }

    @Test
    fun `a tombstone keeps the dead block's position through a round trip`() {
        val doc = BlockDocument(
            emptyList(),
            listOf(
                Tombstone("dead", hlc(300L), pos = "m4", posClock = hlc(400L, 2, "n2")),
                Tombstone("plain", hlc(5)),
                Tombstone("unclocked", hlc(6), pos = "k"),
            ),
        )

        assertEquals(doc, BlockCodec.decode(BlockCodec.encode(doc)))
    }

    @Test
    fun `a tombstone without a position encodes exactly as before`() {
        val encoded = BlockCodec.encode(BlockDocument(emptyList(), listOf(Tombstone("a", hlc(1)))))

        assertEquals("""{"v":1,"blocks":[],"gone":[{"id":"a","c":"${hlc(1)}"}]}""", encoded)
    }

    @Test
    fun `encoding is canonical`() {
        val spansA = listOf(StyleSpan(0, 1, InlineStyle.BOLD), StyleSpan(0, 1, InlineStyle.ITALIC), StyleSpan(2, 3, InlineStyle.BOLD))
        val goneA = listOf(Tombstone("a", hlc(1)), Tombstone("b", hlc(2)))
        val one = BlockDocument(listOf(Block("x", BlockType.PARAGRAPH, "abc", spansA, pos = "m")), goneA)
        val two = BlockDocument(listOf(Block("x", BlockType.PARAGRAPH, "abc", spansA.reversed(), pos = "m")), goneA.reversed())

        assertEquals(BlockCodec.encode(one), BlockCodec.encode(two))
    }

    @Test
    fun `blocks are sorted by pos then id`() {
        val doc = BlockDocument(listOf(
            Block("b", BlockType.PARAGRAPH, pos = "a"),
            Block("a", BlockType.PARAGRAPH, pos = "b"),
            Block("a2", BlockType.PARAGRAPH, pos = "a"),
        ))
        assertEquals(listOf("a2", "b", "a"), doc.blocks.map { it.id })
    }

    @Test
    fun `emoji offsets survive`() {
        val text = "a😀b"
        val block = Block("e", BlockType.PARAGRAPH, text, listOf(StyleSpan(1, 3, InlineStyle.BOLD)))
        val decoded = assertNotNull(BlockCodec.decode(BlockCodec.encode(BlockDocument(listOf(block)))))
        val span = decoded.blocks.single().spans.single()

        assertEquals(StyleSpan(1, 3, InlineStyle.BOLD), span)
        assertEquals("😀", decoded.blocks.single().text.substring(span.start, span.end))
    }

    @Test
    fun `malformed and future versions decode to null`() {
        assertNull(BlockCodec.decode("{"))
        assertNull(BlockCodec.decode("{\"v\":2,\"blocks\":[]}"))
        assertNull(BlockCodec.decode("{\"v\":1,\"blocks\":[{\"id\":\"a\",\"t\":\"table\",\"text\":\"\"}]}"))
    }

    @Test
    fun `sameVisibleContent ignores clocks and tombstones`() {
        val a = BlockDocument(listOf(Block("x", BlockType.CHECK, "t", done = true, pos = "a", clock = hlc(1), posClock = hlc(1))))
        val b = BlockDocument(
            listOf(Block("x", BlockType.CHECK, "t", done = true, pos = "z", clock = hlc(9), posClock = hlc(9))),
            listOf(Tombstone("old", hlc(5))),
        )
        val c = BlockDocument(listOf(Block("x", BlockType.CHECK, "t", done = false)))

        assertTrue(a.sameVisibleContent(b))
        assertFalse(a.sameVisibleContent(c))
    }

    @Test
    fun `block ids for records are stable`() {
        assertEquals("pic:r1", BlockIds.forPicture("r1"))
        assertEquals("drw:r1", BlockIds.forDrawing("r1"))
        assertTrue(BlockIds.random() != BlockIds.random())
    }
}
