package my.cheysoff.core_domain.sketch

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A position in a continuous target space -- a screen at whatever density, a desktop window, a
 * note-block thumbnail -- as opposed to [Point]'s integer canvas grid. Never stored; only ever the
 * output of [CanvasFit.toTarget] on the way to a renderer's own path-building calls.
 */
data class TargetPoint(val x: Double, val y: Double)

/**
 * How a [Sketch]'s `width`x`height` canvas sits inside some other box: one uniform scale plus the
 * offset that centers the shorter axis, i.e. letterboxing rather than stretching. Every point in
 * every stroke of a render reuses the same [CanvasFit] -- it is computed once per box, not once per
 * point.
 */
data class CanvasFit(val scale: Double, val offsetX: Double, val offsetY: Double) {

    /** Canvas-space coordinates into this box's own coordinate units. Rendering's one mapping step. */
    fun toTarget(x: Double, y: Double): TargetPoint = TargetPoint(x * scale + offsetX, y * scale + offsetY)

    /**
     * The inverse of [toTarget]: a position in this box back onto the canvas's integer grid,
     * rounding once, here. Capture's one mapping step -- a screen touch becomes a canvas [Point]
     * exactly here, never again on the way to [StrokeCodec].
     */
    fun toCanvas(x: Double, y: Double): Point =
        Point(((x - offsetX) / scale).roundToInt(), ((y - offsetY) / scale).roundToInt())
}

/**
 * Computes the screen <-> canvas mapping described in [Point]'s own KDoc: the canvas's long edge is
 * always 4096, the short edge follows whatever drew it, and a box of some other size and aspect
 * ratio fits it by shrinking to the binding axis and centering on the other -- never distorting it.
 */
object SketchGeometry {

    fun fit(canvasWidth: Int, canvasHeight: Int, targetWidth: Double, targetHeight: Double): CanvasFit {
        require(canvasWidth > 0 && canvasHeight > 0) { "canvas dimensions must be positive" }
        require(targetWidth > 0 && targetHeight > 0) { "target dimensions must be positive" }

        val scale = min(targetWidth / canvasWidth, targetHeight / canvasHeight)
        val offsetX = (targetWidth - canvasWidth * scale) / 2.0
        val offsetY = (targetHeight - canvasHeight * scale) / 2.0
        return CanvasFit(scale, offsetX, offsetY)
    }
}
