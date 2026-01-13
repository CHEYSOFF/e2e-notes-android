package my.cheysoff.feature_notes.model

import androidx.compose.runtime.Immutable
import my.cheysoff.core_domain.FolderPreview
import java.util.UUID

@Immutable
data class FolderPreviewUi(
    val id: UUID,
    val name: String,
    val notesAmount: Int,
)

fun FolderPreview.toUi() = FolderPreviewUi(
    id = id,
    name = name,
    notesAmount = notesAmount
)
