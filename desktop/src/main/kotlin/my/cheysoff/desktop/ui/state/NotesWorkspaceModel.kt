package my.cheysoff.desktop.ui.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import my.cheysoff.core_domain.model.Folder
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NoteContentFormat
import my.cheysoff.core_domain.model.NotesSortOrder
import my.cheysoff.core_domain.model.SketchData
import my.cheysoff.core_domain.repository.NotesRepository
import my.cheysoff.desktop.store.DesktopSketches
import java.util.UUID

/**
 * The one state holder behind the desktop window.
 *
 * There is no ViewModel here and no DI: `androidx.lifecycle` is an Android library and the desktop
 * app has exactly one screen with one lifetime — the window's. It takes its [scope] from the
 * caller so a test can drive it on a `TestScope` and a window can tie it to its own composition.
 *
 * [now] and [newId] are injected for the same reason: a test that asserts on `updatedAt` or on
 * which note got selected must not depend on the wall clock or on a random UUID.
 */
class NotesWorkspaceModel(
    private val repository: NotesRepository,
    private val scope: CoroutineScope,
    /**
     * Null on the preview/screenshot build ([my.cheysoff.desktop.ui.preview.InMemoryNotesRepository]
     * carries no sketch storage of its own) -- see [DesktopSketches]'s own KDoc for why this is a
     * separate, optional dependency rather than a widening of [NotesRepository].
     */
    private val sketches: DesktopSketches? = null,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    /**
     * How long typing has to stop before the note is written. Long enough that a fast typist
     * produces one write per sentence rather than one per keystroke, short enough that the
     * "Saved" indicator moves while the user is still looking at it.
     */
    private val autosaveDelayMillis: Long = 600L,
    private val sortOrder: NotesSortOrder = NotesSortOrder.DEFAULT,
) {
    private val _state = MutableStateFlow(WorkspaceUiState())
    val state: StateFlow<WorkspaceUiState> = _state.asStateFlow()

    /** The last list the repository emitted, kept so UI-only actions can recompute without it. */
    private var latestNotes: List<Note> = emptyList()
    private var latestFolders: List<Folder> = emptyList()

    private var autosaveJob: Job? = null

    init {
        scope.launch {
            combine(repository.getNotes(sortOrder), repository.getFolders()) { notes, folders ->
                notes to folders
            }.collect { (notes, folders) ->
                latestNotes = notes
                latestFolders = folders
                _state.value = recompute(_state.value, loaded = true)
            }
        }

        // Independent of the notes/folders collection above, exactly like the phone's
        // SingleNoteViewModel: sketches live in their own store with their own live query, and
        // switching notes has to drop the previous note's subscription rather than layer a second
        // one on top -- hence flatMapLatest keyed on the selected id, not a plain collect per
        // selection change.
        val sketchesPort = sketches
        if (sketchesPort != null) {
            scope.launch {
                selectedNoteSketches(sketchesPort)
                    .collect { list -> _state.value = _state.value.copy(sketches = sketchesForDisplay(list)) }
            }
        }
    }

    // ---------------------------------------------------------------- list & folders

    fun selectFolder(folderId: String?) {
        _state.value = recompute(_state.value.copy(selectedFolderId = folderId))
    }

    fun selectNote(noteId: String?) {
        // Flush before switching: the debounce may still be holding the outgoing note's last few
        // keystrokes, and loadDraft is about to replace the draft that holds them.
        flushPendingSave()
        _state.value = recompute(_state.value.copy(selectedNoteId = noteId))
    }

    /**
     * Creates an empty note, writes it immediately and selects it.
     *
     * It is written before it is selected so that the row exists in the list the moment the editor
     * opens — a note that only appears after the first keystroke makes Ctrl+N look like it did
     * nothing. The empty row is the same thing the phone creates, and the same rule applies to it:
     * left blank, it is purged rather than sent to Trash (see [discardIfBlank]).
     */
    fun newNote(): String {
        flushPendingSave()
        val timestamp = now()
        val note = Note(
            id = newId(),
            title = "",
            content = "",
            contentFormat = NoteContentFormat.PLAIN,
            // A note created while a folder chip is active is filed into that folder. Creating it
            // unfiled would drop it out of the very list the user is looking at.
            folderId = _state.value.selectedFolderId,
            createdAt = timestamp,
            updatedAt = timestamp,
        )
        latestNotes = listOf(note) + latestNotes
        _state.value = recompute(_state.value.copy(selectedNoteId = note.id))
        scope.launch { repository.saveNote(note) }
        return note.id
    }

    fun deleteSelectedNote() {
        val id = _state.value.selectedNoteId ?: return
        autosaveJob?.cancel()
        val draft = _state.value.editor
        latestNotes = latestNotes.filterNot { it.id == id }
        _state.value = recompute(_state.value.copy(selectedNoteId = null, saveStatus = SaveStatus.Idle))
        scope.launch {
            // A note that was created and left blank must never reach Trash — it would be a row
            // the user has to clean up for having pressed Ctrl+N by accident.
            if (draft != null && draft.isBlank()) repository.purgeNote(id) else repository.deleteNote(id)
        }
    }

    fun togglePinned() = mutateSelected { it.copy(isPinned = !it.isPinned) }

    fun toggleFavorite() = mutateSelected { it.copy(isFavorite = !it.isFavorite) }

    fun setNoteFolder(folderId: String?) = mutateSelected { it.copy(folderId = folderId) }

    // ---------------------------------------------------------------- sketches

    /**
     * Deletes one sketch. There is no undo and no Trash for a sketch (`TrashEntryKind` is
     * `{NOTE, FOLDER}`), so the confirmation the caller shows before invoking this is the only
     * safety net -- see [my.cheysoff.desktop.ui.notes.SketchSection].
     *
     * Removed from [WorkspaceUiState.sketches] immediately rather than waiting for the repository's
     * own echo, the same optimism [deleteSelectedNote] applies to the note list -- the row the user
     * just deleted must not still be on screen while the write is in flight.
     */
    fun deleteSketch(id: String) {
        val port = sketches ?: return
        _state.value = _state.value.copy(sketches = _state.value.sketches.filterNot { it.id == id })
        scope.launch { port.deleteSketch(id) }
    }

    // ---------------------------------------------------------------- editing

    fun setTitle(title: String) = mutateSelected { it.copy(title = title) }

    /**
     * The rich-text body, as HTML from the editor.
     *
     * Writing to the body promotes the row to [NoteContentFormat.HTML] — the same promotion the
     * Android editor does, and for the same reason: the format is recorded, never guessed.
     * A body that is still empty stays PLAIN, because an empty body is identical either way and
     * an HTML-marked empty note would round-trip through a parser for nothing.
     */
    fun setContent(html: String) = mutateSelected {
        it.copy(
            content = html,
            contentFormat = if (html.isEmpty()) NoteContentFormat.PLAIN else NoteContentFormat.HTML,
        )
    }

    fun addChecklistItem(after: String? = null): String {
        val id = newId()
        mutateSelected { draft ->
            val item = DesktopChecklistItem(id = id, text = "", isDone = false)
            val at = draft.checklist.indexOfFirst { it.id == after }
            val items = draft.checklist.toMutableList()
            if (at >= 0) items.add(at + 1, item) else items.add(item)
            draft.copy(checklist = items)
        }
        return id
    }

    fun setChecklistItemText(itemId: String, text: String) = mutateSelected { draft ->
        draft.copy(
            checklist = draft.checklist.map {
                // Normalized on the way IN, not just on the way out: the format cannot hold a
                // newline, so state that held one would not survive its own serialize/parse echo.
                if (it.id == itemId) it.copy(text = normalizeChecklistText(text)) else it
            }
        )
    }

    fun toggleChecklistItem(itemId: String) = mutateSelected { draft ->
        draft.copy(checklist = draft.checklist.map { if (it.id == itemId) it.copy(isDone = !it.isDone) else it })
    }

    fun removeChecklistItem(itemId: String) = mutateSelected { draft ->
        draft.copy(checklist = draft.checklist.filterNot { it.id == itemId })
    }

    // ---------------------------------------------------------------- search

    fun openSearch() {
        _state.value = _state.value.copy(search = _state.value.search.copy(isOpen = true))
    }

    fun closeSearch() {
        _state.value = _state.value.copy(search = SearchState())
    }

    fun setSearchQuery(query: String) {
        val hits = searchRows(currentRows(), query)
        _state.value = _state.value.copy(
            search = _state.value.search.copy(query = query, hits = hits, highlighted = 0)
        )
    }

    /** Moves the palette's highlight by [delta], clamped rather than wrapped. */
    fun moveSearchHighlight(delta: Int) {
        val search = _state.value.search
        if (search.hits.isEmpty()) return
        val next = (search.highlighted + delta).coerceIn(0, search.hits.lastIndex)
        _state.value = _state.value.copy(search = search.copy(highlighted = next))
    }

    /**
     * Opens the highlighted result and closes the palette.
     *
     * Opening a result clears the folder filter when the hit is not in the filtered list. Search
     * runs over the whole library, so a chip left active would otherwise let the palette select a
     * note the sidebar cannot show — the editor would be on a note with no row.
     */
    fun openHighlightedSearchHit(): String? {
        val search = _state.value.search
        val hit = search.hits.getOrNull(search.highlighted) ?: return null
        openSearchHit(hit.row.id)
        return hit.row.id
    }

    fun openSearchHit(noteId: String) {
        flushPendingSave()
        val note = latestNotes.firstOrNull { it.id == noteId }
        val folderStillShowsIt =
            _state.value.selectedFolderId == null || note?.folderId == _state.value.selectedFolderId
        _state.value = recompute(
            _state.value.copy(
                selectedNoteId = noteId,
                selectedFolderId = if (folderStillShowsIt) _state.value.selectedFolderId else null,
                search = SearchState(),
            )
        )
    }

    // ---------------------------------------------------------------- saving

    /**
     * Writes the draft now, cancelling any pending debounce. Called on window close and before the
     * draft is swapped out; also what Ctrl+S does, which is otherwise a no-op by design.
     */
    fun flushPendingSave() {
        val draft = _state.value.editor ?: return
        if (autosaveJob?.isActive != true) return
        autosaveJob?.cancel()
        persist(draft)
    }

    private fun scheduleSave(draft: EditorDraft) {
        autosaveJob?.cancel()
        _state.value = _state.value.copy(saveStatus = SaveStatus.Pending)
        autosaveJob = scope.launch {
            delay(autosaveDelayMillis)
            persist(draft)
        }
    }

    private fun persist(draft: EditorDraft) {
        val note = draft.toNote(updatedAt = now())
        // The local list is updated ahead of the repository echo so the sidebar row's title and
        // snippet track what is being typed. Waiting for the round trip would make the list lag a
        // whole debounce behind the editor.
        latestNotes = latestNotes.map { if (it.id == note.id) note else it }
        _state.value = recompute(_state.value.copy(saveStatus = SaveStatus.Saved(note.updatedAt)))
        scope.launch { repository.saveNote(note) }
    }

    // ---------------------------------------------------------------- internals

    /**
     * The live list of sketches for whichever note is selected, re-subscribing whenever the
     * selection changes and emitting empty when nothing is. `distinctUntilChanged` keeps an
     * unrelated state change (a keystroke in the body, autosave ticking over) from restarting the
     * subscription for the note that is already open.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun selectedNoteSketches(port: DesktopSketches) =
        state.map { it.selectedNoteId }
            .distinctUntilChanged()
            .flatMapLatest { id -> if (id == null) flowOf(emptyList<SketchData>()) else port.getSketchesForNote(id) }

    private fun currentRows(): List<NoteRowUi> = latestNotes.toRows(latestFolders)

    /**
     * Applies [edit] to the open draft, publishes it and restarts the autosave debounce.
     * A no-op when nothing is open, which is what makes the keyboard shortcuts safe to fire
     * against an empty editor pane.
     */
    private fun mutateSelected(edit: (EditorDraft) -> EditorDraft) {
        val draft = _state.value.editor ?: return
        val updated = edit(draft)
        if (updated == draft) return
        _state.value = _state.value.copy(editor = updated)
        scheduleSave(updated)
    }

    /**
     * Rebuilds the derived halves of [base] — chips, sections, selection, search hits — from the
     * latest repository emission.
     *
     * The draft is rebuilt from the repository only when the SELECTED NOTE changed. While the id
     * is the same the draft is left exactly as it is, which is the rule that keeps an autosave's
     * own echo from resetting the text under the cursor.
     */
    private fun recompute(base: WorkspaceUiState, loaded: Boolean = base.loaded): WorkspaceUiState {
        val rows = currentRows()
        val content = buildListContent(rows, base.selectedFolderId)
        val selected = resolveSelection(content, base.selectedNoteId)
        val editor = when {
            selected == null -> null
            selected == base.editor?.id -> base.editor
            else -> latestNotes.firstOrNull { it.id == selected }?.toDraft()
        }
        val search =
            if (base.search.isOpen) base.search.copy(hits = searchRows(rows, base.search.query))
            else base.search
        return base.copy(
            folders = latestFolders,
            chips = buildFolderChips(latestFolders, rows),
            content = content,
            selectedNoteId = selected,
            editor = editor,
            search = search,
            loaded = loaded,
        )
    }
}

private fun Note.toDraft() = EditorDraft(
    id = id,
    title = title,
    content = content,
    contentFormat = contentFormat,
    checklist = parseChecklist(checklist),
    isPinned = isPinned,
    isFavorite = isFavorite,
    folderId = folderId,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun EditorDraft.toNote(updatedAt: Long) = Note(
    id = id,
    title = title,
    content = content,
    contentFormat = contentFormat,
    checklist = checklist.serializeChecklist(),
    isPinned = isPinned,
    isFavorite = isFavorite,
    folderId = folderId,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

/** A draft with nothing in any of the three fields the user can type into. */
internal fun EditorDraft.isBlank(): Boolean =
    title.isBlank() &&
        htmlToPlainText(content).isBlank() &&
        checklist.all { it.text.isBlank() }
