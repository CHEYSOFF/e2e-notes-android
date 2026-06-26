package my.cheysoff.feature_auth.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.cheysoff.core_ui.theme.AccentIndigo
import my.cheysoff.core_ui.theme.IndigoTint
import my.cheysoff.feature_auth.R

private val KeyDigitColor = Color(0xFFE8E6F5)
private val DotEmptyBorder = Color(0xFF4A4A55)
private val BackspaceTint = Color(0xFFCFCFE0)

/** Row of PIN dots: [filled] solid indigo, the rest hollow outlines. */
@Composable
fun PinDots(filled: Int, total: Int, modifier: Modifier = Modifier) {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val dotSize = (screenWidthDp * 0.046f).dp
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy((screenWidthDp * 0.056f).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(total) { i ->
            val isFilled = i < filled
            Box(
                modifier = Modifier
                    .then(
                        if (isFilled) Modifier.shadow(6.dp, CircleShape, spotColor = IndigoTint)
                        else Modifier
                    )
                    .size(dotSize)
                    .clip(CircleShape)
                    .then(
                        if (isFilled) Modifier.background(IndigoTint)
                        else Modifier.border(2.dp, DotEmptyBorder, CircleShape)
                    )
            )
        }
    }
}

/** One glowing indigo squircle digit key, matching the Unlock pill's gradient. */
@Composable
private fun PinKey(
    modifier: Modifier,
    height: Dp,
    radius: Dp,
    fontSize: TextUnit,
    digit: Char,
    onClick: (Char) -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Box(
        modifier = modifier
            .height(height)
            .shadow(14.dp, shape, spotColor = AccentIndigo, ambientColor = AccentIndigo)
            .clip(shape)
            .background(Brush.radialGradient(colors = listOf(IndigoTint, AccentIndigo)))
            .clickable { onClick(digit) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = digit.toString(),
            color = KeyDigitColor,
            fontSize = fontSize,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * The 0-9 keypad: 3x3 digits then [backspace, 0, empty]. No biometric key — biometric lives only on
 * the landing's Unlock button. [enabled] is false during a lockout.
 */
@Composable
fun PinPad(
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val keyHeight = (screenWidthDp * 0.22f).dp
    val keyRadius = (screenWidthDp * 0.084f).dp
    val keyFont = (screenWidthDp * 0.087f).sp
    val rowGap = (screenWidthDp * 0.033f).dp
    val colGap = (screenWidthDp * 0.041f).dp

    val digit: (Char) -> Unit = { if (enabled) onDigit(it) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(rowGap),
    ) {
        listOf("123", "456", "789").forEach { rowChars ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(colGap),
            ) {
                rowChars.forEach { c ->
                    PinKey(
                        modifier = Modifier.weight(1f),
                        height = keyHeight,
                        radius = keyRadius,
                        fontSize = keyFont,
                        digit = c,
                        onClick = digit,
                    )
                }
            }
        }
        // Bottom row: backspace · 0 · empty
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(colGap),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(keyHeight)
                    .clickable { if (enabled) onBackspace() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_backspace),
                    contentDescription = "Delete",
                    tint = BackspaceTint,
                    modifier = Modifier.size((screenWidthDp * 0.075f).dp),
                )
            }
            PinKey(
                modifier = Modifier.weight(1f),
                height = keyHeight,
                radius = keyRadius,
                fontSize = keyFont,
                digit = '0',
                onClick = digit,
            )
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}
