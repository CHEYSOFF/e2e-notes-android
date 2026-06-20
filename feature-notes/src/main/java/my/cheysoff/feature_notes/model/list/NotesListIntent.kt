package my.cheysoff.feature_notes.model.list

sealed interface NotesListIntent {
    data class NoteClicked(val noteId: String) : NotesListIntent
    data class FolderClicked(val folderId: String) : NotesListIntent
    data object AddNoteClicked : NotesListIntent
    data object SearchClicked : NotesListIntent
    data object CalendarClicked : NotesListIntent
    data object ProfileClicked : NotesListIntent
    data object AllNotesClicked : NotesListIntent
    data class CreateFolder(val name: String, val colorArgb: Long?) : NotesListIntent
    data class UpdateFolder(val id: String, val name: String, val colorArgb: Long?) : NotesListIntent
    data class DeleteFolder(val id: String) : NotesListIntent
    data class MoveNoteToFolder(val noteId: String, val folderId: String?) : NotesListIntent
}
