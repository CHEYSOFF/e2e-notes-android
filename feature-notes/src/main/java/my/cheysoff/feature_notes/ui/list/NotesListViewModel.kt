package my.cheysoff.feature_notes.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.repository.NotesRepository
import my.cheysoff.feature_notes.model.list.BottomBarItem
import my.cheysoff.feature_notes.model.list.NotesListIntent
import my.cheysoff.feature_notes.model.list.NotesListScreenState
import my.cheysoff.feature_notes.model.list.toUi
import my.cheysoff.feature_notes.ui.list.NotesListEvent.NavigateToNote
import java.util.UUID
import javax.inject.Inject

sealed class NotesListEvent {
    data class NavigateToNote(val noteId: String) : NotesListEvent()
}

@HiltViewModel
class NotesListViewModel @Inject constructor(
    private val notesRepository: NotesRepository
) : ViewModel() {
    private val _state = MutableStateFlow(NotesListScreenState())
    val state = _state.asStateFlow()

    private val _events = Channel<NotesListEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        notesRepository.getNotes()
            .onEach { notes ->
                _state.update { it.copy(notePreviews = notes.map { note -> note.toUi() }, isLoading = false) }
            }
            .launchIn(viewModelScope)
    }

    fun onIntent(intent: NotesListIntent) {
        when (intent) {
            is NotesListIntent.NoteClicked -> {
                viewModelScope.launch {
                    _events.send(NavigateToNote(intent.noteId))
                }
            }

            is NotesListIntent.FolderClicked -> {
                // TODO
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
