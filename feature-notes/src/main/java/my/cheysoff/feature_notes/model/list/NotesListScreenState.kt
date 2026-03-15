package my.cheysoff.feature_notes.model.list

import androidx.compose.runtime.Immutable

@Immutable
data class NotesListScreenState(
    val folderPreviews: List<FolderPreviewUi> = emptyList(),
    val notePreviews: List<NotePreviewUi> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val selectedBottomBarItem: BottomBarItem = BottomBarItem.ALL_NOTES
)

enum class BottomBarItem {
    ALL_NOTES, SEARCH, CALENDAR, PROFILE
}
