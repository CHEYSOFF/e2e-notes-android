package my.cheysoff.feature_notes.model.single

import androidx.compose.runtime.Immutable
import my.cheysoff.core_domain.model.Folder
import my.cheysoff.core_domain.model.NoteContentFormat

@Immutable
data class SingleNoteScreenState(
    val title: String = "",
    val content: String = "",
    // How `content` is encoded. Seeded from the stored row and flipped to HTML as soon as the
    // rich-text editor writes the body, so a title-only edit can't relabel untouched plain text.
    val contentFormat: NoteContentFormat = NoteContentFormat.PLAIN,
    val checklist: List<ChecklistItem> = emptyList(),
    val isPinned: Boolean = false,
    val isFavorite: Boolean = false,
    val folderId: String? = null,
    val folders: List<Folder> = emptyList(),
    val updatedAt: Long = 0L,
    val isLoaded: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null
)
