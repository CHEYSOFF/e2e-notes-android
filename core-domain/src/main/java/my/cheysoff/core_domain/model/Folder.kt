package my.cheysoff.core_domain.model

/**
 * A user folder/category. [colorArgb] is an optional ARGB color (as a Long); when null the UI
 * derives a stable color from the id. Kept as a plain Long so the domain has no UI dependency.
 */
data class Folder(
    val id: String,
    val name: String,
    val colorArgb: Long? = null,
)
