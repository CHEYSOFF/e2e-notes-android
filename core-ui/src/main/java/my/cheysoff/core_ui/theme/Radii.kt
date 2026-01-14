package my.cheysoff.core_ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class Radii(
    val none: Dp = 0.dp,
    val small: Dp = 4.dp,
    val medium: Dp = 12.dp,
    val large: Dp = 24.dp,
    val extraLarge: Dp = 32.dp,
    val max: Dp = 999.dp
)
