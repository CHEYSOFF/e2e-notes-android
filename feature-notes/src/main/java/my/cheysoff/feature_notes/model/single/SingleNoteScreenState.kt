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
    // The stored body as last emitted, with the format that says how to read it. The editor never
    // writes these: it reports blocks (SingleNoteIntent.BlocksChanged) and the ViewModel saves them
    // as BLOCKS. Read for the blank-note check and for share/copy before the editor has loaded.
    val content: String = "",
    val contentFormat: NoteContentFormat = NoteContentFormat.PLAIN,
    // The stored legacy checklist field. The editor shows its items as check blocks
    // (OpenDocument.assemble) and the first block save folds them into the body and empties it.
    val checklist: List<ChecklistItem> = emptyList(),
    // The note's drawing records, in display order (sorted by anchor then id, see
    // `SingleNoteViewModel.sortSketches`). The body's drawing blocks look their record up here, and
    // the blank-note discard guard in BackClicked counts them.
    val sketches: List<SketchData> = emptyList(),
    // The note's picture records, in the same display order. Read by the picture blocks, the
    // photo viewer and the blank-note discard guard.
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
    val updatedAt: Long = 0L,
    val isLoaded: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
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
