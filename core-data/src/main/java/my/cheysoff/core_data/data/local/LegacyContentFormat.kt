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
 * passes, while prose that merely mentions an angle-bracketed word mid-sentence does not.
 *
 * Anchoring shrinks the false-positive surface; it does NOT eliminate it. Prose that literally
 * begins with a whitelisted tag — "<p> is the paragraph tag", "<br> is a line break" — is still
 * classified as HTML and will be truncated by setHtml(). That residual case is accepted only
 * because it requires a note to open with markup it is talking about, which is rare in a way that
 * mid-sentence angle brackets are not.
 *
 * Everywhere the call is genuinely uncertain, this errs toward `plain`, because the two errors are
 * not symmetric: a false negative shows raw markup (ugly, immediately obvious, losslessly
 * recoverable) while a false positive silently and permanently truncates a real note.
 */
internal fun looksLikeEditorHtml(content: String): Boolean {
    val trimmed = content.trimStart()
    if (trimmed.length < 3 || trimmed[0] != '<') return false

    // Read the tag name: letters/digits immediately after "<". A closing tag ("</p>") is not a
    // valid document start, so no "/" is skipped here.
    var end = 1
    while (end < trimmed.length && trimmed[end].isLetterOrDigit()) end++
    if (end == 1) return false
    // The name ran to the end of the content, so there is no delimiter to inspect and nothing
    // closed the tag: "<div" is prose, not markup. Without this guard the read below is out of
    // bounds, and since this runs inside MIGRATION_4_5 the throw would abort the migration on
    // every launch — leaving the notes intact on disk but permanently unreachable.
    if (end >= trimmed.length) return false

    // The name must actually terminate the tag; "<see attached spec>" must not read as tag "see"
    // with attributes. Only ">", "/" (self-closing) or whitespace-then-attributes qualify, and
    // the name itself still has to be on the whitelist, which "see" is not.
    val delimiter = trimmed[end]
    if (delimiter != '>' && delimiter != '/' && !delimiter.isWhitespace()) return false

    return trimmed.substring(1, end).lowercase() in EDITOR_BLOCK_TAGS
}

// Block-level elements RichTextState.toHtml() can start its output with. Kept intentionally
// narrow: every extra name is false-positive surface, and a name that the editor cannot actually
// lead with buys no recall in exchange.
//
// Deliberately absent, and why:
//  - "html"/"body": toHtml() emits a document FRAGMENT, not a whole document. The block tags
//    listed below are only reachable as the first tag if nothing wraps them, so listing a wrapper
//    alongside them would be self-contradictory.
//  - "li": a list item is always nested inside "ul"/"ol", both of which are already here, so it
//    can never be the first tag of a well-formed document.
// If either judgement is wrong the cost is a false negative — the note shows its raw markup and
// can be fixed by hand — which is the direction this classifier is supposed to fail in.
private val EDITOR_BLOCK_TAGS = setOf(
    "p", "div", "h1", "h2", "h3", "h4", "h5", "h6",
    "ul", "ol", "blockquote", "pre", "br",
)
