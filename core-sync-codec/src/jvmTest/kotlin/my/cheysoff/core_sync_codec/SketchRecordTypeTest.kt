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
 * The two vocabularies a sketch record has to agree between -- `RecordType.SKETCH.fields` (what
 * [Merge] clocks) and `PayloadFields.columnsOf(RecordType.SKETCH)` (what crosses the wire) -- and
 * the mapping ([SyncRecords.columnsFor]) that connects every field in the first set to at least one
 * column in the second.
 *
 * This mirrors `RecordPayloadCodecTest`'s and `SyncRecords`'s own `init` check for NOTE and FOLDER,
 * pinned here for SKETCH specifically because those existing checks only run over
 * `RecordType.entries` as a whole and would report a coverage gap without ever naming which type
 * or field it was in.
 */
class SketchRecordTypeTest {

    @Test
    fun `a sketch's clocked fields and payload columns line up`() {
        // The two vocabularies differ on purpose -- `deleted` is one clock over two columns -- and
        // this pins that the difference is only ever that one.
        assertEquals(
            setOf("noteId", "anchor", "order", "strokes", "updatedAt", "deleted"),
            RecordType.SKETCH.fields,
        )
        assertEquals(
            setOf("noteId", "anchor", "order", "strokes", "createdAt", "updatedAt", "isDeleted", "deletedAt"),
            PayloadFields.columnsOf(RecordType.SKETCH),
        )
    }

    @Test
    fun `every clocked sketch field maps to columns`() {
        // A field with no column mapping makes SyncRecords.fromPayload return null for every
        // sketch ever sent -- silently, since a null there is "a record to skip".
        RecordType.SKETCH.fields.forEach { field ->
            assertNotNull("no column mapping for '$field'", SyncRecords.columnsFor(field))
        }
    }

    /**
     * `SketchRecordsTest` pins this rule on the desktop path; this is the same rule on the
     * `SyncRecords` path the phone uses. Without `anchor` in `NUMERIC_COLUMNS`, a non-numeric
     * anchor passes this boundary check and `RecordRows.toSketchEntity` silently substitutes 0 --
     * moving the drawing to the top of the note, on this device only, with nothing anywhere saying
     * why.
     */
    @Test
    fun `a non-numeric anchor refuses the record rather than defaulting`() {
        val record = SyncRecord(
            type = RecordType.SKETCH,
            uuid = "s1",
            rowClock = Hlc(1_700_000_000_000L, 0, "nodea"),
            fieldClocks = emptyMap(),
            fields = mapOf(
                FieldClocks.NOTE_ID to FieldValue.of("n1"),
                FieldClocks.ANCHOR to FieldValue.of("2"),
                FieldClocks.ORDER to FieldValue.of("0"),
                FieldClocks.STROKES to FieldValue.of(""),
                FieldClocks.UPDATED_AT to FieldValue.of("100"),
                FieldClocks.DELETED to FieldValue.of("0", null),
            ),
        )
        val payload = SyncRecords.toPayload(record, createdAt = 100L)
        val corrupted = payload.copy(fields = payload.fields + (PayloadFields.ANCHOR to "not-a-number"))

        assertNull(SyncRecords.fromPayload(corrupted))
    }

    /** The sibling check: `order` gets the same refusal, for the same reason. */
    @Test
    fun `a non-numeric order refuses the record rather than defaulting`() {
        val record = SyncRecord(
            type = RecordType.SKETCH,
            uuid = "s1",
            rowClock = Hlc(1_700_000_000_000L, 0, "nodea"),
            fieldClocks = emptyMap(),
            fields = mapOf(
                FieldClocks.NOTE_ID to FieldValue.of("n1"),
                FieldClocks.ANCHOR to FieldValue.of("2"),
                FieldClocks.ORDER to FieldValue.of("0"),
                FieldClocks.STROKES to FieldValue.of(""),
                FieldClocks.UPDATED_AT to FieldValue.of("100"),
                FieldClocks.DELETED to FieldValue.of("0", null),
            ),
        )
        val payload = SyncRecords.toPayload(record, createdAt = 100L)
        val corrupted = payload.copy(fields = payload.fields + (PayloadFields.ORDER to "not-a-number"))

        assertNull(SyncRecords.fromPayload(corrupted))
    }
}
