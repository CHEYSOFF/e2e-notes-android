package my.cheysoff.feature_notes.ui.sketch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.cheysoff.core_domain.sketch.SketchColors
import my.cheysoff.core_ui.theme.IndigoTint
import my.cheysoff.core_ui.theme.TitleGrey
import my.cheysoff.core_ui.theme.ToolbarDark

/**
 * The sheet behind the sketch canvas' seventh swatch: hue by saturation, a brightness slider, and
 * the colours recently mixed here.
 *
 * ## Why the arithmetic is not in this file
 *
 * All of this is gestures and gradients that no unit test in this project will touch — composable
 * behaviour is a deliberate carve-out. So the conversion both ways and the recent-list rule live in
 * `:core-domain`'s [SketchColors] where they are pure and pinned by tests, and this file is left
 * with layout and pointer handling only.
 *
 * ## Why it opens on the current colour
 *
 * [initialArgb] is decomposed back to HSV on entry, so the cursor lands on whatever the pen is
 * already set to, including one of the six fixed presets. Nudging an existing colour is the common
 * case; opening on an unrelated default would make it the awkward one.
 */
@Composable
internal fun ColorPickerSheet(
    initialArgb: Long,
    recents: List<Long>,
    onPicked: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val start = remember(initialArgb) { SketchColors.argbToHsv(initialArgb) }
    var hue by remember(initialArgb) { mutableFloatStateOf(start.first) }
    var saturation by remember(initialArgb) { mutableFloatStateOf(start.second) }
    // A colour that decomposes to zero brightness -- black, or the canvas' own background -- would
    // open the slider at the bottom and show black, which reads as the picker having lost the pen.
    var value by remember(initialArgb) { mutableFloatStateOf(if (start.third == 0f) 1f else start.third) }

    val current = SketchColors.hsvToArgb(hue, saturation, value)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ToolbarDark)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        HueSaturationField(
            hue = hue,
            saturation = saturation,
            value = value,
            onChange = { h, s -> hue = h; saturation = s },
        )

        BrightnessSlider(hue = hue, saturation = saturation, value = value, onChange = { value = it })

        if (recents.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                recents.forEach { argb ->
                    RecentSwatch(
                        color = Color(argb.toInt()),
                        selected = argb == current,
                        onClick = {
                            val hsv = SketchColors.argbToHsv(argb)
                            hue = hsv.first
                            saturation = hsv.second
                            value = if (hsv.third == 0f) 1f else hsv.third
                        },
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(current.toInt()))
            )
            Spacer(Modifier.weight(1f))
            SheetTextButton(label = "Cancel", tint = TitleGrey, onClick = onDismiss)
            SheetTextButton(label = "Use it", tint = IndigoTint, onClick = { onPicked(current) })
        }
    }
}

/**
 * Hue across, saturation up, dimmed to the current brightness so the field previews the actual pen.
 *
 * **Two gesture detectors, not one.** `detectDragGestures.onDragStart` fires only once the pointer
 * has passed touch slop, so a plain tap would place nothing — the same defect that once made every
 * swatch and nib on this screen unselectable. The tap detector answers a tap, the drag detector
 * answers a drag, and they sit in separate `pointerInput` blocks because one block hosts one
 * detector.
 */
@Composable
private fun HueSaturationField(
    hue: Float,
    saturation: Float,
    value: Float,
    onChange: (Float, Float) -> Unit,
) {
    var width by remember { mutableFloatStateOf(0f) }
    var height by remember { mutableFloatStateOf(0f) }

    fun report(offset: Offset) {
        if (width <= 0f || height <= 0f) return
        onChange(
            (offset.x / width).coerceIn(0f, 1f) * 360f,
            1f - (offset.y / height).coerceIn(0f, 1f),
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(170.dp)
            .clip(RoundedCornerShape(12.dp))
            .pointerInput(Unit) { detectTapGestures { report(it) } }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { report(it) },
                    onDrag = { change, _ -> report(change.position); change.consume() },
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            width = size.width
            height = size.height

            drawRect(Brush.horizontalGradient(HUE_STOPS))
            // White at the bottom desaturates; transparent at the top leaves the hue. Drawn after
            // the hue so it tints it rather than being tinted.
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.White)))
            // Brightness is a black veil over the whole field, so the field shows the colour the
            // pen will actually be rather than a bright version of its hue.
            if (value < 1f) drawRect(Color.Black.copy(alpha = 1f - value))

            drawCursor(
                x = (hue / 360f) * size.width,
                y = (1f - saturation) * size.height,
            )
        }
    }
}

/** Black through to the chosen hue at full brightness. */
@Composable
private fun BrightnessSlider(
    hue: Float,
    saturation: Float,
    value: Float,
    onChange: (Float) -> Unit,
) {
    var width by remember { mutableFloatStateOf(0f) }
    val full = Color(SketchColors.hsvToArgb(hue, saturation, 1f).toInt())

    fun report(offset: Offset) {
        if (width <= 0f) return
        onChange((offset.x / width).coerceIn(0f, 1f))
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .pointerInput(Unit) { detectTapGestures { report(it) } }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { report(it) },
                    onDrag = { change, _ -> report(change.position); change.consume() },
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            width = size.width
            drawRect(Brush.horizontalGradient(listOf(Color.Black, full)))
            drawCursor(x = value * size.width, y = size.height / 2f)
        }
    }
}

/**
 * A ring rather than a filled dot, so the colour underneath stays visible.
 *
 * Drawn as a dark ring inside a light one: a single-colour cursor disappears against roughly half
 * the field, whichever colour it is.
 */
private fun DrawScope.drawCursor(x: Float, y: Float) {
    val clampedX = x.coerceIn(0f, size.width)
    val clampedY = y.coerceIn(0f, size.height)
    drawCircle(Color.Black.copy(alpha = 0.6f), radius = 10f, center = Offset(clampedX, clampedY), style = CURSOR_STROKE)
    drawCircle(Color.White, radius = 8f, center = Offset(clampedX, clampedY), style = CURSOR_STROKE)
}

@Composable
private fun RecentSwatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(if (selected) IndigoTint.copy(alpha = 0.25f) else Color.Transparent)
            .padding(4.dp)
            .clip(CircleShape)
            .background(color)
            .pointerInputClick(onClick),
    )
}

@Composable
private fun SheetTextButton(label: String, tint: Color, onClick: () -> Unit) {
    Text(
        text = label,
        color = tint,
        fontSize = 15.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .pointerInputClick(onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

private val CURSOR_STROKE = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)

/**
 * The hue wheel unrolled. Red appears at both ends so the gradient is continuous — without the
 * repeat, dragging to the right edge would land on magenta and the wrap would be visible as a seam.
 */
private val HUE_STOPS = listOf(
    Color(0xFFFF0000),
    Color(0xFFFFFF00),
    Color(0xFF00FF00),
    Color(0xFF00FFFF),
    Color(0xFF0000FF),
    Color(0xFFFF00FF),
    Color(0xFFFF0000),
)
