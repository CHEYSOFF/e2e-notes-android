package my.cheysoff.feature_notes.ui.attachment

/** The words of a picture or drawing delete-confirm dialog. */
internal data class PictureDeleteCopy(val title: String, val body: String, val confirm: String)

/**
 * The confirm dialog's words for deleting a photo ([isDrawing] false) or a drawing.
 *
 * [undoable] is true on the block path, where the delete removes the picture's block from the note
 * as an ordinary editor edit: Undo puts it back, and the save that follows the undo undeletes the
 * record. Saying "can't be undone" there would be false, and would scare people off a safe action.
 * On the legacy path the delete goes straight to the record, which has no Trash, so it is final and
 * the dialog says so.
 */
internal fun pictureDeleteCopy(isDrawing: Boolean, undoable: Boolean): PictureDeleteCopy = when {
    undoable -> PictureDeleteCopy(
        title = if (isDrawing) "Remove this drawing from the note?" else "Remove this picture from the note?",
        body = "You can undo it.",
        confirm = "Remove",
    )
    isDrawing -> PictureDeleteCopy("Delete this drawing?", "This cannot be undone.", "Delete")
    else -> PictureDeleteCopy("Delete this photo?", "This can't be undone. Photos aren't kept in Trash.", "Delete")
}
