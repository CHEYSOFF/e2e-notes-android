package my.cheysoff.core_sync_codec

import my.cheysoff.core_domain.model.AttachmentData
import my.cheysoff.core_domain.sync.FieldClocks
import my.cheysoff.core_domain.sync.Hlc
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AttachmentRecords] both ways.
 *
 * In `jvmTest` rather than a `commonTest`: this module has no common test source set, and every
 * other codec test — `SketchRecordTypeTest`, `RecordPayloadCodecTest` — lives here. Nothing in
 * [AttachmentRecords] is platform-specific, so the placement costs no coverage.
 */
class AttachmentRecordsTest {

    private val clock = Hlc(ms = 1_700_000_000_000L, counter = 3, node = "a1b2c3d4")

    /** Deliberately includes 0x00 and 0xFF, the two bytes a careless codec loses. */
    private val bytes = byteArrayOf(0, 1, 2, 127, -1, -128, 0, -1)
    private val thumbBytes = byteArrayOf(-1, 0, 42)

    private fun row(
        attachment: AttachmentData = attachment(),
        clocks: Map<String, Hlc> = mapOf(FieldClocks.IMAGE to clock),
    ) = AttachmentRow(
        attachment = attachment,
        rowClock = clock,
        clocks = clocks,
        dirty = true,
        lastSyncedSeq = 17L,
    )

    private fun attachment(
        isDeleted: Boolean = true,
        deletedAt: Long? = 900L,
        meta: String = "",
    ) = AttachmentData(
        id = "att-1",
        noteId = "note-1",
        anchor = 4,
        order = 2,
        mimeType = "image/webp",
        width = 1280,
        height = 720,
        bytes = bytes,
        thumbWidth = 64,
        thumbHeight = 36,
        thumbBytes = thumbBytes,
        createdAt = 100L,
        updatedAt = 200L,
        isDeleted = isDeleted,
        deletedAt = deletedAt,
        meta = meta,
    )

    @Test
    fun `a payload round-trips every column`() {
        // A non-empty `meta` proves the opaque column is carried rather than normalised away --
        // this build never writes one, and a build that does not understand it must preserve it.
        val original = row(attachment(meta = """{"caption":"a future build wrote this"}"""))

        val payload = AttachmentRecords.toPayload(original, createdAt = 100L)
        val decoded = AttachmentRecords.fromPayload(payload)!!

        assertEquals(original.attachment, decoded.attachment)
        // Carried as-is, never minted: a zero row clock trips the merge's rollback guard and the
        // record is rejected silently and forever. See AttachmentRecords' KDoc.
        assertEquals(original.rowClock, decoded.rowClock)
        assertEquals(original.clocks, decoded.clocks)
        // Per-device bookkeeping, deliberately not on the wire.
        assertTrue(!decoded.dirty)
        assertEquals(0L, decoded.lastSyncedSeq)
    }

    @Test
    fun `the payload carries exactly the frozen column set`() {
        val payload = AttachmentRecords.toPayload(row(), createdAt = 100L)

        // The decoder demands exact equality against this set, so a column added later decodes as
        // Malformed on every older device and halts its account. This is the pin on that.
        assertEquals(PayloadFields.ATTACHMENT_COLUMNS, payload.fields.keys)
        // `meta` is on the wire and has no clock of its own -- the CREATED_AT arrangement. Both
        // halves are asserted here because either one alone is the bug: on the wire without being
        // reserved is pointless, and clocked without being reserved is a different record shape.
        assertTrue(PayloadFields.META in payload.fields.keys)
        assertTrue(PayloadFields.META !in FieldClocks.ATTACHMENT_FIELDS)
        assertTrue(PayloadFields.CREATED_AT !in FieldClocks.ATTACHMENT_FIELDS)
    }

    @Test
    fun `bytes survive the base64 round trip unchanged`() {
        val payload = AttachmentRecords.toPayload(row(), createdAt = 100L)
        val decoded = AttachmentRecords.fromPayload(payload)!!

        assertArrayEquals(bytes, decoded.attachment.bytes)
        assertArrayEquals(thumbBytes, decoded.attachment.thumbBytes)
    }

    @Test
    fun `an unparseable anchor refuses the record`() {
        val payload = AttachmentRecords.toPayload(row(), createdAt = 100L)
        val corrupted = payload.copy(fields = payload.fields + (PayloadFields.ANCHOR to "top"))

        assertNull(AttachmentRecords.fromPayload(corrupted))
    }

    @Test
    fun `undecodable base64 refuses the record`() {
        val payload = AttachmentRecords.toPayload(row(), createdAt = 100L)

        // `*` is outside RFC 4648 §5's alphabet. Refused rather than defaulted to empty bytes: a
        // blank grey box in someone's note on every device, with nothing reporting a problem, is
        // strictly worse than one record this build declines to read.
        assertNull(
            AttachmentRecords.fromPayload(
                payload.copy(fields = payload.fields + (PayloadFields.BYTES to "not*base64")),
            ),
        )
        assertNull(
            AttachmentRecords.fromPayload(
                payload.copy(fields = payload.fields + (PayloadFields.THUMB_BYTES to "not*base64")),
            ),
        )
    }

    @Test
    fun `a null deletedAt round-trips as null`() {
        val original = row(attachment(isDeleted = false, deletedAt = null))

        val payload = AttachmentRecords.toPayload(original, createdAt = 100L)
        val decoded = AttachmentRecords.fromPayload(payload)!!

        assertNull(payload.field(PayloadFields.DELETED_AT))
        assertNull(decoded.attachment.deletedAt)
        assertTrue(!decoded.attachment.isDeleted)
    }
}
