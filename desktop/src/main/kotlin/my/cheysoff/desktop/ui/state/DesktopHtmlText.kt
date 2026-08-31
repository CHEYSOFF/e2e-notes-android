package my.cheysoff.desktop.ui.state

import com.mohamedrejeb.ksoup.entities.KsoupEntities
import my.cheysoff.core_domain.model.NoteContentFormat

/**
 * Turns a stored note body into the plain text the list card and the search index see.
 *
 * The Android app does this with `HtmlCompat.fromHtml`, which does not exist off Android. Rather
 * than reach for a general HTML parser, this does the two things the app's own editor output
 * actually needs, and nothing else:
 *
 *  1. tags are removed, with the block-level ones turned into a line break so paragraphs and list
 *     items do not run together into one word ("milkeggscoffee");
 *  2. character references are decoded with [KsoupEntities], the *same* codec richeditor used to
 *     write them.
 *
 * Point 2 is the load-bearing one. Bodies written by richeditor 1.0.0-rc14 ran through
 * `KsoupEntities.encodeHtml`, which emits a named entity for nearly every punctuation mark and
 * every non-ASCII letter — "." is stored as `&period;`, a Cyrillic "р" as `&rcy;`. A hand-written
 * entity table would decode the handful of names someone thought of and leave a Russian note
 * looking like `&Pcy;&rcy;&icy;&vcy;&iecy;&tcy;`. Decoding with the encoder's own tables cannot
 * drift from what is on disk.
 *
 * Order matters: tags are stripped BEFORE entities are decoded. The other way round, a note whose
 * literal text is "if a<b> then" (stored as `if a&lt;b&gt; then`) would decode into something the
 * stripper then eats. Decoding last means an escaped angle bracket stays text.
 */
fun htmlToPlainText(html: String): String {
    val stripped = stripTags(html)
    return KsoupEntities.decodeHtml(stripped).trim()
}

/** [htmlToPlainText] for HTML rows; a verbatim trim for PLAIN ones, matching `Note.toUi()`. */
fun noteBodyAsPlainText(content: String, format: NoteContentFormat): String =
    if (format == NoteContentFormat.HTML) htmlToPlainText(content) else content.trim()

/**
 * Tags whose closing (or self-closing) form ends a line of rendered text. Everything else — <b>,
 * <i>, <span>, <a> — is inline and is simply dropped, so a word split by inline markup
 * (`he<b>llo</b>`) comes back out as one searchable word.
 */
private val blockTags = setOf(
    "p", "div", "br", "li", "ul", "ol", "h1", "h2", "h3", "h4", "h5", "h6",
    "blockquote", "pre", "tr", "table", "hr",
)

private fun stripTags(html: String): String {
    val out = StringBuilder(html.length)
    var i = 0
    while (i < html.length) {
        val ch = html[i]
        if (ch != '<') {
            out.append(ch)
            i++
            continue
        }
        val close = html.indexOf('>', i + 1)
        if (close < 0) {
            // An unterminated "<" is not markup — it is a literal character in a body that was
            // never HTML-escaped. Keep it rather than swallowing the rest of the note.
            out.append(html, i, html.length)
            break
        }
        val name = tagName(html, i + 1, close)
        if (name in blockTags && out.isNotEmpty() && out.last() != '\n') out.append('\n')
        i = close + 1
    }
    return out.toString()
}

/** The lowercase element name inside `<...>`, ignoring a leading "/" and any attributes. */
private fun tagName(html: String, from: Int, to: Int): String {
    var start = from
    if (start < to && html[start] == '/') start++
    var end = start
    while (end < to && !html[end].isWhitespace() && html[end] != '/') end++
    return html.substring(start, end).lowercase()
}
