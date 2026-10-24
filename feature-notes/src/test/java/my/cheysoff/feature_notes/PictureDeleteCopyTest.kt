package my.cheysoff.feature_notes

import my.cheysoff.feature_notes.ui.attachment.pictureDeleteCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The delete dialogs of the photo viewer and the drawing canvas. The delete removes a block, an
 * undoable edit, so the dialog must not claim it cannot be undone.
 */
class PictureDeleteCopyTest {

    @Test
    fun `the dialog says the removal can be undone`() {
        for (isDrawing in listOf(false, true)) {
            val copy = pictureDeleteCopy(isDrawing)
            assertFalse(copy.body.contains("undone"))
            assertEquals("You can undo it.", copy.body)
            assertEquals("Remove", copy.confirm)
        }
        assertEquals("Remove this picture from the note?", pictureDeleteCopy(isDrawing = false).title)
        assertEquals("Remove this drawing from the note?", pictureDeleteCopy(isDrawing = true).title)
    }
}
