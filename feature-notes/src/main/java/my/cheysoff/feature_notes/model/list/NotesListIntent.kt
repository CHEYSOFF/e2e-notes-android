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

    /**
     * The user pulled the notes list down.
     *
     * The only sync trigger a person can reach on purpose. The other one is the unlock, which is
     * automatic and invisible; this is the gesture someone makes when they are standing next to
     * their other device wondering why a note has not appeared yet, so it runs a real pass and
     * waits for it rather than re-reading the local database.
     */
    data object RefreshRequested : NotesListIntent

    /** Open Trash. Deliberately not a bottom-bar item — those four slots are already spoken for. */
    data object TrashClicked : NotesListIntent
}
