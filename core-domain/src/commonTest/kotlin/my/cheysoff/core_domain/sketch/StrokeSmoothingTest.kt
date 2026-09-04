package my.cheysoff.core_domain.sketch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Quadratic-Bezier smoothing control points for a stored polyline.
 *
 * `StrokeCodec` stores a stroke as the RDP-thinned points a finger actually visited -- a chain of
 * straight segments if drawn as-is. [StrokeSmoothing.segments] turns that chain into the control
 * points a `quadraticBezierTo` sequence needs so it reads as a drawn line instead. It works entirely
 * in the space its input points are already in (canvas units, or a renderer's target units after
 * [SketchGeometry] has mapped them) -- it does not know or care which.
 */
class StrokeSmoothingTest {

    @Test
    fun `an empty stroke produces no control points`() {
        assertEquals(emptyList(), StrokeSmoothing.segments(emptyList()))
    }

    @Test
    fun `a single point stroke produces no control points`() {
        assertEquals(emptyList(), StrokeSmoothing.segments(listOf(Point(5, 5))))
    }

    @Test
    fun `a two point stroke produces one segment ending exactly at the second point`() {
        val a = Point(0, 0)
        val b = Point(100, 40)

        val segments = StrokeSmoothing.segments(listOf(a, b))

        assertEquals(1, segments.size)
        assertEquals(100.0, segments[0].endX, 0.0001)
        assertEquals(40.0, segments[0].endY, 0.0001)
    }

    @Test
    fun `a two point stroke is smoothed as an exact straight line`() {
        // A quadratic Bezier whose control point is the midpoint of its two endpoints degenerates
        // to the straight line between them -- so a plain two-point stroke must render straight,
        // not bulge to one side.
        val a = Point(0, 0)
        val b = Point(200, 100)

        val segment = StrokeSmoothing.segments(listOf(a, b)).single()

        assertEquals(100.0, segment.controlX, 0.0001)
        assertEquals(50.0, segment.controlY, 0.0001)
    }

    @Test
    fun `a multi point stroke produces one fewer segment than points`() {
        val points = listOf(Point(0, 0), Point(10, 10), Point(20, 0), Point(30, 10))

        val segments = StrokeSmoothing.segments(points)

        assertEquals(points.size - 1, segments.size)
    }

    @Test
    fun `the last segment of a multi point stroke ends exactly at the final point`() {
        val points = listOf(Point(0, 0), Point(10, 10), Point(20, 0), Point(30, 10))

        val last = StrokeSmoothing.segments(points).last()

        assertEquals(30.0, last.endX, 0.0001)
        assertEquals(10.0, last.endY, 0.0001)
    }

    @Test
    fun `every interior segment ends at the midpoint of its two source points`() {
        val points = listOf(Point(0, 0), Point(10, 10), Point(20, 0), Point(30, 10))

        val segments = StrokeSmoothing.segments(points)

        // segments[0] spans points[0]..points[1], ending at the midpoint of points[1] and points[2]
        assertEquals(15.0, segments[0].endX, 0.0001, "segments[0] should end at the midpoint of points[1] and points[2]")
        assertEquals(5.0, segments[0].endY, 0.0001, "segments[0] should end at the midpoint of points[1] and points[2]")
    }

    @Test
    fun `every control point is one of the input points`() {
        val points = listOf(Point(0, 0), Point(10, 10), Point(20, 0), Point(30, 10), Point(15, 25))
        val asDoubles = points.map { it.x.toDouble() to it.y.toDouble() }

        val segments = StrokeSmoothing.segments(points)

        segments.forEach { segment ->
            assertTrue(
                (segment.controlX to segment.controlY) in asDoubles,
                "control point (${segment.controlX}, ${segment.controlY}) was not one of the input points",
            )
        }
    }
}
