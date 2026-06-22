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
import my.cheysoff.feature_notes.model.single.ChecklistItem
import my.cheysoff.feature_notes.model.single.SingleNoteIntent
import my.cheysoff.feature_notes.model.single.SingleNoteScreenState
import my.cheysoff.feature_notes.model.single.parseChecklist
import my.cheysoff.feature_notes.model.single.serializeChecklist
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
                // Only real content flips isLoaded, so the editor always seeds from a loaded note
                // (never from an empty placeholder). A missing/never-loading note is handled on the
                // screen side, where edits are forwarded regardless of load state.
                .filterNotNull()
                .onEach { note ->
                    _state.update { currentState ->
                        val updated = if (currentState.isUITheSame(note)) {
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
                                checklist = parseChecklist(note.checklist),
                                isPinned = note.isPinned,
                                folderId = note.folderId,
                                updatedAt = note.updatedAt,
                            )
                        }
                        // Mark loaded once, so the editor knows it can initialize from the stored HTML.
                        if (updated.isLoaded) updated else updated.copy(isLoaded = true)
                    }
                }
                .launchIn(viewModelScope)
        }

        notesRepository.getFolders()
            .onEach { folders -> _state.update { it.copy(folders = folders) } }
            .launchIn(viewModelScope)
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

            is SingleNoteIntent.ChecklistItemAdded -> {
                _state.update { s ->
                    val item = ChecklistItem(id = intent.newId, text = "", isDone = false)
                    val list = s.checklist
                    val at = intent.afterId?.let { id -> list.indexOfFirst { it.id == id } } ?: -1
                    val next = if (at < 0) list + item else list.toMutableList().apply { add(at + 1, item) }
                    s.copy(checklist = next)
                }
                saveNote(debounce = false)
            }

            is SingleNoteIntent.ChecklistItemToggled -> {
                _state.update { s ->
                    s.copy(checklist = s.checklist.map {
                        if (it.id == intent.id) it.copy(isDone = !it.isDone) else it
                    })
                }
                saveNote(debounce = false)
            }

            is SingleNoteIntent.ChecklistItemTextChanged -> {
                _state.update { s ->
                    s.copy(checklist = s.checklist.map {
                        if (it.id == intent.id) it.copy(text = intent.text) else it
                    })
                }
                saveNote(debounce = true)
            }

            is SingleNoteIntent.ChecklistItemRemoved -> {
                _state.update { s ->
                    s.copy(checklist = s.checklist.filterNot { it.id == intent.id })
                }
                saveNote(debounce = false)
            }

            is SingleNoteIntent.SetFolder -> {
                // Update the editor immediately (accent + pill react), then persist just the
                // folderId via a targeted UPDATE — no full upsert, no updatedAt bump, matching the
                // list's move path. Serialize through saveMutex like saveNote(), and write the
                // LATEST state.folderId inside the lock, so this can't interleave with an autosave
                // upsert (which also writes folderId) or a rapid second SetFolder — every write
                // path converges on the current state instead of a stale captured value.
                _state.update { it.copy(folderId = intent.folderId) }
                noteId?.let { id ->
                    viewModelScope.launch {
                        saveMutex.withLock { notesRepository.setNoteFolder(id, _state.value.folderId) }
                    }
                }
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
                        checklist = current.checklist.serializeChecklist(),
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
                checklist.serializeChecklist() == note.checklist &&
                isPinned == note.isPinned &&
                folderId == note.folderId
    }
}
