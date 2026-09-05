package my.cheysoff.core_domain.repository

import kotlinx.coroutines.flow.Flow
import my.cheysoff.core_domain.model.AttachmentData
import my.cheysoff.core_domain.model.AttachmentPreview
import my.cheysoff.core_domain.model.Folder
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NotesSortOrder

interface NotesRepository {
    /** Notes the user can see: soft-deleted rows are excluded. */
    fun getNotes(sortOrder: NotesSortOrder): Flow<List<Note>>

    /** Emits null for an unknown id AND for a soft-deleted one — Trash rows are not editable. */
    fun getNoteById(id: String): Flow<Note?>

    suspend fun saveNote(note: Note)

    /** Moves the note to Trash (soft delete). Restorable until [purgeExpiredTrash] takes it. */
    suspend fun deleteNote(id: String)

    /** Brings a note back out of Trash. No-op for a note that is not in Trash. */
    suspend fun restoreNote(id: String)

    /**
     * Destroys the note's row. Irreversible — there is no second tier of undo behind this.
     *
     * Two callers: "Delete forever" in Trash, and the editor's discard of a note that was created
     * blank and left blank, which must never reach Trash in the first place.
     */
    suspend fun purgeNote(id: String)

    /** Notes in Trash, newest-deleted first. */
    fun getDeletedNotes(): Flow<List<Note>>

    suspend fun setNoteFolder(noteId: String, folderId: String?)
    suspend fun setNoteFavorite(noteId: String, isFavorite: Boolean)
    suspend fun setNotePinned(noteId: String, isPinned: Boolean)

    /** Folders the user can see: soft-deleted rows are excluded. */
    fun getFolders(): Flow<List<Folder>>

    suspend fun saveFolder(folder: Folder)

    /** Moves the folder to Trash and unfiles its notes, as one transaction. */
    suspend fun deleteFolder(id: String)

    /**
     * Brings a folder back out of Trash.
     *
     * It comes back EMPTY: [deleteFolder] unfiled its notes, and restore does not re-file them —
     * nothing records which notes were in it, and by then the user may have re-filed some by hand.
     */
    suspend fun restoreFolder(id: String)

    /** Destroys the folder's row. Irreversible. Its notes were already unfiled by [deleteFolder]. */
    suspend fun purgeFolder(id: String)

    /** Folders in Trash, newest-deleted first. */
    fun getDeletedFolders(): Flow<List<Folder>>

    /**
     * Purges every note and folder whose Trash retention has run out as of [now], and returns how
     * many rows were destroyed.
     *
     * [now] is a parameter rather than read inside so the expiry decision stays testable; see
     * [my.cheysoff.core_domain.model.TrashPolicy].
     */
    suspend fun purgeExpiredTrash(now: Long): Int

    // ── Attachments. Unlike sketches (see SketchesRepository), attachments have no separate ──────
    // ── repository interface: there is exactly one implementation and no seam anything else ──────
    // ── needs to stand in for yet. See docs/design/image-attachments.md §2. ──────────────────────

    /** Attachments anchored under [noteId], visible ones only, in the rail's display order. */
    fun attachmentsOf(noteId: String): Flow<List<AttachmentPreview>>

    /**
     * One attachment in full, bytes included — the read the full-screen viewer needs. Null for an
     * unknown id and for a soft-deleted one, matching [getNoteById].
     */
    suspend fun attachment(id: String): AttachmentData?

    /**
     * Creates or updates an attachment. One method for both, matching [saveNote]: the caller does
     * not have to know whether [attachment]'s id already exists.
     */
    suspend fun saveAttachment(attachment: AttachmentData)

    /**
     * Soft-deletes one attachment by id: its own tombstone, its own fresh clock, `dirty` set so it
     * is pushed. Mirrors [deleteNote] and `SketchesRepository.deleteSketch`.
     */
    suspend fun deleteAttachment(id: String)
}
