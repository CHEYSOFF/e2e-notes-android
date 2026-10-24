package my.cheysoff.desktop.ui.notes

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStyle
import my.cheysoff.core_domain.sketch.Sketch
import my.cheysoff.core_domain.sketch.SketchGeometry
import my.cheysoff.core_domain.sketch.StrokeSmoothing

/**
 * One drawing, letterboxed into whatever space [modifier] gives it: what a drawing block of the
 * block editor shows. Render-only: drawing is phone-only (a mouse is a poor pen).
 */
@Composable
internal fun SketchCanvas(sketch: Sketch, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) { drawSketch(sketch, size) }
}

/**
 * The desktop's own thin `Path` builder over the shared `:core-domain` geometry -- the mapping
 * ([SketchGeometry]) and the smoothing ([StrokeSmoothing]) are exactly what `:core-ui`'s
 * Android-only `SketchRenderer` uses, because `:core-ui` is an `android.library` and this module
 * cannot borrow its code (see that class's own KDoc). What is here is the same handful of lines
 * that class has under a different `Path` implementation -- Compose Desktop's `Path` is backed by
 * Skia rather than `android.graphics.Path`, but the API the two expose is identical.
 */
private fun DrawScope.drawSketch(sketch: Sketch, size: Size) {
    if (sketch.strokes.isEmpty()) return
    val fit = SketchGeometry.fit(sketch.width, sketch.height, size.width.toDouble(), size.height.toDouble())
    sketch.strokes.forEach { stroke ->
        val points = stroke.points
        if (points.isEmpty()) return@forEach
        val color = Color(stroke.colorArgb.toInt())
        val widthPx = (stroke.width * fit.scale).toFloat()

        if (points.size == 1) {
            val center = fit.toTarget(points[0].x.toDouble(), points[0].y.toDouble())
            drawCircle(color = color, radius = widthPx / 2f, center = Offset(center.x.toFloat(), center.y.toFloat()))
            return@forEach
        }

        val path = Path()
        val first = fit.toTarget(points[0].x.toDouble(), points[0].y.toDouble())
        path.moveTo(first.x.toFloat(), first.y.toFloat())
        StrokeSmoothing.segments(points).forEach { segment ->
            val control = fit.toTarget(segment.controlX, segment.controlY)
            val end = fit.toTarget(segment.endX, segment.endY)
            path.quadraticTo(control.x.toFloat(), control.y.toFloat(), end.x.toFloat(), end.y.toFloat())
        }
        drawPath(
            path = path,
            color = color,
            style = DrawStyle(width = widthPx, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
