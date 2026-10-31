package my.cheysoff.core_blocks

import my.cheysoff.core_domain.model.NoteContentFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlockTextTest {

    private fun b(type: BlockType, text: String = "", done: Boolean = false, id: String = text + type.name) =
        Block(id = id, type = type, text = text, done = done)

    @Test
    fun `checklist and bullets copy as readable text`() {
        val text = BlockText.plain(
            listOf(
                b(BlockType.H1, "Shopping"),
                b(BlockType.CHECK, "milk", done = false),
                b(BlockType.CHECK, "eggs", done = true),
                b(BlockType.BULLET, "note"),
                b(BlockType.PARAGRAPH, "end"),
            ),
        )
        assertEquals("Shopping\n☐ milk\n☑ eggs\n• note\nend", text)
    }

    @Test
    fun `numbering restarts after a paragraph`() {
        val text = BlockText.plain(
            listOf(
                b(BlockType.NUMBER, "a"),
                b(BlockType.NUMBER, "b"),
                b(BlockType.PARAGRAPH, "gap"),
                b(BlockType.NUMBER, "c"),
            ),
        )
        assertEquals("1. a\n2. b\ngap\n1. c", text)
    }

    @Test
    fun `pictures leave no character behind`() {
        val doc = listOf(
            b(BlockType.PARAGRAPH, "before"),
            Block(id = "pic:1", type = BlockType.PICTURE, ref = "1"),
            Block(id = "drw:2", type = BlockType.DRAWING, ref = "2"),
            b(BlockType.PARAGRAPH, "after"),
        )
        val text = BlockText.plain(doc)
        assertEquals("before\nafter", text)
        assertTrue(text.none { it in ''..'' })
    }

    @Test
    fun `a note of only pictures is not empty, a note of empty paragraphs is`() {
        assertFalse(BlockText.isEmpty(listOf(Block(id = "pic:1", type = BlockType.PICTURE, ref = "1"))))
        assertTrue(BlockText.isEmpty(listOf(b(BlockType.PARAGRAPH, ""), b(BlockType.PARAGRAPH, "  ", id = "x"))))
        assertTrue(BlockText.isEmpty(emptyList()))
        assertFalse(BlockText.isEmpty(listOf(b(BlockType.PARAGRAPH, "hi"))))
    }

    @Test
    fun `bodyPlain reads blocks, passes over other formats and shows undecodable text raw`() {
        val json = BlockCodec.encode(BlockDocument(listOf(b(BlockType.BULLET, "x").copy(pos = "a"))))
        assertEquals("• x", BlockText.bodyPlain(json, NoteContentFormat.BLOCKS))
        assertNull(BlockText.bodyPlain("hello", NoteContentFormat.PLAIN))
        assertEquals("not json", BlockText.bodyPlain(" not json ", NoteContentFormat.BLOCKS))
    }

    @Test
    fun `an undecodable blocks body is shown without private-use characters`() {
        val raw = " broken body "
        assertEquals("broken body", BlockText.bodyPlain(raw, NoteContentFormat.BLOCKS))
    }

    @Test
    fun `bodyIsBlank is null for other formats and true for an empty blocks body`() {
        val empty = BlockCodec.encode(BlockDocument(listOf(b(BlockType.PARAGRAPH, ""))))
        assertEquals(true, BlockText.bodyIsBlank(empty, NoteContentFormat.BLOCKS))
        assertNull(BlockText.bodyIsBlank("", NoteContentFormat.PLAIN))
    }
}
