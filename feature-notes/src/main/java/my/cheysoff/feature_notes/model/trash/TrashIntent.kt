package my.cheysoff.feature_notes.model.trash

sealed interface TrashIntent {
    data object BackClicked : TrashIntent

    /** Take the row out of Trash. For a folder this brings back an empty folder — see the screen copy. */
    data class Restore(val id: String, val kind: TrashEntryKind) : TrashIntent

    /** Destroy the row. The screen confirms before sending this; there is nothing behind it. */
    data class DeleteForever(val id: String, val kind: TrashEntryKind) : TrashIntent
}
