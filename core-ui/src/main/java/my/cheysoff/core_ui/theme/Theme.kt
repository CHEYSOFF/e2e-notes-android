package my.cheysoff.core_ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color


val LocalSpacing = staticCompositionLocalOf { Spacing() }
val LocalRadii = staticCompositionLocalOf { Radii() }

// Mañana dark theme — see redesign-design-system memory.
private val DarkColorScheme = darkColorScheme(
    primary = AccentIndigo,
    onPrimary = Color.White,
    primaryContainer = AccentIndigo,
    onPrimaryContainer = Color.White,

    secondary = IndigoTint,
    onSecondary = Color.White,

    background = AppBlack,
    onBackground = TitleGrey,

    surface = SurfaceDark,
    onSurface = TitleGrey,
    surfaceContainer = SurfaceDark,
    surfaceVariant = SurfaceDark,
    onSurfaceVariant = BodyGrey,

    outline = OutlineDark,
)

//

/**
 * Mañana is DARK-ONLY, deliberately — there is no light variant and no theme parameter to pass.
 *
 * The palette is a locked one (see the redesign design system): near-black surfaces with a single
 * indigo accent, and the whole UI is designed against that ground. A light scheme was never built
 * to match it, and dynamic color would replace the accent with whatever the wallpaper suggests,
 * which is the one thing this palette cannot absorb.
 *
 * The system's own light/dark setting is therefore ignored here. The only place it still shows is
 * the Recents card (android:colorBackground in themes.xml).
 */
@Composable
fun NotesTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = DarkColorScheme

    val spacing = Spacing()
    val radii = Radii()

    CompositionLocalProvider(
        LocalSpacing provides spacing,
        LocalRadii provides radii
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}