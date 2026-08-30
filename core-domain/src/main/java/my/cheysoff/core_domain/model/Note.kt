package my.cheysoff.core_domain.model

data class Note(
    val id: String,
    val title: String,
    val content: String,
    // How `content` is encoded. Recorded, never guessed — see NoteContentFormat. New notes start
    // out PLAIN (an empty body is identical either way) and are promoted to HTML the moment the
    // rich-text editor writes to them.
    val contentFormat: NoteContentFormat = NoteContentFormat.PLAIN,
    val checklist: String = "",
    val isPinned: Boolean = false,
    val isFavorite: Boolean = false,
    val folderId: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)
