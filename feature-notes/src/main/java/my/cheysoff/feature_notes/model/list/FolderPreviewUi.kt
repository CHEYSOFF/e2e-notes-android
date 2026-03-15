package my.cheysoff.feature_notes.model.list

import androidx.compose.runtime.Immutable
import my.cheysoff.core_domain.model.FolderPreview

@Immutable
data class FolderPreviewUi(
    val id: String,
    val name: String,
    val notesAmount: Int,
)

fun FolderPreview.toUi() = FolderPreviewUi(
    id = id.toString(),
    name = name,
    notesAmount = notesAmount
)
