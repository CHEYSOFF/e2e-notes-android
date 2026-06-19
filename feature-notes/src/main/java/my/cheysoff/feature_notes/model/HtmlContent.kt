package my.cheysoff.feature_notes.model

// Matches an opening tag like <p>, <b>, <h1 ...>. A bare "<" or "1 < 2" does NOT match because a
// letter must immediately follow "<" and the tag must close with ">".
private val HTML_TAG = Regex("<[a-zA-Z][^>]*>")

/**
 * Heuristic: does this stored note content look like rich-text HTML (as produced by the editor),
 * vs. legacy plain text written before rich text existed?
 *
 * Notes are persisted as HTML now, but older notes are raw plain text. Feeding plain text that
 * happens to contain "<"/">" (e.g. "1 < 2") into an HTML parser would silently drop characters,
 * so callers use this to decide whether to parse as HTML or treat the string as literal text.
 */
fun String.looksLikeHtml(): Boolean = HTML_TAG.containsMatchIn(this)
