package my.cheysoff.feature_notes.ui.attachment

/** The words of a picture or drawing delete-confirm dialog. */
internal data class PictureDeleteCopy(val title: String, val body: String, val confirm: String)

/**
 * The confirm dialog's words for deleting a photo ([isDrawing] false) or a drawing.
 *
 * The delete removes the picture's block from the note as an ordinary editor edit: Undo puts it
 * back, and the save that follows the undo undeletes the record. Saying "can't be undone" would be
 * false, and would scare people off a safe action.
 */
internal fun pictureDeleteCopy(isDrawing: Boolean): PictureDeleteCopy = PictureDeleteCopy(
    title = if (isDrawing) "Remove this drawing from the note?" else "Remove this picture from the note?",
    body = "You can undo it.",
    confirm = "Remove",
)
