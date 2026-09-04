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
        // "<p>" opens at depth 0 and counts; "<div>" opens at depth 1 (nested, unclosed) and does
        // not -- so the one well-formed top-level block is exactly what a usable count means here.
        assertEquals(1, NoteBlocks.count("<p>unclosed<div>", NoteContentFormat.HTML))
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

    @Test
    fun `a line break inside a paragraph does not undercount the blocks after it`() {
        // "<p>first<br>second</p>" is real richeditor toHtml() output -- see the "line-break" case
        // in RichEditorHtmlRoundTripTest.kt:292 -- with a second paragraph appended so a <br> that
        // is wrongly treated as a nesting element shows up: it would leave the scanner's depth at
        // 1 instead of 0 after the first </p>, so "Third" below would never be counted.
        val html = "<p>first<br>second</p><p>Third</p>"
        assertEquals(2, NoteBlocks.count(html, NoteContentFormat.HTML), "the <br> must not swallow the block after it")
    }

    @Test
    fun `hr and img do not undercount the blocks after them either`() {
        // Same failure shape as the <br> case: a void element with no closing tag of its own, left
        // out of the nesting count so it cannot strand the depth above zero for the rest of the body.
        val html = "<p>a</p><hr><img><p>b</p>"
        assertEquals(2, NoteBlocks.count(html, NoteContentFormat.HTML), "hr/img must not swallow the block after them")
    }

    @Test
    fun `a whitespace-only line still renders as a visible line so it still counts as a block`() {
        // Ruling: the anchor positions a drawing among what a reader sees, and a whitespace-only
        // line renders as a visible empty line -- isNotBlank() would make the counter disagree with
        // the renderer. Do not "fix" this to isNotBlank().
        assertEquals(3, NoteBlocks.count("one\n   \nthree", NoteContentFormat.PLAIN))
    }
}
