package my.cheysoff.feature_notes

import android.net.Uri
import my.cheysoff.core_domain.model.AttachmentData
import my.cheysoff.feature_notes.ui.attachment.ImageImporter
import my.cheysoff.feature_notes.ui.attachment.ImportResult

/**
 * Hand-written [ImageImporter] test double. The real implementation ([android.graphics.ImageDecoder])
 * needs a real device and cannot run under a plain JVM unit test -- see `ImageImporter.kt`'s own
 * KDoc -- so every [SingleNoteViewModel] test that exercises `ImportAttachment` goes through this
 * instead, deciding what [import] returns directly rather than actually decoding anything.
 */
internal class FakeImageImporter : ImageImporter {

    /** What the next (and every subsequent) [import] call returns. Defaults to a small success. */
    var result: ImportResult = ImportResult.Imported(
        AttachmentData(
            id = "placeholder",
            noteId = "",
            anchor = 0,
            order = 0,
            mimeType = "image/jpeg",
            width = 100,
            height = 100,
            bytes = ByteArray(1),
            thumbWidth = 10,
            thumbHeight = 10,
            thumbBytes = ByteArray(1),
            createdAt = 0L,
            updatedAt = 0L,
        )
    )

    /** Every [Uri] handed to [import], in call order. */
    val requested = mutableListOf<Uri>()

    override suspend fun import(uri: Uri): ImportResult {
        requested += uri
        return result
    }
}
