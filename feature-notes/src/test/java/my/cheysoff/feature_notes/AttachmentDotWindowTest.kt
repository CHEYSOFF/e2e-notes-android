package my.cheysoff.feature_notes

import my.cheysoff.feature_notes.ui.attachment.dotWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The photo viewer's dot row, which slides once there are more photos than dots.
 *
 * Composable-free by construction: the window is the only part of that row that can be wrong
 * quietly, and it is pure arithmetic, so it is tested here for the same reason `panBounds` is.
 */
class AttachmentDotWindowTest {

    @Test
    fun `a short row shows every photo and never slides`() {
        // Whatever is current, all seven are drawn -- this is what makes a full row never taper.
        assertEquals(0..4, dotWindow(count = 5, current = 0))
        assertEquals(0..4, dotWindow(count = 5, current = 4))
        assertEquals(0..6, dotWindow(count = 7, current = 3))
    }

    @Test
    fun `the window never grows past the dot cap`() {
        for (current in 0 until 40) {
            val window = dotWindow(count = 40, current = current)
            assertTrue("window $window was wider than the cap", window.count() <= 7)
        }
    }

    @Test
    fun `the window centres on the current photo in the middle of a long run`() {
        // 20 photos, showing the 11th (index 10): three either side.
        assertEquals(7..13, dotWindow(count = 20, current = 10))
    }

    @Test
    fun `the window pins at the start rather than running off the front`() {
        assertEquals(0..6, dotWindow(count = 20, current = 0))
        assertEquals(0..6, dotWindow(count = 20, current = 2))
    }

    /** The one that catches an off-by-one: the last photo must be inside the window it produces. */
    @Test
    fun `the window pins at the end and still reaches the last photo`() {
        val window = dotWindow(count = 20, current = 19)

        assertEquals(13..19, window)
        assertTrue("the last photo must be reachable", 19 in window)
    }

    @Test
    fun `every current photo is inside its own window`() {
        for (count in 1..30) {
            for (current in 0 until count) {
                val window = dotWindow(count, current)
                assertTrue("$current fell outside $window at count $count", current in window)
            }
        }
    }

    @Test
    fun `a nonsense count yields no dots rather than a negative range`() {
        assertTrue(dotWindow(count = 0, current = 0).isEmpty())
        assertTrue(dotWindow(count = -3, current = 2).isEmpty())
    }

    /** A current index outside the list is clamped, not propagated into a broken range. */
    @Test
    fun `an out of range current is clamped`() {
        assertEquals(dotWindow(count = 5, current = 4), dotWindow(count = 5, current = 99))
        assertEquals(dotWindow(count = 5, current = 0), dotWindow(count = 5, current = -7))
    }
}
