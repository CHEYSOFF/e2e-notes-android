package my.cheysoff.desktop.ui.state

import androidx.compose.runtime.Immutable

/**
 * Plain-text note search — the desktop half of the feature that shipped on Android as issue #14.
 *
 * The matching rules are the ones in `:feature-notes/model/list/NoteSearch.kt` and the reasoning
 * there applies unchanged; the short version is that both haystacks are already plain text
 * ([NoteRowUi.snippet] has been through [htmlToPlainText]) because matching against stored HTML
 * would both hit markup the user cannot see and miss words that inline markup splits in half.
 *
 * Duplicated for the same reason as the checklist model: the original lives in an Android library.
 */

/** Longest snippet body (excluding the ellipses) shown under a search result. */
const val SNIPPET_MAX_LENGTH = 160

/** Characters of context kept to the LEFT of the first match, so the term lands near the start. */
const val SNIPPET_LEAD_IN = 24

private const val ELLIPSIS = "…" // one character, so it shifts highlight offsets by exactly 1

/**
 * One matched note, reduced to exactly what the result row draws. The highlight ranges are offsets
 * into [title]/[snippet] as given here, NOT into the original note, so the row must render these
 * two strings verbatim or the highlights land on the wrong characters.
 */
@Immutable
data class NoteSearchHit(
    val row: NoteRowUi,
    val title: String,
    val titleHighlights: List<IntRange>,
    val snippet: String,
    val snippetHighlights: List<IntRange>,
)

/** Collapses every run of whitespace to one space and trims. Applied to query and both haystacks. */
fun normalizeSearchText(raw: String): String {
    val out = StringBuilder(raw.length)
    var pendingSpace = false
    for (ch in raw) {
        if (ch.isWhitespace()) {
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
 * Case-insensitivity is [String.indexOf]'s per-character `ignoreCase`, which handles any script
 * whose case mapping is one character to one (Latin, Cyrillic, Greek) but is not full Unicode
 * folding — "ß" does not match "SS". Because it compares character by character a hit is always
 * exactly `needle.length` long, which is what makes the returned ranges usable as offsets.
 */
fun findMatchRanges(haystack: String, needle: String): List<IntRange> {
    if (needle.isEmpty() || needle.length > haystack.length) return emptyList()
    val ranges = mutableListOf<IntRange>()
    var from = 0
    while (from + needle.length <= haystack.length) {
        val at = haystack.indexOf(needle, startIndex = from, ignoreCase = true)
        if (at < 0) break
        ranges += at until (at + needle.length)
        from = at + needle.length
    }
    return ranges
}

data class SnippetUi(val text: String, val highlights: List<IntRange>)

/**
 * Cuts a display-sized window out of [body] around the first match. A match falling outside the
 * window is dropped rather than clamped: half a highlight at the cut edge points at a term the
 * user cannot finish reading.
 */
fun buildSnippet(body: String, matches: List<IntRange>): SnippetUi {
    if (body.isEmpty()) return SnippetUi("", emptyList())

    val first = matches.firstOrNull()
    val start = if (first == null) 0 else (first.first - SNIPPET_LEAD_IN).coerceAtLeast(0)
    val end = (start + SNIPPET_MAX_LENGTH).coerceAtMost(body.length)

    val prefix = if (start > 0) ELLIPSIS else ""
    val suffix = if (end < body.length) ELLIPSIS else ""
    val shift = prefix.length - start

    val highlights = matches.mapNotNull { range ->
        if (range.first >= start && range.last < end) (range.first + shift)..(range.last + shift)
        else null
    }
    return SnippetUi(prefix + body.substring(start, end) + suffix, highlights)
}

/** Matches one row against an already-normalized query. Null when neither field contains it. */
fun matchRow(row: NoteRowUi, normalizedQuery: String): NoteSearchHit? {
    if (normalizedQuery.isEmpty()) return null

    val title = normalizeSearchText(row.title)
    val body = normalizeSearchText(row.snippet)

    val titleHits = findMatchRanges(title, normalizedQuery)
    val bodyHits = findMatchRanges(body, normalizedQuery)
    if (titleHits.isEmpty() && bodyHits.isEmpty()) return null

    val snippet = buildSnippet(body, bodyHits)
    return NoteSearchHit(
        row = row,
        title = title,
        titleHighlights = titleHits,
        snippet = snippet.text,
        snippetHighlights = snippet.highlights,
    )
}

/**
 * Runs [rawQuery] over [rows].
 *
 * Sort order is preserved within each group, but title matches are hoisted above body-only ones: a
 * title hit is the stronger signal and the one the user can confirm at a glance. A blank query
 * returns nothing — showing the whole library for an empty box is just the notes list again.
 *
 * Search deliberately ignores the folder filter and always runs over the whole library. On the
 * phone the filter and the search box are the same screen and scoping felt natural; here search is
 * a ⌘K palette summoned over the top of everything, and a palette that silently hid a note because
 * of a chip clicked ten minutes ago is a palette people stop trusting.
 */
fun searchRows(rows: List<NoteRowUi>, rawQuery: String): List<NoteSearchHit> {
    val query = normalizeSearchText(rawQuery)
    if (query.isEmpty()) return emptyList()
    val hits = rows.mapNotNull { matchRow(it, query) }
    val (titleHits, bodyOnly) = hits.partition { it.titleHighlights.isNotEmpty() }
    return titleHits + bodyOnly
}
