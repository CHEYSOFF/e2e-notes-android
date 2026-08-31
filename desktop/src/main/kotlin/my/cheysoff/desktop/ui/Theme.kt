package my.cheysoff.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The desktop's colours.
 *
 * These values are copied from `core-ui/.../theme/Color.kt` — `AppBlack`, `SurfaceDark`,
 * `OutlineDark`, `AccentIndigo`, `IndigoTint`, `TitleGrey`, `BodyGrey`. Copied, not imported,
 * because `:core-ui` is an Android library: it depends on `androidx.compose`, not on Compose
 * Multiplatform, and cannot be put on this module's classpath.
 *
 * That duplication is a real cost and it is the UI agent's to resolve, not this module's to paper
 * over. The two candidate fixes are moving the palette into a multiplatform module both can use, or
 * accepting the copy and pinning it with a test that reads the Android file. Either is a decision
 * about the design system rather than about the vault, so this file holds the smallest set of
 * values the three foundation screens need and nothing more — there is deliberately no typography
 * scale, no shape system and no component styling here to have to un-duplicate later.
 */
object MananaColors {
    val Black = Color(0xFF000000)
    val Surface = Color(0xFF161618)
    val Outline = Color(0xFF2E2E34)
    val AccentIndigo = Color(0xFF2C1AB0)
    val IndigoTint = Color(0xFF6A5FD0)
    val TitleGrey = Color(0xFFDCDCDC)
    val BodyGrey = Color(0xFF7A7A7E)
    val Warning = Color(0xFFD08A3A)
    val Error = Color(0xFFCF6679)
}

@Composable
fun MananaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = MananaColors.IndigoTint,
            onPrimary = Color.White,
            primaryContainer = MananaColors.AccentIndigo,
            onPrimaryContainer = Color.White,
            background = MananaColors.Black,
            onBackground = MananaColors.TitleGrey,
            surface = MananaColors.Surface,
            onSurface = MananaColors.TitleGrey,
            onSurfaceVariant = MananaColors.BodyGrey,
            outline = MananaColors.Outline,
            error = MananaColors.Error,
        ),
        content = content,
    )
}
