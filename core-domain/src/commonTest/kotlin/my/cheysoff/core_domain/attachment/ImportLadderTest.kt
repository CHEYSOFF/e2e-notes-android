package my.cheysoff.core_domain.attachment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImportLadderTest {

    @Test
    fun `the ladder starts at the full size and the best quality`() {
        assertEquals(EncodeStep(longEdge = 1600, quality = 85), ImportLadder.STEPS.first())
    }

    @Test
    fun `quality drops before dimensions do`() {
        val first = ImportLadder.STEPS.first()
        val second = ImportLadder.next(first)
        assertEquals(EncodeStep(longEdge = 1600, quality = 75), second)
    }

    @Test
    fun `dimensions drop only after the quality ladder is spent`() {
        val lastAtFullSize = EncodeStep(longEdge = 1600, quality = 55)
        assertEquals(EncodeStep(longEdge = 1120, quality = 85), ImportLadder.next(lastAtFullSize))
    }

    @Test
    fun `the ladder ends rather than shrinking forever`() {
        assertNull(ImportLadder.next(ImportLadder.STEPS.last()))
    }

    @Test
    fun `every step is reachable from the first by walking next`() {
        var step: EncodeStep? = ImportLadder.STEPS.first()
        val walked = buildList { while (step != null) { add(step!!); step = ImportLadder.next(step!!) } }
        assertEquals(ImportLadder.STEPS, walked)
    }

    @Test
    fun `fit preserves the aspect ratio on a landscape source`() {
        assertEquals(PixelSize(1600, 900), ImportLadder.fit(4000, 2250, 1600))
    }

    @Test
    fun `fit uses the long edge whichever edge that is`() {
        assertEquals(PixelSize(900, 1600), ImportLadder.fit(2250, 4000, 1600))
    }

    @Test
    fun `fit never upscales a source smaller than the target`() {
        assertEquals(PixelSize(800, 600), ImportLadder.fit(800, 600, 1600))
    }

    @Test
    fun `fit never rounds an edge down to zero`() {
        assertEquals(PixelSize(1600, 1), ImportLadder.fit(40000, 3, 1600))
    }

    @Test
    fun `the ladder starts at MAX_LONG_EDGE rather than at a literal that could drift from it`() {
        assertEquals(ImportLadder.MAX_LONG_EDGE, ImportLadder.STEPS.first().longEdge)
    }

    @Test
    fun `each dimension rung is seventy percent of the one above it`() {
        val edges = ImportLadder.STEPS.map { it.longEdge }.distinct()

        assertEquals(listOf(1600, 1120, 784), edges)
    }
}
