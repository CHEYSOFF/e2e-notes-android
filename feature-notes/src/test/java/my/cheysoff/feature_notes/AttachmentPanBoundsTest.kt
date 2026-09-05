package my.cheysoff.feature_notes

import my.cheysoff.feature_notes.ui.attachment.panBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [panBounds] is the one piece of `AttachmentViewerScreen`'s pinch-zoom/pan gesture handling that
 * is pure arithmetic rather than Compose wiring -- see that file's own KDoc for why the rest of
 * the screen (gesture routing, `graphicsLayer`, the delete dialog) stays in the established
 * composable-free carve-out. This is real geometry with real edge cases, not test theatre.
 *
 * The second and third tests here are the ones that would have caught the original bug: bounding
 * against the viewer's own box rather than the rect the image actually renders to under
 * `ContentScale.Fit`. On the axis `ContentScale.Fit` does NOT letterbox (the "fitted" axis, whose
 * rendered size equals the box), [panBounds] degenerates to exactly the old, naive
 * `box * (scale - 1) / 2` formula -- there is nothing to fix there. The bug was on the OTHER
 * axis (the "letterboxed" one, whose rendered size is smaller than the box): the naive formula
 * still used the full box size there too, letting a zoomed image be dragged until a gap opened
 * along that edge even though the rendered image hadn't grown past the box yet.
 */
class AttachmentPanBoundsTest {

    @Test
    fun `a photo that exactly fits cannot be panned at all`() {
        val bounds = panBounds(
            imageWidth = 1000,
            imageHeight = 2000,
            boxWidth = 500f,
            boxHeight = 1000f,
            scale = 1f,
        )
        assertEquals(0f, bounds.x)
        assertEquals(0f, bounds.y)
    }

    @Test
    fun `a wide photo on a tall screen is bounded by its rendered height not the box`() {
        // 2000x1000 (2:1) into a 1000x2000 box: fit scale is min(1000/2000, 2000/1000) = 0.5, so
        // the image renders at 1000x500 -- width (1000) fills the box exactly (the fitted axis);
        // height (500) is letterboxed, nowhere near the box's own 2000.
        val boxWidth = 1000f
        val boxHeight = 2000f
        val scale = 2f

        val bounds = panBounds(
            imageWidth = 2000,
            imageHeight = 1000,
            boxWidth = boxWidth,
            boxHeight = boxHeight,
            scale = scale,
        )

        // Letterboxed axis (height): rendered height at this scale is 500 * 2 = 1000, still well
        // under the box's 2000, so there is nothing to pan yet.
        assertEquals(0f, bounds.y)
        // The bug this guards against: the old code bounded this axis against the full box
        // height regardless of letterboxing, which would have allowed a real (wrong) pan here.
        val boxBoundedY = (boxHeight * (scale - 1f) / 2f).coerceAtLeast(0f)
        assertTrue(boxBoundedY > 0f)
        assertTrue(bounds.y < boxBoundedY)

        // Fitted axis (width): rendered width equals the box exactly, so this axis's bound is
        // unaffected by the fix -- it matches the naive box-bound formula precisely.
        val boxBoundedX = (boxWidth * (scale - 1f) / 2f).coerceAtLeast(0f)
        assertEquals(boxBoundedX, bounds.x)
    }

    @Test
    fun `a tall photo on a wide screen is bounded by its rendered width not the box`() {
        // 1000x2000 (1:2) into a 2000x1000 box: fit scale is min(2000/1000, 1000/2000) = 0.5, so
        // the image renders at 500x1000 -- height (1000) fills the box exactly (the fitted axis);
        // width (500) is letterboxed, nowhere near the box's own 2000.
        val boxWidth = 2000f
        val boxHeight = 1000f
        val scale = 3f

        val bounds = panBounds(
            imageWidth = 1000,
            imageHeight = 2000,
            boxWidth = boxWidth,
            boxHeight = boxHeight,
            scale = scale,
        )

        // Letterboxed axis (width): rendered width at this scale is 500 * 3 = 1500, still under
        // the box's 2000, so there is nothing to pan yet.
        assertEquals(0f, bounds.x)
        val boxBoundedX = (boxWidth * (scale - 1f) / 2f).coerceAtLeast(0f)
        assertTrue(boxBoundedX > 0f)
        assertTrue(bounds.x < boxBoundedX)

        // Fitted axis (height): rendered height equals the box exactly, so this axis's bound
        // matches the naive box-bound formula precisely.
        val boxBoundedY = (boxHeight * (scale - 1f) / 2f).coerceAtLeast(0f)
        assertEquals(boxBoundedY, bounds.y)
    }

    @Test
    fun `bounds grow with scale`() {
        val at2x = panBounds(
            imageWidth = 2000,
            imageHeight = 1000,
            boxWidth = 1000f,
            boxHeight = 2000f,
            scale = 2f,
        )
        val at4x = panBounds(
            imageWidth = 2000,
            imageHeight = 1000,
            boxWidth = 1000f,
            boxHeight = 2000f,
            scale = 4f,
        )
        assertTrue(at4x.x > at2x.x)
    }

    @Test
    fun `a zero dimension yields no pan rather than dividing by zero`() {
        val zeroImageWidth = panBounds(0, 1000, 500f, 500f, 2f)
        val zeroImageHeight = panBounds(1000, 0, 500f, 500f, 2f)
        val zeroBoxWidth = panBounds(1000, 1000, 0f, 500f, 2f)
        val zeroBoxHeight = panBounds(1000, 1000, 500f, 0f, 2f)
        val negativeWidth = panBounds(-1, 1000, 500f, 500f, 2f)

        for (bounds in listOf(zeroImageWidth, zeroImageHeight, zeroBoxWidth, zeroBoxHeight, negativeWidth)) {
            assertEquals(0f, bounds.x)
            assertEquals(0f, bounds.y)
        }
    }
}
