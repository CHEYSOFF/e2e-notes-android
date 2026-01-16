package my.cheysoff.feature_notes

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class NoteDimensions(
    // --- Primitive scale (numbers only) ---
    val iconL: Dp = 100.dp,
    val maxPreviewHeight: Dp = 300.dp,

    // --- Semantic roles ---
    val folderIconSize: Dp = iconL,
    val noteCardMaxHeight: Dp = maxPreviewHeight
)

val LocalNoteDimensions = staticCompositionLocalOf { NoteDimensions() }