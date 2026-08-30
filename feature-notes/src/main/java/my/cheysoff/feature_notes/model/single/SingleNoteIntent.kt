package my.cheysoff.feature_notes.model.single

sealed interface SingleNoteIntent {
    data class TitleChanged(val title: String) : SingleNoteIntent
    data class ContentChanged(val content: String) : SingleNoteIntent
    data object TogglePin : SingleNoteIntent
    data object ToggleFavorite : SingleNoteIntent
    data object MoreClicked : SingleNoteIntent
    data object BackClicked : SingleNoteIntent

    /**
     * Send this note to Trash and leave the editor. The user's only route to deleting a note; the
     * overflow menu confirms before sending it.
     */
    data object DeleteNote : SingleNoteIntent

    /** Append a new empty checklist item with [newId] after [afterId] (null = at the end). */
    data class ChecklistItemAdded(val newId: String, val afterId: String?) : SingleNoteIntent
    data class ChecklistItemToggled(val id: String) : SingleNoteIntent
    data class ChecklistItemTextChanged(val id: String, val text: String) : SingleNoteIntent
    data class ChecklistItemRemoved(val id: String) : SingleNoteIntent

    /** Assign this note to [folderId], or unfile it when null. */
    data class SetFolder(val folderId: String?) : SingleNoteIntent
}
