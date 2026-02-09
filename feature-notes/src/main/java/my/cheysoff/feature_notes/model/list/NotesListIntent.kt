package my.cheysoff.feature_notes.model.list

sealed interface NotesListIntent {
    data class NoteClicked(val noteId: String) : NotesListIntent
    data class FolderClicked(val folderId: String) : NotesListIntent
    data object AddNoteClicked : NotesListIntent
}
