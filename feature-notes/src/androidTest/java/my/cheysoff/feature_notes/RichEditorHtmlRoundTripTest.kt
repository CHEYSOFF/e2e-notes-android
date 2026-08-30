package my.cheysoff.feature_notes

import android.util.Log
import androidx.compose.ui.text.font.FontWeight
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mohamedrejeb.richeditor.model.RichTextState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
 * that sync depends on, plus the cross-version compatibility properties covered by
 * [rc14StoredHtmlStillDecodes], [v110StoredHtmlStillDecodes], [rc14StoredHtmlKeepsItsBoldRun] and
 * [rc14StoredHtmlKeepsItsBlockStructure]. `dumpRoundTrips` logs the raw strings under the tag
 * `RteRoundTrip` so the actual serialisation can be inspected after a version bump.
 *
 * Re-run this after every `richeditor` version change in `gradle/libs.versions.toml`.
 *
 * Recorded results, both measured on the same API 33 x86_64 emulator:
 *  - **1.0.0-rc14**: all 30 cases idempotent after one pass; 17 of 30 byte-identical on the first
 *    pass. rc14 escapes text through `KsoupEntities.encodeHtml`, which emits a named entity for
 *    nearly every punctuation mark and every non-ASCII letter — `.` becomes `&period;`, `@`
 *    becomes `&commat;`, and each Cyrillic letter becomes something like `&Pcy;`.
 *  - **1.1.0**: all 30 cases idempotent after one pass; 21 of 30 byte-identical. 1.1.0 replaced
 *    that call with a private `encodeHtmlText` that escapes only `&`, `<` and `>`, so text is
 *    stored literally. Both versions still *decode* through `KsoupEntities.decodeHtml`, which is
 *    why each version reads the other version's output without losing a character.
 */
@RunWith(AndroidJUnit4::class)
class RichEditorHtmlRoundTripTest {

    /**
     * Mirrors `SingleNoteScreen`: the real editor sets `config.listIndent = 18` before `setHtml`,
     * and that value is serialised back out into list markup, so a round trip performed without it
     * would not be the round trip the app performs.
     */
    private fun roundTrip(html: String): String = decode(html).toHtml()

    /** Builds the state the app would be in immediately after opening a note whose body is [html]. */
    private fun decode(html: String): RichTextState {
        val state = RichTextState()
        state.config.listIndent = LIST_INDENT
        state.setHtml(html)
        return state
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
     * `.` became `&period;`, every `,` became `&comma;`, every `@` became `&commat;`, and every
     * Cyrillic letter became a named entity such as `&Pcy;` — so an upgrade that stopped
     * *emitting* those entities but also stopped *understanding* them would silently mangle every
     * existing note.
     *
     * Every HTML row in a real database was written by the pinned version, so this is the decisive
     * safety question for an upgrade rather than a corner case. (Rows recorded as PLAIN in the
     * `contentFormat` column never go through `setHtml` at all — see `SingleNoteScreen.kt:254` —
     * so they are not at issue here.)
     *
     * The inputs below are literal `toHtml()` output captured from 1.0.0-rc14 on an API 33
     * emulator. The assertion is on the recovered plain text, not on the HTML, because the HTML is
     * expected to change — that is the whole point.
     */
    @Test
    fun rc14StoredHtmlStillDecodes() {
        for ((rc14Html, expectedText) in RC14_OUTPUT) {
            assertEquals(
                "a note stored by richeditor 1.0.0-rc14 no longer decodes to its own text",
                expectedText,
                decode(rc14Html).toText(),
            )
        }
    }

    /**
     * The mirror image of [rc14StoredHtmlStillDecodes], and the reason the upgrade is not one-way:
     * notes written by **1.1.0** hold literal Cyrillic, a literal `"` and a literal `.`, and rc14
     * must still read them if the version pin is ever reverted. Reverting a dependency is a normal
     * thing to want to do; an upgrade that quietly made it destructive would be a far larger
     * commitment than a one-line version bump looks like.
     *
     * The inputs are literal `toHtml()` output captured from 1.1.0 on the same emulator, for the
     * same seven notes as [RC14_OUTPUT] and paired with the same expected text — the two encodings
     * of one note must decode to the same characters under either version.
     */
    @Test
    fun v110StoredHtmlStillDecodes() {
        for ((v110Html, expectedText) in V110_OUTPUT) {
            assertEquals(
                "a note stored by richeditor 1.1.0 no longer decodes to its own text",
                expectedText,
                decode(v110Html).toText(),
            )
        }
    }

    /**
     * Text alone is not enough: a legacy note must come back with its *formatting* too. This
     * checks the inline half by reading the bold run straight out of `annotatedString`, which is
     * also what the editor's word count and the share/copy export read (`SingleNoteScreen.kt:358`
     * and `:700`).
     *
     * The fixture is the bold-Cyrillic note and deliberately not the heading one: a heading also
     * carries `FontWeight.Bold` in its span style, so a heading here would let the assertion pass
     * for the wrong reason. Zero-length ranges are dropped because the library emits a collapsed
     * span at offset 0 to carry the caret's pending style, and that is not a formatting run.
     */
    @Test
    fun rc14StoredHtmlKeepsItsBoldRun() {
        val state = decode(RC14_BOLD_CYRILLIC)
        val text = state.annotatedString.text
        val boldRuns = state.annotatedString.spanStyles
            .filter { it.item.fontWeight == FontWeight.Bold && it.end > it.start }
            .map { text.substring(it.start, it.end) }
        assertEquals(
            "the bold run in a note stored by rc14 no longer covers the same characters",
            listOf(BOLD_WORD),
            boldRuns,
        )
    }

    /**
     * The block half of the same question: a legacy note's heading, bold run and bullet list must
     * survive the decode as *structure*, not merely as characters.
     *
     * Asserted on tag names in the re-encoded HTML rather than on the whole string, because the
     * whole string legitimately differs between versions — that difference is what
     * [rc14StoredHtmlStillDecodes] exists to bound — while `<h1>`, `<b>`, `<ul>` and `<li>` are
     * emitted identically by rc14 and 1.1.0. This is the property that matters for the pin's
     * stated reason: rc14 was pinned for native `<h1>`/`<h2>`/`<h3>` support, so a version that
     * decoded a stored `<h1>` into a plain paragraph would be disqualifying no matter what it did
     * to entities.
     */
    @Test
    fun rc14StoredHtmlKeepsItsBlockStructure() {
        val html = decode(RC14_RUSSIAN_NOTE).toHtml()
        assertTrue("heading lost when re-encoding an rc14 note: $html", html.startsWith("<h1>"))
        assertTrue("bold run lost when re-encoding an rc14 note: $html", html.contains("<b>"))
        assertTrue("bullet list lost when re-encoding an rc14 note: $html", html.contains("<ul>"))
        assertEquals(
            "wrong number of list items after re-encoding an rc14 note: $html",
            2,
            Regex("<li[ >]").findAll(html).count(),
        )
    }

    /** Not an assertion — this is how the raw serialisation is read out of a device. */
    @Test
    fun dumpRoundTrips() {
        for ((rc14Html, _) in RC14_OUTPUT) {
            val state = decode(rc14Html)
            Log.i(TAG, "RC14IN  |$rc14Html|")
            Log.i(TAG, "RC14TXT |${state.toText()}|")
            Log.i(TAG, "RC14OUT |${state.toHtml()}|")
        }
        for ((name, input) in CASES) {
            val once = roundTrip(input)
            val twice = roundTrip(once)
            Log.i(TAG, "CASE $name")
            Log.i(TAG, "  TXT  |${decode(once).toText()}|")
            Log.i(TAG, "  IN   |$input|")
            Log.i(TAG, "  OUT1 |$once|")
            Log.i(TAG, "  OUT2 |$twice|")
            // UTF-8 byte length, not character length: SQLCipher stores the content column as
            // UTF-8, so this is the number that actually costs database space — and, once sync
            // exists, request payload size and the padding buckets on top of it.
            Log.i(
                TAG,
                "  identical=${once == input} idempotent=${once == twice} " +
                    "bytes=${once.toByteArray(Charsets.UTF_8).size}",
            )
        }
    }

    private companion object {
        const val TAG = "RteRoundTrip"

        /** The value `SingleNoteScreen` assigns to `richTextState.config.listIndent`. */
        const val LIST_INDENT = 18

        /**
         * A realistic Russian note — heading, bold run, bullet list — in the literal, hand-written
         * form. The storage measurement quoted in the upgrade PR is taken from this note.
         */
        const val RUSSIAN_NOTE_SOURCE =
            "<h1>Планы на неделю</h1>" +
                "<p>Купить <b>молоко</b> и хлеб.</p>" +
                "<ul><li>Позвонить маме</li>" +
                "<li>Оплатить счёт</li></ul>"

        /**
         * The plain text every encoding of [RUSSIAN_NOTE_SOURCE] must decode back to. The "• "
         * prefixes are the library's own rendering of `<li>` into `toText()`; both versions emit
         * them.
         */
        const val RUSSIAN_NOTE_TEXT =
            "Планы на неделю\n" +
                "Купить молоко и хлеб.\n" +
                "• Позвонить маме\n" +
                "• Оплатить счёт"

        /** rc14's serialisation of [RUSSIAN_NOTE_SOURCE], captured verbatim from the emulator. */
        const val RC14_RUSSIAN_NOTE =
            "<h1>&Pcy;&lcy;&acy;&ncy;&ycy; &ncy;&acy; &ncy;&iecy;&dcy;&iecy;&lcy;&yucy;</h1>" +
                "<p>&Kcy;&ucy;&pcy;&icy;&tcy;&softcy; <b>&mcy;&ocy;&lcy;&ocy;&kcy;&ocy;</b> " +
                "&icy; &khcy;&lcy;&iecy;&bcy;&period;</p>" +
                "<ul><li>&Pcy;&ocy;&zcy;&vcy;&ocy;&ncy;&icy;&tcy;&softcy; &mcy;&acy;&mcy;&iecy;</li>" +
                "<li>&Ocy;&pcy;&lcy;&acy;&tcy;&icy;&tcy;&softcy; &scy;&chcy;&iocy;&tcy;</li></ul>"

        /** rc14's serialisation of the bold-Cyrillic note, captured verbatim from the emulator. */
        const val RC14_BOLD_CYRILLIC =
            "<p><b>&Zcy;&acy;&gcy;&ocy;&lcy;&ocy;&vcy;&ocy;&kcy;</b> &icy; " +
                "&ocy;&bcy;&ycy;&chcy;&ncy;&ycy;&jcy; &tcy;&iecy;&kcy;&scy;&tcy;</p>"

        /** The one word that [RC14_BOLD_CYRILLIC] marks bold. */
        const val BOLD_WORD = "Заголовок"

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
            // The exact shape of one of the app's seeded notes. Angle brackets around an address
            // are the case that has broken before: if `<john@example.com>` were ever emitted
            // unescaped, the next `setHtml` would parse it as a tag and drop the address.
            "angle-brackets-email" to "<p>Email John &lt;john@example.com&gt; about Q3</p>",
            "russian-note" to RUSSIAN_NOTE_SOURCE,
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
            RC14_BOLD_CYRILLIC to "Заголовок и обычный текст",
            "<p>He said &quot;hi&quot; &amp; left</p>" to "He said \"hi\" & left",
            "<p>ASCII&comma; &Kcy;&icy;&rcy;&icy;&lcy;&lcy;&icy;&tscy;&acy;&comma; " +
                "日本語&comma; emoji 🚀</p>"
                to "ASCII, Кириллица, 日本語, emoji 🚀",
            "<p>Email John &lt;john&commat;example&period;com&gt; about Q3</p>"
                to "Email John <john@example.com> about Q3",
            RC14_RUSSIAN_NOTE to RUSSIAN_NOTE_TEXT,
        )

        /**
         * `toHtml()` output captured verbatim from **1.1.0** on the same emulator, for the same
         * seven notes, paired with the same expected text. Feeding these to rc14 is the
         * reversibility check: it answers "if the pin is reverted, can the old version still read
         * what the new one wrote?".
         */
        val V110_OUTPUT: List<Pair<String, String>> = listOf(
            "<p>First.</p><p>Second.</p>" to "First.\nSecond.",
            "<p>Привет, мир — это заметка.</p>" to "Привет, мир — это заметка.",
            "<p><b>Заголовок</b> и обычный текст</p>" to "Заголовок и обычный текст",
            "<p>He said \"hi\" &amp; left</p>" to "He said \"hi\" & left",
            "<p>ASCII, Кириллица, 日本語, emoji 🚀</p>" to "ASCII, Кириллица, 日本語, emoji 🚀",
            "<p>Email John &lt;john@example.com&gt; about Q3</p>"
                to "Email John <john@example.com> about Q3",
            RUSSIAN_NOTE_SOURCE to RUSSIAN_NOTE_TEXT,
        )
    }
}
