package my.cheysoff.core_domain.model

data class Note(
    val id: String,
    val title: String,
    val content: String,
    val isPinned: Boolean = false,
    val isFavorite: Boolean = false,
    val folderId: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)
