package my.cheysoff.feature_notes.model.single

import androidx.compose.runtime.Immutable
import java.util.UUID

/**
 * One checklist row in the editor. [id] is ephemeral (used only for Compose keys and intent
 * routing within a session) and is NOT persisted — only [text] and [isDone] are serialized.
 */
@Immutable
data class ChecklistItem(
    val id: String,
    val text: String,
    val isDone: Boolean,
)

/**
 * Folds an item's text into the only shape the serialized format can represent: single-line.
 *
 * Apply this before the text enters editor state, not just on the way out. [serializeChecklist] is
 * lossy for text containing a newline, and the merge that keeps [ChecklistItem.id]s alive compares
 * local text against text that has been through serialize + parse. Text held un-normalized in state
 * therefore fails that comparison against its own echo, gets a fresh id, and steals focus from the
 * row being typed into — reachable today by pasting multi-line text into the single-line field.
 */
fun normalizeChecklistText(text: String): String = text.replace("\n", " ")

/**
 * Serialized form: one item per line, first char `1`/`0` = done/undone, the rest is the item
 * text. Items are single-line, so newlines in [text] are stripped to keep the format unambiguous.
 * An empty list serializes to "".
 */
fun List<ChecklistItem>.serializeChecklist(): String =
    joinToString("\n") { (if (it.isDone) "1" else "0") + normalizeChecklistText(it.text) }

/** Inverse of [serializeChecklist]. Assigns each parsed item a fresh ephemeral id. */
fun parseChecklist(raw: String): List<ChecklistItem> =
    if (raw.isEmpty()) emptyList()
    else raw.split("\n").map { line ->
        ChecklistItem(
            id = UUID.randomUUID().toString(),
            isDone = line.firstOrNull() == '1',
            text = line.drop(1),
        )
    }

/**
 * Counts (done, total) directly from the serialized blob without allocating [ChecklistItem]s —
 * used for list-preview progress dots.
 */
fun checklistProgress(raw: String): Pair<Int, Int> {
    if (raw.isEmpty()) return 0 to 0
    var done = 0
    var total = 0
    for (line in raw.split("\n")) {
        total++
        if (line.firstOrNull() == '1') done++
    }
    return done to total
}
