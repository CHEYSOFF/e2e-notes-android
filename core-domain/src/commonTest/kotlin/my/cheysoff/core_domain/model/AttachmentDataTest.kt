package my.cheysoff.core_domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * [AttachmentData] holds two `ByteArray` fields, which a plain `data class` would compare and hash
 * by identity rather than content. These tests are the proof that the [AttachmentData.equals] and
 * [AttachmentData.hashCode] overrides actually compare content instead.
 */
class AttachmentDataTest {

    private fun attachment(
        bytes: ByteArray = byteArrayOf(1, 2, 3),
        thumbBytes: ByteArray = byteArrayOf(9, 8, 7),
    ) = AttachmentData(
        id = "a1",
        noteId = "n1",
        anchor = 0,
        order = 0,
        mimeType = "image/jpeg",
        width = 1600,
        height = 900,
        bytes = bytes,
        thumbWidth = 320,
        thumbHeight = 180,
        thumbBytes = thumbBytes,
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    @Test
    fun `two instances built from separate but identical byte arrays are equal`() {
        val a = attachment(bytes = byteArrayOf(1, 2, 3))
        val b = attachment(bytes = byteArrayOf(1, 2, 3))

        assertEquals(a, b)
    }

    @Test
    fun `instances differing only in bytes are not equal`() {
        val a = attachment(bytes = byteArrayOf(1, 2, 3))
        val b = attachment(bytes = byteArrayOf(4, 5, 6))

        assertNotEquals(a, b)
    }

    @Test
    fun `hashCode agrees with equals on the equal pair`() {
        val a = attachment(bytes = byteArrayOf(1, 2, 3))
        val b = attachment(bytes = byteArrayOf(1, 2, 3))

        assertEquals(a.hashCode(), b.hashCode())
    }
}
