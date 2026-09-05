package my.cheysoff.desktop.ui.state

import my.cheysoff.core_domain.model.SketchData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [sketchesForDisplay]'s own obligation: the decode-failure handling that has to produce a visible
 * placeholder rather than silently dropping a row.
 *
 * The ordering rule itself is no longer tested here. It used to be -- this module carried its own
 * copy of `SingleNoteViewModel.sortSketches`'s rule, pinned only by a pair of tests mirroring the
 * phone's `SingleNoteMergeTest` cases -- but `sketchesForDisplay` now calls `:core-domain`'s
 * `sortSketches` directly (see [SketchOrderingTest][my.cheysoff.core_domain.sketch
 * .SketchOrderingTest], which runs on both `jvmTest` and `mingwX64Test`), so re-asserting the same
 * two cases here would only be testing that function a second time under a different name. The
 * `ordering and decode failure compose` case below stays: it is the one thing genuinely local to
 * this module, proving `sketchesForDisplay` actually calls through to the shared order rather than
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
