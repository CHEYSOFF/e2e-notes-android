package my.cheysoff.feature_notes.model.trash

import androidx.compose.runtime.Immutable

/** Which table a Trash row came from. Restore and Delete forever dispatch on it. */
enum class TrashEntryKind { NOTE, FOLDER }

/**
 * One row in Trash — a deleted note or a deleted folder, flattened into a single list so the two
 * kinds interleave in delete order rather than sitting in separate sections. The user deleted them
 * in one sequence; showing them in that sequence is what makes "the thing I just deleted" the
 * first row.
 *
 * [folderId] and [folderColorArgb] exist only to feed `folderAccentColor`, exactly as
 * NotePreviewUi uses them: for a note they describe the folder it was filed in (which may itself be
 * in Trash), and for a folder they describe the folder itself.
 */
@Immutable
data class TrashEntryUi(
    val id: String,
    val kind: TrashEntryKind,
    val title: String,
    /** Plain-text snippet for a note; empty for a folder, which has no body. */
    val snippet: String,
    val folderId: String?,
    val folderColorArgb: Long? = null,
    /** Null only for a tombstone with no stamp, which TrashPolicy refuses to age. */
    val deletedAt: Long? = null,
    /** From TrashPolicy.daysRemaining, resolved when the list was built. Null = unknown age. */
    val daysRemaining: Int? = null,
)
