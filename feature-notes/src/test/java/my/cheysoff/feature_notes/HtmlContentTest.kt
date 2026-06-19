package my.cheysoff.feature_notes

import my.cheysoff.feature_notes.model.looksLikeHtml
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlContentTest {

    @Test
    fun `editor html is detected as html`() {
        assertTrue("<p>hello</p>".looksLikeHtml())
        assertTrue("<h1>Title</h1>".looksLikeHtml())
        assertTrue("a <b>bold</b> word".looksLikeHtml())
        assertTrue("line<br>break".looksLikeHtml())
    }

    @Test
    fun `plain text is not treated as html`() {
        assertFalse("".looksLikeHtml())
        assertFalse("just some plain text".looksLikeHtml())
        // The classic false-positive: comparison operators with surrounding spaces.
        assertFalse("1 < 2 and 3 > 2".looksLikeHtml())
        // A lone "<" with no closing ">" is not a tag.
        assertFalse("a < b".looksLikeHtml())
    }
}
