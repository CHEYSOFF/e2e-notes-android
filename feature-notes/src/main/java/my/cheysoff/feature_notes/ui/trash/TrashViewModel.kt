package my.cheysoff.feature_notes.ui.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import my.cheysoff.core_domain.model.Folder
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.TrashPolicy
import my.cheysoff.core_domain.repository.NotesRepository
import my.cheysoff.feature_notes.model.list.toUi
import my.cheysoff.feature_notes.model.trash.TrashEntryKind
import my.cheysoff.feature_notes.model.trash.TrashEntryUi
import my.cheysoff.feature_notes.model.trash.TrashIntent
import my.cheysoff.feature_notes.model.trash.TrashScreenState
import javax.inject.Inject

sealed class TrashEvent {
    data object NavigateBack : TrashEvent()
}

@HiltViewModel
class TrashViewModel @Inject constructor(
    private val notesRepository: NotesRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(TrashScreenState())
    val state = _state.asStateFlow()

    private val _events = Channel<TrashEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        // Auto-purge runs here, on Trash open, rather than at app start.
        //
        // That is the simpler of the two placements the design allows, and the only one that is
        // straightforwardly correct today: the database cannot be opened while the app is locked
        // (DataModule.provideNoteDatabase throws), and MainApplication.onCreate runs long before
        // any unlock, so a purge driven from there would need its own unlock-aware, app-scoped
        // coroutine. This screen is only reachable post-unlock, and it is the only place the
        // purged rows were visible, so purging as it opens is also the moment the user could
        // notice.
        //
        // The cost, stated plainly: Trash is never swept while the user stays out of this screen,
        // so an expired note can sit in the database indefinitely. It is not reachable, not shown
        // anywhere, and gone the first time Trash is opened — but it is still on disk until then.
        viewModelScope.launch {
            notesRepository.purgeExpiredTrash(System.currentTimeMillis())
        }

        // getFolders() is combined in for its COLORS, not its rows: a deleted note keeps its
        // folderId, and that folder is usually still alive, so its accent is what the card should
        // wear. Deleted folders contribute their own colors from the second flow.
        combine(
            notesRepository.getDeletedNotes(),
            notesRepository.getDeletedFolders(),
            notesRepository.getFolders(),
        ) { deletedNotes, deletedFolders, liveFolders ->
            Triple(deletedNotes, deletedFolders, liveFolders)
        }
            // Note.toUi() runs HtmlCompat.fromHtml over each body, which is O(content size) — the
            // same reason NotesListViewModel maps previews off the main thread.
            .map { (deletedNotes, deletedFolders, liveFolders) ->
                buildEntries(deletedNotes, deletedFolders, liveFolders, System.currentTimeMillis())
            }
            .flowOn(Dispatchers.Default)
            .onEach { entries -> _state.value = TrashScreenState(entries = entries, isLoading = false) }
            .launchIn(viewModelScope)
    }

    fun onIntent(intent: TrashIntent) {
        when (intent) {
            TrashIntent.BackClicked -> {
                viewModelScope.launch { _events.send(TrashEvent.NavigateBack) }
            }

            is TrashIntent.Restore -> {
                viewModelScope.launch {
                    when (intent.kind) {
                        TrashEntryKind.NOTE -> notesRepository.restoreNote(intent.id)
                        TrashEntryKind.FOLDER -> notesRepository.restoreFolder(intent.id)
                    }
                }
            }

            is TrashIntent.DeleteForever -> {
                viewModelScope.launch {
                    when (intent.kind) {
                        TrashEntryKind.NOTE -> notesRepository.purgeNote(intent.id)
                        TrashEntryKind.FOLDER -> notesRepository.purgeFolder(intent.id)
                    }
                }
            }
        }
    }
}

/**
 * Flattens the two tombstone lists into one newest-deleted-first list.
 *
 * The two source queries are each already ordered by `deletedAt DESC`, but a merge of two sorted
 * lists is not sorted, so the combined list is re-sorted here. `id` breaks ties for the same reason
 * the note queries append it: without a total order, two rows deleted in the same millisecond could
 * swap places between emissions of otherwise-unchanged data.
 *
 * A row with no stamp sorts last (not first), matching what the SQL does with NULL under DESC.
 */
private fun buildEntries(
    deletedNotes: List<Note>,
    deletedFolders: List<Folder>,
    liveFolders: List<Folder>,
    now: Long,
): List<TrashEntryUi> {
    // Colors from BOTH sides: a note whose folder was deleted after it still deserves that
    // folder's accent while both sit in Trash.
    val colorByFolder: Map<String, Long?> =
        (liveFolders + deletedFolders).associate { it.id to it.colorArgb }

    val noteEntries = deletedNotes.map { note ->
        val preview = note.toUi()
        TrashEntryUi(
            id = note.id,
            kind = TrashEntryKind.NOTE,
            title = note.title,
            snippet = preview.content,
            folderId = note.folderId,
            folderColorArgb = note.folderId?.let(colorByFolder::get),
            deletedAt = note.deletedAt,
            daysRemaining = TrashPolicy.daysRemaining(note.deletedAt, now),
        )
    }
    val folderEntries = deletedFolders.map { folder ->
        TrashEntryUi(
            id = folder.id,
            kind = TrashEntryKind.FOLDER,
            title = folder.name,
            snippet = "",
            folderId = folder.id,
            folderColorArgb = folder.colorArgb,
            deletedAt = folder.deletedAt,
            daysRemaining = TrashPolicy.daysRemaining(folder.deletedAt, now),
        )
    }
    return (noteEntries + folderEntries).sortedWith(
        compareByDescending<TrashEntryUi> { it.deletedAt ?: Long.MIN_VALUE }.thenBy { it.id }
    )
}
