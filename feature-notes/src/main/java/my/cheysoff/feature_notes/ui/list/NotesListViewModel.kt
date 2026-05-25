package my.cheysoff.feature_notes.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import my.cheysoff.core_domain.model.HeaderSettings
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.repository.NotesRepository
import my.cheysoff.core_domain.repository.SettingsRepository
import my.cheysoff.feature_notes.model.list.BottomBarItem
import my.cheysoff.feature_notes.model.list.HeaderLineUi
import my.cheysoff.feature_notes.model.list.NotesListIntent
import my.cheysoff.feature_notes.model.list.NotesListScreenState
import my.cheysoff.feature_notes.model.list.toUi
import my.cheysoff.feature_notes.ui.list.NotesListEvent.NavigateToNote
import java.time.LocalTime
import java.util.UUID
import javax.inject.Inject

sealed class NotesListEvent {
    data class NavigateToNote(val noteId: String) : NotesListEvent()
}

@HiltViewModel
class NotesListViewModel @Inject constructor(
    private val notesRepository: NotesRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(NotesListScreenState())
    val state = _state.asStateFlow()

    private val _events = Channel<NotesListEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    // Latest full (unfiltered) note list, kept so folder selection can re-filter without a re-fetch.
    private var allNotes: List<Note> = emptyList()

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
            notesRepository.getNotes(),
        ) { settings, folders, notes -> Triple(settings, folders, notes) }
            .onEach { (settings, folders, notes) ->
                allNotes = notes
                val countByFolder = notes.groupingBy { it.folderId }.eachCount()
                val folderPreviews = folders.map { folder ->
                    folder.toUi(notesAmount = countByFolder[folder.id] ?: 0)
                }
                val stats = if (settings.showStats) {
                    "${notes.size} notes · ${notes.count { it.isPinned }} pinned"
                } else null
                _state.update { current ->
                    val visible = visibleNotes(current.selectedFolderId)
                    current.copy(
                        folderPreviews = folderPreviews,
                        pinnedPreviews = visible.filter { it.isPinned }.map { it.toUi() },
                        notePreviews = visible.filter { !it.isPinned }.map { it.toUi() },
                        statsLine = stats,
                        isLoading = false,
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    private fun visibleNotes(selectedFolderId: String?): List<Note> =
        if (selectedFolderId == null) allNotes
        else allNotes.filter { it.folderId == selectedFolderId }

    private val dailyPhrases = listOf(
        HeaderLineUi("One thing", "at a time."),
        HeaderLineUi("Tomorrow", "starts here."),
        HeaderLineUi("Capture", "the thought."),
        HeaderLineUi("Make it", "count."),
        HeaderLineUi("Today's", "canvas."),
    )

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
                    val visible = visibleNotes(newSelection)
                    current.copy(
                        selectedFolderId = newSelection,
                        pinnedPreviews = visible.filter { it.isPinned }.map { it.toUi() },
                        notePreviews = visible.filter { !it.isPinned }.map { it.toUi() },
                    )
                }
            }

            is NotesListIntent.AddNoteClicked -> {
                createNewNote()
            }

            NotesListIntent.AllNotesClicked -> {
                _state.update { it.copy(selectedBottomBarItem = BottomBarItem.ALL_NOTES) }
            }
            NotesListIntent.CalendarClicked -> {
                _state.update { it.copy(selectedBottomBarItem = BottomBarItem.CALENDAR) }
            }
            NotesListIntent.ProfileClicked -> {
                _state.update { it.copy(selectedBottomBarItem = BottomBarItem.PROFILE) }
            }
            NotesListIntent.SearchClicked -> {
                _state.update { it.copy(selectedBottomBarItem = BottomBarItem.SEARCH) }
            }
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
            _events.send(NavigateToNote(newNote.id))
        }
    }
}
