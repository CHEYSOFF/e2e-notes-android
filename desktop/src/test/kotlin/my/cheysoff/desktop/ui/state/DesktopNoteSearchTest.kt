package my.cheysoff.desktop.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopNoteSearchTest {

    private fun row(id: String, title: String, snippet: String = "") =
        NoteRowUi(id = id, title = title, snippet = snippet)

    @Test
    fun `a blank query matches nothing rather than everything`() {
        val rows = listOf(row("a", "Groceries"), row("b", "Ideas"))
        assertTrue(searchRows(rows, "   ").isEmpty())
    }

    @Test
    fun `matching is case-insensitive across scripts with one-to-one case mapping`() {
        assertEquals(listOf(0..4), findMatchRanges("Привет", "приве"))
        assertEquals(listOf(0..4), findMatchRanges("HELLO there", "hello"))
    }

    @Test
    fun `occurrences never overlap`() {
        // "aaa" in "aaaaa" is two hits, not three: the second starts after the first ends.
        assertEquals(listOf(0..2), findMatchRanges("aaaaa", "aaa"))
    }

    @Test
    fun `title matches are ranked above body-only matches`() {
        val rows = listOf(
            row("body", "Standup", snippet = "remember the milk"),
            row("title", "Milk run", snippet = "nothing here"),
        )

        val hits = searchRows(rows, "milk")

        assertEquals(listOf("title", "body"), hits.map { it.row.id })
    }

    @Test
    fun `a query spanning a line break in the note still matches`() {
        val hit = matchRow(row("a", "x", snippet = "eggs and\n  coffee"), "and coffee")
        assertEquals("eggs and coffee", hit?.snippet)
    }

    @Test
    fun `highlight offsets point into the snippet that is drawn, not into the body`() {
        val body = "x".repeat(400) + " needle " + "y".repeat(400)
        val hit = matchRow(row("a", "untitled", snippet = body), "needle")!!

        val range = hit.snippetHighlights.single()
        assertEquals("needle", hit.snippet.substring(range.first, range.last + 1))
    }

    @Test
    fun `a snippet cut at both ends is marked with ellipses`() {
        val body = "x".repeat(400) + " needle " + "y".repeat(400)
        val hit = matchRow(row("a", "untitled", snippet = body), "needle")!!

        assertTrue(hit.snippet.startsWith("…"))
        assertTrue(hit.snippet.endsWith("…"))
        // Body plus the two ellipses; the window itself must not exceed its budget.
        assertEquals(SNIPPET_MAX_LENGTH + 2, hit.snippet.length)
    }

    @Test
    fun `a match outside the snippet window is dropped rather than clamped`() {
        val body = "needle " + "z".repeat(600) + " needle"
        val hit = matchRow(row("a", "untitled", snippet = body), "needle")!!

        // Only the first occurrence is inside the window; a clamped second would underline text
        // the user cannot read.
        assertEquals(1, hit.snippetHighlights.size)
    }

    @Test
    fun `a title-only match still gets a snippet from the head of the body`() {
        val hit = matchRow(row("a", "Groceries", snippet = "milk and eggs"), "groc")!!

        assertEquals("milk and eggs", hit.snippet)
        assertTrue(hit.snippetHighlights.isEmpty())
    }

    @Test
    fun `a note matching neither field is not a hit`() {
        assertNull(matchRow(row("a", "Groceries", snippet = "milk"), "quantum"))
    }
}
