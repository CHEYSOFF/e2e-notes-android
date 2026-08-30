package my.cheysoff.core_data.data.local

/**
 * One-time classifier used ONLY by MIGRATION_4_5 to backfill `notes.contentFormat` for rows that
 * predate the column. After that pass the app never guesses a note's format again.
 *
 * The old runtime heuristic ("contains anything shaped like a tag") is deliberately NOT reused
 * here: it matched `<john@example.com>`, `<see attached spec>` and `a<b>` inside ordinary prose,
 * which is precisely the data-loss bug this column exists to kill. Repeating it over the whole
 * table would bake that false-positive rate permanently into the user's data.
 *
 * Instead this is ANCHORED and WHITELISTED: content counts as editor HTML only when, after
 * leading whitespace, it *begins* with a tag whose name is one the rich-text editor actually
 * emits. `RichTextState.toHtml()` always opens with a block element, so every genuinely-HTML row
 * passes, while prose that merely mentions an angle-bracketed word mid-sentence does not — a
 * legacy note would have to literally start with "<p>" or "<h1>" to be misread.
 *
 * The residual error is therefore a false NEGATIVE: an HTML note classified as plain shows its
 * raw markup. That is ugly, immediately obvious, and losslessly recoverable — the opposite of the
 * silent, permanent truncation a false positive causes. Given the asymmetry, biasing every
 * uncertain row toward `plain` is the only defensible default for a table holding real notes.
 */
internal fun looksLikeEditorHtml(content: String): Boolean {
    val trimmed = content.trimStart()
    if (trimmed.length < 3 || trimmed[0] != '<') return false

    // Read the tag name: letters/digits immediately after "<". A closing tag ("</p>") is not a
    // valid document start, so no "/" is skipped here.
    var end = 1
    while (end < trimmed.length && trimmed[end].isLetterOrDigit()) end++
    if (end == 1) return false

    // The name must actually terminate the tag; "<see attached spec>" must not read as tag "see"
    // with attributes. Only ">", "/" (self-closing) or whitespace-then-attributes qualify, and
    // the name itself still has to be on the whitelist, which "see" is not.
    val delimiter = trimmed[end]
    if (delimiter != '>' && delimiter != '/' && !delimiter.isWhitespace()) return false

    return trimmed.substring(1, end).lowercase() in EDITOR_BLOCK_TAGS
}

// Block-level elements RichTextState.toHtml() can start its output with. Kept intentionally
// narrow: adding inline tags here would widen the false-positive surface for no real gain, since
// the editor never emits a bare inline element as the first thing in a document.
private val EDITOR_BLOCK_TAGS = setOf(
    "p", "div", "h1", "h2", "h3", "h4", "h5", "h6",
    "ul", "ol", "li", "blockquote", "pre", "br", "body", "html",
)
