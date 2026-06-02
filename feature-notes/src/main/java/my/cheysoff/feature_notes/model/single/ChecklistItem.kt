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
 * Serialized form: one item per line, first char `1`/`0` = done/undone, the rest is the item
 * text. Items are single-line, so newlines in [text] are stripped to keep the format unambiguous.
 * An empty list serializes to "".
 */
fun List<ChecklistItem>.serializeChecklist(): String =
    joinToString("\n") { (if (it.isDone) "1" else "0") + it.text.replace("\n", " ") }

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
