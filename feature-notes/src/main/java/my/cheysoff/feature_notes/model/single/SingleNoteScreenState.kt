package my.cheysoff.feature_notes.model.single

import androidx.compose.runtime.Immutable
import my.cheysoff.core_blocks.BlockDocument
import my.cheysoff.core_blocks.BlockType
import my.cheysoff.core_domain.model.AttachmentPreview
import my.cheysoff.core_domain.model.Folder
import my.cheysoff.core_domain.model.NoteContentFormat
import my.cheysoff.core_domain.model.SketchData

@Immutable
data class SingleNoteScreenState(
    val title: String = "",
    val content: String = "",
    // How `content` is encoded. Seeded from the stored row and flipped to HTML as soon as the
    // rich-text editor writes the body, so a title-only edit can't relabel untouched plain text.
    val contentFormat: NoteContentFormat = NoteContentFormat.PLAIN,
    val checklist: List<ChecklistItem> = emptyList(),
    // Below the note's text, never interleaved with it -- see docs/design/sketch-blocks.md's
    // 2026-09-05 amendment. Always in display order already: sorted by anchor then id (see
    // `SingleNoteViewModel.sortSketches`), the same rule the desktop applies, so both devices show
    // one note's drawings in the same order without exchanging anything about it.
    val sketches: List<SketchData> = emptyList(),
    // The rail this screen doesn't render yet (Task 6 owns it) -- collected here regardless,
    // because the blank-note discard guard in BackClicked needs to know whether one exists before
    // it purges a note that would otherwise look empty. Always in display order already: sorted by
    // anchor then id (see `SingleNoteViewModel.sortAttachments`), matching `sketches` above.
    val attachments: List<AttachmentPreview> = emptyList(),
    /** Colours recently mixed in the sketch canvas, most recent first. Device-local, never synced. */
    val recentSketchColors: List<Long> = emptyList(),
    // True while a picked photo is being decoded, downscaled and encoded off the main thread.
    // Drives the toolbar's progress indication for `SingleNoteIntent.ImportAttachment`.
    val isImportingAttachment: Boolean = false,
    val isPinned: Boolean = false,
    val isFavorite: Boolean = false,
    val folderId: String? = null,
    val folders: List<Folder> = emptyList(),
    // Whether the editor-wide undo/redo stack (title + body + checklist) has anything to take back
    // or replay. Drives the enabled state of the two top-bar buttons.
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    // Bumped every time the ViewModel replaces [content] itself — i.e. on an undo or redo of a body
    // edit — and never when the body changes because the editor reported it. The screen re-seeds
    // RichTextState whenever this changes, so typing (which moves `content` on every flush) can
    // never trigger a re-seed and move the cursor out from under the user.
    val contentRevision: Int = 0,
    val updatedAt: Long = 0L,
    val isLoaded: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    /**
     * Whether the body is edited by the shared block editor and saved as BLOCKS (Task 15), fixed
     * for the ViewModel's life from `BlocksFormat.WRITE_ENABLED`. Off, everything above behaves
     * exactly as before the block editor existed and the three fields below stay at their defaults.
     */
    val blocksEditing: Boolean = false,
    /** The document the block editor shows; null until it is first assembled. See [OpenedNoteDocument]. */
    val blockDocument: OpenedNoteDocument? = null,
    /**
     * Pictures and drawings whose record is written and whose block the editor has yet to insert
     * at the cursor. State rather than a callback so a placement survives the screen being
     * recreated between the write and the insert; the screen inserts each and then reports
     * `SingleNoteIntent.PicturePlaced`.
     */
    val picturesToPlace: List<PicturePlacement> = emptyList(),
)

/**
 * The block editor's input for one note: the note id and its assembled document, in one value so
 * the screen can never hand the editor one note's id with another note's document (the editor
 * would apply the document to the wrong note as a synced change).
 *
 * [unreadableBody] is the raw stored body when it is BLOCKS this build cannot decode (most likely
 * a newer build's format). Such a note is shown read-only and never saved over.
 */
@Immutable
data class OpenedNoteDocument(
    val noteId: String,
    val document: BlockDocument,
    val unreadableBody: String? = null,
)

/** A picture ([BlockType.PICTURE]) or drawing ([BlockType.DRAWING]) record waiting for its block. */
@Immutable
data class PicturePlacement(val recordId: String, val kind: BlockType)
