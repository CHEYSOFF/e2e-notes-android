package my.cheysoff.feature_notes.model.single

import androidx.compose.runtime.Immutable

@Immutable
data class SingleNoteScreenState(
    val title: String = "",
    val content: String = "",
    val checklist: List<ChecklistItem> = emptyList(),
    val isPinned: Boolean = false,
    val folderId: String? = null,
    val updatedAt: Long = 0L,
    val isLoaded: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null
)
