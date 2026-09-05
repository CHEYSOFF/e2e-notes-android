package my.cheysoff.core_domain.sketch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ramer-Douglas-Peucker, done in integers.
 *
 * A finger emits a point per touch event -- hundreds per stroke -- so this thins a raw capture down
 * to the points that actually carry its shape. It has to be exact, not approximate: the perpendicular
 * distance test is done as `cross^2` vs `epsilon^2 * segLenSquared`, entirely in `Long` arithmetic, so
 * the JVM and Kotlin/Native pick exactly the same surviving points for the same input. A float
 * anywhere in this comparison would let the phone and the desktop disagree about which points a
 * stroke kept.
 */
class StrokeSimplifierTest {

    @Test
    fun `a straight line of many collinear points simplifies to its two endpoints`() {
        val points = (0..20).map { Point(it * 10, it * 10) }

        val result = StrokeSimplifier.simplify(points, epsilon = 2)

        assertEquals(listOf(Point(0, 0), Point(200, 200)), result)
    }

    @Test
    fun `a right angle keeps its corner`() {
        val points = listOf(Point(0, 0), Point(50, 0), Point(100, 0), Point(100, 50), Point(100, 100))

        val result = StrokeSimplifier.simplify(points, epsilon = 2)

        assertEquals(listOf(Point(0, 0), Point(100, 0), Point(100, 100)), result)
    }

    @Test
    fun `endpoints are never dropped for a many-point stroke`() {
        val points = (0..30).map { Point(it, it * it % 7) }

        val result = StrokeSimplifier.simplify(points, epsilon = 1)

        assertEquals(points.first(), result.first())
        assertEquals(points.last(), result.last())
    }

    @Test
    fun `a two-point input is returned unchanged`() {
        val points = listOf(Point(0, 0), Point(500, 500))

        val result = StrokeSimplifier.simplify(points, epsilon = 100)

        assertEquals(points, result)
    }

    @Test
    fun `a single-point input is returned unchanged`() {
        val points = listOf(Point(42, 7))

        val result = StrokeSimplifier.simplify(points, epsilon = 5)

        assertEquals(points, result)
    }

    @Test
    fun `an empty input is returned unchanged`() {
        assertEquals(emptyList(), StrokeSimplifier.simplify(emptyList(), epsilon = 5))
    }

    @Test
    fun `every output point is one of the input points`() {
        val points = listOf(
            Point(0, 0), Point(12, 3), Point(25, 1), Point(40, 40), Point(55, 38),
            Point(70, 2), Point(90, 90), Point(120, 91), Point(150, 5),
        )

        val result = StrokeSimplifier.simplify(points, epsilon = 3)

        result.forEach { assertTrue(it in points, "$it was not one of the input points") }
    }

    @Test
    fun `simplification is deterministic for a given input and epsilon`() {
        val points = listOf(
            Point(0, 0), Point(10, 1), Point(20, -1), Point(30, 2), Point(40, 0),
            Point(50, 30), Point(60, 31), Point(70, 29), Point(80, 60),
        )

        val first = StrokeSimplifier.simplify(points, epsilon = 4)
        val second = StrokeSimplifier.simplify(points, epsilon = 4)

        assertEquals(first, second)
    }

    @Test
    fun `a larger epsilon never yields more points than a smaller one`() {
        val points = listOf(
            Point(0, 0), Point(10, 4), Point(20, -3), Point(30, 5), Point(40, 0),
            Point(50, 40), Point(60, 42), Point(70, 38), Point(80, 80), Point(100, 79),
        )

        val loose = StrokeSimplifier.simplify(points, epsilon = 1)
        val tight = StrokeSimplifier.simplify(points, epsilon = 20)

        assertTrue(tight.size <= loose.size, "epsilon=20 kept ${tight.size} points but epsilon=1 kept ${loose.size}")
    }

    @Test
    fun `collinear points where the anchors coincide still resolve by point distance`() {
        // start == end (a stroke that returns to where it began); the segment has zero length, so
        // the distance test must fall back to plain point-to-point distance from that anchor
        // rather than dividing by a zero segment length.
        val points = listOf(Point(10, 10), Point(10, 10), Point(15, 15), Point(10, 10))

        val result = StrokeSimplifier.simplify(points, epsilon = 2)

        assertEquals(Point(10, 10), result.first())
        assertEquals(Point(10, 10), result.last())
        assertTrue(Point(15, 15) in result, "the point 5 units off the coincident anchor should survive epsilon=2")
    }
}
