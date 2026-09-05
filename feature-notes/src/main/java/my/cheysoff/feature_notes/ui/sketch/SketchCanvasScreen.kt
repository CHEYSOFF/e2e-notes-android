package my.cheysoff.feature_notes.ui.sketch

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStyle
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import my.cheysoff.core_domain.sketch.Sketch
import my.cheysoff.core_domain.sketch.Stroke
import my.cheysoff.core_ui.sketch.SketchRenderer
import my.cheysoff.core_ui.theme.AccentIndigo
import my.cheysoff.core_ui.theme.AppBlack
import my.cheysoff.core_ui.theme.BodyGrey
import my.cheysoff.core_ui.theme.CatGreen
import my.cheysoff.core_ui.theme.CatOchre
import my.cheysoff.core_ui.theme.CatRust
import my.cheysoff.core_ui.theme.CatTeal
import my.cheysoff.core_ui.theme.IndigoTint
import my.cheysoff.core_ui.theme.SurfaceDark
import my.cheysoff.core_ui.theme.TitleGrey
import my.cheysoff.core_ui.theme.ToolbarDark

/**
 * The full-screen drawing surface: a black canvas, one tool bar, and Done/Cancel.
 *
 * Everything a finger can do here is already implemented and tested elsewhere -- capture, undo,
 * redo, and stroke-level erase all live in [SketchCaptureState], and the mapping between a touch
 * and a stored canvas point, plus the quadratic smoothing a stroke is drawn with, live in
 * `:core-domain`'s `SketchGeometry`/`StrokeSmoothing` (by way of [SketchRenderer]). This file is
 * wiring: it turns finger gestures into calls on that state machine, and turns that state machine's
 * numbers into pixels on screen. There is deliberately no unit test for it -- the logic worth
 * pinning down already has one, and a test that renders a canvas and asserts pixels is not worth
 * its maintenance.
 *
 * **Coordinate mapping.** The canvas's long edge is fixed at [CANVAS_LONG_EDGE]; the short edge is
 * derived from this box's own aspect ratio the first time it is measured, so the canvas exactly
 * matches the screen it is drawn on. [SketchCaptureState] is built only once that size is known --
 * see [measuredSize] below -- and every touch is mapped screen -> canvas exactly once, in
 * [DrawingSurface]'s gesture handling, via the very same [my.cheysoff.core_domain.sketch.CanvasFit]
 * the renderer uses to map canvas -> screen.
 *
 * **The size guard.** The cap itself is enforced inside [SketchCaptureState.endStroke] -- an
 * oversized stroke is never committed in the first place, so there is nothing here to roll back.
 * This screen only reads that outcome ([SketchCaptureState.EndStrokeResult.REJECTED_TOO_LARGE]) and
 * reports it with a plain, transient message, rather than the person discovering it as an
 * unactionable `413` from the server sometime later.
 *
 * @param initialSketch when reopening an existing drawing for further editing, the [Sketch] it was
 *   last saved as. Null starts from a blank canvas. Its `width`/`height` are used AS-IS for the
 *   session -- not rederived from this box's current measurement -- because every one of its
 *   points is expressed in that canvas's units; [SketchRenderer.fit] already letterboxes whatever
 *   size it is given into whatever box it is handed, so reusing the original size costs nothing and
 *   never distorts, while remeasuring could silently disagree with the points being loaded.
 * @param onDone called with the finished [Sketch] when the person taps Done. If nothing was drawn,
 *   this behaves like [onCancel] instead -- there is nothing to save.
 * @param onCancel called when the person backs out -- Cancel tapped, or confirmed through the
 *   dialog below. A confirmation is asked first only if at least one stroke exists; confirming an
 *   empty canvas would be noise. Hardware back runs the exact same [requestCancel] the Cancel
 *   button does -- one source of truth for the rule, so a future change to it cannot fix one exit
 *   and miss the other.
 */
@Composable
fun SketchCanvasScreen(initialSketch: Sketch? = null, onDone: (Sketch) -> Unit, onCancel: () -> Unit) {
    var selectedColorArgb by remember { mutableStateOf(TitleGrey.toArgb().toLong()) }
    var selectedNib by remember { mutableIntStateOf(NIB_SIZES[1]) }
    var eraseMode by remember { mutableStateOf(false) }
    var limitMessage by remember { mutableStateOf<String?>(null) }
    var showCancelConfirm by remember { mutableStateOf(false) }

    // The canvas cannot be sized -- and so `SketchCaptureState` cannot be built -- until the
    // drawing box has been laid out once. That happens on the very first frame, so nothing is
    // ever visibly blank on it; the null case only guards against acting before then.
    var measuredSize by remember { mutableStateOf<IntSize?>(null) }

    // Derived from `measuredSize` alone -- computed separately from `capture` below so
    // `SketchCaptureState`'s own private `width`/`height` never need a public getter added just
    // for this screen to remember the numbers it already chose. A reopened drawing skips this
    // entirely and keeps its own stored size -- see [initialSketch]'s KDoc.
    val canvasSize = remember(measuredSize, initialSketch) {
        initialSketch?.let { it.width to it.height }
            ?: measuredSize?.let { canvasDimensionsFor(it.width, it.height) }
    }
    val capture = remember(canvasSize) {
        canvasSize?.let { (width, height) ->
            SketchCaptureState(width, height, initialSketch?.strokes ?: emptyList()).apply {
                colorArgb = selectedColorArgb
                strokeWidth = selectedNib
            }
        }
    }

    // Bumped after every call that mutates `capture`'s private lists, so the drawing surface -- a
    // plain `drawBehind` block reading none of Compose's own state types -- knows to redraw.
    var revision by remember { mutableIntStateOf(0) }

    fun hasStrokes() = (capture?.strokes?.isNotEmpty()) == true

    // The one rule for leaving this screen without saving: ask first if there is anything to lose.
    // Both exits -- the Cancel button and hardware back -- call this SAME function rather than each
    // re-deciding the condition, so the two can never drift out of sync with each other.
    fun requestCancel() {
        if (hasStrokes()) showCancelConfirm = true else onCancel()
    }

    // System back must not silently discard a drawing -- this screen has no other BackHandler
    // above it while it is showing (SingleNoteScreen disables its own), so without this, hardware
    // back would fall through to whatever the caller does, bypassing the confirmation entirely.
    BackHandler { requestCancel() }

    fun finishStroke() {
        val state = capture ?: return
        // The size guard lives in SketchCaptureState.endStroke itself -- this just reads its
        // answer and tells the person plainly when a stroke was refused. It never needs to undo
        // anything after the fact: a refused stroke was never committed in the first place.
        val result = state.endStroke()
        revision++
        if (result == SketchCaptureState.EndStrokeResult.REJECTED_TOO_LARGE) {
            limitMessage = "This drawing is full -- erase a stroke to make room for more."
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(AppBlack)) {
        TopBar(
            canUndo = capture?.canUndo == true,
            canRedo = capture?.canRedo == true,
            onUndo = { capture?.undo(); revision++ },
            onRedo = { capture?.redo(); revision++ },
            onCancel = { requestCancel() },
            onDone = {
                val state = capture
                if (state != null && state.strokes.isNotEmpty()) onDone(state.toSketch()) else onCancel()
            },
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(AppBlack),
        ) {
            DrawingSurface(
                capture = capture,
                canvasSize = canvasSize,
                eraseMode = eraseMode,
                revision = revision,
                onMeasured = { if (measuredSize == null) measuredSize = it },
                onExtend = { revision++ },
                onStrokeEnd = { finishStroke() },
                onErase = { revision++ },
            )

            limitMessage?.let { message ->
                LimitBanner(message = message, onDismissed = { limitMessage = null })
            }
        }

        Toolbar(
            selectedColorArgb = selectedColorArgb,
            selectedNib = selectedNib,
            eraseMode = eraseMode,
            onColorSelected = { argb ->
                selectedColorArgb = argb
                capture?.colorArgb = argb
            },
            onNibSelected = { width ->
                selectedNib = width
                capture?.strokeWidth = width
            },
            onEraseToggled = { eraseMode = !eraseMode },
        )
    }

    if (showCancelConfirm) {
        AlertDialog(
            containerColor = SurfaceDark,
            onDismissRequest = { showCancelConfirm = false },
            title = { Text("Discard this drawing?", color = TitleGrey) },
            text = { Text("What you've drawn will not be saved.", color = BodyGrey) },
            confirmButton = {
                TextButton(onClick = { showCancelConfirm = false; onCancel() }) {
                    Text("Discard", color = AccentIndigo)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelConfirm = false }) { Text("Keep drawing", color = BodyGrey) }
            },
        )
    }
}

@Composable
private fun TopBar(
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TextButton(onClick = onCancel) { Text("Cancel", color = BodyGrey) }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = onUndo, enabled = canUndo) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Undo,
                    contentDescription = "Undo",
                    tint = if (canUndo) TitleGrey else BodyGrey,
                )
            }
            IconButton(onClick = onRedo, enabled = canRedo) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Redo,
                    contentDescription = "Redo",
                    tint = if (canRedo) TitleGrey else BodyGrey,
                )
            }
        }

        TextButton(onClick = onDone) { Text("Done", color = IndigoTint) }
    }
}

/**
 * The drawing box itself: a plain `Canvas` for painting and a `pointerInput` gesture for capture.
 * [revision] exists purely to be *read* here -- a state read inside `drawBehind` invalidates just
 * the draw phase, not a full recomposition, which is what lets dragging a finger repaint smoothly.
 */
@Composable
private fun DrawingSurface(
    capture: SketchCaptureState?,
    canvasSize: Pair<Int, Int>?,
    eraseMode: Boolean,
    revision: Int,
    onMeasured: (IntSize) -> Unit,
    onExtend: () -> Unit,
    onStrokeEnd: () -> Unit,
    onErase: () -> Unit,
) {
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged(onMeasured)
            .pointerInput(capture, canvasSize, eraseMode) {
                if (capture == null || canvasSize == null) return@pointerInput
                val (canvasWidth, canvasHeight) = canvasSize
                val fit = SketchRenderer.fit(canvasWidth, canvasHeight, Size(size.width.toFloat(), size.height.toFloat()))
                detectDragGestures(
                    onDragStart = { offset ->
                        val point = fit.toCanvas(offset.x.toDouble(), offset.y.toDouble())
                        if (eraseMode) capture.eraseAt(point.x, point.y).also { onErase() }
                        else capture.beginStroke(point.x, point.y).also { onExtend() }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val point = fit.toCanvas(change.position.x.toDouble(), change.position.y.toDouble())
                        if (eraseMode) capture.eraseAt(point.x, point.y).also { onErase() }
                        else capture.extendStroke(point.x, point.y).also { onExtend() }
                    },
                    onDragEnd = { if (!eraseMode) onStrokeEnd() },
                    onDragCancel = { if (!eraseMode) onStrokeEnd() },
                )
            },
    ) {
        // A state read purely to make this draw phase depend on `revision`.
        @Suppress("UNUSED_EXPRESSION")
        revision

        if (capture == null || canvasSize == null) return@Canvas
        val (canvasWidth, canvasHeight) = canvasSize

        SketchRenderer.render(capture.toSketch(), size).forEach { rendered -> drawRendered(rendered) }

        val activePoints = capture.activeStrokePoints
        if (activePoints.isNotEmpty()) {
            val liveStroke = Stroke(capture.colorArgb, capture.strokeWidth, activePoints)
            drawRendered(SketchRenderer.renderStroke(liveStroke, canvasWidth, canvasHeight, size))
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRendered(
    rendered: my.cheysoff.core_ui.sketch.RenderedStroke,
) {
    if (rendered.isDot) {
        drawCircle(color = rendered.color, radius = rendered.strokeWidthPx / 2f, center = rendered.dotCenter)
    } else {
        drawPath(
            path = rendered.path,
            color = rendered.color,
            style = DrawStyle(width = rendered.strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

@Composable
private fun Toolbar(
    selectedColorArgb: Long,
    selectedNib: Int,
    eraseMode: Boolean,
    onColorSelected: (Long) -> Unit,
    onNibSelected: (Int) -> Unit,
    onEraseToggled: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ToolbarDark)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SWATCH_COLORS.forEach { color ->
                ColorSwatch(
                    color = color,
                    selected = color.toArgb().toLong() == selectedColorArgb,
                    onClick = { onColorSelected(color.toArgb().toLong()) },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                NIB_SIZES.forEach { nib ->
                    NibDot(nibSize = nib, selected = !eraseMode && nib == selectedNib, onClick = { onNibSelected(nib) })
                }
            }

            IconButton(onClick = onEraseToggled) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Backspace,
                    contentDescription = "Eraser",
                    tint = if (eraseMode) IndigoTint else TitleGrey,
                )
            }
        }
    }
}

@Composable
private fun ColorSwatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(if (selected) IndigoTint.copy(alpha = 0.25f) else Color.Transparent)
            .padding(4.dp)
            .clip(CircleShape)
            .background(color)
            .then(Modifier.size(22.dp))
            .pointerInputClick(onClick),
    )
}

/** The three nib sizes as dots of increasing diameter, matching how they will actually draw. */
@Composable
private fun NibDot(nibSize: Int, selected: Boolean, onClick: () -> Unit) {
    val diameter = 6.dp + (nibSize.toFloat() / NIB_SIZES.max() * 10).dp
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .pointerInputClick(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(diameter)
                .clip(CircleShape)
                .background(if (selected) IndigoTint else TitleGrey),
        )
    }
}

@Composable
private fun LimitBanner(message: String, onDismissed: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(message) {
        delay(2500)
        onDismissed()
    }
    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.TopCenter) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(SurfaceDark)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(text = message, color = TitleGrey, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** A `clickable`-equivalent that does not pull in a ripple dependency for a small round swatch. */
private fun Modifier.pointerInputClick(onClick: () -> Unit): Modifier =
    this.pointerInput(onClick) {
        detectDragGestures(onDragStart = { onClick() }, onDrag = { _, _ -> }, onDragEnd = {}, onDragCancel = {})
    }

/**
 * The canvas's long edge is fixed at 4096 (see [my.cheysoff.core_domain.sketch.Point]'s own KDoc);
 * the short edge follows this box's aspect ratio, so the canvas exactly matches the screen it was
 * drawn on and never distorts a stroke to fit some other shape.
 */
private fun canvasDimensionsFor(boxWidthPx: Int, boxHeightPx: Int): Pair<Int, Int> {
    if (boxWidthPx <= 0 || boxHeightPx <= 0) return CANVAS_LONG_EDGE to CANVAS_LONG_EDGE
    return if (boxWidthPx >= boxHeightPx) {
        val height = (CANVAS_LONG_EDGE.toLong() * boxHeightPx / boxWidthPx).toInt().coerceAtLeast(1)
        CANVAS_LONG_EDGE to height
    } else {
        val width = (CANVAS_LONG_EDGE.toLong() * boxWidthPx / boxHeightPx).toInt().coerceAtLeast(1)
        width to CANVAS_LONG_EDGE
    }
}

private const val CANVAS_LONG_EDGE = 4096

/** Three nib widths, in canvas units -- not screen px -- so a stroke reads the same width on every
 * device regardless of its screen's density or size. */
private val NIB_SIZES = listOf(12, 24, 40)

/**
 * Six swatches, every one legible on the app's black. [TitleGrey] is first and the default: a pen
 * on a dark surface is what people expect, and the note's own body text is already light-on-black.
 * The rest are [IndigoTint] plus the brightest entries in the existing category palette -- no new
 * brand colours, per the spec.
 */
private val SWATCH_COLORS = listOf(TitleGrey, IndigoTint, CatOchre, CatRust, CatTeal, CatGreen)
