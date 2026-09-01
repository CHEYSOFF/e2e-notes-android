package my.cheysoff.ios.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The "Mañana" palette, as `:core-ui`'s `Color.kt` defines it.
 *
 * The values are copied rather than shared, and that is a compromise worth naming: `:core-ui` is an
 * Android library module built on `androidx.compose`, so an iOS module cannot depend on it, and
 * making it multiplatform is a real piece of work that would have to move every screen in the app
 * with it. Copying eleven colour constants was the smaller change; it is also a place the two
 * platforms can silently drift, and the fix when that matters is to make `:core-ui`'s theme package
 * a multiplatform module rather than to copy them again.
 *
 * The design is a **locked dark palette** — there is no light scheme on Android either, and adding
 * one here would be inventing a design rather than porting one.
 *
 * ## What is deliberately not ported
 *
 * The type. The Android app is set in Urbanist, loaded from a font resource; this uses the system
 * face. Matching it means shipping the font files and a Compose resources pipeline that cannot be
 * checked on a machine with no simulator, and the difference is immediately visible to anyone who
 * runs the app — which makes it a good thing to leave undone and a bad thing to get subtly wrong.
 */
object Manana {
    val Black = Color(0xFF000000)
    val Surface = Color(0xFF161618)
    val Outline = Color(0xFF2E2E34)
    val AccentIndigo = Color(0xFF2C1AB0)
    val IndigoTint = Color(0xFF6A5FD0)
    val TitleGrey = Color(0xFFDCDCDC)
    val BodyGrey = Color(0xFF7A7A7E)
    val WelcomeGrey = Color(0xFFE2E2E2)
    val EncryptedNoteGrey = Color(0xFF666666)
}

private val MananaColors = darkColorScheme(
    primary = Manana.AccentIndigo,
    onPrimary = Manana.WelcomeGrey,
    secondary = Manana.IndigoTint,
    background = Manana.Black,
    onBackground = Manana.TitleGrey,
    surface = Manana.Surface,
    onSurface = Manana.TitleGrey,
    surfaceVariant = Manana.Surface,
    onSurfaceVariant = Manana.BodyGrey,
    outline = Manana.Outline,
    error = Color(0xFF9C1838),
)

@Composable
fun MananaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MananaColors, content = content)
}
