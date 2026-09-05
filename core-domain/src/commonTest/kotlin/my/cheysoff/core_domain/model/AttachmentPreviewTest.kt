package my.cheysoff.core_domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The same proof [AttachmentDataTest] gives, for the sibling class that has the same trap.
 *
 * [AttachmentPreview] holds `thumbBytes`, so a plain `data class` would compare and hash it by
 * identity. It carries the same overrides for the same reason, and it needs its own pin: the two
 * classes' field sets differ (a preview has no `bytes`), so a test on one says nothing about the
 * other. Without this, a field added to only one of [AttachmentPreview.equals] and
 * [AttachmentPreview.hashCode] would go unnoticed -- and two objects that are equal while hashing
 * differently break every `Set` and `Map` that holds them, silently and far from the change.
 */
class AttachmentPreviewTest {

    private fun preview(
        id: String = "a1",
        thumbBytes: ByteArray = byteArrayOf(9, 8, 7),
    ) = AttachmentPreview(
        id = id,
        noteId = "n1",
        anchor = 0,
        order = 0,
        mimeType = "image/jpeg",
        width = 1600,
        height = 900,
        thumbWidth = 320,
        thumbHeight = 180,
        thumbBytes = thumbBytes,
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    @Test
    fun `two previews built from separate but identical byte arrays are equal`() {
        assertEquals(preview(thumbBytes = byteArrayOf(1, 2, 3)), preview(thumbBytes = byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `previews differing only in thumbBytes are not equal`() {
        assertNotEquals(preview(thumbBytes = byteArrayOf(1, 2, 3)), preview(thumbBytes = byteArrayOf(4, 5, 6)))
    }

    @Test
    fun `hashCode agrees with equals on the equal pair`() {
        val a = preview(thumbBytes = byteArrayOf(1, 2, 3))
        val b = preview(thumbBytes = byteArrayOf(1, 2, 3))

        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `a preview is distinguished by a field that is not its bytes`() {
        assertNotEquals(preview(id = "a1"), preview(id = "a2"))
    }

    @Test
    fun `two previews differing only in identity hash to different values`() {
        assertNotEquals(preview(id = "a1").hashCode(), preview(id = "a2").hashCode())
    }

    @Test
    fun `previews differing only in meta are not equal`() {
        assertNotEquals(preview().copy(meta = ""), preview().copy(meta = "caption"))
    }
}
