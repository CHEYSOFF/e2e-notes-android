package my.cheysoff.core_blocks

import my.cheysoff.core_domain.model.NoteContentFormat
import my.cheysoff.core_domain.sync.Hlc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * When a BLOCKS save may clear the legacy checklist field: only when it carries the editor's
 * knowledge (a `blocksBase`), because only the editor's document had the leftover items folded in.
 */
class BlocksSaveChecklistTest {

    private val t1 = Hlc(100, 0, "a")
    private val stored = BlocksSave.encodeForSave(listOf(Block("p", BlockType.PARAGRAPH, text = "body")))

    @Test
    fun `a save without a base keeps the stored checklist`() {
        val out = assertIs<BlocksSave.Write>(BlocksSave.prepare(NoteContentFormat.BLOCKS, stored, stored, t1, blocksBase = null))

        assertFalse(out.clearsChecklist)
        assertEquals("0milk\n1eggs", out.checklistToStore(prior = "0milk\n1eggs", incoming = ""))
    }

    @Test
    fun `a save without a base and no stored note keeps the incoming checklist`() {
        val out = assertIs<BlocksSave.Write>(BlocksSave.prepare(null, "", stored, t1, blocksBase = null))

        assertEquals("0milk", out.checklistToStore(prior = null, incoming = "0milk"))
    }

    @Test
    fun `a save with the editor's base clears the checklist`() {
        val out = assertIs<BlocksSave.Write>(BlocksSave.prepare(NoteContentFormat.BLOCKS, stored, stored, t1, blocksBase = stored))

        assertTrue(out.clearsChecklist)
        assertEquals("", out.checklistToStore(prior = "0milk", incoming = "0milk"))
    }
}
