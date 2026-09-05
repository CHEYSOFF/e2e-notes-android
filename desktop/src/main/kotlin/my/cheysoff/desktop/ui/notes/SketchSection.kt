package my.cheysoff.desktop.ui.notes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStyle
import androidx.compose.ui.unit.dp
import my.cheysoff.core_domain.sketch.Sketch
import my.cheysoff.core_domain.sketch.SketchGeometry
import my.cheysoff.core_domain.sketch.StrokeSmoothing
import my.cheysoff.desktop.ui.state.DisplaySketch
import my.cheysoff.desktop.ui.theme.AccentIndigo
import my.cheysoff.desktop.ui.theme.BodyGrey
import my.cheysoff.desktop.ui.theme.SurfaceDark
import my.cheysoff.desktop.ui.theme.TitleGrey

/**
 * One card per sketch, below the note's text -- never interleaved with it, matching where the
 * phone's own `SketchSection` puts them (see `docs/design/sketch-blocks.md`'s 2026-09-05
 * amendment). [sketches] arrives already ordered and decode-checked -- see
 * [my.cheysoff.desktop.ui.state.sketchesForDisplay] -- so this just lays them out.
 *
 * Render-only: there is no way to draw here, on purpose (see task 6's brief -- a mouse is a poor
 * pen and the capture surface is phone-only). The only affordance is delete, and it asks first:
 * unlike a note (soft-deleted into Trash, restorable) a sketch delete goes straight through
 * [my.cheysoff.desktop.ui.state.NotesWorkspaceModel.deleteSketch] with no restore path
 * (`TrashEntryKind` is `{NOTE, FOLDER}`), and this dialog is deliberately worded and shaped the same
 * as the phone's own confirm for the same delete, so the two read as one idea.
 */
@Composable
fun SketchSection(sketches: List<DisplaySketch>, onDelete: (String) -> Unit) {
    if (sketches.isEmpty()) return

    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        sketches.forEach { row ->
            when (row) {
                is DisplaySketch.Drawing ->
                    SketchCard(sketch = row.sketch, onDeleted = { pendingDeleteId = row.id })
                is DisplaySketch.Undecodable ->
                    UndecodableSketchCard(onDeleted = { pendingDeleteId = row.id })
            }
        }
    }

    pendingDeleteId?.let { id ->
        AlertDialog(
            containerColor = SurfaceDark,
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("Delete this drawing?", color = TitleGrey) },
            text = { Text("This cannot be undone.", color = BodyGrey) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDeleteId = null
                    onDelete(id)
                }) { Text("Delete", color = AccentIndigo) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) { Text("Cancel", color = BodyGrey) }
            },
        )
    }
}

@Composable
private fun SketchCard(sketch: Sketch, onDeleted: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(sketch.width.toFloat() / sketch.height.toFloat())
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1C1C22)),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawSketch(sketch, size)
        }
        DeleteCorner(onClick = onDeleted, description = "Delete drawing")
    }
}

/**
 * The placeholder for a [DisplaySketch.Undecodable] row: fixed-shape (there is no width/height to
 * letterbox to -- decoding never got that far), visible, and still deletable. See
 * [my.cheysoff.desktop.ui.state.sketchesForDisplay]'s own KDoc for why this shows up at all rather
 * than being skipped the way the phone's `SketchSection` skips the same case.
 */
@Composable
private fun UndecodableSketchCard(onDeleted: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1C1C22)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Can't display this drawing",
            color = BodyGrey,
            style = MaterialTheme.typography.bodySmall,
        )
        DeleteCorner(onClick = onDeleted, description = "Delete drawing")
    }
}

@Composable
private fun BoxScope.DeleteCorner(onClick: () -> Unit, description: String) {
    IconButton(onClick = onClick, modifier = Modifier.align(Alignment.TopEnd)) {
        Icon(imageVector = Icons.Default.Delete, contentDescription = description, tint = BodyGrey)
    }
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
