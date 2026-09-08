package my.cheysoff.desktop.ui.attachment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The viewer's dot row, which slides once a note holds more photos than there are dots.
 *
 * Mirrors `AttachmentDotWindowTest` on the phone deliberately. The two are separate implementations
 * of one rule -- the shared module has no UI to put it in -- so the cases are kept identical: if
 * the two ever disagree, the same note would report a different position on each of the user's
 * devices, and neither platform's tests alone could see it.
 */
class DesktopDotWindowTest {

    @Test
    fun `a short row shows every photo and never slides`() {
        assertEquals(0..4, dotWindow(count = 5, current = 0))
        assertEquals(0..4, dotWindow(count = 5, current = 4))
        assertEquals(0..6, dotWindow(count = 7, current = 3))
    }

    @Test
    fun `the window never grows past the dot cap`() {
        for (current in 0 until 40) {
            assertTrue(dotWindow(count = 40, current = current).count() <= 7)
        }
    }

    @Test
    fun `the window centres on the current photo in the middle of a long run`() {
        assertEquals(7..13, dotWindow(count = 20, current = 10))
    }

    @Test
    fun `the window pins at the start rather than running off the front`() {
        assertEquals(0..6, dotWindow(count = 20, current = 0))
        assertEquals(0..6, dotWindow(count = 20, current = 2))
    }

    /** The one that catches an off-by-one: the last photo must be inside the window. */
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
                assertTrue("$current fell outside its window at count $count", current in dotWindow(count, current))
            }
        }
    }

    @Test
    fun `a nonsense count yields no dots rather than a negative range`() {
        assertTrue(dotWindow(count = 0, current = 0).isEmpty())
        assertTrue(dotWindow(count = -3, current = 2).isEmpty())
    }

    @Test
    fun `an out of range current is clamped`() {
        assertEquals(dotWindow(count = 5, current = 4), dotWindow(count = 5, current = 99))
        assertEquals(dotWindow(count = 5, current = 0), dotWindow(count = 5, current = -7))
    }
}
