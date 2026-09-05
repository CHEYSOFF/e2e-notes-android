package my.cheysoff.core_domain.attachment

import my.cheysoff.core_domain.model.AttachmentPreview
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The one rule both the phone and the desktop render a note's attachments by: anchor first, id
 * breaking ties. Mirrors `SketchOrderingTest` -- see [sortAttachments]'s own KDoc for why this is one
 * shared function rather than two copies.
 */
class AttachmentOrderingTest {

    private fun preview(id: String, anchor: Int = 0, order: Int = 0) = AttachmentPreview(
        id = id,
        noteId = "n1",
        anchor = anchor,
        order = order,
        mimeType = "image/jpeg",
        width = 1600,
        height = 900,
        thumbWidth = 320,
        thumbHeight = 180,
        thumbBytes = byteArrayOf(1, 2, 3),
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    @Test
    fun `orders by anchor first`() {
        val attachments = listOf(preview("a", anchor = 2), preview("b", anchor = 0), preview("c", anchor = 1))

        assertEquals(listOf("b", "c", "a"), sortAttachments(attachments).map { it.id })
    }

    @Test
    fun `ties break by id -- not by order or insertion position`() {
        // Same anchor, `order` deliberately disagreeing with the desired id order, insertion order
        // deliberately reversed too -- only an explicit id tie-break can produce "a, b, c" here.
        val attachments = listOf(
            preview("c", anchor = 0, order = 0),
            preview("b", anchor = 0, order = 5),
            preview("a", anchor = 0, order = 9),
        )

        assertEquals(listOf("a", "b", "c"), sortAttachments(attachments).map { it.id })
    }

    @Test
    fun `attachments differing only in order are not reordered by it`() {
        val attachments = listOf(preview("a", anchor = 0, order = 9), preview("b", anchor = 0, order = 0))

        assertEquals(listOf("a", "b"), sortAttachments(attachments).map { it.id })
    }
}
