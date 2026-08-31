package my.cheysoff.desktop.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The Mañana palette, byte-for-byte the same values as `:core-ui/theme/Color.kt`.
 *
 * It is restated rather than imported because :core-ui is an Android library — it compiles against
 * `androidx.compose.ui` and reads its fonts out of an Android resource table, neither of which a
 * Kotlin/JVM module can link to. Only the token VALUES are duplicated, and they are the part of
 * the design system that is deliberately frozen ("a locked dark palette"), so the copy is stable
 * by construction.
 *
 * The day :core-ui becomes a multiplatform module this file should be deleted, not merged into.
 */

// Surfaces
val AppBlack = Color(0xFF000000)
val SurfaceDark = Color(0xFF161618)
val ToolbarDark = Color(0xFF17171C)
val OutlineDark = Color(0xFF2E2E34)

// Accent
val AccentIndigo = Color(0xFF2C1AB0)
val IndigoTint = Color(0xFF6A5FD0)

// Text
val TitleGrey = Color(0xFFDCDCDC)
val BodyGrey = Color(0xFF7A7A7E)
val MetaGrey = Color(0xFF5E5E62)
val PlaceholderGrey = Color(0xFF4A4A50)
val EditorBodyGrey = Color(0xFFB9B9BD)

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
 * Deterministic category color for a folder key.
 *
 * The hash is [String.hashCode], which the Kotlin/JVM stdlib specifies exactly, so a folder gets
 * the same colour here as it does on Android — the two platforms must not disagree about what
 * colour "Work" is.
 */
fun colorForCategory(key: String?): Color {
    if (key.isNullOrBlank()) return UncategorizedEdge
    val index = (key.hashCode() and 0x7FFFFFFF) % CategoryColors.size
    return CategoryColors[index]
}

/**
 * A note's accent: the folder's explicitly chosen colour when set, else the deterministic hash
 * colour. Null when the note has no folder, so the caller picks the neutral/accent fallback.
 */
fun folderAccentColor(folderId: String?, colorArgb: Long?): Color? =
    if (folderId.isNullOrBlank()) null
    else colorArgb?.let { Color(it.toInt()) } ?: colorForCategory(folderId)
