package my.cheysoff.feature_notes.model.list

import androidx.compose.runtime.Immutable

/**
 * The single random line shown atop the notes list. [prefix] renders in the dim/light color,
 * [accent] in the indigo accent (usually broken onto a second line). When null, the screen
 * falls back to a small "Mañana" wordmark.
 */
@Immutable
data class HeaderLineUi(
    val prefix: String,
    val accent: String,
)
