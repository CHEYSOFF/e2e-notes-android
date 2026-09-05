package my.cheysoff.core_ui.sketch

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import my.cheysoff.core_domain.sketch.CanvasFit
import my.cheysoff.core_domain.sketch.Sketch
import my.cheysoff.core_domain.sketch.SketchGeometry
import my.cheysoff.core_domain.sketch.Stroke
import my.cheysoff.core_domain.sketch.StrokeSmoothing

/**
 * One stroke, ready for a `DrawScope` to paint: its colour, its nib width already scaled into the
 * same target units as [path], and the path itself -- already mapped through a [CanvasFit] and
 * smoothed by [StrokeSmoothing]. A single-point stroke (a tap, not a drag) has no path to speak of,
 * so it comes back as [isDot] with [dotCenter] instead; a caller draws a filled circle for that case
 * rather than an empty path.
 *
 * This is the Android-only half of the plan's "shared renderer": the maths both platforms must
 * agree on (the mapping, the smoothing) lives in `:core-domain` as plain numbers, because `:core-ui`
 * is an Android library and the desktop cannot borrow its code. What is here is a thin adapter --
 * turning those numbers into a Compose `Path` -- and it is used by the canvas and by the note's
 * sketch block; the desktop builds its own equivalent from the same `:core-domain` numbers.
 */
data class RenderedStroke(
    val path: Path,
    val color: Color,
    val strokeWidthPx: Float,
    val isDot: Boolean,
    val dotCenter: Offset,
)

object SketchRenderer {

    /** The letterboxing fit for a [sketch] rendered into a box of [size]. Exposed separately from
     * [render] so a caller that also needs to map its own coordinates -- capture mapping a touch,
     * or a live in-progress stroke via [renderStroke] -- uses the exact same mapping the finished
     * strokes are drawn with. */
    fun fit(sketch: Sketch, size: Size): CanvasFit = fit(sketch.width, sketch.height, size)

    fun fit(canvasWidth: Int, canvasHeight: Int, size: Size): CanvasFit =
        SketchGeometry.fit(canvasWidth, canvasHeight, size.width.toDouble(), size.height.toDouble())

    /** Every stroke of [sketch], mapped and smoothed for a box of [size]. */
    fun render(sketch: Sketch, size: Size): List<RenderedStroke> {
        if (sketch.strokes.isEmpty()) return emptyList()
        val canvasFit = fit(sketch, size)
        return sketch.strokes.map { strokeToRendered(it, canvasFit) }
    }

    /**
     * One stroke on its own, mapped for a [canvasWidth]x[canvasHeight] canvas rendered into a box
     * of [size]. For the in-progress gesture the canvas is still drawing -- it is not part of any
     * [Sketch] yet, so [render] cannot be asked for it.
     */
    fun renderStroke(stroke: Stroke, canvasWidth: Int, canvasHeight: Int, size: Size): RenderedStroke =
        strokeToRendered(stroke, fit(canvasWidth, canvasHeight, size))

    private fun strokeToRendered(stroke: Stroke, canvasFit: CanvasFit): RenderedStroke {
        val points = stroke.points
        // `Color(Long)` expects the low 32 bits to be the ARGB value; `stroke.colorArgb` is exactly
        // that, sign-extension and all, because `toInt()` truncates back to those same 32 bits
        // regardless of the Long's sign -- the same relationship `StrokeCodec.encode`'s own mask
        // comment documents, in reverse.
        val color = Color(stroke.colorArgb.toInt())
        val widthPx = (stroke.width * canvasFit.scale).toFloat()

        if (points.size <= 1) {
            val center = points.firstOrNull()?.let { canvasFit.toTarget(it.x.toDouble(), it.y.toDouble()) }
            val dotCenter = if (center != null) Offset(center.x.toFloat(), center.y.toFloat()) else Offset.Zero
            return RenderedStroke(Path(), color, widthPx, isDot = center != null, dotCenter = dotCenter)
        }

        val path = Path()
        val first = canvasFit.toTarget(points[0].x.toDouble(), points[0].y.toDouble())
        path.moveTo(first.x.toFloat(), first.y.toFloat())
        StrokeSmoothing.segments(points).forEach { segment ->
            val control = canvasFit.toTarget(segment.controlX, segment.controlY)
            val end = canvasFit.toTarget(segment.endX, segment.endY)
            path.quadraticTo(control.x.toFloat(), control.y.toFloat(), end.x.toFloat(), end.y.toFloat())
        }
        return RenderedStroke(path, color, widthPx, isDot = false, dotCenter = Offset.Zero)
    }
}
