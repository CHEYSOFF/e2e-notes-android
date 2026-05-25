package my.cheysoff.feature_notes.model.list

import androidx.compose.runtime.Immutable

@Immutable
data class NotesListScreenState(
    val headerLine: HeaderLineUi? = null,
    val statsLine: String? = null,
    val folderPreviews: List<FolderPreviewUi> = emptyList(),
    val pinnedPreviews: List<NotePreviewUi> = emptyList(),
    val notePreviews: List<NotePreviewUi> = emptyList(),
    val selectedFolderId: String? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val selectedBottomBarItem: BottomBarItem = BottomBarItem.ALL_NOTES
)

enum class BottomBarItem {
    ALL_NOTES, SEARCH, CALENDAR, PROFILE
}
