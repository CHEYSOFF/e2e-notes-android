package my.cheysoff.core_domain.sketch

import my.cheysoff.core_domain.model.SketchData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [sketchesForDisplay]'s own obligation: the decode-failure handling that has to produce a visible
 * placeholder rather than silently dropping a row, on both platforms that render one. Runs on both
 * `jvmTest` and `mingwX64Test`, like [SketchOrderingTest].
 *
 * The ordering rule itself is not re-tested here -- [SketchOrderingTest] already covers
 * [sortSketches] directly, and `sketchesForDisplay` is a thin decode step on top of it. The
 * `ordering and decode failure compose` case below stays, though: it is the one thing genuinely
 * local to this function, proving it actually calls through to the shared order rather than
 * silently reordering around a placeholder.
 */
class SketchDisplayTest {

    private fun sketch(
        id: String,
        anchor: Int = 0,
        order: Int = 0,
        strokes: String = "1|10x10|ff000000,4:0,0",
    ) = SketchData(
        id = id,
        noteId = "n1",
        anchor = anchor,
        order = order,
        strokes = strokes,
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    // ---------------------------------------------------------------- decode failure

    @Test
    fun `a sketch that decodes cleanly renders as a Drawing`() {
        val result = sketchesForDisplay(listOf(sketch("a")))

        val row = result.single()
        assertTrue(row is DisplaySketch.Drawing, "expected a Drawing, got $row")
        assertEquals("a", row.id)
    }

    @Test
    fun `garbage strokes text becomes a visible placeholder -- not a dropped row`() {
        val result = sketchesForDisplay(listOf(sketch("a", strokes = "not a sketch at all")))

        assertEquals(1, result.size)
        assertEquals(DisplaySketch.Undecodable("a"), result.single())
    }

    @Test
    fun `empty strokes text becomes a placeholder rather than crashing`() {
        val result = sketchesForDisplay(listOf(sketch("a", strokes = "")))

        assertEquals(DisplaySketch.Undecodable("a"), result.single())
    }

    @Test
    fun `a decoded sketch with a zero dimension is still a placeholder -- not an unrenderable Drawing`() {
        // `StrokeCodec.decode` accepts "0x0" -- it is a well-formed header, just not a canvas
        // `Modifier.aspectRatio` (or SketchGeometry.fit's own `require`) can do anything with.
        val result = sketchesForDisplay(listOf(sketch("a", strokes = "1|0x0")))

        assertEquals(DisplaySketch.Undecodable("a"), result.single())
    }

    @Test
    fun `ordering and decode failure compose -- a placeholder still sorts by anchor and id`() {
        val sketches = listOf(
            sketch("z", anchor = 0, strokes = "garbage"),
            sketch("a", anchor = 0),
        )

        val result = sketchesForDisplay(sketches)

        assertEquals(listOf("a", "z"), result.map { it.id })
        assertTrue(result[0] is DisplaySketch.Drawing)
        assertEquals(DisplaySketch.Undecodable("z"), result[1])
    }
}
