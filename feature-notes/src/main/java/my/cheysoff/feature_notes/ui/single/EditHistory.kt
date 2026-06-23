package my.cheysoff.feature_notes.ui.single

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * Editor-scoped undo/redo over content HTML snapshots. The caller decides WHEN to [record]
 * (time-coalesced), so this stays a pure stack. [record] dedupes against the current entry,
 * which also guards the setHtml -> snapshotFlow -> record feedback loop. [index] is Compose
 * state so [canUndo]/[canRedo] drive button recomposition.
 */
class EditHistory(initial: String) {
    private val entries = ArrayList<String>().apply { add(initial) }
    private var index by mutableIntStateOf(0)

    val canUndo: Boolean get() = index > 0
    val canRedo: Boolean get() = index < entries.size - 1

    fun record(state: String) {
        if (state == entries[index]) return
        while (entries.size > index + 1) entries.removeAt(entries.size - 1)
        entries.add(state)
        index = entries.size - 1
    }

    fun undo(): String? {
        if (!canUndo) return null
        index -= 1
        return entries[index]
    }

    fun redo(): String? {
        if (!canRedo) return null
        index += 1
        return entries[index]
    }

    fun reset(initial: String) {
        entries.clear()
        entries.add(initial)
        index = 0
    }
}
