package my.cheysoff.feature_notes.model.trash

import androidx.compose.runtime.Immutable

@Immutable
data class TrashScreenState(
    /** Notes and folders together, newest-deleted first. */
    val entries: List<TrashEntryUi> = emptyList(),
    /**
     * True until the first emission from the database. It exists so the empty state isn't flashed
     * for a frame before the rows arrive — "Trash is empty" is a claim, and it should only be made
     * once it has been checked.
     */
    val isLoading: Boolean = true,
)
