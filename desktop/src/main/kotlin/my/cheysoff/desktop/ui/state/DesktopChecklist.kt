package my.cheysoff.desktop.ui.state

import java.util.UUID

/**
 * The checklist block model, restated from `:feature-notes/model/single/ChecklistItem.kt`.
 *
 * The serialization format is a data format shared by both platforms, so this file must stay
 * byte-compatible with that one: one item per line, first character `1`/`0` for done/undone, the
 * rest of the line is the text, empty list serializes to "". A desktop that wrote a different
 * shape would corrupt every checklist the phone syncs down.
 *
 * It is duplicated rather than shared because :feature-notes is an Android library. The functions
 * are pure Kotlin with no Android imports, so the right long-term home is :core-domain's
 * commonMain — see the note in the report.
 */
data class DesktopChecklistItem(
    val id: String,
    val text: String,
    val isDone: Boolean,
)

/** Folds an item's text into the only shape the format can represent: single-line. */
fun normalizeChecklistText(text: String): String = text.replace("\n", " ")

fun List<DesktopChecklistItem>.serializeChecklist(): String =
    joinToString("\n") { (if (it.isDone) "1" else "0") + normalizeChecklistText(it.text) }

/** Inverse of [serializeChecklist]. Assigns each parsed item a fresh ephemeral id. */
fun parseChecklist(raw: String): List<DesktopChecklistItem> =
    if (raw.isEmpty()) emptyList()
    else raw.split("\n").map { line ->
        DesktopChecklistItem(
            id = UUID.randomUUID().toString(),
            isDone = line.firstOrNull() == '1',
            text = line.drop(1),
        )
    }

/** (done, total) straight off the serialized blob, without allocating items — for list previews. */
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
