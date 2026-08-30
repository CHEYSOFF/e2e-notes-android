package my.cheysoff.core_data

import my.cheysoff.core_data.data.local.looksLikeEditorHtml
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the one-time v4 -> v5 backfill classifier. Every false positive here is a note the
 * migration would mislabel as HTML, which the editor would then silently truncate — so the
 * plain-text cases are the ones that matter most.
 */
class LegacyContentFormatTest {

    @Test
    fun `editor output is recognised as html`() {
        assertTrue(looksLikeEditorHtml("<p>hello</p>"))
        assertTrue(looksLikeEditorHtml("<h1>Title</h1><p>body</p>"))
        assertTrue(looksLikeEditorHtml("<ul><li>one</li></ul>"))
        assertTrue(looksLikeEditorHtml("<ol><li>one</li></ol>"))
        assertTrue(looksLikeEditorHtml("<blockquote>quoted</blockquote>"))
        assertTrue(looksLikeEditorHtml("<br>"))
        // Attributes on the opening tag (the editor emits inline styles).
        assertTrue(looksLikeEditorHtml("""<p style="text-align:center;">centered</p>"""))
        // Tag casing is not guaranteed.
        assertTrue(looksLikeEditorHtml("<P>hello</P>"))
        // Leading whitespace/newlines must not defeat the anchor.
        assertTrue(looksLikeEditorHtml("\n  <p>hello</p>"))
    }

    @Test
    fun `prose containing angle brackets is not html`() {
        // The exact strings that caused the data-loss bug.
        assertFalse(looksLikeEditorHtml("Email John <john@example.com> about Q3"))
        assertFalse(looksLikeEditorHtml("TODO <see attached spec> before Friday"))
        assertFalse(looksLikeEditorHtml("if a<b> then"))
        // The cases the old heuristic did handle, which must keep working.
        assertFalse(looksLikeEditorHtml(""))
        assertFalse(looksLikeEditorHtml("just some plain text"))
        assertFalse(looksLikeEditorHtml("1 < 2 and 3 > 2"))
        assertFalse(looksLikeEditorHtml("a < b"))
    }

    @Test
    fun `angle bracket at the very start is still not enough`() {
        // Anchored, but the tag name must be one the editor actually emits.
        assertFalse(looksLikeEditorHtml("<john@example.com> owes me a reply"))
        assertFalse(looksLikeEditorHtml("<see attached spec>"))
        assertFalse(looksLikeEditorHtml("<3 you"))
        assertFalse(looksLikeEditorHtml("<-- start here"))
        assertFalse(looksLikeEditorHtml("<>"))
        assertFalse(looksLikeEditorHtml("<"))
        // A closing tag is not a valid document start.
        assertFalse(looksLikeEditorHtml("</p> stray"))
        // "p" is whitelisted, but "please" is not — the name has to terminate the tag.
        assertFalse(looksLikeEditorHtml("<please review> the draft"))
    }

    /**
     * Regression: the tag-name scan can run to the end of the string, leaving no delimiter to
     * read. This threw StringIndexOutOfBoundsException, and because the classifier runs inside
     * MIGRATION_4_5 the throw rolled the migration back on every launch — the notes stayed on
     * disk but the app could never open the database again. Only PLAIN rows can have this shape
     * (real markup always contains a ">"), i.e. exactly the rows the migration walks.
     */
    @Test
    fun `an unterminated tag name does not throw and is not html`() {
        assertFalse(looksLikeEditorHtml("<div"))
        assertFalse(looksLikeEditorHtml("<pre"))
        assertFalse(looksLikeEditorHtml("<h1"))
        assertFalse(looksLikeEditorHtml("<33"))
        assertFalse(looksLikeEditorHtml("\n  <html"))
        assertFalse(looksLikeEditorHtml("<p"))
    }

    /** Tags the editor cannot lead its output with are off the whitelist, so prose may use them. */
    @Test
    fun `wrapper and nested-only tags are not treated as a document start`() {
        assertFalse(looksLikeEditorHtml("<html> is the root element"))
        assertFalse(looksLikeEditorHtml("<body> language matters"))
        assertFalse(looksLikeEditorHtml("<li> must live in a <ul>"))
    }

    /**
     * Pins the KNOWN residual false positive rather than pretending it does not exist: prose that
     * literally opens with a whitelisted tag is still read as HTML. Anchoring shrinks this surface
     * to notes that begin by naming a tag; it does not remove it. If the classifier is ever
     * tightened further, these assertions are the ones that should flip.
     */
    @Test
    fun `prose that opens with a whitelisted tag is a known false positive`() {
        assertTrue(looksLikeEditorHtml("<p> is the paragraph tag"))
        assertTrue(looksLikeEditorHtml("<br> is a line break"))
    }
}
