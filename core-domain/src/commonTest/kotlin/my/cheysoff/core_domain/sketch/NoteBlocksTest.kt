package my.cheysoff.core_domain.sketch

import my.cheysoff.core_domain.model.NoteContentFormat
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Where a drawing sits in a note's text.
 *
 * The index is stored on the sketch and never in the note's body, so a build without sketch support
 * reads and edits the note normally and cannot damage a reference it does not hold. The cost is
 * that such a build's edits shift the blocks this counts, so a drawing can render a paragraph away
 * from where it was put — misplaced, never lost.
 *
 * Both platforms must agree exactly. A disagreement here is the same note showing the drawing in
 * two different places on two devices, which no test on either platform alone would catch.
 */
class NoteBlocksTest {

    @Test
    fun `html blocks are its top-level elements`() {
        val html = "<p>One</p><p>Two</p><ul><li>a</li><li>b</li></ul>"
        assertEquals(3, NoteBlocks.count(html, NoteContentFormat.HTML), "the list is one block, not two")
    }

    @Test
    fun `plain text blocks are its lines`() {
        assertEquals(3, NoteBlocks.count("one\ntwo\nthree", NoteContentFormat.PLAIN))
    }

    @Test
    fun `an empty body has no blocks`() {
        assertEquals(0, NoteBlocks.count("", NoteContentFormat.HTML))
        assertEquals(0, NoteBlocks.count("", NoteContentFormat.PLAIN))
    }

    @Test
    fun `content it cannot parse still returns a usable count`() {
        // Never throws: an unparseable body must cost a drawing its position, never its existence.
        val count = NoteBlocks.count("<p>unclosed<div>", NoteContentFormat.HTML)
        assertEquals(true, count >= 0)
    }

    @Test
    fun `an anchor past the end clamps to the last block`() {
        assertEquals(3, NoteBlocks.clamp(anchor = 99, blockCount = 3))
        assertEquals(0, NoteBlocks.clamp(anchor = -4, blockCount = 3))
        assertEquals(2, NoteBlocks.clamp(anchor = 2, blockCount = 3))
    }

    @Test
    fun `an anchor of zero means before the first block`() {
        assertEquals(0, NoteBlocks.clamp(anchor = 0, blockCount = 3))
    }
}
