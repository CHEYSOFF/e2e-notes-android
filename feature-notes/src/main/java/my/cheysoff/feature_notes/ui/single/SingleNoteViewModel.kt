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

/**
 * The editor-owned fields of the stored row as this screen last knew them: either the values it
 * last wrote, or the values the last DB emission reported — whichever happened more recently.
 *
 * This is the reference point for the question "has the user changed this field since the DB last
 * held it?". Room's InvalidationTracker re-emits the row after *every* write, including this
 * screen's own autosave, so the load flow is a firehose of echoes rather than a source of news.
 * Without a reference point, an echo that lags behind the user's typing looks exactly like an
 * external change and silently rolls the editor back.
 */
internal data class EditorBaseline(
    val title: String,
    val content: String,
    val checklist: String,
    val isPinned: Boolean,
    val isFavorite: Boolean,
    val folderId: String?,
)

internal fun Note.toEditorBaseline(): EditorBaseline = EditorBaseline(
    title = title,
    content = content,
    checklist = checklist,
    isPinned = isPinned,
    isFavorite = isFavorite,
    folderId = folderId,
)

internal data class NoteMergeResult(
    val state: SingleNoteScreenState,
    val baseline: EditorBaseline,
)

/**
 * Reconciles one row from the DB against what the editor currently shows.
 *
 * The rule is per field, not per row: adopt the incoming value only when the local value still
 * equals [baseline] — i.e. the user has not touched that field since the DB last held it. A field
 * the user *has* moved always wins, because the local value is by construction newer than anything
 * the DB can report (every edit is followed by a write of that same local value).
 *
 * Per field rather than per row so a genuine external change still lands while the user types:
 * today the only realistic one is the list screen's MoveNoteToFolder (and the folder-delete
 * unfiling) changing `folderId` while this screen is open. Row-level "keep everything local" would
 * swallow that; row-level "take everything from the DB" is the bug this function exists to fix.
 */
internal fun mergeIncomingNote(
    current: SingleNoteScreenState,
    baseline: EditorBaseline?,
    incoming: Note,
): NoteMergeResult {
    // First row for this note. There is no baseline to reason against yet and the editor has not
    // seeded its rich-text content (SingleNoteScreen only starts forwarding edits once isLoaded
    // flips), so the stored row simply wins: losing a keystroke typed into a not-yet-loaded editor
    // is far cheaper than overwriting a real note with the blank state the editor starts in.
    if (baseline == null) {
        return NoteMergeResult(
            state = current.copy(
                title = incoming.title,
                content = incoming.content,
                checklist = parseChecklist(incoming.checklist),
                isPinned = incoming.isPinned,
                isFavorite = incoming.isFavorite,
                folderId = incoming.folderId,
                updatedAt = incoming.updatedAt,
                isLoaded = true,
            ),
            baseline = incoming.toEditorBaseline(),
        )
    }

    val checklistUntouched = current.checklist.serializeChecklist() == baseline.checklist
    return NoteMergeResult(
        state = current.copy(
            title = if (current.title == baseline.title) incoming.title else current.title,
            content = if (current.content == baseline.content) incoming.content else current.content,
            // Even when adopting, go through mergeChecklist rather than parseChecklist: the common
            // case is the echo of our own write, where the items are identical and re-parsing would
            // only destroy their ids.
            checklist = if (checklistUntouched) {
                mergeChecklist(current.checklist, incoming.checklist)
            } else {
                current.checklist
            },
            isPinned = if (current.isPinned == baseline.isPinned) incoming.isPinned else current.isPinned,
            isFavorite = if (current.isFavorite == baseline.isFavorite) {
                incoming.isFavorite
            } else {
                current.isFavorite
            },
            folderId = if (current.folderId == baseline.folderId) incoming.folderId else current.folderId,
            // Not editable, so always adopted — this is what keeps the editor's "Edited … ago"
            // line honest after a save.
            updatedAt = incoming.updatedAt,
            isLoaded = true,
        ),
        // Whatever the merge decided to *show*, the row we were just handed is what the DB *holds*,
        // so that is the new reference point. (Combined with the write path recording every value
        // it sends, the baseline is always "the last value that passed between us and the DB".)
        baseline = incoming.toEditorBaseline(),
    )
}

/**
 * Adopts [incoming] while keeping the [ChecklistItem.id]s of rows that did not actually change.
 *
 * Ids are ephemeral and never serialized, so a naive re-parse mints fresh ones on every emission.
 * That strands any in-flight ChecklistItemTextChanged intent (it addresses the id the row had when
 * the keystroke happened, which now matches nothing, so the keystroke is silently dropped) and
 * steals focus from the row being typed into, because Compose keys off the id.
 *
 * Rows are matched positionally: that covers the echo of our own write and small in-place edits.
 * A genuinely reordered list does get fresh ids, which is acceptable — nothing in this app reorders
 * a checklist behind the editor's back.
 */
internal fun mergeChecklist(current: List<ChecklistItem>, incoming: String): List<ChecklistItem> {
    val parsed = parseChecklist(incoming)
    val merged = parsed.mapIndexed { index, item ->
        val existing = current.getOrNull(index)
        if (existing != null && existing.text == item.text && existing.isDone == item.isDone) {
            existing
        } else {
            item
        }
    }
    // Hand back the original instance when nothing moved, so the StateFlow's equality check sees
    // "no change" and the list doesn't recompose at all.
    return if (merged == current) current else merged
}

/**
 * How much slack to allow between the "+" button persisting a blank note and this screen's
 * ViewModel being constructed. Generous: the two are separated only by one INSERT and a navigation.
 */
internal const val NEW_NOTE_GRACE_MS = 10_000L

/**
 * True when [note] is the blank row the "+" button inserted moments before this screen opened —
 * the only note this screen is ever allowed to auto-discard.
 *
 * "Blank on open" alone is NOT sufficient and used to be the test, which made this a data-loss
 * path: a user who empties an existing note, backs out (correctly not deleted), then reopens it,
 * would see a blank first emission and lose the note — along with its pin/favorite/folder and
 * createdAt — by simply backing out again. There is no other delete path in the app and no undo.
 *
 * The three extra conditions each rule that out:
 *  - createdAt != 0L skips legacy pre-migration rows, which carry no real timestamps.
 *  - createdAt == updatedAt means nothing has been written since the INSERT; emptying an existing
 *    note goes through saveNote, which bumps updatedAt.
 *  - the grace window means the INSERT happened while this screen was being opened, not in some
 *    earlier session.
 *
 * Erring is one-directional: a missed detection just leaves an abandoned blank note in the list.
 */
internal fun isFreshlyCreatedBlankNote(note: Note, screenOpenedAt: Long): Boolean =
    note.title.isBlank() && note.content.isBlank() && note.checklist.isBlank() &&
            note.createdAt != 0L &&
            note.createdAt == note.updatedAt &&
            note.createdAt >= screenOpenedAt - NEW_NOTE_GRACE_MS

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

    // Tracks the latest metadata write (pin/favorite/folder). All serialize on saveMutex, so awaiting
    // this one in BackClicked also flushes any earlier-queued meta write before navigation cancels
    // viewModelScope and could drop an in-flight UPDATE.
    private var metaWriteJob: Job? = null

    // Serializes DB writes so an older/delayed save can't run concurrently with a newer one.
    private val saveMutex = Mutex()

    // What the stored row is known to hold for the fields this editor owns — moved by both the load
    // flow (what the DB told us) and every write path (what we told the DB). Null until the first
    // row arrives. See EditorBaseline / mergeIncomingNote: this is what lets an emission be
    // classified as "our own echo" instead of news worth clobbering the user's typing with, and it
    // is also what hasUnsavedContent() compares against.
    private var baseline: EditorBaseline? = null

    // When this screen opened, used to recognise the blank row the "+" button inserted a moment ago.
    private val screenOpenedAt: Long = System.currentTimeMillis()

    // True only for a note that was created-as-blank for this very screen (see
    // isFreshlyCreatedBlankNote). Such a note is discarded if it's still empty on back. Any other
    // note — including an existing one the user has emptied out, in this session or a previous
    // one — is NEVER auto-deleted: that would be data loss with no undo.
    private var createdBlankNote = false

    init {
        noteId?.let { id ->
            notesRepository.getNoteById(id)
                // Only real content flips isLoaded, so the editor always seeds from a loaded note
                // (never from an empty placeholder). A missing/never-loading note is handled on the
                // screen side, where edits are forwarded regardless of load state.
                .filterNotNull()
                .onEach { note ->
                    // Decided once, on the first row: whether this screen is looking at a note that
                    // was just created for it. Later emissions can't be trusted for this — a note
                    // the user empties out looks identical to a brand new one.
                    if (baseline == null) {
                        createdBlankNote = isFreshlyCreatedBlankNote(note, screenOpenedAt)
                    }
                    // Read/merge/assign rather than update {}: the merge also has to move the
                    // baseline, which must happen exactly once per emission (update {} may retry
                    // its block). Safe because every mutation of _state runs on the main
                    // dispatcher and there is no suspension point between the read and the write.
                    val merged = mergeIncomingNote(_state.value, baseline, note)
                    baseline = merged.baseline
                    _state.value = merged.state
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
                    writeMeta(
                        persist = { notesRepository.setNotePinned(id, it.isPinned) },
                        record = { base, s -> base.copy(isPinned = s.isPinned) },
                    )
                }
            }

            is SingleNoteIntent.ToggleFavorite -> {
                // React immediately; persist just isFavorite via a targeted UPDATE (the upsert never
                // writes isFavorite). Serialize through saveMutex like SetFolder, reading the LATEST
                // state inside the lock so it can't interleave with an autosave or a rapid re-toggle.
                _state.update { it.copy(isFavorite = !it.isFavorite) }
                noteId?.let { id ->
                    writeMeta(
                        persist = { notesRepository.setNoteFavorite(id, it.isFavorite) },
                        record = { base, s -> base.copy(isFavorite = s.isFavorite) },
                    )
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
                    writeMeta(
                        persist = { notesRepository.setNoteFolder(id, it.folderId) },
                        record = { base, s -> base.copy(folderId = s.folderId) },
                    )
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
                        // A note created for this screen and never written into is discarded rather
                        // than saved — otherwise an abandoned "+" tap would sit at the top of the
                        // newest-first list. Note the guard is createdBlankNote, not "is blank now":
                        // an existing note the user emptied out is kept.
                        id != null && createdBlankNote && current.title.isBlank() &&
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

    /**
     * Runs one targeted metadata UPDATE (pin/favorite/folder). [persist] writes against the LATEST
     * state — read inside the lock, so queueing behind an autosave can never persist a stale
     * value — and [record] folds the written value into the baseline, so the row Room re-emits for
     * this write is recognised as our own echo.
     *
     * [record] must fold in ONLY the field [persist] actually wrote: claiming a field the UPDATE
     * didn't touch would make the next emission (which still carries the old value for it) look
     * like an external change and get adopted, reverting a still-queued sibling write.
     */
    private fun writeMeta(
        persist: suspend (SingleNoteScreenState) -> Unit,
        record: (EditorBaseline, SingleNoteScreenState) -> EditorBaseline,
    ) {
        metaWriteJob = viewModelScope.launch {
            saveMutex.withLock {
                val current = _state.value
                persist(current)
                // No suspension between the write and this line, so the load flow cannot slip in
                // between and see a baseline that predates the write.
                baseline = baseline?.let { record(it, current) }
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
                val checklist = current.checklist.serializeChecklist()
                notesRepository.saveNote(
                    Note(
                        id = id,
                        title = current.title,
                        content = current.content,
                        checklist = checklist,
                        isPinned = current.isPinned,
                        folderId = current.folderId
                    )
                )
                // The DB now holds exactly these fields, so move the baseline before yielding (see
                // writeMeta). isFavorite is deliberately absent: the upsert doesn't write it.
                baseline = baseline?.copy(
                    title = current.title,
                    content = current.content,
                    checklist = checklist,
                    isPinned = current.isPinned,
                    folderId = current.folderId,
                )
            }
        }
        saveJob = job
        return job
    }

    // Content fields only (title/body/checklist) — exactly what a save stamps updatedAt for.
    // Metadata (pin/favorite/folder) persists through targeted UPDATEs and must not force an upsert.
    // Compared against the baseline rather than the last row *seen*: an edit made after the last
    // write must count as unsaved even though Room hasn't echoed that write back yet.
    private fun SingleNoteScreenState.hasUnsavedContent(): Boolean {
        val persisted = baseline ?: return true
        return title != persisted.title ||
                content != persisted.content ||
                checklist.serializeChecklist() != persisted.checklist
    }
}
