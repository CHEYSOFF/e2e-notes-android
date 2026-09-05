package my.cheysoff.core_sync_codec

import my.cheysoff.core_domain.sync.FieldClocks
import my.cheysoff.core_domain.sync.FieldValue
import my.cheysoff.core_domain.sync.Hlc
import my.cheysoff.core_domain.sync.RecordType
import my.cheysoff.core_domain.sync.SyncRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * An attachment on the **wire** codec — `SyncRecords`, the path both phone and desktop actually
 * sync through — as opposed to [AttachmentRecordsTest], which covers `AttachmentRecords`, the
 * desktop's local vault-load codec.
 *
 * The distinction is the reason this file exists. `SyncRecords.fromPayload` is where every pulled
 * record is refused or accepted on both platforms, and before this nothing exercised it on an
 * `ATTACHMENT` payload at all: its numeric and base64 guards could have been deleted outright and
 * the whole suite would have stayed green while the blank-grey-box failure they exist to prevent
 * was re-armed.
 *
 * Shaped after [SketchRecordTypeTest], which makes the same argument for a sketch.
 */
class AttachmentRecordTypeTest {

    private val clock = Hlc(1_700_000_000_000L, 0, "nodea")

    /** `AP8BgH8A_w` is `Base64Url.encode(byteArrayOf(0, -1, 1, -128, 127, 0, -1))`. */
    private val imageBase64 = "AP8BgH8A_w"
    private val thumbBase64 = "B_k"

    private fun record() = SyncRecord(
        type = RecordType.ATTACHMENT,
        uuid = "att-1",
        rowClock = clock,
        fieldClocks = emptyMap(),
        fields = mapOf(
            FieldClocks.NOTE_ID to FieldValue.of("n1"),
            FieldClocks.ANCHOR to FieldValue.of("3"),
            FieldClocks.ORDER to FieldValue.of("1"),
            FieldClocks.IMAGE to FieldValue.of(imageBase64, "image/webp", "800", "600"),
            FieldClocks.THUMB to FieldValue.of(thumbBase64, "40", "30"),
            FieldClocks.UPDATED_AT to FieldValue.of("1000"),
            FieldClocks.DELETED to FieldValue.of("0", null),
        ),
    )

    private fun corrupted(column: String, value: String?): RecordPayload {
        val payload = SyncRecords.toPayload(record(), createdAt = 100L, meta = "carry-me")
        return payload.copy(fields = payload.fields + (column to value))
    }

    // -- the two vocabularies --------------------------------------------------------------------

    @Test
    fun `an attachment's clocked fields and payload columns line up`() {
        assertEquals(
            setOf("noteId", "anchor", "order", "image", "thumb", "updatedAt", "deleted"),
            RecordType.ATTACHMENT.fields,
        )
        // Fifteen columns, frozen. `image` is four of them and `thumb` three; `createdAt` and
        // `meta` are the two that carry no clock at all.
        assertEquals(
            setOf(
                "noteId", "anchor", "order", "bytes", "mimeType", "width", "height",
                "thumbBytes", "thumbWidth", "thumbHeight", "meta",
                "createdAt", "updatedAt", "isDeleted", "deletedAt",
            ),
            PayloadFields.columnsOf(RecordType.ATTACHMENT),
        )
    }

    @Test
    fun `every clocked attachment field maps to columns`() {
        // A field with no column mapping makes SyncRecords.fromPayload return null for every
        // attachment ever sent -- silently, since a null there is "a record to skip".
        RecordType.ATTACHMENT.fields.forEach { field ->
            assertNotNull("no column mapping for this field", SyncRecords.columnsFor(field))
        }
    }

    // -- the round trip on the wire codec --------------------------------------------------------

    @Test
    fun `an attachment survives the payload round trip on the wire codec`() {
        val payload = SyncRecords.toPayload(record(), createdAt = 100L, meta = "carry-me")

        assertEquals(record().normalized(), SyncRecords.fromPayload(payload))
        // The unclocked pair is on the payload and not on the record, which is the whole shape of
        // this conversion. `meta` reaches the store beside the record, never inside it.
        assertEquals("100", payload.field(PayloadFields.CREATED_AT))
        assertEquals("carry-me", payload.field(PayloadFields.META))
        // Each half of `image` landed in its own column rather than being packed or reordered.
        assertEquals(imageBase64, payload.field(PayloadFields.BYTES))
        assertEquals("image/webp", payload.field(PayloadFields.MIME_TYPE))
        assertEquals("800", payload.field(PayloadFields.WIDTH))
        assertEquals("600", payload.field(PayloadFields.HEIGHT))
        assertEquals(thumbBase64, payload.field(PayloadFields.THUMB_BYTES))
    }

    // -- the guards, on the path that is actually used ------------------------------------------

    /**
     * Undecodable base64 in `bytes` is refused here, not defaulted.
     *
     * `RecordRows.toAttachmentEntity` falls back to `ByteArray(0)` when its own decode fails, and
     * that fallback is only unreachable because of this check. Delete the `BASE64_COLUMNS` loop and
     * the record is accepted, the row is written with no pixels, and a blank grey box appears in
     * the note on every device with nothing reporting a problem.
     */
    @Test
    fun `undecodable bytes refuse the record`() {
        assertNull(SyncRecords.fromPayload(corrupted(PayloadFields.BYTES, "not*base64")))
    }

    /** The same rule for the thumbnail, which is what a note's attachment rail renders. */
    @Test
    fun `undecodable thumbBytes refuse the record`() {
        assertNull(SyncRecords.fromPayload(corrupted(PayloadFields.THUMB_BYTES, "not*base64")))
    }

    /**
     * A JSON-null image column is refused too, and is a different case from undecodable text.
     *
     * `bytes` is not nullable in this schema and neither of this project's encoders can emit a null
     * one — but a peer can, and "absent" must not quietly become "an empty image". The nullable
     * numeric columns are skipped by the same loop, so this pins that the two are treated
     * differently on purpose.
     */
    @Test
    fun `a null bytes column refuses the record`() {
        assertNull(SyncRecords.fromPayload(corrupted(PayloadFields.BYTES, null)))
        assertNull(SyncRecords.fromPayload(corrupted(PayloadFields.THUMB_BYTES, null)))
    }

    /** The dimensions are numeric columns and get the same refusal an anchor does. */
    @Test
    fun `a non-numeric dimension refuses the record`() {
        assertNull(SyncRecords.fromPayload(corrupted(PayloadFields.WIDTH, "wide")))
        assertNull(SyncRecords.fromPayload(corrupted(PayloadFields.THUMB_HEIGHT, "tall")))
    }

    /** And `anchor`, the column that decides where the photograph sits in the note. */
    @Test
    fun `a non-numeric anchor refuses the record`() {
        assertNull(SyncRecords.fromPayload(corrupted(PayloadFields.ANCHOR, "top")))
    }

    /**
     * A note payload has no `bytes` column at all, and the base64 loop must skip it rather than
     * read its absence as a null and refuse every note on the account.
     */
    @Test
    fun `the base64 guard does not refuse a record type without image columns`() {
        val note = SyncRecord(
            type = RecordType.NOTE,
            uuid = "n1",
            rowClock = clock,
            fieldClocks = emptyMap(),
            fields = mapOf(
                FieldClocks.TITLE to FieldValue.of("t"),
                FieldClocks.CONTENT to FieldValue.of("body", "html"),
                FieldClocks.CHECKLIST to FieldValue.of(""),
                FieldClocks.PINNED to FieldValue.of("0"),
                FieldClocks.FAVORITE to FieldValue.of("0"),
                FieldClocks.FOLDER to FieldValue.of(null),
                FieldClocks.UPDATED_AT to FieldValue.of("1000"),
                FieldClocks.DELETED to FieldValue.of("0", null),
            ),
        )

        assertNotNull(SyncRecords.fromPayload(SyncRecords.toPayload(note, createdAt = 100L)))
    }
}
