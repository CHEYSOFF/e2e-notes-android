package my.cheysoff.feature_notes.model.single

sealed interface SingleNoteIntent {
    data class TitleChanged(val title: String) : SingleNoteIntent
    data class ContentChanged(val content: String) : SingleNoteIntent
    data object TogglePin : SingleNoteIntent
    data object ToggleFavorite : SingleNoteIntent
    data object MoreClicked : SingleNoteIntent
    data object BackClicked : SingleNoteIntent

    /**
     * Take back / replay the last editor edit, whichever field it touched. The screen must flush
     * any debounced body content before sending these, or the undo would step back past edits the
     * ViewModel has not been told about yet.
     */
    data object Undo : SingleNoteIntent
    data object Redo : SingleNoteIntent

    /** Append a new empty checklist item with [newId] after [afterId] (null = at the end). */
    data class ChecklistItemAdded(val newId: String, val afterId: String?) : SingleNoteIntent
    data class ChecklistItemToggled(val id: String) : SingleNoteIntent
    data class ChecklistItemTextChanged(val id: String, val text: String) : SingleNoteIntent
    data class ChecklistItemRemoved(val id: String) : SingleNoteIntent

    /** Assign this note to [folderId], or unfile it when null. */
    data class SetFolder(val folderId: String?) : SingleNoteIntent
}
