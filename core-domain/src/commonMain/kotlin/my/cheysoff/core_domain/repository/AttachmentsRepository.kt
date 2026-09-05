package my.cheysoff.core_domain.repository

import kotlinx.coroutines.flow.Flow
import my.cheysoff.core_domain.model.AttachmentData
import my.cheysoff.core_domain.model.AttachmentPreview

/**
 * The seam a UI (or a test standing in for one) uses to read and write attachments, instead of
 * going through `NotesRepository` or a DAO directly.
 *
 * This is its own interface rather than a widening of [NotesRepository], for the reason
 * `desktop/.../DesktopSketches.kt`'s own KDoc already makes for sketches: the cascade in
 * `RoomNotesRepository.deleteNote`/`restoreNote`/`purgeNote` never calls these four methods — it
 * reaches for `AttachmentDao` directly, exactly the way it already reaches for `SketchDao` while
 * sketches live on the separate [SketchesRepository]. Moving these declarations changes zero lines
 * of the cascade; the coupling that matters (one class, one clock generator, one seed, one
 * transaction) lives at the class, not at which interface a method is declared on.
 *
 * Splitting this out is also what lets `RecordNotesRepository` (`NotesRepository,
 * AttachmentsRepository, DesktopSketches` on the desktop — the same one-class-many-interfaces shape
 * it already has for sketches) and `InMemoryNotesRepository` (the preview/screenshot entry point,
 * which stores no attachments) each implement only what they actually support, rather than the
 * preview build growing four silent stubs — an `attachmentsOf` returning `emptyFlow()` for a
 * feature it never exercises is exactly the dead seam an interface split exists to prevent.
 */
interface AttachmentsRepository {

    /** Attachments anchored under [noteId], visible ones only, in the rail's display order. */
    fun attachmentsOf(noteId: String): Flow<List<AttachmentPreview>>

    /**
     * One attachment in full, bytes included — the read the full-screen viewer needs. Null for an
     * unknown id and for a soft-deleted one, matching `NotesRepository.getNoteById`.
     */
    suspend fun attachment(id: String): AttachmentData?

    /**
     * Creates or updates an attachment. One method for both, matching `NotesRepository.saveNote`:
     * the caller does not have to know whether [attachment]'s id already exists.
     */
    suspend fun saveAttachment(attachment: AttachmentData)

    /**
     * Soft-deletes one attachment by id: its own tombstone, its own fresh clock, `dirty` set so it
     * is pushed. Mirrors `NotesRepository.deleteNote` and `SketchesRepository.deleteSketch`.
     */
    suspend fun deleteAttachment(id: String)
}
