package my.cheysoff.feature_notes

sealed class NotesDestination(val route: String) {
    object NotesList : NotesDestination("notes_list")
    object NoteDetails : NotesDestination("note_details/{noteId}") {
        fun createRoute(noteId: String) = "note_details/$noteId"
    }
}