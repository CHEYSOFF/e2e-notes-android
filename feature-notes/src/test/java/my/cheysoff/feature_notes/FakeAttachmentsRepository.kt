package my.cheysoff.feature_notes

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import my.cheysoff.core_domain.model.AttachmentData
import my.cheysoff.core_domain.model.AttachmentPreview
import my.cheysoff.core_domain.repository.AttachmentsRepository

/**
 * Hand-written [AttachmentsRepository] test double, matching [FakeSketchesRepository]'s own
 * reasoning exactly: the flow is a [MutableStateFlow] the test drives directly, and [saveGate] lets
 * a test pin down the ViewModel's actual concurrent interleaving (a `BackClicked` racing an
 * in-flight `ImportAttachment`) rather than hoping the two happen to run in the order a bug would
 * hide.
 */
internal class FakeAttachmentsRepository : AttachmentsRepository {

    /** The row set [attachmentsOf] hands out, for whatever noteId the test cares about. */
    val attachmentsByNote = MutableStateFlow<List<AttachmentPreview>>(emptyList())

    /** Every [AttachmentData] handed to [saveAttachment], in call order. */
    val saved = mutableListOf<AttachmentData>()

    /** Every id handed to [deleteAttachment], in call order. */
    val deleted = mutableListOf<String>()

    /** See [FakeSketchesRepository.saveGate] -- same mechanism, same purpose, for attachments. */
    var saveGate: CompletableDeferred<Unit>? = null

    override fun attachmentsOf(noteId: String): Flow<List<AttachmentPreview>> = attachmentsByNote

    override suspend fun attachment(id: String): AttachmentData? = saved.find { it.id == id }

    override suspend fun saveAttachment(attachment: AttachmentData) {
        saveGate?.await()
        saved += attachment
    }

    override suspend fun deleteAttachment(id: String) {
        deleted += id
    }
}
