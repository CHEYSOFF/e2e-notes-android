package my.cheysoff.core_domain.model

data class Note(
    val id: String,
    val title: String,
    val content: String,
    val isPinned: Boolean = false,
    val folderId: String? = null
)
