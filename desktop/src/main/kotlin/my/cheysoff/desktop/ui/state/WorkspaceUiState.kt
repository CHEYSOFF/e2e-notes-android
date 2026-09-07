package my.cheysoff.desktop.ui.state

import androidx.compose.runtime.Immutable
import my.cheysoff.core_domain.model.AttachmentData
import my.cheysoff.core_domain.model.Folder
import my.cheysoff.core_domain.model.NoteContentFormat
import my.cheysoff.core_domain.sketch.DisplaySketch
import my.cheysoff.core_domain.sketch.sketchesForDisplay

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
     * The open note's live attachments, unsorted -- ordering an attachment rail is a UI concern
     * (`AttachmentRail` applies `sortAttachments` at render time), not something computed here, the
     * same split [getSketchesForNote][my.cheysoff.desktop.store.RecordNotesRepository.getSketchesForNote]
     * documents for [sketches]. Unlike [sketches] there is no decode-checked "for display" mapping:
     * an attachment's bytes are decoded per tile in the rail itself, and a failed decode there is a
     * placeholder tile, not a filtered-out row. Empty whenever nothing is open, or on the
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
     * False until the repository has emitted once. Distinguishes "no notes yet" from "not asked
     * yet" — without it the empty state flashes on every launch before the first emission lands.
     */
    val loaded: Boolean = false,
)

/**
 * The note the editor pane is editing, held apart from the repository's copy.
 *
 * This is the authoritative version while the pane is open: a repository emission for the same id
 * does NOT overwrite it. The alternative — treating the database as the source of truth for a note
 * being typed into — is the clobbering bug the Android editor had to be fixed for, where the
 * autosave's own echo arrived a keystroke late and reset the cursor.
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
