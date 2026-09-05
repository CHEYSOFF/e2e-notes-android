package my.cheysoff.desktop.store

import kotlinx.coroutines.flow.Flow
import my.cheysoff.core_domain.model.AttachmentData

/**
 * The desktop UI's one seam onto a note's image attachments: read them live, and delete one.
 *
 * A field-for-field mirror of [DesktopSketches], and its KDoc's reasoning applies unchanged.
 * Attachments are not a `NotesRepository` concern on any platform — the phone reads and writes them
 * through the separate `AttachmentsRepository` — and `NotesWorkspaceModel` still has to run against
 * `InMemoryNotesRepository` in the preview/screenshot entry point, which carries no attachment
 * storage of its own. Making this its own nullable dependency means the preview simply omits it and
 * the attachment rail does not appear, rather than that build having to fake a whole attachment
 * store for a feature it never exercises.
 *
 * There is no `saveAttachment`, for the reason [DesktopSketches] gives for having no `saveSketch`:
 * importing an image is the phone's job in this plan, so a write seam here would be an untested,
 * unreachable one. The desktop is a render-and-delete replica.
 *
 * [RecordNotesRepository] is the only real implementation.
 */
interface DesktopAttachments {

    /** Attachments anchored under [noteId] that are not soft-deleted, live, unsorted. */
    fun getAttachmentsForNote(noteId: String): Flow<List<AttachmentData>>

    /**
     * Soft-deletes one attachment. See [RecordNotesRepository.deleteAttachment] for what that means
     * here — its own tombstone and its own fresh clock, never a hard delete.
     */
    suspend fun deleteAttachment(id: String)
}
