package my.cheysoff.feature_notes

import my.cheysoff.core_domain.model.AttachmentData
import my.cheysoff.feature_notes.ui.attachment.ImportResult
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What this file does NOT test, stated up front rather than implied by its presence: the ladder
 * loop and the refusal cases are exercised through `AndroidImageImporter.import`, which needs a
 * real device ([android.graphics.ImageDecoder]) and has no JVM coverage anywhere in this module --
 * see Task 8's instrumented pass and `ImageImporter.kt`'s own KDoc. `ImportLadder` itself (the pure
 * decision logic the importer loops over) is already covered by `core-domain`'s `ImportLadderTest`,
 * so re-testing it here by re-implementing the loop over `ImportLadder` directly, without going
 * through the importer, would prove nothing about the importer and duplicate `core-domain`'s own
 * tests besides.
 *
 * What's left, and the only thing this file asserts, is the one property with genuine compile-time
 * value: [ImportResult] stays exhaustive with no `else` branch. If a fourth case is ever added,
 * this file simply stops compiling until it is taught about it -- that's the actual guarantee; the
 * runtime assertion below only demonstrates each branch is reachable.
 */
class ImageImportOutcomeTest {

    @Test
    fun `ImportResult stays exhaustive over its three cases`() {
        val attachment = AttachmentData(
            id = "id",
            noteId = "note",
            anchor = 0,
            order = 0,
            mimeType = "image/jpeg",
            width = 1,
            height = 1,
            bytes = ByteArray(0),
            thumbWidth = 1,
            thumbHeight = 1,
            thumbBytes = ByteArray(0),
            createdAt = 0L,
            updatedAt = 0L,
        )
        val results: List<ImportResult> = listOf(
            ImportResult.Imported(attachment),
            ImportResult.TooLarge,
            ImportResult.NotAnImage,
        )

        val labels = results.map { result ->
            when (result) {
                is ImportResult.Imported -> "imported"
                ImportResult.TooLarge -> "too-large"
                ImportResult.NotAnImage -> "not-an-image"
            }
        }

        assertEquals(listOf("imported", "too-large", "not-an-image"), labels)
    }
}
