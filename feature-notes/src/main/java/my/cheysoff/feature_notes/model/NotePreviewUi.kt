package my.cheysoff.feature_notes.model

import androidx.compose.runtime.Immutable
import my.cheysoff.core_domain.NotePreview

@Immutable
data class NotePreviewUi(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val content: String,
)

fun NotePreview.toUi() = NotePreviewUi(
    title = title,
    content = content
)
