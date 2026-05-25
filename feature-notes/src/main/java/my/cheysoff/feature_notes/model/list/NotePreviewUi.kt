package my.cheysoff.feature_notes.model.list

import androidx.compose.runtime.Immutable
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NotePreview

@Immutable
data class NotePreviewUi(
    val id: String,
    val title: String,
    val content: String,
    val isPinned: Boolean = false,
    val isFavorite: Boolean = false,
    val folderId: String? = null,
    val updatedAt: Long = 0L,
)

fun NotePreview.toUi() = NotePreviewUi(
    id = id,
    title = title,
    content = content,
)

fun Note.toUi() = NotePreviewUi(
    id = id,
    title = title,
    content = content,
    isPinned = isPinned,
    isFavorite = isFavorite,
    folderId = folderId,
    updatedAt = updatedAt,
)
