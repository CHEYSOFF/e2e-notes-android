package my.cheysoff.core_ui.theme

import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)


val Graphite = Color(0xFF2C2D31)
val Emerald = Color(0xFF7FCD89)
val Honeydew = Color(0xFFDFF3E3)
val FrozenLake = Color(0xFF6697F8)
val AzureMist = Color(0xFFD8F1FB)
val BrightSnow = Color(0xFFF8F8F8)
val White = Color(0xFFFFFFFF)
val DarkPlatinum = Color(0xFFEFEFEF)
val Silver = Color(0xFFB0B0B0)

// ---------------------------------------------------------------------------
// Mañana dark redesign tokens
// ---------------------------------------------------------------------------

// Surfaces
val AppBlack = Color(0xFF000000)
val SurfaceDark = Color(0xFF161618)      // dark card / bottom bar
val ToolbarDark = Color(0xFF17171C)      // floating editor toolbar
val OutlineDark = Color(0xFF2E2E34)

// Accent
val AccentIndigo = Color(0xFF2C1AB0)     // signature accent: FAB, active pill, hero card
val IndigoTint = Color(0xFF6A5FD0)       // lighter indigo for text/icons on black

// Text
val TitleGrey = Color(0xFFDCDCDC)        // card / list titles
val BodyGrey = Color(0xFF7A7A7E)         // body / secondary
val WelcomeGrey = Color(0xFFE2E2E2)      // big unlock title
val EncryptedNoteGrey = Color(0xFF666666)

// Checklist progress dots
val ChecklistGreen = Color(0xFF1F9E4A)
val ChecklistTodo = Color(0xFF333333)

// Category palette — equal *perceived* depth (see redesign-design-system)
val CatBlue = Color(0xFF1D4F87)
val CatTeal = Color(0xFF15695E)
val CatGreen = Color(0xFF1A6E34)
val CatOchre = Color(0xFF8A5616)
val CatRust = Color(0xFF993417)
val CatCrimson = Color(0xFF9C1838)
val CatPlum = Color(0xFF7D1793)
val UncategorizedEdge = Color(0xFF3A3A40)

/** The category colors a note/folder can take, in palette order. */
val CategoryColors = listOf(
    AccentIndigo, CatBlue, CatTeal, CatGreen, CatOchre, CatRust, CatCrimson, CatPlum
)

/**
 * Deterministic category color for a folder/category key. Returns a stable color
 * for the same key so the list looks consistent before per-note colors are stored.
 * Null/blank keys map to a neutral grey edge.
 */
fun colorForCategory(key: String?): Color {
    if (key.isNullOrBlank()) return UncategorizedEdge
    val index = (key.hashCode() and 0x7FFFFFFF) % CategoryColors.size
    return CategoryColors[index]
}

/**
 * A note's accent color: the folder's explicitly chosen [colorArgb] when set, else the
 * deterministic hash color for [folderId]. Returns null when the note has no folder, so the
 * caller supplies the neutral/accent fallback (matching the prior noteColor/editorAccent behavior).
 */
fun folderAccentColor(folderId: String?, colorArgb: Long?): Color? =
    if (folderId.isNullOrBlank()) null
    else colorArgb?.let { Color(it.toInt()) } ?: colorForCategory(folderId)