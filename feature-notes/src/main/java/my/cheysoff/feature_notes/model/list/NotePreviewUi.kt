package my.cheysoff.feature_notes.model.list

import androidx.compose.runtime.Immutable
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NotePreview

@Immutable
data class NotePreviewUi(
    val id: String,
    val title: String,
    val content: String,
)

fun NotePreview.toUi() = NotePreviewUi(
    id = id,
    title = title,
    content = content
)

fun Note.toUi() = NotePreviewUi(
    id = id,
    title = title,
    content = content
)
