package my.cheysoff.feature_notes.model.single

sealed interface SingleNoteIntent {
    data class TitleChanged(val title: String) : SingleNoteIntent
    data class ContentChanged(val content: String) : SingleNoteIntent
    data object TogglePin : SingleNoteIntent
    data object MoreClicked : SingleNoteIntent
    data object BackClicked : SingleNoteIntent
}
