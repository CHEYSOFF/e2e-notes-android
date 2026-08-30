package my.cheysoff.feature_notes

import my.cheysoff.feature_notes.model.list.NotePreviewUi
import my.cheysoff.feature_notes.model.list.SNIPPET_MAX_LENGTH
import my.cheysoff.feature_notes.model.list.buildSnippet
import my.cheysoff.feature_notes.model.list.findMatchRanges
import my.cheysoff.feature_notes.model.list.matchPreview
import my.cheysoff.feature_notes.model.list.normalizeSearchText
import my.cheysoff.feature_notes.model.list.searchPreviews
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the pure half of the Search tab. Everything here runs on the JVM: NoteSearch.kt touches
 * no Android type, which is exactly why the HTML-to-text step lives outside it (in Note.toUi(),
 * which needs HtmlCompat) and these functions only ever see plain text.
 */
class NoteSearchTest {

    private fun preview(
        id: String = "n1",
        title: String = "",
        content: String = "",
    ) = NotePreviewUi(id = id, title = title, content = content)

    // ── normalizeSearchText ───────────────────────────────────────────────────

    @Test fun `normalize collapses runs of whitespace and trims`() {
        assertEquals("a b c", normalizeSearchText("  a \n\n b\t\tc  "))
    }

    @Test fun `normalize of blank input is empty`() {
        assertEquals("", normalizeSearchText("   \n \t "))
        assertEquals("", normalizeSearchText(""))
    }

    @Test fun `normalize leaves an already-tidy string alone`() {
        assertEquals("shopping list", normalizeSearchText("shopping list"))
    }

    // ── findMatchRanges ───────────────────────────────────────────────────────

    @Test fun `matching ignores case in both directions`() {
        assertEquals(listOf(0 until 5), findMatchRanges("Hello", "hello"))
        assertEquals(listOf(0 until 5), findMatchRanges("hello", "HELLO"))
    }

    @Test fun `matching is case-insensitive for Cyrillic too`() {
        // Cyrillic case mapping is one character to one character, which is what the ignoreCase
        // comparison handles. Note bodies are routinely Russian in this app.
        assertEquals(listOf(0 until 6), findMatchRanges("Привет", "привет"))
    }

    @Test fun `every occurrence is reported, in order and non-overlapping`() {
        assertEquals(listOf(0 until 2, 2 until 4, 4 until 6), findMatchRanges("aaaaaa", "aa"))
        assertEquals(listOf(0 until 3, 6 until 9), findMatchRanges("catxxxCAT", "cat"))
    }

    @Test fun `a needle longer than the haystack matches nothing`() {
        assertEquals(emptyList<IntRange>(), findMatchRanges("ab", "abc"))
    }

    @Test fun `an empty needle matches nothing rather than everything`() {
        assertEquals(emptyList<IntRange>(), findMatchRanges("anything", ""))
    }

    @Test fun `a range always spans exactly the needle length`() {
        val ranges = findMatchRanges("The Meeting notes: meeting at ten", "meeting")
        assertEquals(2, ranges.size)
        ranges.forEach { assertEquals(7, it.last - it.first + 1) }
    }

    // ── buildSnippet ──────────────────────────────────────────────────────────

    @Test fun `a short body is shown whole, with no ellipsis`() {
        val snippet = buildSnippet("short body", findMatchRanges("short body", "body"))
        assertEquals("short body", snippet.text)
        assertEquals(listOf(6 until 10), snippet.highlights)
    }

    @Test fun `the window opens before the first match and the highlight follows it`() {
        val body = "x".repeat(400) + "needle" + "y".repeat(400)
        val snippet = buildSnippet(body, findMatchRanges(body, "needle"))
        // Both ends were cut, so both ellipses are present.
        assertTrue(snippet.text.startsWith("…"))
        assertTrue(snippet.text.endsWith("…"))
        assertEquals(1, snippet.highlights.size)
        val hit = snippet.highlights.single()
        assertEquals("needle", snippet.text.substring(hit.first, hit.last + 1))
    }

    @Test fun `the snippet stays within the display budget`() {
        val body = "z".repeat(5_000)
        val snippet = buildSnippet(body, emptyList())
        // At most the budget plus the two single-character ellipses.
        assertTrue(snippet.text.length <= SNIPPET_MAX_LENGTH + 2)
    }

    @Test fun `a match beyond the window is dropped rather than clamped`() {
        val body = "needle" + "z".repeat(1_000) + "needle"
        val snippet = buildSnippet(body, findMatchRanges(body, "needle"))
        // The second hit is far outside the window; a clamped range would point at "zzz".
        assertEquals(1, snippet.highlights.size)
        val hit = snippet.highlights.single()
        assertEquals("needle", snippet.text.substring(hit.first, hit.last + 1))
    }

    @Test fun `every highlight indexes inside its own snippet`() {
        val body = "alpha beta " + "q".repeat(300) + " beta"
        val snippet = buildSnippet(body, findMatchRanges(body, "beta"))
        snippet.highlights.forEach { range ->
            assertTrue(range.first >= 0)
            assertTrue(range.last < snippet.text.length)
            assertEquals("beta", snippet.text.substring(range.first, range.last + 1))
        }
    }

    @Test fun `an empty body yields an empty snippet`() {
        val snippet = buildSnippet("", emptyList())
        assertEquals("", snippet.text)
        assertEquals(emptyList<IntRange>(), snippet.highlights)
    }

    // ── matchPreview ──────────────────────────────────────────────────────────

    @Test fun `a title-only hit still produces a body snippet, without highlights`() {
        val match = matchPreview(preview(title = "Groceries", content = "milk, eggs"), "groc")
        assertNotNull(match)
        assertEquals(listOf(0 until 4), match!!.titleHighlights)
        assertEquals("milk, eggs", match.snippet)
        assertEquals(emptyList<IntRange>(), match.snippetHighlights)
    }

    @Test fun `a body-only hit leaves the title unhighlighted`() {
        val match = matchPreview(preview(title = "Groceries", content = "milk, eggs"), "eggs")
        assertNotNull(match)
        assertEquals(emptyList<IntRange>(), match!!.titleHighlights)
        assertEquals(listOf(6 until 10), match.snippetHighlights)
    }

    @Test fun `a note that contains the query nowhere does not match`() {
        assertNull(matchPreview(preview(title = "Groceries", content = "milk"), "bicycle"))
    }

    @Test fun `an empty query matches nothing`() {
        assertNull(matchPreview(preview(title = "Groceries", content = "milk"), ""))
    }

    @Test fun `a phrase split across a line break still matches`() {
        // The HTML-to-text conversion turns block markup into newlines, so the rendered phrase
        // "meeting notes" can arrive with a newline in the middle of it.
        val match = matchPreview(preview(content = "meeting\nnotes for friday"), "meeting notes")
        assertNotNull(match)
        assertEquals(listOf(0 until 13), match!!.snippetHighlights)
        assertEquals("meeting notes for friday", match.snippet)
    }

    @Test fun `matching sees text, not markup`() {
        // What reaches these functions is the plain text produced by Note.toUi(): the stored
        // "<p>Buy <b>mi</b>lk</p>" has already become "Buy milk". Both halves of the HTML problem
        // are covered here - the tag name is gone, and the split word is whole.
        val plain = "Buy milk"
        assertNotNull(matchPreview(preview(content = plain), "milk"))
        assertNull(matchPreview(preview(content = plain), "<b>"))
        assertNull(matchPreview(preview(content = plain), "p"))
    }

    // ── searchPreviews ────────────────────────────────────────────────────────

    @Test fun `a blank query returns nothing rather than the whole library`() {
        val notes = listOf(preview(id = "a", title = "One"), preview(id = "b", title = "Two"))
        assertEquals(emptyList<Any>(), searchPreviews(notes, ""))
        assertEquals(emptyList<Any>(), searchPreviews(notes, "   \n "))
    }

    @Test fun `title matches are ordered ahead of body-only matches`() {
        val notes = listOf(
            preview(id = "body", title = "Untitled", content = "about the report"),
            preview(id = "title", title = "Report", content = "nothing else"),
        )
        assertEquals(listOf("title", "body"), searchPreviews(notes, "report").map { it.preview.id })
    }

    @Test fun `input order is preserved inside each group`() {
        val notes = listOf(
            preview(id = "t1", title = "report one"),
            preview(id = "b1", content = "a report"),
            preview(id = "t2", title = "report two"),
            preview(id = "b2", content = "another report"),
        )
        assertEquals(
            listOf("t1", "t2", "b1", "b2"),
            searchPreviews(notes, "report").map { it.preview.id },
        )
    }

    @Test fun `non-matching notes are excluded`() {
        val notes = listOf(
            preview(id = "a", title = "Groceries", content = "milk"),
            preview(id = "b", title = "Trip", content = "flights"),
        )
        assertEquals(listOf("b"), searchPreviews(notes, "FLIGHT").map { it.preview.id })
    }

    @Test fun `a query typed with odd spacing still matches`() {
        val notes = listOf(preview(id = "a", content = "the quick brown fox"))
        assertEquals(1, searchPreviews(notes, "  quick   brown ").size)
    }
}
