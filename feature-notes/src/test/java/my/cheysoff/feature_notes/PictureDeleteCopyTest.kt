package my.cheysoff.feature_notes

import my.cheysoff.feature_notes.ui.attachment.pictureDeleteCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The delete dialogs of the photo viewer and the drawing canvas. On the block path the delete
 * removes a block, an undoable edit, so the dialog must not claim it cannot be undone.
 */
class PictureDeleteCopyTest {

    @Test
    fun `the legacy path says the delete is final`() {
        val photo = pictureDeleteCopy(isDrawing = false, undoable = false)
        assertEquals("Delete this photo?", photo.title)
        assertTrue(photo.body.contains("can't be undone"))
        assertEquals("Delete", photo.confirm)
        assertEquals("This cannot be undone.", pictureDeleteCopy(isDrawing = true, undoable = false).body)
    }

    @Test
    fun `the block path says the removal can be undone`() {
        for (isDrawing in listOf(false, true)) {
            val copy = pictureDeleteCopy(isDrawing, undoable = true)
            assertFalse(copy.body.contains("undone"))
            assertEquals("You can undo it.", copy.body)
            assertEquals("Remove", copy.confirm)
        }
        assertEquals("Remove this picture from the note?", pictureDeleteCopy(isDrawing = false, undoable = true).title)
        assertEquals("Remove this drawing from the note?", pictureDeleteCopy(isDrawing = true, undoable = true).title)
    }
}
