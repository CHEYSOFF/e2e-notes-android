package my.cheysoff.feature_notes

import my.cheysoff.core_domain.sketch.Point
import my.cheysoff.core_domain.sketch.SketchLimits
import my.cheysoff.core_domain.sketch.Stroke
import my.cheysoff.core_domain.sketch.StrokeCodec
import my.cheysoff.feature_notes.ui.sketch.SketchCaptureState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure state machine behind the sketch canvas -- capture, undo/redo and stroke-level erase --
 * exercised without a device, exactly the way it is meant to be tested.
 *
 * Simplification happens at [SketchCaptureState.endStroke], not lazily in
 * [SketchCaptureState.toSketch]: undo must operate on the strokes that were actually stored, the
 * size guard has to see the real stored size, and a drawing must not visibly move the instant it is
 * saved. A round trip through [StrokeCodec] is the join between this state machine and the existing
 * codec, so it is asserted directly rather than assumed.
 */
class SketchCaptureStateTest {

    private fun state(width: Int = 4096, height: Int = 4096) = SketchCaptureState(width, height)

    // --- basic capture ---------------------------------------------------------------------

    @Test
    fun `a completed stroke appears in strokes`() {
        val s = state()

        s.beginStroke(0, 0)
        s.extendStroke(10, 0)
        s.extendStroke(20, 0)
        s.endStroke()

        assertEquals(1, s.strokes.size)
        assertEquals(Point(0, 0), s.strokes[0].points.first())
        assertEquals(Point(20, 0), s.strokes[0].points.last())
    }

    @Test
    fun `a stroke with a single point is still a stroke`() {
        val s = state()

        s.beginStroke(50, 60)
        s.endStroke()

        assertEquals(1, s.strokes.size)
        assertEquals(listOf(Point(50, 60)), s.strokes[0].points)
    }

    @Test
    fun `ending a stroke that was never begun does nothing`() {
        val s = state()

        s.endStroke()

        assertTrue(s.strokes.isEmpty())
    }

    @Test
    fun `a straight-line stroke is simplified down to its endpoints at endStroke`() {
        val s = state()

        s.beginStroke(0, 0)
        for (i in 1..50) s.extendStroke(i * 4, 0)
        s.endStroke()

        // Simplification happened already, not lazily in toSketch: the stored stroke itself is
        // thin, so undo and the size guard downstream see the real, small shape.
        assertEquals(2, s.strokes[0].points.size)
    }

    @Test
    fun `activeStrokePoints reflects the in-progress gesture and clears on endStroke`() {
        val s = state()

        assertTrue(s.activeStrokePoints.isEmpty())

        s.beginStroke(0, 0)
        s.extendStroke(5, 5)
        assertEquals(listOf(Point(0, 0), Point(5, 5)), s.activeStrokePoints)

        s.endStroke()
        assertTrue(s.activeStrokePoints.isEmpty())
    }

    // --- clamping ----------------------------------------------------------------------------

    @Test
    fun `coordinates outside the canvas are clamped, not dropped`() {
        val s = state(width = 100, height = 100)

        s.beginStroke(-20, -30)
        s.extendStroke(500, 40)
        s.extendStroke(40, 900)
        s.endStroke()

        val points = s.strokes[0].points
        assertEquals(Point(0, 0), points.first())
        assertTrue(points.any { it == Point(100, 40) })
        assertTrue(points.any { it == Point(40, 100) })
    }

    // --- undo / redo -------------------------------------------------------------------------

    @Test
    fun `undo removes the last completed stroke`() {
        val s = state()

        s.beginStroke(0, 0); s.endStroke()
        s.beginStroke(10, 10); s.endStroke()
        assertEquals(2, s.strokes.size)

        s.undo()

        assertEquals(1, s.strokes.size)
        assertEquals(Point(0, 0), s.strokes[0].points.first())
    }

    @Test
    fun `redo restores the stroke that undo removed`() {
        val s = state()

        s.beginStroke(0, 0); s.endStroke()
        s.beginStroke(10, 10); s.endStroke()
        s.undo()

        s.redo()

        assertEquals(2, s.strokes.size)
        assertEquals(Point(10, 10), s.strokes[1].points.first())
    }

    @Test
    fun `a new stroke after an undo clears the redo stack`() {
        val s = state()

        s.beginStroke(0, 0); s.endStroke()
        s.beginStroke(10, 10); s.endStroke()
        s.undo()
        assertTrue(s.canRedo)

        s.beginStroke(20, 20); s.endStroke()

        assertFalse(s.canRedo)
        s.redo()
        assertEquals(2, s.strokes.size)
        assertEquals(Point(20, 20), s.strokes[1].points.first())
    }

    @Test
    fun `undo on an empty history does nothing`() {
        val s = state()

        s.undo()

        assertTrue(s.strokes.isEmpty())
        assertFalse(s.canUndo)
    }

    @Test
    fun `redo with nothing undone does nothing`() {
        val s = state()
        s.beginStroke(0, 0); s.endStroke()

        s.redo()

        assertEquals(1, s.strokes.size)
        assertFalse(s.canRedo)
    }

    // --- reopening an existing drawing ----------------------------------------------------------

    @Test
    fun `initialStrokes seed strokes with nothing to undo yet`() {
        val loaded = listOf(Stroke(0xFF000000L, 8, listOf(Point(0, 0), Point(5, 5))))
        val s = SketchCaptureState(4096, 4096, initialStrokes = loaded)

        assertEquals(loaded, s.strokes)
        assertFalse("the load itself must not be an undoable step", s.canUndo)
    }

    @Test
    fun `undo stops at the as-loaded strokes instead of walking back to blank`() {
        val loaded = listOf(Stroke(0xFF000000L, 8, listOf(Point(0, 0), Point(5, 5))))
        val s = SketchCaptureState(4096, 4096, initialStrokes = loaded)

        s.beginStroke(50, 50); s.endStroke()
        assertEquals(2, s.strokes.size)

        s.undo()
        assertEquals(
            "undo must land exactly on the as-loaded drawing, not remove any of it",
            loaded,
            s.strokes,
        )
        assertFalse("nothing left this session to undo", s.canUndo)

        // A further undo -- e.g. a stray extra tap -- must be a no-op, not an out-of-bounds
        // removal of an as-loaded stroke. This is the decision this test exists to pin: without it,
        // a whole previously-saved drawing would be one tap away from silent discard.
        s.undo()
        assertEquals(loaded, s.strokes)
    }

    @Test
    fun `erasing an as-loaded stroke is itself undoable`() {
        val loaded = listOf(Stroke(0xFF000000L, 8, listOf(Point(0, 0), Point(5, 5))))
        val s = SketchCaptureState(4096, 4096, initialStrokes = loaded)

        s.eraseAt(0, 0)
        assertTrue("erasing an as-loaded stroke works like erasing any other", s.strokes.isEmpty())

        s.undo()
        assertEquals("undoing the erase restores the as-loaded stroke", loaded, s.strokes)
    }

    // --- erase ---------------------------------------------------------------------------------

    @Test
    fun `erase is stroke-level -- touching near any point of a stroke removes the whole stroke`() {
        val s = state()
        s.beginStroke(0, 0)
        s.extendStroke(100, 0)
        s.extendStroke(200, 0)
        s.endStroke()

        s.eraseAt(100, 5) // near the middle of the stroke, not exactly on a stored point

        assertTrue(s.strokes.isEmpty())
    }

    @Test
    fun `erase does nothing when nothing is within tolerance`() {
        val s = state()
        s.beginStroke(0, 0)
        s.extendStroke(200, 0)
        s.endStroke()

        s.eraseAt(0, 500)

        assertEquals(1, s.strokes.size)
    }

    @Test
    fun `erase only removes the nearest stroke, leaving others intact`() {
        val s = state()
        s.beginStroke(0, 0); s.extendStroke(100, 0); s.endStroke()
        s.beginStroke(0, 300); s.extendStroke(100, 300); s.endStroke()

        s.eraseAt(50, 2) // right on the first stroke

        assertEquals(1, s.strokes.size)
        assertEquals(Point(0, 300), s.strokes[0].points.first())
    }

    @Test
    fun `an erase is undoable`() {
        val s = state()
        s.beginStroke(0, 0); s.extendStroke(100, 0); s.endStroke()

        s.eraseAt(50, 0)
        assertTrue(s.strokes.isEmpty())

        s.undo()

        assertEquals(1, s.strokes.size)
        assertEquals(Point(0, 0), s.strokes[0].points.first())
    }

    @Test
    fun `redoing an undone erase removes the stroke again`() {
        val s = state()
        s.beginStroke(0, 0); s.extendStroke(100, 0); s.endStroke()
        s.eraseAt(50, 0)
        s.undo()

        s.redo()

        assertTrue(s.strokes.isEmpty())
    }

    @Test
    fun `an erased stroke restores at its original position among the others`() {
        val s = state()
        s.beginStroke(0, 0); s.endStroke() // index 0
        s.beginStroke(10, 10); s.endStroke() // index 1
        s.beginStroke(20, 20); s.endStroke() // index 2

        s.eraseAt(10, 10) // removes the middle stroke
        assertEquals(2, s.strokes.size)

        s.undo()

        assertEquals(3, s.strokes.size)
        assertEquals(Point(10, 10), s.strokes[1].points.first())
    }

    // --- size guard ----------------------------------------------------------------------------

    /**
     * Draws one zigzag stroke: x advances steadily while y alternates between 0 and 30 on every
     * point, so every interior point is a real corner far outside RDP's epsilon of 2 and survives
     * simplification -- unlike a merely-perturbed-but-still-near-straight line, which RDP (rightly)
     * thins back down to two points regardless of how "jittery" it looks, defeating the point of
     * this helper. Piling up many of these approaches [SketchLimits.MAX_ENCODED_BYTES] the same way
     * a real dense scribble would.
     */
    private fun drawJitteryStroke(s: SketchCaptureState, seed: Int, pointCount: Int = 40) =
        run {
            val baseX = (seed * 5) % 3000
            s.beginStroke(baseX, 0)
            for (p in 1 until pointCount) {
                val y = if (p % 2 == 0) 0 else 30
                s.extendStroke(baseX + p * 5, y)
            }
            s.endStroke()
        }

    @Test
    fun `a stroke that would breach the cap is not committed and leaves nothing to redo`() {
        val s = state()
        var result = SketchCaptureState.EndStrokeResult.ADDED
        var seed = 0
        while (result != SketchCaptureState.EndStrokeResult.REJECTED_TOO_LARGE && seed < 5000) {
            result = drawJitteryStroke(s, seed)
            seed++
        }

        assertEquals(SketchCaptureState.EndStrokeResult.REJECTED_TOO_LARGE, result)
        assertFalse("a rejected stroke must not become redoable", s.canRedo)
    }

    @Test
    fun `repeatedly drawing past the cap never produces a toSketch whose encoding exceeds the limit`() {
        val s = state()

        repeat(1000) { seed -> drawJitteryStroke(s, seed) }

        val encoded = StrokeCodec.encode(s.toSketch())
        assertTrue(
            "encoded sketch was ${encoded.encodeToByteArray().size} bytes, over the ${SketchLimits.MAX_ENCODED_BYTES}-byte cap",
            SketchLimits.withinLimit(encoded),
        )
    }

    // --- toSketch / codec round trip ------------------------------------------------------------

    @Test
    fun `toSketch carries the canvas dimensions it was constructed with`() {
        val s = state(width = 3277, height = 4096)
        s.beginStroke(1, 1); s.endStroke()

        val sketch = s.toSketch()

        assertEquals(3277, sketch.width)
        assertEquals(4096, sketch.height)
    }

    @Test
    fun `toSketch round-trips through StrokeCodec unchanged`() {
        val s = state()
        s.beginStroke(0, 0)
        s.extendStroke(50, 10)
        s.extendStroke(120, 5)
        s.endStroke()
        s.beginStroke(200, 200)
        s.endStroke() // a dot

        val sketch = s.toSketch()
        val roundTripped = StrokeCodec.decode(StrokeCodec.encode(sketch))

        assertEquals(sketch, roundTripped)
    }

    @Test
    fun `an empty capture still round-trips through StrokeCodec`() {
        val s = state()

        val sketch = s.toSketch()
        val roundTripped = StrokeCodec.decode(StrokeCodec.encode(sketch))

        assertEquals(sketch, roundTripped)
    }

    @Test
    fun `toSketch does not include an in-progress, unfinished stroke`() {
        val s = state()
        s.beginStroke(0, 0)
        s.extendStroke(10, 10)
        // no endStroke

        val sketch = s.toSketch()

        assertTrue(sketch.strokes.isEmpty())
    }

    @Test
    fun `default color and width populate a stroke drawn without any tool change`() {
        val s = state()

        s.beginStroke(0, 0)
        s.endStroke()

        assertEquals(SketchCaptureState.DEFAULT_COLOR_ARGB, s.strokes[0].colorArgb)
        assertEquals(SketchCaptureState.DEFAULT_STROKE_WIDTH, s.strokes[0].width)
    }

    @Test
    fun `changing colorArgb and strokeWidth before a stroke is reflected in that stroke`() {
        val s = state()
        s.colorArgb = 0xffff0000
        s.strokeWidth = 32

        s.beginStroke(0, 0)
        s.endStroke()

        assertEquals(0xffff0000, s.strokes[0].colorArgb)
        assertEquals(32, s.strokes[0].width)
    }
}
