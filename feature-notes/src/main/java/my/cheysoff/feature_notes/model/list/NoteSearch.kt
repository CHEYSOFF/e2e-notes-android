package my.cheysoff.feature_notes.model.list

import androidx.compose.runtime.Immutable

/**
 * Plain-text note search: the matching half of the Search tab.
 *
 * Everything in this file is pure Kotlin over plain [String]s — no Android types, no Room, no
 * coroutines — so it is covered by ordinary JVM unit tests (see NoteSearchTest).
 *
 * ## Why the input is already plain text
 *
 * A note's body is stored as HTML for rich-text notes and as literal text for the rest; which one
 * a row is, is read from its recorded `contentFormat` (see NoteContentFormat). Matching against
 * the stored bytes would be wrong in both directions:
 *
 *  - it matches markup the user cannot see — searching "span", "href" or "br" would hit tag names
 *    and attributes;
 *  - it misses text the user *can* see, because inline markup splits words: the rendered word
 *    "hello" can be stored as `he<b>llo</b>`, which does not contain the substring "hello".
 *
 * So the callers hand these functions the plain text that the list already produces:
 * [NotePreviewUi.content] is filled by `Note.toUi()`, which runs the stored body through
 * `HtmlCompat.fromHtml` for HTML rows and leaves PLAIN rows verbatim. That is the same single
 * conversion the note cards already display — search does not add a second, differently-behaved
 * stripper, and it therefore matches exactly the characters the user sees on the card.
 */

/** Longest snippet body (excluding the ellipses) shown under a search result. */
const val SNIPPET_MAX_LENGTH = 160

/**
 * How many characters of context to keep to the LEFT of the first match when the snippet window
 * has to start mid-body. Small on purpose: the matched term should land near the start of the
 * snippet, where the eye looks first.
 */
const val SNIPPET_LEAD_IN = 24

private const val ELLIPSIS = "…" // single character, so it shifts highlight offsets by 1

/**
 * One note that matched, already reduced to exactly what the result card draws.
 *
 * [title] and [snippet] are the strings to render, and the highlight ranges are offsets **into
 * those strings**, not into the original note. Rendering anything else would misplace the
 * highlight, so the card must draw these two fields verbatim.
 */
@Immutable
data class NoteSearchMatchUi(
    val preview: NotePreviewUi,
    /** Whitespace-collapsed note title. May be empty; the card then shows its "Untitled" filler. */
    val title: String,
    val titleHighlights: List<IntRange>,
    val snippet: String,
    val snippetHighlights: List<IntRange>,
)

/**
 * Collapses every run of whitespace to a single space and trims the ends.
 *
 * Applied to the query and to both haystacks before matching, for two reasons. First, the
 * plain text that comes out of the HTML conversion is full of newlines and block padding, so a
 * raw substring window would show ragged, mostly-empty snippets. Second, normalizing both sides
 * means a query typed with a single space still matches a phrase that happens to span a line
 * break in the note.
 */
fun normalizeSearchText(raw: String): String {
    val out = StringBuilder(raw.length)
    var pendingSpace = false
    for (ch in raw) {
        if (ch.isWhitespace()) {
            // Only remember that a gap occurred; it is emitted lazily so trailing runs vanish.
            if (out.isNotEmpty()) pendingSpace = true
        } else {
            if (pendingSpace) {
                out.append(' ')
                pendingSpace = false
            }
            out.append(ch)
        }
    }
    return out.toString()
}

/**
 * Every non-overlapping, case-insensitive occurrence of [needle] in [haystack], in order.
 *
 * Case-insensitivity comes from [String.indexOf]'s `ignoreCase` flag, which compares character by
 * character via `Char.equals(other, ignoreCase = true)` — i.e. it compares the uppercase forms and
 * then the lowercase forms of each pair. That works for any script whose case mapping is
 * one-character-to-one-character (Latin, Cyrillic, Greek). It is not full Unicode case folding:
 * pairs whose mapping changes length, such as "ß" ↔ "SS", are NOT treated as equal here. Because
 * the comparison is strictly per character, a match is always exactly `needle.length` characters
 * long, which is what lets the returned ranges be used as offsets into [haystack] unchanged.
 */
fun findMatchRanges(haystack: String, needle: String): List<IntRange> {
    if (needle.isEmpty() || needle.length > haystack.length) return emptyList()
    val ranges = mutableListOf<IntRange>()
    var from = 0
    while (from + needle.length <= haystack.length) {
        val at = haystack.indexOf(needle, startIndex = from, ignoreCase = true)
        if (at < 0) break
        ranges += at until (at + needle.length)
        from = at + needle.length // step past the whole hit: occurrences never overlap
    }
    return ranges
}

/** A snippet plus the highlight offsets that are valid inside it. */
data class SnippetUi(val text: String, val highlights: List<IntRange>)

/**
 * Cuts a display-sized window out of [body] around the first entry of [matches].
 *
 * With no matches (the query hit the title only) the window is simply the head of the body. Any
 * match that falls outside the window is dropped rather than clamped — a partial highlight at the
 * cut edge would point at a term the user cannot fully read.
 */
fun buildSnippet(body: String, matches: List<IntRange>): SnippetUi {
    if (body.isEmpty()) return SnippetUi("", emptyList())

    val first = matches.firstOrNull()
    val start = if (first == null) 0 else (first.first - SNIPPET_LEAD_IN).coerceAtLeast(0)
    val end = (start + SNIPPET_MAX_LENGTH).coerceAtMost(body.length)

    val prefix = if (start > 0) ELLIPSIS else ""
    val suffix = if (end < body.length) ELLIPSIS else ""
    // Offsets move left by the characters we dropped and right by the leading ellipsis.
    val shift = prefix.length - start

    val highlights = matches.mapNotNull { range ->
        if (range.first >= start && range.last < end) {
            (range.first + shift)..(range.last + shift)
        } else null
    }
    return SnippetUi(prefix + body.substring(start, end) + suffix, highlights)
}

/**
 * Matches one already-plain-text preview against an already-normalized [normalizedQuery].
 * Returns null when neither the title nor the body contains the query.
 */
fun matchPreview(preview: NotePreviewUi, normalizedQuery: String): NoteSearchMatchUi? {
    if (normalizedQuery.isEmpty()) return null

    val title = normalizeSearchText(preview.title)
    val body = normalizeSearchText(preview.content)

    val titleHits = findMatchRanges(title, normalizedQuery)
    val bodyHits = findMatchRanges(body, normalizedQuery)
    if (titleHits.isEmpty() && bodyHits.isEmpty()) return null

    val snippet = buildSnippet(body, bodyHits)
    return NoteSearchMatchUi(
        preview = preview,
        title = title,
        titleHighlights = titleHits,
        snippet = snippet.text,
        snippetHighlights = snippet.highlights,
    )
}

/**
 * Runs [rawQuery] over [previews] and returns the notes that matched.
 *
 * [previews] arrives in the user's chosen sort order and that order is preserved *within* each
 * group, but notes whose TITLE matched are moved ahead of the notes that only matched in the
 * body: a title hit is the stronger signal, and it is the one the user can confirm at a glance.
 * A blank query returns no results at all — showing the whole library for an empty box would just
 * be the notes list again, slower.
 */
fun searchPreviews(previews: List<NotePreviewUi>, rawQuery: String): List<NoteSearchMatchUi> {
    val query = normalizeSearchText(rawQuery)
    if (query.isEmpty()) return emptyList()
    val matches = previews.mapNotNull { matchPreview(it, query) }
    val (titleMatches, bodyOnly) = matches.partition { it.titleHighlights.isNotEmpty() }
    return titleMatches + bodyOnly
}
