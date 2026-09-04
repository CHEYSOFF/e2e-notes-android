package my.cheysoff.core_sync_codec

import my.cheysoff.core_domain.sync.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
}
