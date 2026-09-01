package my.cheysoff.desktop.ui.notes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.cheysoff.desktop.ui.theme.ChecklistGreen
import my.cheysoff.desktop.ui.theme.IndigoTint
import my.cheysoff.desktop.ui.theme.MetaGrey
import my.cheysoff.desktop.ui.theme.SurfaceDark
import java.awt.Cursor

/** The hand cursor. On desktop, anything clickable that does not change the pointer reads as dead. */
val handCursor = PointerIcon(Cursor(Cursor.HAND_CURSOR))

/** `── PINNED ──` etc. Same treatment as the Android list's section label. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        color = MetaGrey,
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
        ),
        modifier = modifier.padding(start = 10.dp, top = 14.dp, bottom = 8.dp),
    )
}

/**
 * The checklist progress dots from the note card: filled green for done, dark for to-do, and a
 * `done/total` label. Longer lists fill dots proportionally rather than literally, so the row
 * never grows past its budget.
 */
@Composable
fun ChecklistProgress(done: Int, total: Int, onColor: Boolean) {
    val maxDots = 7
    val shown = total.coerceAtMost(maxDots)
    val filled = if (total <= maxDots) done else (done * maxDots) / total
    val remaining = if (onColor) Color.White.copy(alpha = 0.3f) else Color(0xFF333333)
    val labelColor = if (onColor) Color.White.copy(alpha = 0.6f) else Color(0xFF6A6A70)
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(shown) { i ->
            Box(
                modifier = Modifier
                    .padding(end = 3.dp)
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(if (i < filled) ChecklistGreen else remaining)
            )
        }
        Spacer(Modifier.width(3.dp))
        Text(
            text = "$done/$total",
            color = labelColor,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp, fontWeight = FontWeight.Medium),
        )
    }
}

/**
 * A push-pin, drawn rather than imported.
 *
 * `Icons.Default` has no pin and pulling in material-icons-extended for one glyph costs an
 * artifact an order of magnitude larger than this whole module.
 */
@Composable
fun PinGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(11.dp)) {
        val w = size.width
        val h = size.height
        // Head: a rounded cap across the top two-thirds, with the stem dropping from its centre.
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.18f, 0f),
            size = androidx.compose.ui.geometry.Size(w * 0.64f, h * 0.62f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.22f),
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.5f, h * 0.62f),
            end = Offset(w * 0.5f, h),
            strokeWidth = w * 0.16f,
        )
    }
}

/** A compact icon button in the app's pill idiom: SurfaceDark ground, hand cursor, hover tint. */
@Composable
fun PillIconButton(
    icon: ImageVector,
    description: String,
    tint: Color,
    modifier: Modifier = Modifier,
    background: Color = SurfaceDark,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(background)
            .pointerHoverIcon(handCursor)
            .clickable(onClickLabel = description, onClick = onClick)
            .padding(7.dp),
        contentAlignment = Alignment.Center,
    ) {
        // The accessible name is on the clickable Box, so the glyph itself is not announced twice.
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
    }
}

/** Renders [text] with [ranges] tinted — the search palette's match highlighting. */
fun highlighted(text: String, ranges: List<IntRange>): AnnotatedString = buildAnnotatedString {
    append(text)
    ranges.forEach { range ->
        // Defensive clamp: a range past the end would throw inside the composition rather than at
        // the call site that produced it, which is a far worse place to find out.
        val start = range.first.coerceIn(0, text.length)
        val end = (range.last + 1).coerceIn(start, text.length)
        addStyle(SpanStyle(color = IndigoTint, fontWeight = FontWeight.Bold), start, end)
    }
}

/** Keyboard-shortcut chip, e.g. `Ctrl K`, used in the title bar and the empty states. */
@Composable
fun KeyHint(keys: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        keys.split(" ").forEach { key ->
            Text(
                text = key,
                color = MetaGrey,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(0xFF1C1C22))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * "3 hours ago" / "12 Mar". Same buckets as the Android list, so a note reads the same on both.
 * [now] is a parameter so the string is a pure function of its inputs.
 */
fun relativeTime(timestamp: Long, now: Long): String {
    if (timestamp <= 0L) return ""
    val delta = now - timestamp
    return when {
        delta < 0L -> "just now" // clock skew; "in 3 hours" for a note you just saved is worse
        delta < 60_000L -> "just now"
        delta < 3_600_000L -> "${delta / 60_000L} min ago"
        delta < 86_400_000L -> "${delta / 3_600_000L} h ago"
        delta < 7 * 86_400_000L -> "${delta / 86_400_000L} d ago"
        else -> {
            val date = java.time.Instant.ofEpochMilli(timestamp)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDate()
            "${date.dayOfMonth} ${date.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)}"
        }
    }
}
