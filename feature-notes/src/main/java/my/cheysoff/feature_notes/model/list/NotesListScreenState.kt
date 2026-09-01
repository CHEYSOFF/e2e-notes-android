package my.cheysoff.feature_notes.model.list

import androidx.compose.runtime.Immutable
import my.cheysoff.core_domain.model.NotesSortOrder
import java.time.LocalDate
import java.time.YearMonth

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

    /**
     * True while a pull-to-refresh sync pass is running.
     *
     * Deliberately not [isLoading]. That one is about whether this screen has its data yet and
     * gates the whole list; this one is about a network round trip happening behind a list that is
     * already on screen and fully usable. Collapsing them would blank the library every time
     * someone pulled down.
     */
    val refreshing: Boolean = false,

    /**
     * One line about the sync pass the user just asked for, or null.
     *
     * Shown only after a **manual** refresh, because that is the only sync a person is waiting on
     * an answer for. A pass triggered by the unlock reports itself in Settings and stays out of the
     * way of someone who came here to read a note.
     */
    val syncNotice: String? = null,
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

    // --- Calendar tab ---
    /**
     * The month the grid is showing. Null until the first calendar state emission, which is what
     * lets the screen tell "not computed yet" from "computed, and this month is empty" — the same
     * distinction [searchResultsQuery] draws for search. The ViewModel seeds it from the clock on
     * the first emission rather than at construction, so the value cannot be a stale month if the
     * ViewModel outlives midnight.
     */
    val calendarMonth: YearMonth? = null,
    /** The day whose notes are listed under the grid. */
    val calendarSelectedDay: LocalDate? = null,
    /** Note count per day, for every day that has any. Days absent from the map have none. */
    val calendarCounts: Map<LocalDate, Int> = emptyMap(),
    /** The notes on [calendarSelectedDay], in the list's own order. */
    val calendarDayNotes: List<NotePreviewUi> = emptyList(),
    /**
     * Notes with no usable timestamp, which therefore appear on no day. Surfaced rather than
     * dropped: a note that is invisible in every view is indistinguishable from a lost note.
     */
    val calendarUndatedCount: Int = 0,
)

enum class BottomBarItem {
    ALL_NOTES, SEARCH, CALENDAR, PROFILE
}
