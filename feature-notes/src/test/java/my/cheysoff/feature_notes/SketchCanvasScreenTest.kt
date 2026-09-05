package my.cheysoff.feature_notes

import my.cheysoff.feature_notes.ui.sketch.shouldSaveSketchOnDone
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [shouldSaveSketchOnDone] is the one decision in `SketchCanvasScreen` worth pinning down on its
 * own: whether tapping Done hands the canvas to [onDone][my.cheysoff.feature_notes.ui.sketch.SketchCanvasScreen]
 * or falls through to `onCancel`. Everything else in that file is gesture/rendering wiring with no
 * unit worth writing (see the file's own KDoc) -- this one function is a plain boolean decision,
 * pulled out so it does not have to be exercised through Compose.
 *
 * L8: a reopened sketch whose strokes were all erased must still be SAVED (as an empty result),
 * not silently discarded back to the drawing that was on disk before -- only a brand-new,
 * never-drawn-on canvas has "nothing to save".
 */
class SketchCanvasScreenTest {

    @Test
    fun `a brand-new canvas with no strokes has nothing to save`() {
        assertFalse(shouldSaveSketchOnDone(hasStrokes = false, isReopenedSketch = false))
    }

    @Test
    fun `a brand-new canvas with strokes is saved`() {
        assertTrue(shouldSaveSketchOnDone(hasStrokes = true, isReopenedSketch = false))
    }

    @Test
    fun `a reopened sketch erased down to nothing is still saved, as an empty result`() {
        assertTrue(shouldSaveSketchOnDone(hasStrokes = false, isReopenedSketch = true))
    }

    @Test
    fun `a reopened sketch that still has strokes is saved`() {
        assertTrue(shouldSaveSketchOnDone(hasStrokes = true, isReopenedSketch = true))
    }
}
