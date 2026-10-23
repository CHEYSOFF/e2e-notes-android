package my.cheysoff.feature_notes

import my.cheysoff.core_blocks.Block
import my.cheysoff.core_blocks.BlockCodec
import my.cheysoff.core_blocks.BlockDocument
import my.cheysoff.core_blocks.BlockType
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NoteContentFormat
import my.cheysoff.feature_notes.model.list.searchPreviews
import my.cheysoff.feature_notes.model.list.toUi
import my.cheysoff.feature_notes.model.single.ChecklistItem
import my.cheysoff.feature_notes.model.single.buildNoteShareText
import org.junit.Assert.assertEquals
import org.junit.Test

class BlocksBodyReadersTest {

    private val body = BlockCodec.encode(
        BlockDocument(
            listOf(
                Block(id = "a", type = BlockType.PARAGRAPH, text = "Trip plan", pos = "a"),
                Block(id = "b", type = BlockType.CHECK, text = "buy tickets", pos = "b"),
            ),
        ),
    )

    private fun note(content: String = body) =
        Note(id = "n", title = "T", content = content, contentFormat = NoteContentFormat.BLOCKS)

    @Test
    fun `a BLOCKS note shows readable text in the list snippet and is found by search`() {
        val preview = note().toUi()
        assertEquals("Trip plan\n☐ buy tickets", preview.content)
        val hits = searchPreviews(listOf(preview), "tickets")
        assertEquals(listOf("n"), hits.map { it.preview.id })
    }

    @Test
    fun `an undecodable BLOCKS body shows its raw text`() {
        assertEquals("{broken", note("{broken").toUi().content)
    }

    @Test
    fun `share text for a BLOCKS note reads the blocks and keeps a leftover checklist`() {
        val text = buildNoteShareText(
            title = "T",
            content = body,
            format = NoteContentFormat.BLOCKS,
            checklist = listOf(ChecklistItem(id = "c1", text = "old item", isDone = true)),
        )
        assertEquals("T\n\nTrip plan\n☐ buy tickets\n\n[x] old item", text)
    }
}
