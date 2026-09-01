package my.cheysoff.desktop.ui.state

import androidx.compose.runtime.Immutable
import my.cheysoff.core_domain.model.Folder
import my.cheysoff.core_domain.model.Note

/**
 * The list pane's content, and the pure functions that derive it.
 *
 * Nothing in this file touches Compose beyond the `@Immutable` marker, a repository, or a clock,
 * which is the point: every rule about what appears in the sidebar — which folder filters what,
 * what counts as pinned, how a chip's count is worked out, what happens to the selection when the
 * selected note disappears — is decided here and covered by WorkspaceContentTest.
 */

@Immutable
data class NoteRowUi(
    val id: String,
    val title: String,
    /** Plain text, already stripped of HTML. Both the card body and the search index use it. */
    val snippet: String,
    val isPinned: Boolean = false,
    val isFavorite: Boolean = false,
    val folderId: String? = null,
    val folderColorArgb: Long? = null,
    val updatedAt: Long = 0L,
    val checklistDone: Int = 0,
    val checklistTotal: Int = 0,
)

/** One folder filter above the list. [id] is null for the "All" chip. */
@Immutable
data class FolderChipUi(
    val id: String?,
    val name: String,
    val count: Int,
    val colorArgb: Long? = null,
)

/** The two sections of the sketch: `── Pinned ──` above `── Recent ──`. */
@Immutable
data class NoteListContent(
    val pinned: List<NoteRowUi> = emptyList(),
    val recent: List<NoteRowUi> = emptyList(),
) {
    val isEmpty: Boolean get() = pinned.isEmpty() && recent.isEmpty()
    val all: List<NoteRowUi> get() = pinned + recent
}

/**
 * Projects a domain [Note] into a row.
 *
 * [folderColorArgb] is passed in rather than looked up because the note does not know its folder's
 * colour — only the folder list does, and resolving it here would need this function to take the
 * whole folder list for one nullable Long.
 */
fun Note.toRow(folderColorArgb: Long? = null): NoteRowUi {
    val (done, total) = checklistProgress(checklist)
    return NoteRowUi(
        id = id,
        title = title,
        snippet = noteBodyAsPlainText(content, contentFormat),
        isPinned = isPinned,
        isFavorite = isFavorite,
        folderId = folderId,
        folderColorArgb = folderColorArgb,
        updatedAt = updatedAt,
        checklistDone = done,
        checklistTotal = total,
    )
}

fun List<Note>.toRows(folders: List<Folder>): List<NoteRowUi> {
    val colorById = folders.associate { it.id to it.colorArgb }
    // `colorById[folderId]` is doubly nullable — absent folder vs. folder with no colour — and
    // both mean the same thing to the row, so the flattening is intentional.
    return map { it.toRow(folderColorArgb = it.folderId?.let { id -> colorById[id] }) }
}

/**
 * The chip row: "All" first, then every folder, each carrying how many notes are filed in it.
 *
 * Folders with no notes are kept. On a phone the chip row is a scarce horizontal strip and hiding
 * empties would be defensible; in a sidebar there is room, and a folder that vanished the moment
 * its last note moved would be a folder the user could no longer file anything into.
 */
fun buildFolderChips(folders: List<Folder>, notes: List<NoteRowUi>): List<FolderChipUi> {
    val counts = notes.groupingBy { it.folderId }.eachCount()
    return buildList {
        add(FolderChipUi(id = null, name = "All", count = notes.size))
        folders.forEach { folder ->
            add(
                FolderChipUi(
                    id = folder.id,
                    name = folder.name,
                    count = counts[folder.id] ?: 0,
                    colorArgb = folder.colorArgb,
                )
            )
        }
    }
}

/**
 * Filters by folder and splits into the pinned and recent sections.
 *
 * The incoming order is the repository's chosen sort order and is preserved inside each section;
 * this only partitions. A pinned note appears in Pinned and NOT again in Recent — the phone shows
 * the same note twice because its pinned strip is a swipeable pager the eye reads as a separate
 * object, but in a single flat sidebar a duplicated row reads as a bug.
 */
fun buildListContent(rows: List<NoteRowUi>, selectedFolderId: String?): NoteListContent {
    val visible = if (selectedFolderId == null) rows else rows.filter { it.folderId == selectedFolderId }
    val (pinned, recent) = visible.partition { it.isPinned }
    return NoteListContent(pinned = pinned, recent = recent)
}

/**
 * Which note the editor should show after the list changed.
 *
 * Keeps the current selection when it is still visible; otherwise falls to the first row, and to
 * null only when there is nothing to show. Without this, deleting the open note (or switching to a
 * folder that does not contain it) leaves the editor displaying a note the list no longer offers —
 * and, because the editor autosaves, quietly writing it back.
 */
fun resolveSelection(content: NoteListContent, current: String?): String? {
    val visible = content.all
    if (current != null && visible.any { it.id == current }) return current
    return visible.firstOrNull()?.id
}
