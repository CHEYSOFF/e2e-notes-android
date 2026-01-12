package my.cheysoff.feature_notes.model

import androidx.compose.runtime.Immutable

@Immutable
data class NotesListScreenState(
    val folderPreviews: List<FolderPreviewUi> = emptyList(),
    val notePreviews: List<NotePreviewUi> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
)
