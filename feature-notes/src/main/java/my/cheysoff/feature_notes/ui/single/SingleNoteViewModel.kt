package my.cheysoff.feature_notes.ui.single

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import my.cheysoff.core_blocks.Block
import my.cheysoff.core_blocks.BlockCodec
import my.cheysoff.core_blocks.BlockDocument
import my.cheysoff.core_blocks.BlockText
import my.cheysoff.core_blocks.BlockType
import my.cheysoff.core_blocks.BlocksSave
import my.cheysoff.core_blocks.BlocksFormat
import my.cheysoff.core_blocks.OpenDocument
import my.cheysoff.core_domain.attachment.sortAttachments
import my.cheysoff.core_domain.model.AttachmentData
import my.cheysoff.core_domain.model.AttachmentPreview
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NoteContentFormat
import my.cheysoff.core_domain.model.SketchData
import my.cheysoff.core_domain.repository.AttachmentsRepository
import my.cheysoff.core_domain.repository.NotesRepository
import my.cheysoff.core_domain.repository.SettingsRepository
import my.cheysoff.core_domain.repository.SketchesRepository
import my.cheysoff.core_domain.sketch.NoteBlocks
import my.cheysoff.core_domain.sketch.Sketch
import my.cheysoff.core_domain.sketch.StrokeCodec
import my.cheysoff.core_domain.sketch.sortSketches as sortSketchesForDisplay
import my.cheysoff.feature_notes.model.single.ChecklistItem
import my.cheysoff.feature_notes.model.single.OpenedNoteDocument
import my.cheysoff.feature_notes.model.single.PicturePlacement
import my.cheysoff.feature_notes.model.single.SingleNoteIntent
import my.cheysoff.feature_notes.model.single.SingleNoteScreenState
import my.cheysoff.feature_notes.model.single.normalizeChecklistText
import my.cheysoff.feature_notes.model.single.parseChecklist
import my.cheysoff.feature_notes.model.single.serializeChecklist
import my.cheysoff.feature_notes.ui.attachment.ImageImporter
import my.cheysoff.feature_notes.ui.attachment.ImportResult
import java.util.UUID
import javax.inject.Inject

sealed class SingleNoteEvent {
    data object NavigateBack : SingleNoteEvent()

    /**
     * A copy of this note has been written as a new row. [title] is the copy's title, so the host
     * can name it in its confirmation. The editor stays on the original, so nothing on this screen
     * changes when a duplicate is made — this event is the only signal that the write happened.
     */
    data class NoteDuplicated(val title: String) : SingleNoteEvent()

    /**
     * An attachment import ([SingleNoteIntent.ImportAttachment]) could not be completed. [tooLarge]
     * distinguishes "every rung of the ladder was still over the cap" from "the picked source never
     * decoded as an image at all" -- a single generic failure message is worse than none
     * (`docs/design/image-attachments.md` §7).
     */
    data class AttachmentImportFailed(val tooLarge: Boolean) : SingleNoteEvent()

    /**
     * Block path: a new drawing ([isDrawing]) or picture was not added, because the note has no
     * editable body to put it in (it is read-only, or its document has not opened yet). Reported
     * rather than dropped: a drawing in particular is minutes of work the user believes was saved.
     */
    data class PictureRefused(val isDrawing: Boolean) : SingleNoteEvent()
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
    /** Always moves with [content]: the marker says how those exact bytes are to be read. */
    val contentFormat: NoteContentFormat,
    val checklist: String,
    val isPinned: Boolean,
    val isFavorite: Boolean,
    val folderId: String?,
)

internal fun Note.toEditorBaseline(): EditorBaseline = EditorBaseline(
    title = title,
    content = content,
    contentFormat = contentFormat,
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
                contentFormat = incoming.contentFormat,
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
    // A body adopted from the row that differs from what the editor shows is a REMOTE change (the
    // echo of our own write is equal to current). The rich-text editor owns its text and only
    // re-reads state when contentRevision moves, so without the bump an open note keeps showing the
    // old body -- and its next edit would write that stale body back over the incoming one.
    val bodyReplaced = current.content == baseline.content &&
        (incoming.content != current.content || incoming.contentFormat != current.contentFormat)
    return NoteMergeResult(
        state = current.copy(
            title = if (current.title == baseline.title) incoming.title else current.title,
            // content and contentFormat are merged as a single unit, never independently: the
            // marker describes how those exact bytes are read, so adopting one without the other
            // hands a plain body to the HTML reader (or the reverse) and truncates the note.
            content = if (current.content == baseline.content) incoming.content else current.content,
            contentFormat = if (current.content == baseline.content) {
                incoming.contentFormat
            } else {
                current.contentFormat
            },
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
            contentRevision = if (bodyReplaced) current.contentRevision + 1 else current.contentRevision,
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
    // Nothing moved, so hand back the original instance rather than an equal copy. That is an
    // allocation concern, not a correctness one: List equality is structural and `merged` is
    // element-wise identical to `current` here, so the StateFlow would suppress the emission either
    // way. What keeps focus and in-flight intents alive is the positional match above, which reuses
    // the existing ChecklistItem instances and therefore their ids.
    return if (merged == current) current else merged
}

/**
 * True when this screen is allowed to auto-discard [note] — that is, [openedForNewNote] (the list
 * screen inserted this row specifically so this screen could open it) and the row is still empty.
 *
 * [openedForNewNote] is an explicit nav argument, not an inference. It used to be inferred from
 * timestamps — createdAt != 0, createdAt == updatedAt, and createdAt inside a grace window of the
 * screen opening — which the upsert's own backfill defeats: a legacy row stores createdAt = 0, and
 * `createdAt = CASE WHEN notes.createdAt = 0 THEN excluded.createdAt ELSE notes.createdAt END`
 * rewrites it to now on the first post-migration save, setting createdAt = updatedAt = now. All
 * three clauses then hold for an ordinary note, making it silently deletable — and this discard is
 * a PURGE, not a move to Trash (see BackClicked), so there would be no undo for it. The user-facing
 * delete added alongside Trash is a separate path and is always soft.
 *
 * "Blank" alone is deliberately NOT sufficient either: a user who empties an existing note and
 * reopens it would otherwise lose it — along with its pin/favorite/folder — by backing out.
 *
 * Erring stays one-directional: [openedForNewNote] is false for every route that doesn't set it, so
 * a missed detection just leaves an abandoned blank note in the list.
 */
internal fun isDiscardableOnOpen(openedForNewNote: Boolean, note: Note): Boolean =
    openedForNewNote &&
            note.title.isBlank() && bodyIsBlank(note.content, note.contentFormat) && note.checklist.isBlank()

/**
 * Suffix a duplicate's title carries. Plain and un-numbered on purpose: choosing "(copy 2)" would
 * mean knowing every other title in the database, which this screen never loads — so duplicating a
 * duplicate yields a doubled suffix rather than a number that could be wrong.
 */
private const val DUPLICATE_TITLE_SUFFIX = " (copy)"

/**
 * Title for a duplicate of a note currently titled [title].
 *
 * A blank title becomes "Untitled (copy)" rather than the bare suffix: the copy has to be findable
 * in the list, and a card reading "(copy)" says less than one reading "Untitled (copy)".
 */
internal fun duplicateTitle(title: String): String {
    val trimmed = title.trim()
    return if (trimmed.isEmpty()) {
        "Untitled$DUPLICATE_TITLE_SUFFIX"
    } else {
        trimmed + DUPLICATE_TITLE_SUFFIX
    }
}

/**
 * The row a "Duplicate" writes: a fresh [newId] carrying the editor's current title, body (with its
 * format marker, which must always travel with the same bytes it describes), checklist and folder.
 *
 * Timestamps are left at their defaults because nothing set here would survive: `saveNote` upserts,
 * and for an id the table has never held, that INSERT stamps createdAt and updatedAt with its own
 * `System.currentTimeMillis()`.
 *
 * isPinned is dropped rather than copied: a pin is curation of one specific note, and a second
 * pinned card with a near-identical title reads as a glitch in the list's pinned pager. isFavorite
 * is not really a choice made here — that same INSERT writes isFavorite = 0 for a new row, so a
 * copy is never favorited whatever this function passes.
 */
internal fun buildDuplicate(state: SingleNoteScreenState, newId: String): Note = Note(
    id = newId,
    title = duplicateTitle(state.title),
    content = state.content,
    contentFormat = state.contentFormat,
    checklist = state.checklist.serializeChecklist(),
    isPinned = false,
    isFavorite = false,
    folderId = state.folderId,
)

/**
 * Sketches in the order [SketchSection][my.cheysoff.feature_notes.ui.single.SketchSection] renders
 * them: by [SketchData.anchor], ties broken by [SketchData.id].
 *
 * A one-line delegation to `:core-domain`'s `sortSketches` -- see that function's own KDoc for the
 * rule itself and for why it is one shared function rather than a copy kept in step with the
 * desktop's by hand. This wrapper exists only so every call site and test in this module keeps
 * referring to `sortSketches` unqualified; the rule it applies lives in `:core-domain`, not here.
 */
internal fun sortSketches(sketches: List<SketchData>): List<SketchData> = sortSketchesForDisplay(sketches)

/**
 * The `order` a brand-new sketch anchored at [anchor] should be given: one past the highest
 * `order` any existing sketch at that same anchor already holds, or `0` if none does.
 */
internal fun nextSketchOrder(sketches: List<SketchData>, anchor: Int): Int =
    (sketches.filter { it.anchor == anchor }.maxOfOrNull { it.order } ?: -1) + 1

/**
 * The `order` a brand-new attachment anchored at [anchor] should be given: one past the highest
 * `order` any existing attachment at that same anchor already holds, or `0` if none does. Mirrors
 * [nextSketchOrder] exactly.
 */
internal fun nextAttachmentOrder(attachments: List<AttachmentPreview>, anchor: Int): Int =
    (attachments.filter { it.anchor == anchor }.maxOfOrNull { it.order } ?: -1) + 1

/**
 * [AttachmentData] as saved, reduced to the [AttachmentPreview] this screen's state actually holds
 * -- the full-size [AttachmentData.bytes] never sit in Compose state (see
 * `docs/design/image-attachments.md` §5's "no multi-row query selects `bytes`" rule; this is that
 * same discipline applied to the one row this screen just wrote, not only to what a query returns).
 */
internal fun AttachmentData.toPreview(): AttachmentPreview = AttachmentPreview(
    id = id,
    noteId = noteId,
    anchor = anchor,
    order = order,
    mimeType = mimeType,
    width = width,
    height = height,
    thumbWidth = thumbWidth,
    thumbHeight = thumbHeight,
    thumbBytes = thumbBytes,
    createdAt = createdAt,
    updatedAt = updatedAt,
    isDeleted = isDeleted,
    deletedAt = deletedAt,
    meta = meta,
)

@HiltViewModel
class SingleNoteViewModel internal constructor(
    private val notesRepository: NotesRepository,
    private val sketchesRepository: SketchesRepository,
    private val attachmentsRepository: AttachmentsRepository,
    private val imageImporter: ImageImporter,
    private val settingsRepository: SettingsRepository,
    savedStateHandle: SavedStateHandle,
    /**
     * Whether the body is the shared block editor, saved as BLOCKS. The injected constructor passes
     * [BlocksFormat.WRITE_ENABLED], which stays off until both apps ship the block editor (Task 16):
     * a phone writing BLOCKS while the laptop still ran a build that refuses them would hand it
     * notes it cannot open. A parameter rather than a read of the constant so tests run both paths.
     * Off, this ViewModel behaves exactly as it did before the block editor existed.
     */
    private val blocksWriteEnabled: Boolean,
    /**
     * The debounce between the block editor's last change and the save, the desktop's value.
     * Shorter than the legacy path's, because the editor reports finished blocks rather than a
     * field that has to be re-serialised first.
     */
    private val blocksSaveDelayMillis: Long = 400L,
    private val log: (String) -> Unit = { Log.w("SingleNoteViewModel", it) },
) : ViewModel() {

    @Inject constructor(
        notesRepository: NotesRepository,
        sketchesRepository: SketchesRepository,
        attachmentsRepository: AttachmentsRepository,
        imageImporter: ImageImporter,
        settingsRepository: SettingsRepository,
        savedStateHandle: SavedStateHandle,
    ) : this(
        notesRepository, sketchesRepository, attachmentsRepository, imageImporter, settingsRepository,
        savedStateHandle, blocksWriteEnabled = BlocksFormat.WRITE_ENABLED,
    )

    private val noteId: String? = savedStateHandle["noteId"]

    // Set only by the route NotesListViewModel.createNewNote() navigates to, immediately after it
    // inserted this row. Absent (and therefore false) on every other way into this screen.
    private val openedForNewNote: Boolean = savedStateHandle["isNew"] ?: false

    private val _state = MutableStateFlow(SingleNoteScreenState(blocksEditing = blocksWriteEnabled))
    val state = _state.asStateFlow()

    private val _events = Channel<SingleNoteEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var saveJob: Job? = null

    // Tracks the latest metadata write (pin/favorite/folder). All serialize on saveMutex, so awaiting
    // this one in BackClicked also flushes any earlier-queued meta write before navigation cancels
    // viewModelScope and could drop an in-flight UPDATE.
    private var metaWriteJob: Job? = null

    // The in-flight "Duplicate", if any. Held for two reasons: a second tap is ignored while the
    // first insert is still running, and BackClicked joins it so navigating away can't cancel that
    // insert mid-flight.
    private var duplicateJob: Job? = null

    // The in-flight sketch save, if any. saveSketch's write and current.sketches (which the
    // blank-note discard in BackClicked reads) are connected only through this device's own Flow
    // echo — without joining this job first, a drawing saved just before Done->back can still be
    // mid-flight (or written but not yet re-emitted into _state) when the guard runs, so the note
    // gets purged with the sketch orphaned under it.
    private var sketchSaveJob: Job? = null

    // Same reasoning and same fix as sketchSaveJob just above, for the other block-level attachment
    // to a note: an import saved just before Done->back can still be mid-flight (or written but not
    // yet re-emitted into _state) when BackClicked's blank-note guard runs, so it must join this job
    // before reading current.attachments, or the note gets purged with the image orphaned under it.
    //
    // Asymmetric with sketchSaveJob in one way worth naming: this job covers the WHOLE import --
    // decode, up to four ladder re-decodes, encode and thumbnail -- not only the DB write the way
    // sketchSaveJob does (a sketch is already-encoded Sketch data by the time SketchSaved fires).
    // Tapping back mid-import therefore blocks navigation for the length of the encode, not just an
    // insert. That is the correct trade -- joining only the DB write half would still race the
    // encode that produces the AttachmentData being written -- but it is a real, felt difference
    // from the sketch path and not an oversight.
    //
    // A set, not a single slot: on the block path a keyboard can commit several images at once
    // (each its own import), and a slot would let a later import overwrite the reference to an
    // earlier one still running, leaving it ungoverned by the guard. Each job leaves the set when
    // it completes; BackClicked joins all of them.
    private val attachmentSaveJobs = mutableSetOf<Job>()

    // Serializes DB writes so an older/delayed save can't run concurrently with a newer one.
    private val saveMutex = Mutex()

    // What the stored row is known to hold for the fields this editor owns — moved by both the load
    // flow (what the DB told us) and every write path (what we told the DB). Null until the first
    // row arrives. See EditorBaseline / mergeIncomingNote: this is what lets an emission be
    // classified as "our own echo" instead of news worth clobbering the user's typing with, and it
    // is also what hasUnsavedContent() compares against.
    private var baseline: EditorBaseline? = null

    // One stack for every field this editor owns, in the order the edits reached this ViewModel.
    // The body arrives already debounced by the screen (CONTENT_SERIALIZE_DEBOUNCE_MS), so a body
    // edit is recorded up to that late: switching from the body to the title inside that window
    // can land the two edits on the stack in the opposite order to the one they were typed in.
    // Everything else is recorded on the keystroke that caused it.
    private val history = EditorHistory()

    // True only for a note that was created-as-blank for this very screen (see
    // isDiscardableOnOpen). Such a note is discarded if it's still empty on back. Any other
    // note — including an existing one the user has emptied out, in this session or a previous
    // one — is NEVER auto-deleted: that would be data loss with no undo.
    private var createdBlankNote = false

    // ---- Block path only (blocksWriteEnabled). The desktop twin is NotesWorkspaceModel. ----------

    /**
     * The block editor's latest unsaved blocks with the base they were edited against, or null when
     * the last ones were written. Captured together by [onBlocksChanged], never re-read at save
     * time (see [SingleNoteIntent.BlocksChanged]); cleared by the save that writes them, because
     * re-sending them later would claim blocks the other device may have changed since.
     */
    private var unsavedBlocks: UnsavedBlocks? = null

    private class UnsavedBlocks(val blocks: List<Block>, val base: BlockDocument)

    /**
     * The blocks of the last local edit, kept after they are saved: the blank-note discard asks
     * them whether the editor shows anything, because [SingleNoteScreenState.content] trails the
     * editor by a save and its echo.
     */
    private var lastEditedBlocks: List<Block>? = null

    /** True from scheduling a block-path save until it starts writing; what [flushBlocksSave] acts on. */
    private var blocksSavePending = false

    /**
     * Ids of pictures and drawings whose record is being written but whose block the editor has not
     * inserted yet. Left out of the assembled document meanwhile: a record with no block is
     * otherwise appended at the end by [OpenDocument.assemble], so the editor would receive it
     * there first and the insert at the cursor would then meet a block it already shows.
     */
    private val picturesBeingPlaced = MutableStateFlow<Set<String>>(emptySet())

    init {
        noteId?.let { id ->
            notesRepository.getNoteById(id)
                // Only real content flips isLoaded, so the editor always seeds from a loaded note
                // (never from an empty placeholder). A missing/never-loading note is handled on the
                // screen side, where edits are forwarded regardless of load state.
                .filterNotNull()
                .onEach { note ->
                    // Decided once, on the first row this ViewModel sees. The nav argument alone
                    // would be enough for a fresh screen, but it is restored verbatim when this
                    // ViewModel is rebuilt for the same back-stack entry (rotation, process death),
                    // by which point the user may already have typed into the note — so the first
                    // row still has to be blank.
                    if (baseline == null) {
                        createdBlankNote = isDiscardableOnOpen(openedForNewNote, note)
                    }
                    // Read/merge/assign rather than update {}: the merge also has to move the
                    // baseline, which must happen exactly once per emission (update {} may retry
                    // its block). Safe because every mutation of _state runs on the main
                    // dispatcher and there is no suspension point between the read and the write.
                    val merged = mergeIncomingNote(_state.value, baseline, note)
                    baseline = merged.baseline
                    // contentRevision re-seeds the rich-text editor; the block editor takes synced
                    // bodies through its document instead (assembled below), so on that path a
                    // replaced body must not bump it.
                    _state.value = if (blocksWriteEnabled) {
                        merged.state.copy(contentRevision = _state.value.contentRevision)
                    } else {
                        merged.state
                    }
                }
                .launchIn(viewModelScope)

            if (blocksWriteEnabled) {
                openedDocuments(id)
                    .onEach { opened -> _state.update { it.copy(blockDocument = opened) } }
                    .launchIn(viewModelScope)
            }

            // Independent of the note-load flow above, exactly like the folders collection below:
            // sketches live in their own table with their own Flow, and their write path (the
            // canvas' Done button) never goes through the editor's title/body/checklist baseline.
            sketchesRepository.getSketchesForNote(id)
                .onEach { list -> _state.update { it.copy(sketches = sortSketches(list)) } }
                .launchIn(viewModelScope)

            // Independent of both flows above for the same reason sketches are: attachments live in
            // their own table with their own write path (the photo picker), never through the
            // editor's title/body/checklist baseline.
            attachmentsRepository.attachmentsOf(id)
                .onEach { list -> _state.update { it.copy(attachments = sortAttachments(list)) } }
                .launchIn(viewModelScope)
        }

        notesRepository.getFolders()
            .onEach { folders -> _state.update { it.copy(folders = folders) } }
            .launchIn(viewModelScope)

        // Outside the `noteId` block above: the recent-colour strip belongs to the drawing tool, not
        // to a note, so it is collected even on a note that has never had a sketch.
        settingsRepository.recentSketchColors
            .onEach { colors -> _state.update { it.copy(recentSketchColors = colors) } }
            .launchIn(viewModelScope)
    }

    /**
     * Records a colour mixed in the canvas' picker.
     *
     * Deliberately fire-and-forget rather than part of a sketch save: the colour is worth keeping
     * whether or not the drawing it was mixed for is ever committed, and a failure to persist it
     * must not be able to fail the drawing.
     */
    fun onSketchColorMixed(argb: Long) {
        viewModelScope.launch { settingsRepository.addRecentSketchColor(argb) }
    }

    /**
     * The one seam `AttachmentViewerScreen` uses to load an attachment's full bytes, by id, on
     * demand. Everything else on this screen (the toolbar, [SingleNoteScreenState.attachments])
     * holds only [AttachmentPreview] -- this is deliberately the single call site in the whole
     * screen that can return [AttachmentData.bytes], matching
     * `docs/design/image-attachments.md` §5's "one DAO method, by id" rule one layer up.
     */
    suspend fun attachment(id: String): AttachmentData? = attachmentsRepository.attachment(id)

    fun onIntent(intent: SingleNoteIntent) {
        when (intent) {
            is SingleNoteIntent.TitleChanged -> {
                val current = _state.value
                if (blocksWriteEnabled) {
                    // No EditorHistory on the block path: undo there is the block editor's own,
                    // and a title step on a second stack would interleave with it unpredictably.
                    if (intent.title != current.title) {
                        _state.update { it.copy(title = intent.title) }
                        scheduleBlocksSave()
                    }
                    return
                }
                // Nothing to save and nothing to undo when the value did not actually move; the
                // guard keeps a step that would appear to do nothing off the history stack.
                if (intent.title != current.title) {
                    edit(EditorRevision.Title(current.title), EditGroup.Title) {
                        it.copy(title = intent.title)
                    }
                    saveNote(debounce = true)
                }
            }

            is SingleNoteIntent.ContentChanged -> {
                // Only the rich-text editor emits this, and it always sends toHtml() output — so
                // this is the one place a note earns the HTML label. Marking the note HTML in any
                // other write path (a title-only edit, say) would relabel an untouched legacy
                // plain-text body and destroy it on the next open.
                val current = _state.value
                // A flush can carry bytes identical to what state already holds (the editor is
                // serialized on an idle gap, not on a diff). Such an emission is only worth acting
                // on while the note still carries the PLAIN marker, which this write clears.
                val changed = intent.content != current.content ||
                        current.contentFormat != NoteContentFormat.HTML
                if (changed) {
                    edit(
                        EditorRevision.Body(current.content, current.contentFormat),
                        EditGroup.Body,
                    ) {
                        it.copy(content = intent.content, contentFormat = NoteContentFormat.HTML)
                    }
                    saveNote(debounce = true)
                }
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

            // The checklist branches below build the next list up front, because the list as it
            // stands is also what the undo step has to remember. The three that can be handed an id
            // that is no longer in the list (toggle/text/remove — an intent racing a removal) then
            // compare the two and drop the intent when nothing moved, rather than recording an undo
            // step that does nothing when taken; an add always changes the list.
            //
            // Reading _state.value here is safe for the same reason the load flow's
            // read/merge/assign is: intents arrive on the main dispatcher and nothing suspends
            // between the read and edit()'s update.
            is SingleNoteIntent.ChecklistItemAdded -> {
                val current = _state.value.checklist
                val item = ChecklistItem(id = intent.newId, text = "", isDone = false)
                val at = intent.afterId?.let { id -> current.indexOfFirst { it.id == id } } ?: -1
                val next = if (at < 0) {
                    current + item
                } else {
                    current.toMutableList().apply { add(at + 1, item) }
                }
                edit(EditorRevision.Checklist(current), EditGroup.Structural) {
                    it.copy(checklist = next)
                }
                saveNote(debounce = false)
            }

            is SingleNoteIntent.ChecklistItemToggled -> {
                val current = _state.value.checklist
                val next = current.map {
                    if (it.id == intent.id) it.copy(isDone = !it.isDone) else it
                }
                if (next != current) {
                    edit(EditorRevision.Checklist(current), EditGroup.Structural) {
                        it.copy(checklist = next)
                    }
                    saveNote(debounce = false)
                }
            }

            is SingleNoteIntent.ChecklistItemTextChanged -> {
                // Normalize here rather than at serialization time: this is the only way text
                // enters the checklist (parseChecklist can't produce a newline), so it is the one
                // choke point that keeps state and its serialized form in exact correspondence.
                // Without it, a multi-line paste round-trips to different text and mergeChecklist
                // re-ids the row the user is typing into. See normalizeChecklistText.
                val text = normalizeChecklistText(intent.text)
                val current = _state.value.checklist
                val next = current.map { if (it.id == intent.id) it.copy(text = text) else it }
                if (next != current) {
                    // Grouped by item id, so typing runs together within one row but moving to
                    // another row starts a new undo step.
                    edit(
                        EditorRevision.Checklist(current),
                        EditGroup.ChecklistItemText(intent.id),
                    ) {
                        it.copy(checklist = next)
                    }
                    saveNote(debounce = true)
                }
            }

            is SingleNoteIntent.ChecklistItemRemoved -> {
                val current = _state.value.checklist
                val next = current.filterNot { it.id == intent.id }
                if (next != current) {
                    edit(EditorRevision.Checklist(current), EditGroup.Structural) {
                        it.copy(checklist = next)
                    }
                    saveNote(debounce = false)
                }
            }

            // Undo and redo are ordinary local edits: they move state and then go through
            // saveNote() like any other, so the write and the baseline move together and the row
            // Room echoes back is still recognised as our own. See applyRevision.
            is SingleNoteIntent.Undo -> applyRevision(history.undo(::currentRevision))

            is SingleNoteIntent.Redo -> applyRevision(history.redo(::currentRevision))

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

            is SingleNoteIntent.SketchSaved -> saveSketch(intent.editingId, intent.sketch)

            is SingleNoteIntent.SketchDeleted -> {
                viewModelScope.launch { sketchesRepository.deleteSketch(intent.id) }
            }

            is SingleNoteIntent.ImportAttachment -> importAttachment(intent.uri)

            is SingleNoteIntent.AttachmentDeleted -> {
                viewModelScope.launch { attachmentsRepository.deleteAttachment(intent.id) }
            }

            is SingleNoteIntent.DuplicateNote -> duplicateNote()

            is SingleNoteIntent.BlocksChanged -> onBlocksChanged(intent.noteId, intent.blocks, intent.base)

            is SingleNoteIntent.PicturePlaced -> {
                picturesBeingPlaced.update { it - intent.recordId }
                _state.update { s -> s.copy(picturesToPlace = s.picturesToPlace.filterNot { it.recordId == intent.recordId }) }
            }

            is SingleNoteIntent.FlushPendingSave -> if (blocksWriteEnabled) flushBlocksSave()

            is SingleNoteIntent.DeleteNote -> {
                val id = noteId ?: return
                viewModelScope.launch {
                    // Cancel any pending autosave first. It would run against a note that is on its
                    // way to Trash; the upsert leaves the tombstone alone (see NoteDao.upsertNote),
                    // so it could not resurrect the note, but it would still bump updatedAt for an
                    // edit the user has just discarded.
                    saveJob?.cancel()
                    unsavedBlocks = null
                    blocksSavePending = false
                    metaWriteJob?.join()
                    // SOFT delete: the row keeps its content, pin, favorite and folder so Restore is
                    // lossless. Contrast the blank-note discard in BackClicked, which purges.
                    saveMutex.withLock { notesRepository.deleteNote(id) }
                    _events.send(SingleNoteEvent.NavigateBack)
                }
            }

            is SingleNoteIntent.BackClicked -> {
                viewModelScope.launch {
                    // Flush any pending metadata write (favorite/folder/pin) before navigating, so
                    // popping the screen can't cancel an in-flight UPDATE. Same reason for the
                    // duplicate: its INSERT runs on viewModelScope, which the pop cancels.
                    metaWriteJob?.join()
                    duplicateJob?.join()
                    // A drawing saved between the last edit and this back tap must be counted before
                    // the guard below decides whether the note is still blank — otherwise the note
                    // gets purged with the sketch orphaned live underneath it. See saveSketch's own
                    // comment for why joining this job is what makes current.sketches trustworthy
                    // here, not just "usually right in time".
                    sketchSaveJob?.join()
                    // Same fix, same reason, for an imported photo: without joining this too, a
                    // picked image saved just before back can still be mid-flight (or written but
                    // not yet re-emitted into _state) when current.attachments is read below, and
                    // the note gets purged with the image orphaned under it.
                    attachmentSaveJobs.toList().joinAll()
                    if (blocksWriteEnabled) {
                        leaveBlocksNote()
                        _events.send(SingleNoteEvent.NavigateBack)
                        return@launch
                    }
                    val current = _state.value
                    val id = noteId
                    when {
                        // A note created for this screen and never written into is discarded rather
                        // than saved — otherwise an abandoned "+" tap would sit at the top of the
                        // newest-first list. Note the guard is createdBlankNote, not "is blank now":
                        // an existing note the user emptied out is kept. current.sketches and
                        // current.attachments are checked too: a note whose only content is a
                        // drawing or a photo is not "still blank" either — without this, purgeNote's
                        // hard delete below would leave that row live in the database under a note
                        // id that no longer exists.
                        id != null && createdBlankNote && current.title.isBlank() &&
                                bodyIsBlank(current.content, current.contentFormat) && current.checklist.isEmpty() &&
                                current.sketches.isEmpty() && current.attachments.isEmpty() -> {
                            saveJob?.cancel()
                            // purgeNote, NOT deleteNote: this is a discard, not a deletion the user
                            // asked for. deleteNote became a soft delete when Trash landed, and
                            // routing an abandoned "+" tap through it would fill Trash with empty
                            // notes the user never knowingly created — each one then needing a
                            // manual "delete forever". There is nothing here worth keeping: the
                            // guard above has already established the row is blank in every field.
                            saveMutex.withLock { notesRepository.purgeNote(id) }
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
     * Writes a copy of this note as a new row and reports it back through [SingleNoteEvent].
     * The editor stays on the original — nothing about this screen's note changes.
     *
     * Three things this shares with every other write path here:
     * - the copy is built from the LATEST `_state`, read inside the coroutine rather than captured
     *   at intent time, so it carries what the editor holds now;
     * - the INSERT runs under [saveMutex], so it cannot interleave with an autosave upsert;
     * - the baseline is deliberately left alone. It describes the row for [noteId], and this write
     *   touches a different id, so folding anything into it would make the next emission for THIS
     *   note look like an external change and roll the user's typing back.
     *
     * The original is flushed first when it has unsaved content: the copy is taken from live editor
     * state, so without that flush a process death right here would leave the copy ahead of the note
     * it was copied from. When there is nothing pending, `hasUnsavedContent()` is false and no write
     * happens — so duplicating a note the user only read does not bump its updatedAt or reorder the
     * list.
     */
    private fun duplicateNote() {
        // No noteId means this screen was opened without a note to copy.
        if (noteId == null) return
        // A second tap while the first insert is still in flight would mint a second copy. The menu
        // closes on tap, so this is a guard against a double tap, not an expected path.
        if (duplicateJob?.isActive == true) return
        duplicateJob = viewModelScope.launch {
            val copy = if (blocksWriteEnabled) {
                blocksDuplicate() ?: return@launch
            } else {
                if (_state.value.hasUnsavedContent()) saveNote(debounce = false)?.join()
                buildDuplicate(_state.value, newId = UUID.randomUUID().toString())
            }
            saveMutex.withLock { notesRepository.saveNote(copy) }
            _events.send(SingleNoteEvent.NoteDuplicated(copy.title))
        }
    }

    /**
     * Persists a drawing just finished on the canvas.
     *
     * A brand-new sketch ([editingId] null) is anchored at the note's CURRENT block count -- the
     * end of the text, which is where [SketchSection][my.cheysoff.feature_notes.ui.single.SketchSection]
     * renders every drawing -- and given the next [SketchData.order] at that anchor via
     * [nextSketchOrder]; the screen flushes any pending body edit before opening the canvas for a
     * new sketch, so `current.content` here is never a debounce behind what is on screen.
     *
     * Re-editing an existing one ([editingId] non-null) only ever replaces its `strokes`: its
     * `anchor`/`order`/`createdAt` are left exactly as they were, because reopening a drawing to add
     * a line is not a re-anchor. If the id is not found in `current.sketches` (a delete raced the
     * canvas being open, say) this falls back to creating a fresh row rather than silently dropping
     * the drawing.
     *
     * `updatedAt` is stamped HERE, on every branch: `SketchData`'s timestamps are caller-owned,
     * unlike `Note`'s (whose repository stamps `updatedAt` itself) -- a save that forgot this would
     * leave a stale value that sorts and merges wrongly, looking like a sync bug that is really
     * this code's own.
     */
    private fun saveSketch(editingId: String?, sketch: Sketch) {
        val id = noteId ?: return
        val job = viewModelScope.launch {
            val current = _state.value
            val now = System.currentTimeMillis()
            val encoded = StrokeCodec.encode(sketch)
            val existing = editingId?.let { eid -> current.sketches.find { it.id == eid } }
            // On the block path a new drawing is a block of the body: refused, before anything is
            // written, where there is no editable body to put it in (see canPlacePicture).
            if (existing == null && blocksWriteEnabled && !canPlacePicture()) {
                _events.send(SingleNoteEvent.PictureRefused(isDrawing = true))
                return@launch
            }
            val data = existing?.copy(strokes = encoded, updatedAt = now) ?: run {
                val anchor = NoteBlocks.count(current.content, current.contentFormat)
                SketchData(
                    id = UUID.randomUUID().toString(),
                    noteId = id,
                    anchor = anchor,
                    order = nextSketchOrder(current.sketches, anchor),
                    strokes = encoded,
                    createdAt = now,
                    updatedAt = now,
                )
            }
            // The record first, then the block: a block must never point at a record that was not
            // written. Kept out of the assembled document until the editor has placed it.
            val place = blocksWriteEnabled && existing == null
            if (place) picturesBeingPlaced.update { it + data.id }
            sketchesRepository.saveSketch(data)
            if (place) {
                _state.update { it.copy(picturesToPlace = it.picturesToPlace + PicturePlacement(data.id, BlockType.DRAWING)) }
            }
            // Mirror the write into `_state` right here, rather than waiting for
            // sketchesRepository's Flow to echo it back: BackClicked's blank-note discard guard
            // reads current.sketches immediately after joining this job (sketchSaveJob), and
            // Room's invalidation-tracker re-query runs on its own dispatcher — without this, the
            // guard could still observe an empty list for a sketch that has, in fact, just been
            // durably saved, and purge the note out from under it (see purgeNote's own cascade for
            // the other half of that fix).
            _state.update { it.copy(sketches = sortSketches(it.sketches.filterNot { s -> s.id == data.id } + data)) }
        }
        sketchSaveJob = job
    }

    /**
     * Imports a photo picked via [SingleNoteIntent.ImportAttachment].
     *
     * [imageImporter] knows nothing about this note -- it decodes, downscales and encodes off the
     * main thread and hands back an [AttachmentData] with placeholder identity/anchor/order/
     * timestamps (see [ImportResult]'s own KDoc). This function supplies the parts only a note can
     * know: a brand-new attachment is anchored at the note's CURRENT block count, exactly like a
     * brand-new sketch in [saveSketch], and given the next order at that anchor via
     * [nextAttachmentOrder].
     *
     * [SingleNoteScreenState.isImportingAttachment] brackets the whole suspend call so the toolbar
     * can show progress; [ImportResult.TooLarge] and [ImportResult.NotAnImage] each report through
     * [SingleNoteEvent.AttachmentImportFailed] rather than silently doing nothing, because a picker
     * that appears to eat the tap is worse than a message that says which failure it was.
     */
    private fun importAttachment(uri: String) {
        val id = noteId ?: return
        // On the block path a picture is a block of the body. A read-only note has no editable body
        // to put it in, and one whose document is not assembled yet has no editor to insert it;
        // either way a record written now would be a picture the user did not see land. Refused
        // before anything is imported, as on the desktop.
        if (blocksWriteEnabled && !canPlacePicture()) {
            viewModelScope.launch { _events.send(SingleNoteEvent.PictureRefused(isDrawing = false)) }
            return
        }
        _state.update { it.copy(isImportingAttachment = true) }
        val job = viewModelScope.launch {
            when (val result = imageImporter.import(uri)) {
                is ImportResult.Imported -> {
                    val current = _state.value
                    val now = System.currentTimeMillis()
                    val anchor = NoteBlocks.count(current.content, current.contentFormat)
                    val attachment = result.attachment.copy(
                        id = UUID.randomUUID().toString(),
                        noteId = id,
                        anchor = anchor,
                        order = nextAttachmentOrder(current.attachments, anchor),
                        createdAt = now,
                        updatedAt = now,
                    )
                    // saveAttachment deliberately never sets `meta` itself -- it stays "" here and
                    // the repository is what carries a stored value forward. See AttachmentData.meta
                    // and AttachmentsRepository.saveAttachment's own KDoc.
                    // The record first, then the block: a block must never point at a record that
                    // was not written. Kept out of the assembled document until the editor has
                    // placed it.
                    if (blocksWriteEnabled) picturesBeingPlaced.update { it + attachment.id }
                    attachmentsRepository.saveAttachment(attachment)
                    // Mirror the write into `_state` right here, for the exact reason saveSketch
                    // does: BackClicked's blank-note guard reads current.attachments immediately
                    // after joining this job (attachmentSaveJobs), and the repository's own Flow
                    // echo runs on its own dispatcher -- without this, the guard could observe an
                    // empty list for an attachment that has, in fact, just been durably saved.
                    _state.update {
                        it.copy(
                            attachments = sortAttachments(
                                it.attachments.filterNot { a -> a.id == attachment.id } + attachment.toPreview()
                            ),
                            isImportingAttachment = false,
                            picturesToPlace = if (blocksWriteEnabled) {
                                it.picturesToPlace + PicturePlacement(attachment.id, BlockType.PICTURE)
                            } else {
                                it.picturesToPlace
                            },
                        )
                    }
                }

                ImportResult.TooLarge -> {
                    _state.update { it.copy(isImportingAttachment = false) }
                    _events.send(SingleNoteEvent.AttachmentImportFailed(tooLarge = true))
                }

                ImportResult.NotAnImage -> {
                    _state.update { it.copy(isImportingAttachment = false) }
                    _events.send(SingleNoteEvent.AttachmentImportFailed(tooLarge = false))
                }
            }
        }
        attachmentSaveJobs += job
        job.invokeOnCompletion { attachmentSaveJobs -= job }
    }

    /**
     * Applies one user edit to the editor state and records what the edited slice held [before] it,
     * so undo can put that back.
     *
     * Call it only for an edit that actually changes something (each caller checks first): a
     * recorded no-op becomes an undo step that appears to do nothing when taken. [group] decides
     * whether this edit continues the previous undo step or begins a new one — see [EditGroup].
     *
     * Persisting is left to the caller, because the debounce differs per field (typing debounces,
     * a structural checklist change does not).
     */
    private fun edit(
        before: EditorRevision,
        group: EditGroup,
        apply: (SingleNoteScreenState) -> SingleNoteScreenState,
    ) {
        history.record(before, group)
        // record() has already run, so the flags below are the post-edit ones: canUndo is now true
        // and canRedo false (any recorded edit drops the redo stack).
        _state.update { apply(it).copy(canUndo = history.canUndo, canRedo = history.canRedo) }
    }

    /** The value the same slice as [like] holds right now — what undo/redo pushes onto the far stack. */
    private fun currentRevision(like: EditorRevision): EditorRevision {
        val current = _state.value
        return when (like) {
            is EditorRevision.Title -> EditorRevision.Title(current.title)
            is EditorRevision.Body -> EditorRevision.Body(current.content, current.contentFormat)
            is EditorRevision.Checklist -> EditorRevision.Checklist(current.checklist)
        }
    }

    /**
     * Puts [revision] back into the editor state and persists it, or does nothing when the stack
     * was empty.
     *
     * The write goes through saveNote() exactly like a keystroke would, so the baseline moves with
     * it and mergeIncomingNote classifies the resulting Room emission as this screen's own echo.
     * Anything else — writing the row directly, or assigning `baseline` by hand — would make the
     * undo look to the merge like an external change, or the echo look like one.
     */
    private fun applyRevision(revision: EditorRevision?) {
        if (revision == null) return
        _state.update { s ->
            val restored = when (revision) {
                is EditorRevision.Title -> s.copy(title = revision.text)
                // Bumping contentRevision is what tells the screen to re-seed RichTextState from
                // this content; without it the editor would keep displaying the undone body while
                // state (and the DB) held the restored one.
                is EditorRevision.Body -> s.copy(
                    content = revision.content,
                    contentFormat = revision.format,
                    contentRevision = s.contentRevision + 1,
                )
                // The restored list holds the ChecklistItem instances that were in state before the
                // edit, so every row keeps the id it had — no focus jump, no stranded intent.
                is EditorRevision.Checklist -> s.copy(checklist = revision.items)
            }
            restored.copy(canUndo = history.canUndo, canRedo = history.canRedo)
        }
        // Immediate rather than debounced: pressing undo is a discrete action, like toggling a
        // checklist item, not a keystroke in a burst.
        saveNote(debounce = false)
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
                // Nothing suspends between persist() returning and this assignment, so no coroutine
                // can interleave between these two statements. That is the whole of the guarantee:
                // it does NOT order this line against the load flow, because Room delivers its
                // invalidation emission on its own schedule rather than on this coroutine's
                // continuation. The row for this write can in principle reach the collector first
                // and be merged against a baseline that predates the write; writer-first is
                // near-certain in practice, not enforced here.
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
                        contentFormat = current.contentFormat,
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
                    contentFormat = current.contentFormat,
                    checklist = checklist,
                    isPinned = current.isPinned,
                    folderId = current.folderId,
                )
            }
        }
        saveJob = job
        return job
    }

    // ---------------------------------------------------------------- block path

    /**
     * The open note's document for the block editor, re-assembled whenever the stored row, its
     * attachments, its sketches or [picturesBeingPlaced] change. Each emission carries its note id
     * so the screen hands the editor the id and the document together.
     */
    private fun openedDocuments(id: String) = combine(
        notesRepository.getNoteById(id).filterNotNull().distinctUntilChanged(),
        attachmentsRepository.attachmentsOf(id),
        sketchesRepository.getSketchesForNote(id),
        picturesBeingPlaced,
    ) { note, pictures, drawings, placing ->
        OpenedNoteDocument(
            noteId = id,
            document = OpenDocument.assemble(
                note,
                pictures.filterNot { it.id in placing }.map { it.toAssemblyRecord() },
                drawings.filterNot { it.id in placing },
            ),
            unreadableBody = note.content.takeIf {
                note.contentFormat == NoteContentFormat.BLOCKS && BlockCodec.decode(it) == null
            },
        )
    }

    /** Whether a picture or drawing may be added now: the document is assembled and readable. */
    private fun canPlacePicture(): Boolean {
        val opened = _state.value.blockDocument ?: return false
        return opened.noteId == noteId && opened.unreadableBody == null
    }

    /**
     * A local edit in the block editor. Saved after [blocksSaveDelayMillis] of quiet, or at once
     * by [flushBlocksSave] on back, on leaving the screen and on `ON_STOP`.
     *
     * Ignored with the block path off (logged, so a stray call is visible), for any note but this
     * one, and while the body is unreadable: it is read-only and must never be saved over.
     */
    private fun onBlocksChanged(editedNoteId: String, blocks: List<Block>, base: BlockDocument) {
        if (!blocksWriteEnabled) {
            log("block edit of note $editedNoteId not saved: BlocksFormat.WRITE_ENABLED is off")
            return
        }
        if (editedNoteId != noteId) return
        val opened = _state.value.blockDocument ?: return
        if (opened.noteId != editedNoteId || opened.unreadableBody != null) return
        unsavedBlocks = UnsavedBlocks(blocks, base)
        lastEditedBlocks = blocks
        scheduleBlocksSave()
    }

    private fun scheduleBlocksSave() {
        if (noteId == null) return
        saveJob?.cancel()
        blocksSavePending = true
        saveJob = viewModelScope.launch {
            delay(blocksSaveDelayMillis)
            persistBlocksPath()
        }
    }

    /**
     * Writes a pending block-path save now, cancelling its debounce, and returns the job writing
     * it (or the save already writing, for a caller that wants to wait for it).
     *
     * [scope] is [viewModelScope] except from [onCleared], where that scope is already cancelled.
     * Either way the write itself runs [NonCancellable] (see [persistBlocksPath]), so the back
     * stack being cleared right after `ON_STOP` (the app lock) cannot cut it short.
     */
    private fun flushBlocksSave(scope: CoroutineScope = viewModelScope): Job? {
        if (!blocksSavePending) return saveJob
        saveJob?.cancel()
        val job = scope.launch { persistBlocksPath() }
        saveJob = job
        return job
    }

    /**
     * The block path's one write. With block edits pending, the body is those blocks, built only by
     * [BlocksSave.encodeForSave] and saved against the editor's base, which the repository stamps
     * and merges. Without (a title edit before the editor loaded, or on a read-only body), the body
     * is the stored row's, read fresh under [saveMutex]: [SingleNoteScreenState.content] can trail
     * a block save whose echo has not come back yet, and writing it would put the older body back.
     *
     * The baseline moves for the title and metadata only. The body's baseline is left alone, so the
     * stored row's echo is adopted into [SingleNoteScreenState.content] as news.
     */
    private suspend fun persistBlocksPath() = withContext(NonCancellable) {
        saveMutex.withLock {
            blocksSavePending = false
            val id = noteId ?: return@withLock
            val current = _state.value
            val readable = current.blockDocument?.unreadableBody == null
            val typed = unsavedBlocks?.takeIf { readable }
            unsavedBlocks = null
            val note = if (typed != null) {
                Note(
                    id = id,
                    title = current.title,
                    content = BlocksSave.encodeForSave(typed.blocks),
                    contentFormat = NoteContentFormat.BLOCKS,
                    checklist = current.checklist.serializeChecklist(),
                    isPinned = current.isPinned,
                    folderId = current.folderId,
                )
            } else {
                val stored = notesRepository.getNoteById(id).first() ?: return@withLock
                stored.copy(title = current.title, isPinned = current.isPinned, folderId = current.folderId)
            }
            notesRepository.saveNote(note, typed?.let { BlockCodec.encode(it.base) })
            baseline = baseline?.copy(title = current.title, isPinned = current.isPinned, folderId = current.folderId)
        }
    }

    /**
     * Back on the block path: discards a note created for this screen and still blank, else writes
     * any pending save.
     *
     * Blank is asked of the editor's last edit when there was one ([BlockText.isEmpty]: a picture
     * or drawing block is content), else of the stored body. A picture or drawing record of the
     * note, or one still waiting for its block, keeps the note too, so no photo is purged with it.
     */
    private suspend fun leaveBlocksNote() {
        val id = noteId ?: return
        val current = _state.value
        val bodyBlank = lastEditedBlocks?.let(BlockText::isEmpty)
            ?: (bodyIsBlank(current.content, current.contentFormat) && current.checklist.isEmpty())
        val blank = createdBlankNote && current.title.isBlank() && bodyBlank &&
            current.sketches.isEmpty() && current.attachments.isEmpty() && current.picturesToPlace.isEmpty()
        if (blank) {
            saveJob?.cancel()
            unsavedBlocks = null
            blocksSavePending = false
            saveMutex.withLock { notesRepository.purgeNote(id) }
        } else {
            flushBlocksSave()?.join()
        }
    }

    /**
     * The block path's duplicate: the body as stored once any pending save is written, so the copy
     * matches the note on screen. Picture and drawing blocks are left out: their records belong to
     * this note, and a copy pointing at them would show placeholders forever; the legacy duplicate
     * never copied photos or drawings either.
     */
    private suspend fun blocksDuplicate(): Note? {
        val id = noteId ?: return null
        flushBlocksSave()?.join()
        val stored = notesRepository.getNoteById(id).first() ?: return null
        val doc = if (stored.contentFormat == NoteContentFormat.BLOCKS) BlockCodec.decode(stored.content) else null
        val content = doc?.let { d ->
            BlocksSave.encodeForSave(d.visible().filter { it.type != BlockType.PICTURE && it.type != BlockType.DRAWING })
        } ?: stored.content
        return Note(
            id = UUID.randomUUID().toString(),
            title = duplicateTitle(_state.value.title),
            content = content,
            contentFormat = stored.contentFormat,
            checklist = stored.checklist,
            isPinned = false,
            isFavorite = false,
            folderId = _state.value.folderId,
        )
    }

    /**
     * A block save still waiting for its debounce when the ViewModel goes (the back stack cleared
     * under it). [viewModelScope] is cancelled by now, so the write gets a scope of its own; it is
     * the same [NonCancellable] write as every other.
     */
    override fun onCleared() {
        if (blocksWriteEnabled && blocksSavePending) flushBlocksSave(CoroutineScope(Dispatchers.Main.immediate))
        super.onCleared()
    }

    // Content fields only (title/body/checklist) — exactly what a save stamps updatedAt for.
    // Metadata (pin/favorite/folder) persists through targeted UPDATEs and must not force an upsert.
    // Compared against the baseline rather than the last row *seen*: an edit made after the last
    // write must count as unsaved even though Room hasn't echoed that write back yet.
    private fun SingleNoteScreenState.hasUnsavedContent(): Boolean {
        val persisted = baseline ?: return true
        // On the block path the body is the editor's, unsaved exactly while a block save is pending.
        if (blocksWriteEnabled) return blocksSavePending || title != persisted.title
        return title != persisted.title ||
                content != persisted.content ||
                // A body that changed format but not bytes still has to be flushed, or the row
                // would keep a stale marker and be re-parsed with the wrong reader on reopen.
                contentFormat != persisted.contentFormat ||
                checklist.serializeChecklist() != persisted.checklist
    }
}

/**
 * Whether a stored body is empty. A BLOCKS body is JSON and never a blank string, so it is asked of
 * the blocks; the other formats keep the plain `isBlank` they always had.
 */
private fun bodyIsBlank(content: String, format: NoteContentFormat): Boolean =
    BlockText.bodyIsBlank(content, format) ?: content.isBlank()

/**
 * [AttachmentPreview] as the [AttachmentData] that [OpenDocument.assemble] takes. Assembly reads
 * only identity, ownership, order and the tombstone, so the full-size bytes this screen never holds
 * are left empty rather than loaded.
 */
private fun AttachmentPreview.toAssemblyRecord(): AttachmentData = AttachmentData(
    id = id,
    noteId = noteId,
    anchor = anchor,
    order = order,
    mimeType = mimeType,
    width = width,
    height = height,
    bytes = ByteArray(0),
    thumbWidth = thumbWidth,
    thumbHeight = thumbHeight,
    thumbBytes = thumbBytes,
    createdAt = createdAt,
    updatedAt = updatedAt,
    isDeleted = isDeleted,
    deletedAt = deletedAt,
    meta = meta,
)
