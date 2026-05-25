package my.cheysoff.feature_notes.ui.single

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    private var saveJob: Job? = null

    // Serializes DB writes so an older/delayed save can't run concurrently with a newer one.
    private val saveMutex = Mutex()

    init {
        noteId?.let { id ->
            notesRepository.getNoteById(id)
                .filterNotNull()
                .onEach { note ->
                    _state.update { currentState ->
                        if (currentState.isUITheSame(note)) {
                            // Editable fields unchanged; still refresh updatedAt so the editor's
                            // "Edited … ago" meta reflects the latest save without clobbering edits.
                            if (currentState.updatedAt != note.updatedAt) {
                                currentState.copy(updatedAt = note.updatedAt)
                            } else {
                                currentState
                            }
                        } else {
                            currentState.copy(
                                title = note.title,
                                content = note.content,
                                isPinned = note.isPinned,
                                folderId = note.folderId,
                                updatedAt = note.updatedAt,
                            )
                        }
                    }
                }
                .launchIn(viewModelScope)
        }
    }

    fun onIntent(intent: SingleNoteIntent) {
        when (intent) {
            is SingleNoteIntent.TitleChanged -> {
                _state.update { it.copy(title = intent.title) }
                saveNote(debounce = true)
            }

            is SingleNoteIntent.ContentChanged -> {
                _state.update { it.copy(content = intent.content) }
                saveNote(debounce = true)
            }

            is SingleNoteIntent.TogglePin -> {
                _state.update { it.copy(isPinned = !it.isPinned) }
                saveNote(debounce = false)
            }

            is SingleNoteIntent.MoreClicked -> {
                // TODO: Implement more options
            }

            is SingleNoteIntent.BackClicked -> {
                viewModelScope.launch {
                    saveNote(debounce = false)?.join()
                    _events.send(SingleNoteEvent.NavigateBack)
                }
            }
        }
    }

    private fun saveNote(debounce: Boolean): Job? {
        val id = noteId ?: return null
        saveJob?.cancel()
        val job = viewModelScope.launch {
            if (debounce) {
                delay(300)
            }
            // Serialize writes and persist the LATEST state (not a snapshot captured before
            // the delay), so a delayed/older save can't overwrite newer edits.
            saveMutex.withLock {
                val current = _state.value
                notesRepository.saveNote(
                    Note(
                        id = id,
                        title = current.title,
                        content = current.content,
                        isPinned = current.isPinned,
                        folderId = current.folderId
                    )
                )
            }
        }
        saveJob = job
        return job
    }

    private fun SingleNoteScreenState.isUITheSame(note: Note): Boolean {
        return title == note.title &&
                content == note.content &&
                isPinned == note.isPinned &&
                folderId == note.folderId
    }
}
