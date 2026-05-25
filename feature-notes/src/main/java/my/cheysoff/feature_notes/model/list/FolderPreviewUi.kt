package my.cheysoff.feature_notes.model.list

import androidx.compose.runtime.Immutable
import my.cheysoff.core_domain.model.Folder

@Immutable
data class FolderPreviewUi(
    val id: String,
    val name: String,
    val notesAmount: Int,
    val colorArgb: Long? = null,
)

fun Folder.toUi(notesAmount: Int) = FolderPreviewUi(
    id = id,
    name = name,
    notesAmount = notesAmount,
    colorArgb = colorArgb,
)
