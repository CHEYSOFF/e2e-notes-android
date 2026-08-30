package my.cheysoff.feature_notes.model.list

import androidx.compose.runtime.Immutable
import my.cheysoff.core_domain.model.NotesSortOrder

@Immutable
data class NotesListScreenState(
    val headerLine: HeaderLineUi? = null,
    val statsLine: String? = null,
    val folderPreviews: List<FolderPreviewUi> = emptyList(),
    val pinnedPreviews: List<NotePreviewUi> = emptyList(),
    val notePreviews: List<NotePreviewUi> = emptyList(),
    val selectedFolderId: String? = null,
    val sortOrder: NotesSortOrder = NotesSortOrder.DEFAULT,
    val isLoading: Boolean = true,
    val error: String? = null,
    val selectedBottomBarItem: BottomBarItem = BottomBarItem.ALL_NOTES,

    // --- Search tab ---
    /** Exactly what is in the search field. Updated on every keystroke, before the debounce. */
    val searchQuery: String = "",
    /** Results for [searchResultsQuery] — NOT necessarily for [searchQuery]; see below. */
    val searchResults: List<NoteSearchMatchUi> = emptyList(),
    /**
     * The normalized query [searchResults] was actually computed from, or "" when no search has
     * run yet. It lags [searchQuery] by the debounce window, and the screen needs both to tell
     * "still typing" (show the previous results) from "searched and found nothing" (show the
     * no-results state). Deriving that from [searchResults] alone is impossible: an empty list
     * means the same thing in both cases.
     */
    val searchResultsQuery: String = "",
)

enum class BottomBarItem {
    ALL_NOTES, SEARCH, CALENDAR, PROFILE
}
