package my.cheysoff.feature_notes.model.single

import androidx.compose.runtime.Immutable
import my.cheysoff.core_domain.model.Folder
import my.cheysoff.core_domain.model.NoteContentFormat
import my.cheysoff.core_domain.model.SketchData

@Immutable
data class SingleNoteScreenState(
    val title: String = "",
    val content: String = "",
    // How `content` is encoded. Seeded from the stored row and flipped to HTML as soon as the
    // rich-text editor writes the body, so a title-only edit can't relabel untouched plain text.
    val contentFormat: NoteContentFormat = NoteContentFormat.PLAIN,
    val checklist: List<ChecklistItem> = emptyList(),
    // Below the note's text, never interleaved with it -- see docs/design/sketch-blocks.md's
    // 2026-09-05 amendment. Always in display order already: sorted by anchor then id (see
    // `SingleNoteViewModel.sortSketches`), the same rule the desktop applies, so both devices show
    // one note's drawings in the same order without exchanging anything about it.
    val sketches: List<SketchData> = emptyList(),
    val isPinned: Boolean = false,
    val isFavorite: Boolean = false,
    val folderId: String? = null,
    val folders: List<Folder> = emptyList(),
    // Whether the editor-wide undo/redo stack (title + body + checklist) has anything to take back
    // or replay. Drives the enabled state of the two top-bar buttons.
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    // Bumped every time the ViewModel replaces [content] itself — i.e. on an undo or redo of a body
    // edit — and never when the body changes because the editor reported it. The screen re-seeds
    // RichTextState whenever this changes, so typing (which moves `content` on every flush) can
    // never trigger a re-seed and move the cursor out from under the user.
    val contentRevision: Int = 0,
    val updatedAt: Long = 0L,
    val isLoaded: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null
)
