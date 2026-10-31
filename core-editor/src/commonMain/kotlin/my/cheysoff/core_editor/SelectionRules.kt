package my.cheysoff.core_editor

import androidx.compose.ui.text.TextRange

/**
 * Where a selection should be instead of [selection], or null to leave it.
 *
 * A selection within one list line that starts on the line's marker (a word selection takes the
 * marker as part of the first word; a handle dragged to the line start lands on it) is moved to
 * start just after it, so the highlight begins at the text, not under the checkbox, bullet or
 * number. A selection over several lines keeps its markers: copy, cut and delete of whole items
 * act on their structure. Picture and drawing lines are their marker and are left alone.
 */
internal fun selectionPastMarker(text: CharSequence, selection: TextRange): TextRange? {
    if (selection.collapsed) return null
    val min = selection.min
    val max = selection.max
    val c = text.getOrNull(min) ?: return null
    if (!Markers.isPrivateUse(c) || c == Markers.PICTURE || c == Markers.DRAWING) return null
    if (min > 0 && text[min - 1] != '\n') return null
    if (max == min + 1 || (min until max).any { text[it] == '\n' }) return null
    return if (selection.start <= selection.end) TextRange(min + 1, max) else TextRange(max, min + 1)
}
