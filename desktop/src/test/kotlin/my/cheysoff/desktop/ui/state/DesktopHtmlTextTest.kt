package my.cheysoff.desktop.ui.state

import my.cheysoff.core_domain.model.NoteContentFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopHtmlTextTest {

    @Test
    fun `block tags become line breaks so words do not run together`() {
        assertEquals(
            "Milk\neggs\ncoffee",
            htmlToPlainText("<ul><li>Milk</li><li>eggs</li><li>coffee</li></ul>"),
        )
    }

    @Test
    fun `inline markup does not split a word`() {
        // The whole reason search matches plain text: the rendered word is "hello", and a
        // substring search over the stored bytes would never find it.
        assertEquals("hello", htmlToPlainText("he<b>llo</b>"))
    }

    @Test
    fun `rc14 entity escaping round-trips back to the characters it replaced`() {
        // What richeditor 1.0.0-rc14 wrote for "Milk, eggs, coffee." — bodies in this shape are
        // still on disk and the desktop must read them.
        val stored = "<p>Milk&comma; eggs&comma; coffee&period;</p>"
        assertEquals("Milk, eggs, coffee.", htmlToPlainText(stored))
    }

    @Test
    fun `rc14 cyrillic entities decode to cyrillic`() {
        // &Pcy;&rcy;&icy;&vcy;&iecy; is "Приве" — the case a hand-written entity table would miss.
        assertEquals("Привет", htmlToPlainText("<p>&Pcy;&rcy;&icy;&vcy;&iecy;&tcy;</p>"))
    }

    @Test
    fun `escaped angle brackets survive as literal characters`() {
        assertEquals("if a<b> then", htmlToPlainText("<p>if a&lt;b&gt; then</p>"))
    }

    @Test
    fun `a plain note is never parsed`() {
        // The exact shape that used to be silently truncated on Android by format sniffing.
        val plain = "guest network <ask reception> — rotates monthly"
        assertEquals(plain, noteBodyAsPlainText(plain, NoteContentFormat.PLAIN))
    }

    @Test
    fun `an unterminated angle bracket does not swallow the rest of the note`() {
        assertEquals("keep 3 < 4 and this", htmlToPlainText("keep 3 < 4 and this"))
    }
}
