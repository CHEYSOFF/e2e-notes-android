package my.cheysoff.feature_notes

import my.cheysoff.core_domain.attachment.EncodeStep
import my.cheysoff.core_domain.attachment.ImportLadder
import my.cheysoff.core_domain.model.AttachmentData
import my.cheysoff.feature_notes.ui.attachment.ImportResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What can be proven about the import path without a device.
 *
 * [android.graphics.ImageDecoder] -- what [my.cheysoff.feature_notes.ui.attachment.AndroidImageImporter]
 * is built on -- needs a real device and cannot run under a plain JVM unit test, so the encode path
 * itself is NOT covered here. It is covered by Task 8's instrumented pass and by using the feature.
 * What follows is everything that can be proven without a bitmap: the ladder the importer loops
 * over terminates, a refusal carries no partial attachment data, and [ImportResult] stays exhaustive.
 */
class ImageImportOutcomeTest {

    @Test
    fun `the ladder the importer loops over terminates`() {
        // Mirrors AndroidImageImporter's own loop shape (docs/design/image-attachments.md §7):
        // start at the first rung, call next() until it returns null. Bounded by the ladder's own
        // size, so a bug that made next() cycle forever fails this test instead of hanging it.
        var current: EncodeStep? = ImportLadder.STEPS.first()
        var iterations = 0
        while (current != null) {
            iterations++
            assertTrue("ladder loop ran more rungs than exist", iterations <= ImportLadder.STEPS.size)
            current = ImportLadder.next(current)
        }
        assertEquals(ImportLadder.STEPS.size, iterations)
    }

    @Test
    fun `TooLarge and NotAnImage are singletons carrying no partial attachment`() {
        // Both refusal cases are objects: there is no field on either that a caller could
        // accidentally save half of. is/!is checked explicitly, not just reference equality, so
        // this fails if either case is ever turned into a class carrying a stray field.
        val tooLarge: ImportResult = ImportResult.TooLarge
        val notAnImage: ImportResult = ImportResult.NotAnImage
        assertTrue(tooLarge !is ImportResult.Imported)
        assertTrue(notAnImage !is ImportResult.Imported)
        assertSame(ImportResult.TooLarge, tooLarge)
        assertSame(ImportResult.NotAnImage, notAnImage)
    }

    @Test
    fun `ImportResult stays exhaustive over its three cases`() {
        // This `when` compiles only because every branch is handled with no else. If a fourth case
        // is ever added to ImportResult, this file stops compiling until it is taught about it --
        // that compile-time guarantee is the actual assertion; the runtime checks below just prove
        // each branch is reachable and behaves as labelled.
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
