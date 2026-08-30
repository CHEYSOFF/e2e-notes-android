package my.cheysoff.feature_notes.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import my.cheysoff.core_domain.model.Folder
import my.cheysoff.core_domain.model.HeaderSettings
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NotesSortOrder
import my.cheysoff.core_domain.repository.NotesRepository
import my.cheysoff.core_domain.repository.SettingsRepository
import my.cheysoff.feature_notes.model.list.BottomBarItem
import my.cheysoff.feature_notes.model.list.HeaderLineUi
import my.cheysoff.feature_notes.model.list.NotePreviewUi
import my.cheysoff.feature_notes.model.list.NotesListIntent
import my.cheysoff.feature_notes.model.list.NotesListScreenState
import my.cheysoff.feature_notes.model.list.normalizeFolderName
import my.cheysoff.feature_notes.model.list.normalizeSearchText
import my.cheysoff.feature_notes.model.list.searchPreviews
import my.cheysoff.feature_notes.model.list.toUi
import my.cheysoff.feature_notes.ui.list.NotesListEvent.NavigateToNote
import java.time.LocalTime
import java.util.UUID
import javax.inject.Inject

sealed class NotesListEvent {
    /**
     * [isNew] is true only when this screen inserted the row itself, moments ago, so the editor can
     * open it. It is the editor's sole licence to auto-discard the row on back — see
     * isDiscardableOnOpen. It must stay false for every other navigation: opening an existing note
     * with it set would make that note deletable by simply emptying it and backing out.
     */
    data class NavigateToNote(val noteId: String, val isNew: Boolean = false) : NotesListEvent()

    data object NavigateToTrash : NotesListEvent()
}

/**
 * Quiet period the search field waits out before a query is actually run, matching the editor's
 * autosave/serialize debounce (SingleNoteScreen.CONTENT_SERIALIZE_DEBOUNCE_MS).
 */
private const val SEARCH_DEBOUNCE_MS = 300L

/** Carries the off-main-thread result (previews already parsed) to the main-thread state update. */
private data class ListData(
    val settings: HeaderSettings,
    val folders: List<Folder>,
    val previews: List<NotePreviewUi>,
    val sortOrder: NotesSortOrder,
)

@HiltViewModel
class NotesListViewModel @Inject constructor(
    private val notesRepository: NotesRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(NotesListScreenState())
    val state = _state.asStateFlow()

    private val _events = Channel<NotesListEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    // Latest full (unfiltered) previews, kept so folder selection can re-filter without re-parsing
    // (each preview's HTML→plain-text conversion already happened off the main thread on load).
    //
    // It is a flow rather than a plain field because the Search tab also reads it and has to
    // re-run its query when the notes change (an edit, a delete) — see the search pipeline below.
    private val allPreviewsFlow = MutableStateFlow<List<NotePreviewUi>>(emptyList())
    private val allPreviews: List<NotePreviewUi> get() = allPreviewsFlow.value

    // The raw search field text. Kept separate from _state so the debounce below sees every
    // keystroke as its own emission.
    private val searchQuery = MutableStateFlow("")

    // The notes query itself carries the ordering (one verified @Query per order), so a change to
    // the persisted preference has to re-subscribe rather than re-sort what we already have —
    // hence flatMapLatest, which drops the old subscription. The order is carried alongside the
    // notes so the state update below can echo it back to the picker without a second flow.
    // Declared before init for the same reason as dailyPhrases: init may run it synchronously.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val sortedNotes: Flow<Pair<NotesSortOrder, List<Note>>> =
        settingsRepository.notesSortOrder.flatMapLatest { order ->
            notesRepository.getNotes(order).map { notes -> order to notes }
        }

    // Declared before init: the init coroutine can resume synchronously (Main.immediate + a
    // DataStore flow that emits its first value without suspending), so pickMotivationalLine()
    // may run during construction — this list must already be initialized by then.
    private val dailyPhrases = listOf(
        HeaderLineUi("One thing", "at a time."),
        HeaderLineUi("Tomorrow", "starts here."),
        HeaderLineUi("Capture", "the thought."),
        HeaderLineUi("Make it", "count."),
        HeaderLineUi("Today's", "canvas."),
    )

    init {
        // Pick the motivational line once per screen open (random greeting/phrase among the enabled).
        viewModelScope.launch {
            val settings = settingsRepository.headerSettings.first()
            _state.update { it.copy(headerLine = pickMotivationalLine(settings)) }
        }

        // Stats is a separate, always-visible sub-line (not part of the random rotation) and tracks
        // the live note counts.
        combine(
            settingsRepository.headerSettings,
            notesRepository.getFolders(),
            sortedNotes,
        ) { settings, folders, sorted -> Triple(settings, folders, sorted) }
            // Map notes → previews on a background dispatcher: Note.toUi() parses each note's HTML
            // via HtmlCompat.fromHtml, which is O(content size) and would jank the UI on large lists.
            .map { (settings, folders, sorted) ->
                val (sortOrder, notes) = sorted
                // Resolve each note's folder color here (we have the folder list) so the cards can
                // render the chosen color without looking the folder up at draw time. Stays off-main
                // thread via the .flowOn(Dispatchers.Default) below.
                val colorByFolder: Map<String, Long?> = folders.associate { it.id to it.colorArgb }
                val previews = notes.map { note ->
                    note.toUi().copy(folderColorArgb = note.folderId?.let(colorByFolder::get))
                }
                ListData(settings, folders, previews, sortOrder)
            }
            .flowOn(Dispatchers.Default)
            .onEach { (settings, folders, previews, sortOrder) ->
                allPreviewsFlow.value = previews
                val countByFolder = previews.groupingBy { it.folderId }.eachCount()
                val folderPreviews = folders.map { folder ->
                    folder.toUi(notesAmount = countByFolder[folder.id] ?: 0)
                }
                val stats = if (settings.showStats) {
                    "${previews.size} notes · ${previews.count { it.isPinned }} pinned"
                } else null
                _state.update { current ->
                    val visible = visiblePreviews(current.selectedFolderId)
                    current.copy(
                        folderPreviews = folderPreviews,
                        pinnedPreviews = visible.filter { it.isPinned },
                        notePreviews = visible.filter { !it.isPinned },
                        statsLine = stats,
                        sortOrder = sortOrder,
                        isLoading = false,
                    )
                }
            }
            .launchIn(viewModelScope)

        observeSearchQuery()
    }

    /**
     * Search (the SEARCH bottom-bar tab).
     *
     * The query runs over [allPreviewsFlow] — the previews the list itself is built from — rather
     * than over a second database query. Three consequences, all deliberate:
     *
     *  1. Correctness on HTML bodies. These previews are already plain text: `Note.toUi()` ran the
     *     stored body through HtmlCompat for rows recorded as HTML. A SQL `content LIKE` would
     *     instead scan the stored markup, matching tag and attribute names the user cannot see and
     *     missing rendered words that inline markup splits (`he<b>llo</b>` renders as "hello" but
     *     does not contain that substring).
     *  2. Search and the list see the same set of notes, because there is exactly one query that
     *     decides which rows exist for this screen and both read its output. (The folder chips are
     *     a separate, list-only view filter applied in [visiblePreviews]; search deliberately spans
     *     every folder, which is why it reads [allPreviewsFlow] and not the filtered slice.)
     *  3. No extra parsing. The HTML→text conversion has already been paid for by the list; search
     *     re-reads the result instead of repeating it.
     *
     * Cost model: one pass over every preview's title and body per settled keystroke, on
     * Dispatchers.Default, with no I/O. Linear in total library text.
     *
     * Re-emitting on [allPreviewsFlow] (not just on the query) keeps an open result list live: an
     * edit or delete elsewhere re-runs the current query immediately, without waiting out another
     * debounce window.
     */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private fun observeSearchQuery() {
        combine(
            searchQuery.debounce(SEARCH_DEBOUNCE_MS),
            allPreviewsFlow,
        ) { query, previews -> query to previews }
            // mapLatest so a newer query cancels a scan still in flight; its result would only be
            // overwritten anyway.
            .mapLatest { (query, previews) ->
                normalizeSearchText(query) to searchPreviews(previews, query)
            }
            .flowOn(Dispatchers.Default)
            .onEach { (normalizedQuery, results) ->
                _state.update {
                    it.copy(searchResults = results, searchResultsQuery = normalizedQuery)
                }
            }
            .launchIn(viewModelScope)
    }

    private fun visiblePreviews(selectedFolderId: String?): List<NotePreviewUi> =
        if (selectedFolderId == null) allPreviews
        else allPreviews.filter { it.folderId == selectedFolderId }

    private fun pickMotivationalLine(settings: HeaderSettings): HeaderLineUi? {
        val sources = buildList {
            if (settings.showGreetings) add("greeting")
            if (settings.showDailyPhrases) add("phrase")
        }
        if (sources.isEmpty()) return null // -> screen shows the small "Mañana" wordmark
        return when (sources.random()) {
            "greeting" -> {
                val word = when (LocalTime.now().hour) {
                    in 5..11 -> "morning."
                    in 12..16 -> "afternoon."
                    in 17..21 -> "evening."
                    else -> "night."
                }
                HeaderLineUi("Good", word)
            }
            else -> dailyPhrases.random()
        }
    }

    fun onIntent(intent: NotesListIntent) {
        when (intent) {
            is NotesListIntent.NoteClicked -> {
                viewModelScope.launch {
                    _events.send(NavigateToNote(intent.noteId))
                }
            }

            is NotesListIntent.FolderClicked -> {
                _state.update { current ->
                    // Toggle: tapping the active folder clears the filter (back to All).
                    val newSelection =
                        if (current.selectedFolderId == intent.folderId) null else intent.folderId
                    val visible = visiblePreviews(newSelection)
                    current.copy(
                        selectedFolderId = newSelection,
                        pinnedPreviews = visible.filter { it.isPinned },
                        notePreviews = visible.filter { !it.isPinned },
                    )
                }
            }

            is NotesListIntent.AddNoteClicked -> {
                createNewNote()
            }

            NotesListIntent.AllNotesClicked -> selectBottomBarItem(BottomBarItem.ALL_NOTES)
            NotesListIntent.CalendarClicked -> selectBottomBarItem(BottomBarItem.CALENDAR)
            NotesListIntent.ProfileClicked -> selectBottomBarItem(BottomBarItem.PROFILE)
            NotesListIntent.SearchClicked -> selectBottomBarItem(BottomBarItem.SEARCH)

            is NotesListIntent.SearchQueryChanged -> {
                // Two writes on purpose. The field itself is state, so it has to echo the
                // keystroke immediately or typing would feel dropped; the matching is driven off
                // searchQuery, where the debounce lives.
                _state.update { it.copy(searchQuery = intent.query) }
                searchQuery.value = intent.query
            }

            is NotesListIntent.CreateFolder -> {
                val name = normalizeFolderName(intent.name) ?: return
                viewModelScope.launch {
                    notesRepository.saveFolder(
                        Folder(id = UUID.randomUUID().toString(), name = name, colorArgb = intent.colorArgb)
                    )
                }
            }

            is NotesListIntent.UpdateFolder -> {
                val name = normalizeFolderName(intent.name) ?: return
                viewModelScope.launch {
                    notesRepository.saveFolder(Folder(id = intent.id, name = name, colorArgb = intent.colorArgb))
                }
            }

            is NotesListIntent.DeleteFolder -> {
                viewModelScope.launch { notesRepository.deleteFolder(intent.id) }
                // If the deleted folder was the active filter, fall back to "All" immediately. (The notes flow
                // re-emits after unfiling, but reset selection now so the chip selection isn't left dangling.)
                _state.update { current ->
                    if (current.selectedFolderId != intent.id) current
                    else {
                        val visible = visiblePreviews(null)
                        current.copy(
                            selectedFolderId = null,
                            pinnedPreviews = visible.filter { it.isPinned },
                            notePreviews = visible.filter { !it.isPinned },
                        )
                    }
                }
            }

            is NotesListIntent.MoveNoteToFolder -> {
                viewModelScope.launch { notesRepository.setNoteFolder(intent.noteId, intent.folderId) }
            }

            NotesListIntent.TrashClicked -> {
                viewModelScope.launch { _events.send(NotesListEvent.NavigateToTrash) }
            }

            is NotesListIntent.SortOrderSelected -> {
                // Persist only. The new value comes back through the settings flow, which
                // re-subscribes the notes query and re-emits the list (and the picker's state)
                // already in the chosen order — so there is no second, optimistic path to keep
                // in sync with what the database actually returns.
                viewModelScope.launch { settingsRepository.setNotesSortOrder(intent.order) }
            }
        }
    }

    /**
     * Switches bottom-bar tab, dropping the search query on the way OUT of the Search tab. Leaving
     * the tab is the user saying they are done searching, and a query left behind would keep the
     * search pipeline re-running on every note change while a different tab is on screen.
     *
     * Opening a note from a result does not come through here — that is a navigation, not a tab
     * switch — so returning from the editor still finds the query and its results in place.
     */
    private fun selectBottomBarItem(item: BottomBarItem) {
        val leavingSearch = item != BottomBarItem.SEARCH
        if (leavingSearch) searchQuery.value = ""
        _state.update { current ->
            current.copy(
                selectedBottomBarItem = item,
                searchQuery = if (leavingSearch) "" else current.searchQuery,
                searchResults = if (leavingSearch) emptyList() else current.searchResults,
                searchResultsQuery = if (leavingSearch) "" else current.searchResultsQuery,
            )
        }
    }

    private fun createNewNote() {
        viewModelScope.launch {
            val newNote = Note(
                id = UUID.randomUUID().toString(),
                title = "",
                content = ""
            )
            notesRepository.saveNote(newNote)
            // The one place isNew may be set: we own this id and just inserted it blank.
            _events.send(NavigateToNote(newNote.id, isNew = true))
        }
    }
}
