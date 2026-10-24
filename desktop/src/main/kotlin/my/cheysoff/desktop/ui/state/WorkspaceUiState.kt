package my.cheysoff.desktop.ui.state

import androidx.compose.runtime.Immutable
import my.cheysoff.core_blocks.BlockDocument
import my.cheysoff.core_domain.model.AttachmentData
import my.cheysoff.core_domain.model.Folder
import my.cheysoff.core_domain.model.NoteContentFormat
import my.cheysoff.core_domain.sketch.DisplaySketch
import my.cheysoff.core_domain.sketch.sketchesForDisplay
import my.cheysoff.desktop.ui.attachment.DesktopImportResult

/** Everything the two panes draw, in one snapshot. */
@Immutable
data class WorkspaceUiState(
    val folders: List<Folder> = emptyList(),
    val chips: List<FolderChipUi> = emptyList(),
    val content: NoteListContent = NoteListContent(),
    val selectedFolderId: String? = null,
    val selectedNoteId: String? = null,
    val editor: EditorDraft? = null,
    val search: SearchState = SearchState(),
    val saveStatus: SaveStatus = SaveStatus.Idle,
    /**
     * The open note's sketches, already ordered and decode-checked -- see [sketchesForDisplay].
     * Empty whenever nothing is open, or on the preview build (no [DesktopSketches] to read from).
     */
    val sketches: List<DisplaySketch> = emptyList(),
    /**
     * The open note's live attachments, unsorted -- ordering them is a UI concern (the viewer pages
     * through `orderedForDisplay`), not something computed here, the
     * same split [getSketchesForNote][my.cheysoff.desktop.store.RecordNotesRepository.getSketchesForNote]
     * documents for [sketches]. Unlike [sketches] there is no decode-checked "for display" mapping:
     * an attachment's bytes are decoded where its picture block is drawn, and a failed decode there
     * is a placeholder, not a filtered-out row. Empty whenever nothing is open, or on the
     * preview build (no [my.cheysoff.desktop.store.DesktopAttachments] to read from).
     */
    val attachments: List<AttachmentData> = emptyList(),
    /**
     * The id of the attachment currently shown full-screen, or null when the viewer is closed.
     * Lives here rather than as local UI state so `NotesWorkspaceScreen` (which renders
     * `AttachmentViewer` as a sibling of `SearchPalette` in the window's root) and
     * `DesktopApp.handleShortcut` (which closes it on Escape) can both see it -- the same shape
     * [SearchState.isOpen] uses for the palette. `NotesWorkspaceModel.openAttachmentViewer`/
     * `closeAttachmentViewer` are the only writers.
     */
    val viewingAttachmentId: String? = null,
    /**
     * Why the last Ctrl+V of an image attached nothing ([DesktopImportResult.TooLarge] or
     * [DesktopImportResult.NotAnImage]), shown under the editor until dismissed or the next paste.
     */
    val pasteFailure: DesktopImportResult? = null,
    /**
     * False until the repository has emitted once. Distinguishes "no notes yet" from "not asked
     * yet" — without it the empty state flashes on every launch before the first emission lands.
     */
    val loaded: Boolean = false,
)

/**
 * The note the editor pane is editing, held apart from the repository's copy.
 *
 * A repository emission for the same id does NOT simply overwrite it -- treating the database as
 * the source of truth for a note being typed into is the clobbering bug the Android editor had to
 * be fixed for, where the autosave's own echo arrived a keystroke late and reset the cursor.
 * Instead it is merged field by field against a baseline (see `NotesWorkspaceModel.mergeIncoming`):
 * a field the user has not touched takes the stored value, which is how a change synced in from
 * the phone reaches a note that is open here.
 */
@Immutable
data class EditorDraft(
    val id: String,
    val title: String,
    val content: String,
    val contentFormat: NoteContentFormat,
    val checklist: List<DesktopChecklistItem>,
    val isPinned: Boolean,
    val isFavorite: Boolean,
    val folderId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    /**
     * The document the shared block editor shows for this note, from `OpenDocument.assemble` over
     * the stored row and the note's live pictures and drawings. Null until the first assembly
     * lands.
     *
     * It lives on the draft, next to [id], so the note id and its document always travel as one
     * value: the pane can never hand note A's document to an editor opened for note B. Re-assembled
     * on every storage change of the note, its attachments or its sketches; the editor applies each
     * one as a synced change (RemoteApply), so a synced body never reloads the editor.
     */
    val blocks: BlockDocument? = null,
    /**
     * The raw stored body when it is BLOCKS but does not decode -- most likely written by a newer
     * build. Such a note is shown read-only and its body is never saved, since overwriting it would
     * destroy what that build wrote. Null otherwise.
     */
    val unreadableBody: String? = null,
)

@Immutable
data class SearchState(
    val isOpen: Boolean = false,
    val query: String = "",
    val hits: List<NoteSearchHit> = emptyList(),
    /** Index into [hits] that Enter would open, moved by the arrow keys. */
    val highlighted: Int = 0,
)

sealed interface SaveStatus {
    /** Nothing pending and nothing recently written. */
    data object Idle : SaveStatus

    /** Edited, not yet flushed. The autosave debounce is running. */
    data object Pending : SaveStatus

    /** Last write completed at [at] (wall clock ms). */
    data class Saved(val at: Long) : SaveStatus
}
