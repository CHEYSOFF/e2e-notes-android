package my.cheysoff.feature_notes.model.list

import my.cheysoff.core_domain.model.NotesSortOrder
import java.time.LocalDate

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
    data class SortOrderSelected(val order: NotesSortOrder) : NotesListIntent
    /** The search field's text changed (or was cleared, with an empty string). */
    data class SearchQueryChanged(val query: String) : NotesListIntent

    /** Step the calendar grid one month back or forward. */
    data object CalendarPreviousMonth : NotesListIntent
    data object CalendarNextMonth : NotesListIntent

    /**
     * A day cell was tapped. Tapping a day outside the shown month moves the grid to that day's
     * month too, so the selection is always visible.
     */
    data class CalendarDaySelected(val day: LocalDate) : NotesListIntent

    /** Open Trash. Deliberately not a bottom-bar item — those four slots are already spoken for. */
    data object TrashClicked : NotesListIntent
}
