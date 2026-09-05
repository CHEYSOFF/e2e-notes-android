package my.cheysoff.desktop.ui.state

import my.cheysoff.core_domain.model.SketchData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [sketchesForDisplay]'s two obligations: the ordering rule that has to match
 * `SingleNoteViewModel.sortSketches` on the phone exactly (see that function's own test cases,
 * `sortSketches orders by anchor first` and `sortSketches ties break by id, not by order or
 * insertion position`, in `feature-notes`' `SingleNoteMergeTest` -- these are the same two cases,
 * transcribed for this module's copy of the rule), and the decode-failure handling that has to
 * produce a visible placeholder rather than silently dropping a row.
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

    // ---------------------------------------------------------------- ordering

    @Test
    fun `orders by anchor first`() {
        val sketches = listOf(sketch("a", anchor = 2), sketch("b", anchor = 0), sketch("c", anchor = 1))

        assertEquals(listOf("b", "c", "a"), sketchesForDisplay(sketches).map { it.id })
    }

    @Test
    fun `ties break by id, not by order or insertion position`() {
        // Same anchor, `order` deliberately disagreeing with the desired id order, insertion order
        // deliberately reversed too -- only an explicit id tie-break can produce "a, b, c" here.
        val sketches = listOf(
            sketch("c", anchor = 0, order = 0),
            sketch("b", anchor = 0, order = 5),
            sketch("a", anchor = 0, order = 9),
        )

        assertEquals(listOf("a", "b", "c"), sketchesForDisplay(sketches).map { it.id })
    }

    // ---------------------------------------------------------------- decode failure

    @Test
    fun `a sketch that decodes cleanly renders as a Drawing`() {
        val result = sketchesForDisplay(listOf(sketch("a")))

        val row = result.single()
        assertTrue("expected a Drawing, got $row", row is DisplaySketch.Drawing)
        assertEquals("a", row.id)
    }

    @Test
    fun `garbage strokes text becomes a visible placeholder, not a dropped row`() {
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
    fun `a decoded sketch with a zero dimension is still a placeholder, not an unrenderable Drawing`() {
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
