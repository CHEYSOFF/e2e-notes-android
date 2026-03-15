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
import my.cheysoff.core_domain.repository.NotesRepository
import my.cheysoff.feature_notes.model.list.NotesListIntent
import my.cheysoff.feature_notes.model.list.NotesListScreenState
import my.cheysoff.feature_notes.model.list.toUi
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
                _state.update { it.copy(notePreviews = notes.map { note -> note.toUi() }) }
            }
            .launchIn(viewModelScope)
    }

    fun onIntent(intent: NotesListIntent) {
        when (intent) {
            is NotesListIntent.NoteClicked -> {
                viewModelScope.launch {
                    _events.send(NotesListEvent.NavigateToNote(intent.noteId))
                }
            }

            is NotesListIntent.FolderClicked -> {
                // TODO
            }

            is NotesListIntent.AddNoteClicked -> {
                // TODO
            }
        }
    }
}
