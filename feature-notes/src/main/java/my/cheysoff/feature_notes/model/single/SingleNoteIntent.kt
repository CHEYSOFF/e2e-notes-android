package my.cheysoff.feature_notes.model.single

import my.cheysoff.core_blocks.Block
import my.cheysoff.core_blocks.BlockDocument
import my.cheysoff.core_domain.sketch.Sketch

sealed interface SingleNoteIntent {
    data class TitleChanged(val title: String) : SingleNoteIntent
    data class ContentChanged(val content: String) : SingleNoteIntent
    data object TogglePin : SingleNoteIntent
    data object ToggleFavorite : SingleNoteIntent

    /**
     * Write a copy of this note as a brand-new row, leaving the editor on the original.
     * See `buildDuplicate` for exactly which fields the copy carries.
     */
    data object DuplicateNote : SingleNoteIntent

    data object BackClicked : SingleNoteIntent

    /**
     * Take back / replay the last editor edit, whichever field it touched. The screen must flush
     * any debounced body content before sending these, or the undo would step back past edits the
     * ViewModel has not been told about yet.
     */
    data object Undo : SingleNoteIntent
    data object Redo : SingleNoteIntent

    /**
     * Send this note to Trash and leave the editor. The user's only route to deleting a note; the
     * overflow menu confirms before sending it.
     */
    data object DeleteNote : SingleNoteIntent

    /** Append a new empty checklist item with [newId] after [afterId] (null = at the end). */
    data class ChecklistItemAdded(val newId: String, val afterId: String?) : SingleNoteIntent
    data class ChecklistItemToggled(val id: String) : SingleNoteIntent
    data class ChecklistItemTextChanged(val id: String, val text: String) : SingleNoteIntent
    data class ChecklistItemRemoved(val id: String) : SingleNoteIntent

    /** Assign this note to [folderId], or unfile it when null. */
    data class SetFolder(val folderId: String?) : SingleNoteIntent

    /**
     * A drawing was finished on the canvas and should be persisted. [editingId] is the id of the
     * sketch being replaced when this is a re-edit of an existing drawing (opened by tapping it);
     * null means this is a brand-new one. See `SingleNoteViewModel.saveSketch` for exactly how each
     * case is anchored, ordered and stamped.
     */
    data class SketchSaved(val editingId: String?, val sketch: Sketch) : SingleNoteIntent

    /** Soft-deletes one sketch, through `SketchesRepository.deleteSketch`. */
    data class SketchDeleted(val id: String) : SingleNoteIntent

    /**
     * A photo was picked (via `ActivityResultContracts.PickVisualMedia`) and should be imported:
     * decoded, downscaled, capped and saved as an attachment anchored at the note's current block
     * count. See `SingleNoteViewModel.importAttachment` for exactly how it is anchored, ordered and
     * stamped -- the same shape `saveSketch` uses for a brand-new drawing.
     *
     * [uri] is a `String`, not an `android.net.Uri`, so this intent (and everything it flows
     * through -- `ImageImporter`, `ImportResult`, `SingleNoteViewModel`) stays platform-free. The
     * screen converts the picker's `android.net.Uri` with `.toString()` before sending this intent;
     * see `ImageImporter`'s own KDoc for why that round trip is lossless.
     */
    data class ImportAttachment(val uri: String) : SingleNoteIntent

    /**
     * Deletes one attachment by id, through `AttachmentsRepository.deleteAttachment`. Sent only
     * from the full-screen viewer's confirmed delete -- there is no inline delete on a rail tile,
     * matching `docs/design/image-attachments.md` §8. Unlike [SketchDeleted]'s soft delete, which
     * at least leaves a route back through a sketch-aware future build, an attachment has no
     * Trash entry at all (`TrashEntryKind` is `{NOTE, FOLDER}`), so the viewer's confirmation
     * dialog says plainly that this cannot be undone.
     */
    data class AttachmentDeleted(val id: String) : SingleNoteIntent

    // ---- Block path only (SingleNoteScreenState.blocksEditing) ---------------------------------

    /**
     * The block editor's [blocks] for note [noteId] after a local edit, with the editor's [base]
     * read at that same moment (`BlockEditorController.base`). Captured together, never re-read at
     * save time: a synced change applied between the edit and the save moves the base but not
     * these blocks, and stamping old blocks against a newer base would read the synced change as
     * the user undoing it.
     */
    data class BlocksChanged(val noteId: String, val blocks: List<Block>, val base: BlockDocument) : SingleNoteIntent

    /** The editor inserted the block for [recordId] from `SingleNoteScreenState.picturesToPlace`. */
    data class PicturePlaced(val recordId: String) : SingleNoteIntent

    /**
     * Write a debounced save now. Sent when the screen leaves composition and on `ON_STOP`, after
     * which the process may be killed without another callback.
     */
    data object FlushPendingSave : SingleNoteIntent
}
