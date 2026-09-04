package my.cheysoff.core_domain.sketch

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The screen <-> canvas mapping shared by every renderer and by capture.
 *
 * The canvas is an integer grid whose long edge is always 4096; a target box (a phone screen, a
 * desktop window, a note-block thumbnail) is a continuous space of some other size and aspect
 * ratio entirely. [SketchGeometry.fit] computes the one uniform scale and centering offset that
 * lets a canvas point land in that box without distorting it -- shrinking to fit the shorter axis
 * and centering on the longer one, i.e. letterboxing.
 *
 * This is pure, platform-independent arithmetic on purpose: capture uses [CanvasFit.toCanvas] once,
 * at the moment a touch is recorded, and every renderer (the live canvas, the note block, the
 * desktop) uses [CanvasFit.toTarget] on the same numbers, so a drawing looks identical everywhere
 * it appears.
 */
class SketchGeometryTest {

    @Test
    fun `a point maps proportionally into a larger target box`() {
        val fit = SketchGeometry.fit(canvasWidth = 100, canvasHeight = 200, targetWidth = 300.0, targetHeight = 600.0)

        // The canvas is scaled up 3x on both axes with no letterboxing needed (same aspect ratio),
        // so the canvas center (50, 100) must land exactly on the target center (150, 300).
        val mapped = fit.toTarget(50.0, 100.0)

        assertEquals(150.0, mapped.x, 0.0001)
        assertEquals(300.0, mapped.y, 0.0001)
    }

    @Test
    fun `mapping to the target and back to canvas recovers the original point within rounding`() {
        val fit = SketchGeometry.fit(canvasWidth = 3277, canvasHeight = 4096, targetWidth = 1080.0, targetHeight = 1349.0)
        val original = Point(123, 4001)

        val target = fit.toTarget(original.x.toDouble(), original.y.toDouble())
        val recovered = fit.toCanvas(target.x, target.y)

        assertTrue(abs(recovered.x - original.x) <= 1, "x drifted from ${original.x} to ${recovered.x}")
        assertTrue(abs(recovered.y - original.y) <= 1, "y drifted from ${original.y} to ${recovered.y}")
    }

    @Test
    fun `a wider than tall target letterboxes a square canvas instead of stretching it`() {
        val fit = SketchGeometry.fit(canvasWidth = 1000, canvasHeight = 1000, targetWidth = 2000.0, targetHeight = 1000.0)

        // The height is the binding constraint, so the scale must come from it, not the width --
        // otherwise the square canvas would stretch into a rectangle on the wide target.
        assertEquals(1.0, fit.scale, 0.0001)
        assertEquals(500.0, fit.offsetX, 0.0001)
        assertEquals(0.0, fit.offsetY, 0.0001)
    }

    @Test
    fun `a taller than wide target letterboxes a square canvas on the vertical axis`() {
        val fit = SketchGeometry.fit(canvasWidth = 1000, canvasHeight = 1000, targetWidth = 1000.0, targetHeight = 2000.0)

        assertEquals(1.0, fit.scale, 0.0001)
        assertEquals(0.0, fit.offsetX, 0.0001)
        assertEquals(500.0, fit.offsetY, 0.0001)
    }

    @Test
    fun `the canvas origin maps to the letterbox offset not the target origin`() {
        val fit = SketchGeometry.fit(canvasWidth = 1000, canvasHeight = 1000, targetWidth = 2000.0, targetHeight = 1000.0)

        val mapped = fit.toTarget(0.0, 0.0)

        assertEquals(500.0, mapped.x, 0.0001)
        assertEquals(0.0, mapped.y, 0.0001)
    }
}
