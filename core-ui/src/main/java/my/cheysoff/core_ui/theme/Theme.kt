package my.cheysoff.core_ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext


val LocalSpacing = staticCompositionLocalOf { Spacing() }

private val DarkColorScheme = darkColorScheme( // todo: change colors
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

//private val LightColorScheme = lightColorScheme(
//    primary = Purple40,
//    secondary = PurpleGrey40,
//    tertiary = Pink40
//
//    /* Other default colors to override
//    background = Color(0xFFFFFBFE),
//    surface = Color(0xFFFFFBFE),
//    onPrimary = Color.White,
//    onSecondary = Color.White,
//    onTertiary = Color.White,
//    onBackground = Color(0xFF1C1B1F),
//    onSurface = Color(0xFF1C1B1F),
//    */
//)

private val LightColorScheme = lightColorScheme(
    // PRIMARY (brand / main actions)
    primary = FrozenLake,
    onPrimary = BrightSnow,
    primaryContainer = AzureMist,
    onPrimaryContainer = Graphite,

    // SECONDARY (supporting actions)
    secondary = Emerald,
    onSecondary = Graphite,
    secondaryContainer = Honeydew,
    onSecondaryContainer = Graphite,

    // BACKGROUND & SURFACE
    background = BrightSnow,
    onBackground = Graphite,

    surface = White,
    onSurface = Graphite,
    surfaceVariant = DarkPlatinum,
    onSurfaceVariant = Graphite,

    // OUTLINE
    outline = Silver,
)


@Composable
fun NotesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val spacing = Spacing()

    CompositionLocalProvider(
        LocalSpacing provides spacing
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}