package my.cheysoff.core_sync_codec

import my.cheysoff.core_domain.model.SketchData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SketchRecordsTest {

    @Test
    fun `a sketch round-trips through a payload`() {
        val row = SketchRow(
            sketch = SketchData(
                id = "s1",
                noteId = "n1",
                anchor = 2,
                order = 0,
                strokes = "1|3277x4096|ff2c1ab0,24:0,0;120,40",
                createdAt = 1_700_000_000_000,
                updatedAt = 1_700_000_001_000,
                isDeleted = false,
                deletedAt = null,
            ),
            dirty = true,
            lastSyncedSeq = 0L,
        )

        val back = SketchRecords.fromPayload(SketchRecords.toPayload(row, createdAt = row.sketch.createdAt))

        assertEquals(row.sketch, back?.sketch)
    }

    @Test
    fun `an unparseable anchor refuses the record rather than defaulting`() {
        // Substituting 0 would silently move someone's drawing to the top of the note, on every
        // device, with nothing anywhere saying why.
        val payload = payloadWith(anchor = "not-a-number")
        assertNull(SketchRecords.fromPayload(payload))
    }

    /** A well-formed sketch payload, with [anchor] substituted for the column's real value. */
    private fun payloadWith(anchor: String): RecordPayload {
        val row = SketchRow(
            sketch = SketchData(
                id = "s1",
                noteId = "n1",
                anchor = 0,
                order = 0,
                strokes = "1|3277x4096|ff2c1ab0,24:0,0;120,40",
                createdAt = 1_700_000_000_000,
                updatedAt = 1_700_000_001_000,
            ),
            dirty = false,
            lastSyncedSeq = 0L,
        )
        val valid = SketchRecords.toPayload(row, createdAt = row.sketch.createdAt)
        return valid.copy(fields = valid.fields + (PayloadFields.ANCHOR to anchor))
    }
}
