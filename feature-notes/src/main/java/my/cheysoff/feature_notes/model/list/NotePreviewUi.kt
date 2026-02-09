package my.cheysoff.feature_notes.model.list

import androidx.compose.runtime.Immutable
import my.cheysoff.core_domain.NotePreview
import java.util.UUID

@Immutable
data class NotePreviewUi(
    val id: String = UUID.randomUUID().toString(), // todo introduce stable ids
    val title: String,
    val content: String,
)

fun NotePreview.toUi() = NotePreviewUi(
    title = title,
    content = content
)
