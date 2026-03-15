package my.cheysoff.feature_notes.ui.single

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.repository.NotesRepository
import my.cheysoff.feature_notes.model.single.SingleNoteIntent
import my.cheysoff.feature_notes.model.single.SingleNoteScreenState
import javax.inject.Inject

sealed class SingleNoteEvent {
    data object NavigateBack : SingleNoteEvent()
}

@HiltViewModel
class SingleNoteViewModel @Inject constructor(
    private val notesRepository: NotesRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val noteId: String? = savedStateHandle["noteId"]
    private val _state = MutableStateFlow(SingleNoteScreenState())
    val state = _state.asStateFlow()

    private val _events = Channel<SingleNoteEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        noteId?.let { id ->
            notesRepository.getNoteById(id)
                .filterNotNull()
                .onEach { note ->
                    _state.update {
                        it.copy(
                            title = note.title,
                            content = note.content,
                            isPinned = note.isPinned
                        )
                    }
                }
                .launchIn(viewModelScope)
        }
    }

    fun onIntent(intent: SingleNoteIntent) {
        when (intent) {
            is SingleNoteIntent.TitleChanged -> {
                updateNote { it.copy(title = intent.title) }
            }

            is SingleNoteIntent.ContentChanged -> {
                updateNote { it.copy(content = intent.content) }
            }

            is SingleNoteIntent.TogglePin -> {
                updateNote { it.copy(isPinned = !it.isPinned) }
            }

            is SingleNoteIntent.MoreClicked -> {
                // TODO: Implement more options
            }

            is SingleNoteIntent.BackClicked -> {
                viewModelScope.launch {
                    _events.send(SingleNoteEvent.NavigateBack)
                }
            }
        }
    }

    private fun updateNote(update: (Note) -> Note) {
        val currentNote = Note(
            id = noteId ?: return, // for now only existing notes
            title = _state.value.title,
            content = _state.value.content,
            isPinned = _state.value.isPinned
        )
        val updatedNote = update(currentNote)
        viewModelScope.launch {
            notesRepository.saveNote(updatedNote)
        }
    }
}
