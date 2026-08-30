package my.cheysoff.feature_notes

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mohamedrejeb.richeditor.model.RichTextState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Characterisation test for `richeditor-compose`'s HTML round trip.
 *
 * This exists for **sync**, not for the editor. A sync engine decides what to upload by comparing
 * a note's stored bytes against the bytes the editor now produces. If `setHtml(x).toHtml()` is not
 * a *fixed point* — if feeding the editor its own previous output produces different bytes — then
 * every note goes dirty the moment it is opened, and after a library upgrade every note on every
 * device goes dirty at once and stampedes the server.
 *
 * Two different properties are tested and they are NOT equally important:
 *
 *  - **Byte-identity on hand-written HTML** (`toHtml(setHtml(x)) == x` for arbitrary `x`) is *not*
 *    required and is *not* asserted. The library normalises; that is expected and harmless,
 *    because the app never stores hand-written HTML.
 *  - **Idempotence after one pass** (`f(f(x)) == f(x)`) is the property that actually matters,
 *    because the app only ever stores `toHtml()` output. It IS asserted, per case.
 *
 * The test deliberately asserts nothing about the *shape* of the normalised output — that would
 * make it a change-detector for the library's formatting. It asserts only the fixed-point property
 * that sync depends on. `dumpRoundTrips` logs the raw strings under the tag `RteRoundTrip` so the
 * actual serialisation can be inspected after a version bump.
 *
 * Re-run this after every `richeditor` version change in `gradle/libs.versions.toml`.
 * Recorded result for **1.0.0-rc14**: all cases below are idempotent after one pass; several are
 * not byte-identical on the first pass.
 */
@RunWith(AndroidJUnit4::class)
class RichEditorHtmlRoundTripTest {

    /**
     * Mirrors `SingleNoteScreen`: the real editor sets `config.listIndent = 18` before `setHtml`,
     * and that value is serialised back out into list markup, so a round trip performed without it
     * would not be the round trip the app performs.
     */
    private fun roundTrip(html: String): String {
        val state = RichTextState()
        state.config.listIndent = LIST_INDENT
        state.setHtml(html)
        return state.toHtml()
    }

    @Test
    fun oneRoundTripIsAFixedPoint() {
        val unstable = mutableListOf<String>()
        for ((name, input) in CASES) {
            val once = roundTrip(input)
            val twice = roundTrip(once)
            if (once != twice) unstable += "$name: <$once> -> <$twice>"
        }
        assertEquals(
            "toHtml(setHtml(x)) is not idempotent for these cases, so opening an unchanged note " +
                "would mark it dirty forever",
            emptyList<String>(),
            unstable,
        )
    }

    @Test
    fun threeRoundTripsAgreeWithTwo() {
        // Guards against a two-cycle (f(f(x)) != f(x) but f(f(f(x))) == f(x)), which would look
        // stable to the test above only by coincidence of where the corpus starts.
        for ((name, input) in CASES) {
            val two = roundTrip(roundTrip(input))
            val three = roundTrip(two)
            assertEquals("case '$name' oscillates between serialisations", two, three)
        }
    }

    /**
     * Stored notes written by **1.0.0-rc14** must still read back correctly under whatever version
     * is on the classpath now. rc14 escaped far more aggressively than later versions do — every
     * `.` became `&period;`, every `,` became `&comma;`, and every Cyrillic letter became a named
     * entity such as `&Pcy;` — so an upgrade that stopped *emitting* those entities but also
     * stopped *understanding* them would silently mangle every existing note.
     *
     * The inputs below are literal `toHtml()` output captured from 1.0.0-rc14 on an API 33
     * emulator. The assertion is on the recovered plain text, not on the HTML, because the HTML is
     * expected to change — that is the whole point.
     */
    @Test
    fun rc14StoredHtmlStillDecodes() {
        for ((rc14Html, expectedText) in RC14_OUTPUT) {
            val state = RichTextState()
            state.config.listIndent = LIST_INDENT
            state.setHtml(rc14Html)
            assertEquals(
                "a note stored by richeditor 1.0.0-rc14 no longer decodes to its own text",
                expectedText,
                state.toText(),
            )
        }
    }

    /** Not an assertion — this is how the raw serialisation is read out of a device. */
    @Test
    fun dumpRoundTrips() {
        for ((rc14Html, _) in RC14_OUTPUT) {
            val state = RichTextState()
            state.config.listIndent = LIST_INDENT
            state.setHtml(rc14Html)
            Log.i(TAG, "RC14IN  |$rc14Html|")
            Log.i(TAG, "RC14TXT |${state.toText()}|")
            Log.i(TAG, "RC14OUT |${state.toHtml()}|")
        }
        for ((name, input) in CASES) {
            val once = roundTrip(input)
            val twice = roundTrip(once)
            Log.i(TAG, "CASE $name")
            Log.i(TAG, "  IN   |$input|")
            Log.i(TAG, "  OUT1 |$once|")
            Log.i(TAG, "  OUT2 |$twice|")
            Log.i(
                TAG,
                "  identical=${once == input} idempotent=${once == twice}",
            )
        }
    }

    private companion object {
        const val TAG = "RteRoundTrip"

        /** The value `SingleNoteScreen` assigns to `richTextState.config.listIndent`. */
        const val LIST_INDENT = 18

        /**
         * Representative note bodies. Written as the *stored* string would look: some are
         * hand-written HTML (what a naive test would use), some are real `toHtml()` output shapes.
         */
        val CASES: List<Pair<String, String>> = listOf(
            "empty" to "",
            "plain-paragraph" to "<p>Hello world</p>",
            "two-paragraphs" to "<p>First.</p><p>Second.</p>",
            "bold-b" to "<p><b>bold</b></p>",
            "bold-strong" to "<p><strong>bold</strong></p>",
            "italic-i" to "<p><i>italic</i></p>",
            "italic-em" to "<p><em>italic</em></p>",
            "underline" to "<p><u>underlined</u></p>",
            "h1" to "<h1>Heading one</h1>",
            "h2" to "<h2>Heading two</h2>",
            "h3" to "<h3>Heading three</h3>",
            "heading-then-body" to "<h2>Title</h2><p>Body text.</p>",
            "unordered-list" to "<ul><li>alpha</li><li>beta</li></ul>",
            "ordered-list" to "<ol><li>one</li><li>two</li></ol>",
            "list-then-paragraph" to "<ul><li>item</li></ul><p>after</p>",
            "nested-formatting" to "<p><b>bold and <i>also italic</i></b> then plain</p>",
            "triple-nested" to "<p><b><i><u>all three</u></i></b></p>",
            "cyrillic" to "<p>Привет, мир — это заметка.</p>",
            "cyrillic-bold" to "<p><b>Заголовок</b> и обычный текст</p>",
            "mixed-scripts" to "<p>ASCII, Кириллица, 日本語, emoji 🚀</p>",
            "entities-escaped" to "<p>a &lt; b &amp; c &gt; d</p>",
            "entities-raw-ampersand" to "<p>Tom &amp; Jerry</p>",
            "quote-entities" to "<p>He said &quot;hi&quot; &amp; left</p>",
            "line-break" to "<p>first<br>second</p>",
            "span-style" to "<p><span style=\"font-weight: bold;\">styled</span></p>",
            "long-paragraph" to "<p>${"word ".repeat(200).trim()}</p>",
            "whitespace-runs" to "<p>a    b\tc</p>",
            "leading-trailing-space" to "<p> padded </p>",
        )

        /**
         * `toHtml()` output captured verbatim from **1.0.0-rc14** on an API 33 emulator, paired
         * with the plain text it must still decode to. Do not "tidy" these strings — they are
         * evidence, not fixtures.
         */
        val RC14_OUTPUT: List<Pair<String, String>> = listOf(
            "<p>First&period;</p><p>Second&period;</p>" to "First.\nSecond.",
            "<p>&Pcy;&rcy;&icy;&vcy;&iecy;&tcy;&comma; &mcy;&icy;&rcy; &mdash; " +
                "&ecy;&tcy;&ocy; &zcy;&acy;&mcy;&iecy;&tcy;&kcy;&acy;&period;</p>"
                to "Привет, мир — это заметка.",
            "<p><b>&Zcy;&acy;&gcy;&ocy;&lcy;&ocy;&vcy;&ocy;&kcy;</b> &icy; " +
                "&ocy;&bcy;&ycy;&chcy;&ncy;&ycy;&jcy; &tcy;&iecy;&kcy;&scy;&tcy;</p>"
                to "Заголовок и обычный текст",
            "<p>He said &quot;hi&quot; &amp; left</p>" to "He said \"hi\" & left",
            "<p>ASCII&comma; &Kcy;&icy;&rcy;&icy;&lcy;&lcy;&icy;&tscy;&acy;&comma; " +
                "日本語&comma; emoji 🚀</p>"
                to "ASCII, Кириллица, 日本語, emoji 🚀",
        )
    }
}
