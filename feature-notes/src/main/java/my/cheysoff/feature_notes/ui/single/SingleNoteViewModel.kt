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

    // Tracks the latest metadata write (favorite/folder). Both serialize on saveMutex, so awaiting
    // this one in BackClicked also flushes any earlier-queued meta write before navigation cancels
    // viewModelScope and could drop an in-flight UPDATE.
    private var metaWriteJob: Job? = null

    // Serializes DB writes so an older/delayed save can't run concurrently with a newer one.
    private val saveMutex = Mutex()

    // Last note row seen from the DB (refreshed by the load flow after every write). Lets
    // BackClicked skip the upsert when the content fields are unchanged, so merely opening a
    // note never bumps updatedAt (which now drives the list's newest-first order).
    private var lastPersisted: Note? = null

    // True only when this screen opened on an already-empty note — i.e. the blank row that
    // createNewNote persists before navigating. Such a note is discarded if it's still empty on
    // back. An existing note the user empties out is NEVER auto-deleted: that would be data loss.
    private var openedEmpty: Boolean? = null

    init {
        noteId?.let { id ->
            notesRepository.getNoteById(id)
                // Only real content flips isLoaded, so the editor always seeds from a loaded note
                // (never from an empty placeholder). A missing/never-loading note is handled on the
                // screen side, where edits are forwarded regardless of load state.
                .filterNotNull()
                .onEach { note ->
                    lastPersisted = note
                    if (openedEmpty == null) {
                        openedEmpty = note.title.isBlank() && note.content.isBlank() &&
                                note.checklist.isBlank()
                    }
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
                                isFavorite = note.isFavorite,
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
                // Persist just isPinned via a targeted UPDATE, matching the favorite/folder paths:
                // pinning is metadata, so it must not bump updatedAt (which orders the list).
                _state.update { it.copy(isPinned = !it.isPinned) }
                noteId?.let { id ->
                    metaWriteJob = viewModelScope.launch {
                        saveMutex.withLock { notesRepository.setNotePinned(id, _state.value.isPinned) }
                    }
                }
            }

            is SingleNoteIntent.ToggleFavorite -> {
                // React immediately; persist just isFavorite via a targeted UPDATE (the upsert never
                // writes isFavorite). Serialize through saveMutex like SetFolder, reading the LATEST
                // state inside the lock so it can't interleave with an autosave or a rapid re-toggle.
                _state.update { it.copy(isFavorite = !it.isFavorite) }
                noteId?.let { id ->
                    metaWriteJob = viewModelScope.launch {
                        saveMutex.withLock { notesRepository.setNoteFavorite(id, _state.value.isFavorite) }
                    }
                }
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
                    metaWriteJob = viewModelScope.launch {
                        saveMutex.withLock { notesRepository.setNoteFolder(id, _state.value.folderId) }
                    }
                }
            }

            is SingleNoteIntent.MoreClicked -> {
                // TODO: Implement more options
            }

            is SingleNoteIntent.BackClicked -> {
                viewModelScope.launch {
                    // Flush any pending metadata write (favorite/folder/pin) before navigating, so
                    // popping the screen can't cancel an in-flight UPDATE.
                    metaWriteJob?.join()
                    val current = _state.value
                    val id = noteId
                    when {
                        // A never-written note is discarded rather than saved — otherwise an
                        // abandoned "+" tap would sit at the top of the newest-first list.
                        id != null && openedEmpty == true && current.title.isBlank() &&
                            current.content.isBlank() && current.checklist.isEmpty() -> {
                            saveJob?.cancel()
                            saveMutex.withLock { notesRepository.deleteNote(id) }
                        }

                        current.hasUnsavedContent() -> saveNote(debounce = false)?.join()

                        // Content matches the persisted row: skip the upsert entirely, so just
                        // reading a note doesn't refresh updatedAt and reorder the list.
                    }
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

    // Content fields only (title/body/checklist) — exactly what a save stamps updatedAt for.
    // Metadata (pin/favorite/folder) persists through targeted UPDATEs and must not force an upsert.
    private fun SingleNoteScreenState.hasUnsavedContent(): Boolean {
        val persisted = lastPersisted ?: return true
        return title != persisted.title ||
                content != persisted.content ||
                checklist.serializeChecklist() != persisted.checklist
    }

    private fun SingleNoteScreenState.isUITheSame(note: Note): Boolean {
        return title == note.title &&
                content == note.content &&
                checklist.serializeChecklist() == note.checklist &&
                isPinned == note.isPinned &&
                isFavorite == note.isFavorite &&
                folderId == note.folderId
    }
}
