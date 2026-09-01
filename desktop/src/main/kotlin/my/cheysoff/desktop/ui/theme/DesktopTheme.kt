package my.cheysoff.desktop.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Spacing, restated from `:core-ui/theme/Spacing.kt`.
 *
 * Only the primitive scale and the roles a desktop window actually has are carried over; the
 * phone-only roles (FAB size, bottom-bar height, nav-bar clearance) are deliberately absent
 * rather than copied and left unused — there is no bottom bar and no FAB in a two-pane window.
 */
@Immutable
data class DesktopSpacing(
    val none: Dp = 0.dp,
    val xs: Dp = 4.dp,
    val s: Dp = 8.dp,
    val m: Dp = 16.dp,
    val l: Dp = 24.dp,
    val xl: Dp = 32.dp,
    val xxl: Dp = 48.dp,

    /** Padding from a pane's edge to its content. */
    val paneHorizontal: Dp = m,
    val listItemSpacing: Dp = s,
    val sectionSpacing: Dp = l,
)

@Immutable
data class DesktopRadii(
    val none: Dp = 0.dp,
    val small: Dp = 4.dp,
    val medium: Dp = 12.dp,
    val large: Dp = 18.dp,
    val max: Dp = 999.dp,
)

val LocalDesktopSpacing = staticCompositionLocalOf { DesktopSpacing() }
val LocalDesktopRadii = staticCompositionLocalOf { DesktopRadii() }

/**
 * Urbanist, loaded from the same four .ttf files the Android app ships (this module's
 * build script adds :core-ui's font directory as a resource root — see there for why).
 *
 * Light (300) and Medium (500) are driven off the variable face's `wght` axis exactly as
 * `:core-ui/theme/Type.kt` does; Regular/Bold/ExtraBold use the bundled static instances.
 *
 * The Cyrillic gap noted in Type.kt applies here too: Urbanist has no Cyrillic glyphs, so Russian
 * note text falls back to a system face. The fix is one shared family swap, which is why this
 * module reads the Android font files instead of owning copies.
 */
private val urbanist: FontFamily = FontFamily(
    Font(
        identity = "Urbanist-Light",
        data = readFontBytes("urbanist_variable.ttf"),
        weight = FontWeight.Light,
        variationSettings = FontVariation.Settings(FontVariation.weight(300)),
    ),
    Font(identity = "Urbanist-Regular", data = readFontBytes("urbanist_regular.ttf"), weight = FontWeight.Normal),
    Font(
        identity = "Urbanist-Medium",
        data = readFontBytes("urbanist_variable.ttf"),
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    ),
    Font(identity = "Urbanist-Bold", data = readFontBytes("urbanist_bold.ttf"), weight = FontWeight.Bold),
    Font(identity = "Urbanist-ExtraBold", data = readFontBytes("urbanist_extra_bold.ttf"), weight = FontWeight.ExtraBold),
)

private fun readFontBytes(name: String): ByteArray =
    checkNotNull(object {}.javaClass.classLoader.getResourceAsStream(name)) {
        "Font resource '$name' is missing from the desktop classpath. It is contributed by " +
            "core-ui/src/main/res/font via this module's build script."
    }.use { it.readBytes() }

/**
 * The type scale, rescaled for a desktop window.
 *
 * The Android scale is not copied verbatim, and that is the one deliberate departure from
 * :core-ui. Its sizes are phone sizes (titleLarge 37sp, bodyMedium 20sp) and most of the screen
 * multiplies them again by a fraction of `screenWidthDp`, i.e. they are a function of a ~400dp
 * viewport read at arm's length. A 1400dp window read at a desk is a different reading distance
 * and a different density expectation; carrying 20sp body text into it produces a phone blown up
 * on a monitor, which is exactly the "phone app in a window" the brief rules out.
 *
 * What IS carried over unchanged is everything that makes the identity: the family, the weight
 * per role (Light hero, Medium titles, Normal body), the negative tracking on the hero line, and
 * the colour roles below.
 */
private val DesktopTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = urbanist,
        fontWeight = FontWeight.Light,
        fontSize = 30.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.5).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = urbanist,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        letterSpacing = 0.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = urbanist,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = urbanist,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = urbanist,
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = urbanist,
        fontWeight = FontWeight.Normal,
        fontSize = 10.5.sp,
        letterSpacing = 0.sp,
    ),
)

// Same scheme as :core-ui/theme/Theme.kt. Mañana is dark-only by decision, so there is no light
// variant here either and no parameter to pass one in.
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

@Composable
fun MananaDesktopTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalDesktopSpacing provides DesktopSpacing(),
        LocalDesktopRadii provides DesktopRadii(),
    ) {
        MaterialTheme(
            colorScheme = DarkColorScheme,
            typography = DesktopTypography,
            content = content,
        )
    }
}
