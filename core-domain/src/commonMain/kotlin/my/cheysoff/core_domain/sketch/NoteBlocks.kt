package my.cheysoff.core_domain.sketch

import my.cheysoff.core_domain.model.NoteContentFormat

/**
 * Counts a note's top-level blocks, and clamps a sketch's anchor into that range.
 *
 * A sketch stores *where* it sits as an index over the note's top-level blocks rather than as a
 * marker inside the note's own text: a build without sketch support then reads and edits the note
 * normally, because it never holds a reference it could damage. The trade is that such a build's
 * edits shift the blocks this counts, so a drawing can end up rendering a paragraph away from where
 * it was put — misplaced, never lost.
 *
 * Both platforms must count identically. If they do not, the same note shows the drawing in two
 * different places on two devices, and no test on either platform alone would catch it.
 */
object NoteBlocks {

    /**
     * HTML tags this treats as a block boundary, matching the shapes `richeditor-compose`'s
     * `toHtml()` actually emits: `<p>`, headings, lists (as a whole, not per `<li>`), block quotes
     * and preformatted text.
     *
     * Changing this set moves the anchor of every sketch already saved against an existing note,
     * because the block count a sketch's index was chosen against changes retroactively for notes
     * that used to sit past the changed point. It must change on both platforms in the same
     * release, or their counts disagree and two devices that used to agree start showing a note's
     * sketch in different places.
     */
    private val BLOCK_TAGS = setOf(
        "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "blockquote", "pre",
    )

    /**
     * HTML elements that never have a matching closing tag. Left out of the nesting stack so a
     * `<br>` inside a `<p>` (`richeditor`'s own line-break shape) does not desynchronise the depth
     * count from the closing `</p>` that follows it.
     */
    private val VOID_TAGS = setOf("br", "hr", "img")

    /**
     * The number of top-level blocks in [content]. Never throws: a body this cannot make sense of
     * costs a sketch its position, never its existence, so parse difficulty returns whatever count
     * was reached rather than propagating a failure.
     */
    fun count(content: String, format: NoteContentFormat): Int = when (format) {
        NoteContentFormat.PLAIN -> countPlainBlocks(content)
        NoteContentFormat.HTML -> countHtmlBlocks(content)
    }

    /** Coerces [anchor] into `0..blockCount`, so a stale or corrupt anchor still points somewhere. */
    fun clamp(anchor: Int, blockCount: Int): Int = anchor.coerceIn(0, blockCount)

    /** A plain-text block is a non-empty line. */
    private fun countPlainBlocks(content: String): Int =
        content.lineSequence().count { it.isNotEmpty() }

    /**
     * Scans [html] by hand for opening tags in [BLOCK_TAGS] that sit at nesting depth zero, so a
     * `<li>` inside a `<ul>` does not count on top of the `<ul>` itself, and a block tag nested
     * inside another one (malformed, but not impossible in a corrupt body) is not double-counted.
     *
     * This is a scan, not a parser: it tracks only enough nesting to answer "is this the outermost
     * element right now", and never raises on a body it cannot make full sense of.
     */
    private fun countHtmlBlocks(html: String): Int {
        var count = 0
        try {
            var depth = 0
            var i = 0
            val length = html.length
            while (i < length) {
                if (html[i] != '<') {
                    i++
                    continue
                }
                val close = html.indexOf('>', i + 1)
                if (close < 0) break // An unterminated tag: nothing more can be read reliably.

                val inner = html.substring(i + 1, close).trim()
                val isClosing = inner.startsWith("/")
                val nameSource = if (isClosing) inner.substring(1) else inner
                val name = leadingTagName(nameSource)
                if (name.isNotEmpty()) {
                    val isSelfClosing = inner.endsWith("/")
                    when {
                        isClosing -> if (depth > 0) depth--
                        name in VOID_TAGS || isSelfClosing -> {
                            if (depth == 0 && name in BLOCK_TAGS) count++
                        }
                        else -> {
                            if (depth == 0 && name in BLOCK_TAGS) count++
                            depth++
                        }
                    }
                }
                i = close + 1
            }
        } catch (e: Exception) {
            // Defensive only: the loop above is bounds-safe by construction. If something
            // unforeseen still goes wrong, the count accumulated so far is returned rather than an
            // exception propagating out of what is supposed to be a best-effort scan.
        }
        return count
    }

    /** The lowercase run of letters/digits at the start of [raw] -- an element's tag name. */
    private fun leadingTagName(raw: String): String {
        val trimmed = raw.trimStart()
        var end = 0
        while (end < trimmed.length && trimmed[end].isLetterOrDigit()) end++
        return trimmed.substring(0, end).lowercase()
    }
}
