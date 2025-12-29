package my.cheysoff.feature_notes.model

import my.cheysoff.core_domain.FolderPreview
import my.cheysoff.core_domain.NotePreview

data class NotesListScreenState(
    val folderPreviews: List<FolderPreview> = emptyList(),
    val notePreviews: List<NotePreview> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
)