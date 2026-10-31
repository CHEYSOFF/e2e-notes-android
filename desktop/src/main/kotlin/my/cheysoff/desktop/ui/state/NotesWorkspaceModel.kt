package my.cheysoff.desktop.ui.state

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import my.cheysoff.core_blocks.Block
import my.cheysoff.core_blocks.BlockCodec
import my.cheysoff.core_blocks.BlockDocument
import my.cheysoff.core_blocks.BlockText
import my.cheysoff.core_blocks.BlocksSave
import my.cheysoff.core_blocks.OpenDocument
import my.cheysoff.core_domain.model.AttachmentData
import my.cheysoff.core_domain.sketch.NoteBlocks
import my.cheysoff.desktop.ui.attachment.DesktopImportResult
import my.cheysoff.core_domain.model.Folder
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NoteContentFormat
import my.cheysoff.core_domain.model.NotesSortOrder
import my.cheysoff.core_domain.model.SketchData
import my.cheysoff.core_domain.repository.NotesRepository
import my.cheysoff.core_domain.sketch.sketchesForDisplay
import my.cheysoff.desktop.store.DesktopAttachments
import my.cheysoff.desktop.store.DesktopSketches
import my.cheysoff.desktop.ui.attachment.orderedForDisplay
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
    /**
     * Null on the preview/screenshot build, for the exact same reason [sketches] is -- see
     * [DesktopAttachments]'s own KDoc.
     */
    private val attachments: DesktopAttachments? = null,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    /**
     * How long typing has to stop before the note is written. Long enough that a fast typist
     * produces one write per sentence rather than one per keystroke, short enough that the
     * "Saved" indicator moves while the user is still looking at it.
     */
    private val autosaveDelayMillis: Long = 600L,
    private val sortOrder: NotesSortOrder = NotesSortOrder.DEFAULT,
    /** Where a pasted image is decoded and re-encoded; injected so a test can keep it on its scheduler. */
    private val encodeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /**
     * The debounce between the block editor's last change and the save. Shorter than
     * [autosaveDelayMillis] because the editor reports finished blocks, not a field to re-serialise.
     */
    private val blocksSaveDelayMillis: Long = 400L,
    private val log: (String) -> Unit = { System.err.println(it) },
) {
    private val _state = MutableStateFlow(WorkspaceUiState())
    val state: StateFlow<WorkspaceUiState> = _state.asStateFlow()

    /** The last list the repository emitted, kept so UI-only actions can recompute without it. */
    private var latestNotes: List<Note> = emptyList()
    private var latestFolders: List<Folder> = emptyList()

    private var autosaveJob: Job? = null

    /** Saves launched and not yet finished; [flushAndAwait] joins them. Completion may run off-thread. */
    private val savesInFlight = HashSet<Job>()

    /** Serializes saves; see [persist]. */
    private val saveMutex = Mutex()

    /** The stored row the open draft was last reconciled with. See [mergeIncoming]. */
    private var editorBaseline: Note? = null

    /**
     * The block editor's latest unsaved state, with the base it was edited against, or null when
     * the last one was written. Captured together at [onBlocksChanged] time, never re-read at save
     * time: a synced change the editor applies between the edit and the save moves the editor's base
     * but not these blocks, and stamping old blocks against a newer base would read the synced change
     * as the user undoing it. Cleared by the save that writes it, for the same reason: re-sending it
     * later would claim blocks the other device has changed since.
     */
    private var unsavedBlocks: UnsavedBlocks? = null

    private class UnsavedBlocks(val noteId: String, val blocks: List<Block>, val base: BlockDocument)

    /**
     * Ids of pasted pictures whose record is being written but whose block the editor has not
     * inserted yet. Left out of the assembled document meanwhile, because a record with no block is
     * otherwise appended at the end by [OpenDocument.assemble] -- the editor would receive it there
     * first and the insert at the caret would then meet a block it already shows.
     */
    private val picturesBeingPlaced = MutableStateFlow<Set<String>>(emptySet())

    /** The last assembled document, re-applied to a draft created after it arrived (see [recompute]). */
    private var lastOpened: OpenedDocument? = null

    private class OpenedDocument(val noteId: String, val document: BlockDocument, val unreadableBody: String?)

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

        // The exact mirror of the sketches subscription above: its own live query, its own
        // flatMapLatest keyed on the selection so switching notes drops the previous note's
        // subscription rather than layering a second one on top.
        val attachmentsPort = attachments
        if (attachmentsPort != null) {
            scope.launch {
                selectedNoteAttachments(attachmentsPort)
                    .collect { list -> _state.value = _state.value.copy(attachments = list) }
            }
        }

        // The open note's document for the block editor; see openedDocuments.
        scope.launch { openedDocuments().collect(::attachDocument) }
    }

    // ---------------------------------------------------------------- list & folders

    fun selectFolder(folderId: String?) {
        _state.value = recompute(_state.value.copy(selectedFolderId = folderId))
    }

    fun selectNote(noteId: String?) {
        // Flush before switching: the debounce may still be holding the outgoing note's last few
        // keystrokes, and loadDraft is about to replace the draft that holds them.
        flushPendingSave()
        // A viewer left open on the outgoing note's photo must not silently persist over the
        // incoming note -- see WorkspaceUiState.viewingAttachmentId's own KDoc.
        _state.value = recompute(
            _state.value.copy(selectedNoteId = noteId, viewingAttachmentId = null, pasteFailure = null),
        )
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
        _state.value = recompute(_state.value.copy(selectedNoteId = note.id, viewingAttachmentId = null))
        scope.launch { repository.saveNote(note) }
        return note.id
    }

    fun deleteSelectedNote() {
        val id = _state.value.selectedNoteId ?: return
        val draft = _state.value.editor
        val typed = unsavedBlocks?.takeIf { it.noteId == id }
        // Typed blocks still waiting for their debounce are written first, so Trash (and Undo out
        // of it) holds what was on screen, not the body of the last save.
        if (typed != null && draft != null && draft.id == id && autosaveJob?.isActive == true) {
            autosaveJob?.cancel()
            persist(draft)
        }
        autosaveJob?.cancel()
        unsavedBlocks = null
        val savesBefore = synchronized(savesInFlight) { savesInFlight.toList() }
        // A pasted photo or a sketch is content too, even with no text beside it. Read before the
        // selection is cleared, which empties both lists.
        val hasMedia = _state.value.attachments.isNotEmpty() || _state.value.sketches.isNotEmpty()
        latestNotes = latestNotes.filterNot { it.id == id }
        _state.value = recompute(
            _state.value.copy(selectedNoteId = null, saveStatus = SaveStatus.Idle, viewingAttachmentId = null),
        )
        scope.launch {
            // After every save already launched, the flush above included, or the delete could
            // land first and the save would write the note back over it.
            savesBefore.joinAll()
            // A note that was created and left blank must never reach Trash — it would be a row
            // the user has to clean up for having pressed Ctrl+N by accident.
            val blank = draft != null && draft.isBlank() && (typed == null || BlockText.isEmpty(typed.blocks))
            if (blank && !hasMedia) repository.purgeNote(id) else repository.deleteNote(id)
        }
    }

    fun togglePinned() = mutateSelected { it.copy(isPinned = !it.isPinned) }

    fun toggleFavorite() = mutateSelected { it.copy(isFavorite = !it.isFavorite) }

    fun setNoteFolder(folderId: String?) = mutateSelected { it.copy(folderId = folderId) }

    // ---------------------------------------------------------------- attachments

    /**
     * Attaches a pasted image to the open note, at the end of its body -- the same placement the
     * phone gives a picked photo: anchored at the note's current block count, next in order there.
     *
     * [encode] is the slow half (decode, downscale, JPEG) and is expected to have run off the UI
     * thread already; this only stamps identity and placement and writes. Added to
     * [WorkspaceUiState.attachments] immediately rather than after the repository's echo.
     *
     * The editor passes [onAttached], which gets the new record's id once the record is written, to
     * place the picture block at the caret. Until then the record is kept out of the assembled
     * document (see [picturesBeingPlaced]). Without it the record is only written, and the next
     * assembly appends its picture at the end of the document (`OpenDocument.assemble`).
     */
    fun pasteImage(
        // First, so the encode stays the trailing lambda at every existing call site.
        onAttached: ((recordId: String) -> Unit)? = null,
        encode: () -> DesktopImportResult,
    ): Job? {
        val port = attachments ?: return null
        val noteId = _state.value.selectedNoteId ?: return null
        if (onAttached != null) {
            // A picture is a block of the body. A read-only note has no editable
            // body to put it in, and a note whose document has not been assembled yet has no editor
            // to insert it; either way a record written now would be a picture the user did not see
            // land, appended at the end on the next open. Refused before anything is written.
            val draft = _state.value.editor?.takeIf { it.id == noteId } ?: return null
            if (draft.unreadableBody != null || draft.blocks == null) return null
        }
        return scope.launch {
            val result = withContext(encodeDispatcher) { encode() }
            if (result !is DesktopImportResult.Imported) {
                _state.value = _state.value.copy(pasteFailure = result)
                return@launch
            }
            val draft = _state.value.editor?.takeIf { it.id == noteId }
            val anchor = if (draft == null) 0 else NoteBlocks.count(draft.content, draft.contentFormat)
            val existing = _state.value.attachments
            val attachment = result.attachment.copy(
                id = newId(),
                noteId = noteId,
                anchor = anchor,
                order = (existing.filter { it.anchor == anchor }.maxOfOrNull { it.order } ?: -1) + 1,
                createdAt = now(),
                updatedAt = now(),
            )
            if (_state.value.selectedNoteId == noteId) {
                _state.value = _state.value.copy(attachments = existing + attachment, pasteFailure = null)
            }
            if (onAttached == null) {
                port.saveAttachment(attachment)
                return@launch
            }
            picturesBeingPlaced.value += attachment.id
            try {
                // The record first, then the block: a block must never point at a record that was
                // not written. If the user has moved to another note meanwhile, the block is not
                // placed; the record is still the note's, and opening it appends the picture.
                port.saveAttachment(attachment)
                if (_state.value.selectedNoteId == noteId) onAttached(attachment.id)
            } finally {
                picturesBeingPlaced.value -= attachment.id
            }
        }
    }

    fun dismissPasteFailure() {
        _state.value = _state.value.copy(pasteFailure = null)
    }

    /** Opens the full-screen viewer on [id]. See [WorkspaceUiState.viewingAttachmentId]. */
    fun openAttachmentViewer(id: String) {
        _state.value = _state.value.copy(viewingAttachmentId = id)
    }

    /**
     * Moves the viewer [delta] photos along `orderedForDisplay`'s order, or does nothing at either end.
     *
     * Deliberately does not wrap. Paging off the last photo back to the first reads as the viewer
     * having jumped somewhere rather than as having reached the end, and there is no page indicator
     * on this platform to explain it.
     */
    fun stepAttachmentViewer(delta: Int) {
        val current = _state.value
        val viewing = current.viewingAttachmentId ?: return
        val ordered = orderedForDisplay(current.attachments)
        val index = ordered.indexOfFirst { it.id == viewing }
        if (index < 0) return
        val next = ordered.getOrNull(index + delta) ?: return
        _state.value = current.copy(viewingAttachmentId = next.id)
    }

    /** Closes the full-screen viewer, whichever attachment it was showing. */
    fun closeAttachmentViewer() {
        _state.value = _state.value.copy(viewingAttachmentId = null)
    }

    // ---------------------------------------------------------------- editing

    fun setTitle(title: String) = mutateSelected { it.copy(title = title) }

    /**
     * The block editor's [blocks] for note [noteId] after a local edit, with the editor's [base]
     * at that moment (`BlockEditorController.base`). Saved after [blocksSaveDelayMillis] of quiet,
     * or at once by [flushPendingSave] when the user switches notes or closes the window.
     *
     * Ignored when [noteId] is not the open note (an edit cannot be saved over another note), and
     * when the open note's body is unreadable (it is read-only; see [EditorDraft.unreadableBody]).
     */
    fun onBlocksChanged(noteId: String, blocks: List<Block>, base: BlockDocument) {
        val draft = _state.value.editor ?: return
        if (draft.id != noteId || draft.unreadableBody != null) return
        unsavedBlocks = UnsavedBlocks(noteId, blocks, base)
        scheduleSave(draft, blocksSaveDelayMillis)
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
                viewingAttachmentId = null,
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

    /**
     * [flushPendingSave], then waits until every save already handed to the repository has
     * finished, for at most [timeoutMillis]. Returns false on timeout.
     *
     * For the moments the store is about to go away -- a lock closes it, a window close ends the
     * process -- where a save that was merely launched would be lost: the debounced keystrokes, or
     * a write still in flight. The timeout keeps a hung save from hanging the lock or the exit;
     * losing the last second of typing is better than a window that will not close.
     */
    suspend fun flushAndAwait(timeoutMillis: Long = FLUSH_TIMEOUT_MILLIS): Boolean {
        flushPendingSave()
        val pending = synchronized(savesInFlight) { savesInFlight.toList() }
        val done = withTimeoutOrNull(timeoutMillis) { pending.joinAll() } != null
        if (!done) log("NotesWorkspaceModel: a save did not finish within $timeoutMillis ms; continuing")
        return done
    }

    /**
     * Runs [action] once [flushAndAwait] returns. What the lock button and the window's close
     * request go through, so neither closes the store under a pending save.
     */
    fun flushThen(action: () -> Unit): Job = scope.launch {
        flushAndAwait()
        action()
    }

    private fun scheduleSave(draft: EditorDraft, delayMillis: Long = autosaveDelayMillis) {
        autosaveJob?.cancel()
        _state.value = _state.value.copy(saveStatus = SaveStatus.Pending)
        autosaveJob = scope.launch {
            delay(delayMillis)
            // The draft as it is NOW, not as it was when the debounce started: a sync landing
            // inside the window may have merged a remote change into it, and saving the captured
            // copy would write the older value straight back over that change.
            persist(_state.value.editor?.takeIf { it.id == draft.id } ?: draft)
        }
    }

    /**
     * Writes [draft]. With block edits pending for it, the body is those blocks as BLOCKS, built
     * only by [BlocksSave.encodeForSave] and saved against the editor's base; the repository stamps
     * and merges it (see `BlocksSave`). Without, it is a title/metadata save: the stored row is read
     * fresh, under [saveMutex], and only the title, pin, favorite and folder are applied to it, as
     * the phone does. The draft's body is the stored body as last EMITTED, and a block the store
     * merged in since (a sync, or an earlier save's merge) would be reverted or, sent without a
     * base, tombstoned by writing it back.
     *
     * Every save goes through [saveMutex], so a title save can never read the row before an
     * earlier block save of the same note has written it.
     */
    private fun persist(draft: EditorDraft) {
        val typed = unsavedBlocks?.takeIf { it.noteId == draft.id && draft.unreadableBody == null }
        unsavedBlocks = null
        val plain = draft.toNote(updatedAt = now())
        val note = if (typed == null) plain else plain.copy(
            content = BlocksSave.encodeForSave(typed.blocks),
            contentFormat = NoteContentFormat.BLOCKS,
        )
        val blocksBase = typed?.let { BlockCodec.encode(it.base) }
        // The local list is updated ahead of the repository echo so the sidebar row's title and
        // snippet track what is being typed. Waiting for the round trip would make the list lag a
        // whole debounce behind the editor.
        latestNotes = latestNotes.map { if (it.id == note.id) note else it }
        _state.value = recompute(_state.value.copy(saveStatus = SaveStatus.Saved(note.updatedAt)))
        val save = scope.launch {
            saveMutex.withLock {
                val toSave = if (typed != null) {
                    note
                } else {
                    val stored = repository.getNoteById(note.id).first() ?: note
                    stored.copy(
                        title = note.title,
                        isPinned = note.isPinned,
                        isFavorite = note.isFavorite,
                        folderId = note.folderId,
                        updatedAt = note.updatedAt,
                    )
                }
                repository.saveNote(toSave, blocksBase)
            }
        }
        synchronized(savesInFlight) { savesInFlight += save }
        save.invokeOnCompletion { synchronized(savesInFlight) { savesInFlight -= save } }
    }

    // ---------------------------------------------------------------- internals

    /**
     * The open note's document for the block editor, re-assembled whenever the stored row, its
     * attachments or its sketches change, and dropped for the next note on a switch
     * (flatMapLatest). Each emission names its note, so [attachDocument] can refuse one that
     * arrives after the selection moved on.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun openedDocuments(): Flow<OpenedDocument> =
        state.map { it.selectedNoteId }
            .distinctUntilChanged()
            .flatMapLatest { id ->
                if (id == null) return@flatMapLatest emptyFlow()
                combine(
                    repository.getNoteById(id).filterNotNull().distinctUntilChanged(),
                    attachments?.getAttachmentsForNote(id) ?: flowOf(emptyList()),
                    sketches?.getSketchesForNote(id) ?: flowOf(emptyList()),
                    picturesBeingPlaced,
                ) { note, pictures, drawings, placing ->
                    OpenedDocument(
                        noteId = id,
                        document = OpenDocument.assemble(note, pictures.filterNot { it.id in placing }, drawings),
                        unreadableBody = note.content.takeIf {
                            note.contentFormat == NoteContentFormat.BLOCKS && BlockCodec.decode(it) == null
                        },
                    )
                }
            }

    private fun attachDocument(opened: OpenedDocument) {
        lastOpened = opened
        val draft = _state.value.editor ?: return
        if (draft.id != opened.noteId) return
        _state.value = _state.value.copy(editor = draft.withDocument(opened))
    }

    private fun EditorDraft.withDocument(opened: OpenedDocument?): EditorDraft =
        if (opened == null || opened.noteId != id) this
        else copy(blocks = opened.document, unreadableBody = opened.unreadableBody)

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

    /** The exact mirror of [selectedNoteSketches], for [DesktopAttachments] instead. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun selectedNoteAttachments(port: DesktopAttachments) =
        state.map { it.selectedNoteId }
            .distinctUntilChanged()
            .flatMapLatest { id ->
                if (id == null) flowOf(emptyList<AttachmentData>()) else port.getAttachmentsForNote(id)
            }

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
        val stored = selected?.let { id -> latestNotes.firstOrNull { it.id == id } }
        val editor = when {
            selected == null -> null
            selected == base.editor?.id -> base.editor.let { draft ->
                val baseline = editorBaseline
                if (stored == null || baseline == null || baseline.id != draft.id) draft
                else mergeIncoming(draft, baseline, stored)
            }
            // A fresh draft picks up a document already assembled for its note, if one arrived
            // before the draft could exist (the stored row reached getNoteById first).
            else -> stored?.toDraft()?.withDocument(lastOpened)
        }
        editorBaseline = if (editor == null) null else stored ?: editorBaseline
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

    companion object {
        /** How long a lock or a window close waits for pending saves; see [flushAndAwait]. */
        const val FLUSH_TIMEOUT_MILLIS = 2_000L
    }
}

/**
 * The open [draft] after the stored row became [incoming], given the row it was last reconciled
 * with ([baseline]) -- the desktop twin of the phone's `mergeIncomingNote`.
 *
 * Per field: if the draft still holds the baseline value, the user has not touched it since, so
 * the stored value is taken (a change synced in from the phone, or the echo of our own save, which
 * is equal anyway). If the user has changed it, their edit stands and will be saved over it.
 * `content` and `contentFormat` move as one value, as everywhere else.
 */
internal fun mergeIncoming(draft: EditorDraft, baseline: Note, incoming: Note): EditorDraft {
    val bodyUntouched = draft.content == baseline.content && draft.contentFormat == baseline.contentFormat
    val checklistUntouched = draft.checklist.serializeChecklist() == baseline.checklist
    return draft.copy(
        title = if (draft.title == baseline.title) incoming.title else draft.title,
        content = if (bodyUntouched) incoming.content else draft.content,
        contentFormat = if (bodyUntouched) incoming.contentFormat else draft.contentFormat,
        checklist = when {
            !checklistUntouched -> draft.checklist
            // Our own echo: keep the items (and their ids, which focus is keyed on) as they are.
            incoming.checklist == draft.checklist.serializeChecklist() -> draft.checklist
            else -> parseChecklist(incoming.checklist)
        },
        isPinned = if (draft.isPinned == baseline.isPinned) incoming.isPinned else draft.isPinned,
        isFavorite = if (draft.isFavorite == baseline.isFavorite) incoming.isFavorite else draft.isFavorite,
        folderId = if (draft.folderId == baseline.folderId) incoming.folderId else draft.folderId,
        updatedAt = maxOf(draft.updatedAt, incoming.updatedAt),
    )
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
        (BlockText.bodyIsBlank(content, contentFormat) ?: htmlToPlainText(content).isBlank()) &&
        checklist.all { it.text.isBlank() }
