package my.cheysoff.core_ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import my.cheysoff.core_ui.R

// Light (300) and Medium (500) are driven from the Urbanist variable font via the
// 'wght' axis; Regular/Bold/ExtraBold use the bundled static instances.
//
// TODO(font): Urbanist has NO Cyrillic glyphs, so Russian (and other Cyrillic) note text falls
// back to a system font and looks inconsistent with the Latin UI. Replace this family with a
// geometric sans that covers Latin + Cyrillic in the same weights — Onest (closest to Urbanist),
// Manrope, or Nunito Sans (all OFL, have 300/500/700 + Cyrillic). One change here updates the
// whole app. Compose can't auto-fallback per-script across separate Font entries, so use a single
// dual-script family rather than adding Urbanist + a Cyrillic font side by side.
@OptIn(ExperimentalTextApi::class)
val UrbanistFontFamily = FontFamily(
    Font(
        R.font.urbanist_variable,
        weight = FontWeight.Light,
        variationSettings = FontVariation.Settings(FontVariation.weight(300))
    ),
    Font(R.font.urbanist_regular, FontWeight.Normal),
    Font(
        R.font.urbanist_variable,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500))
    ),
    Font(R.font.urbanist_bold, FontWeight.Bold),
    Font(R.font.urbanist_extra_bold, FontWeight.ExtraBold)
)

val Typography = Typography(
    // Hero / screen titles — thin, airy (the "Welcome back." / "My Notes" look)
    titleLarge = TextStyle(
        fontFamily = UrbanistFontFamily,
        fontWeight = FontWeight.Light,
        fontSize = 37.sp,
        letterSpacing = (-0.5).sp,
    ),
    // Card / section titles — comfortable mid-weight
    titleMedium = TextStyle(
        fontFamily = UrbanistFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 25.sp,
        letterSpacing = 0.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = UrbanistFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = UrbanistFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 20.sp,
        letterSpacing = 0.sp
    ),
    bodySmall = TextStyle(
        fontFamily = UrbanistFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        letterSpacing = 0.sp
    ),
    labelSmall = TextStyle(
        fontFamily = UrbanistFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        letterSpacing = 0.sp
    ),
)