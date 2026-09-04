package my.cheysoff.core_sync_codec

import my.cheysoff.core_domain.model.SketchData
import my.cheysoff.core_domain.sync.FieldClocks
import my.cheysoff.core_domain.sync.Hlc
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
            rowClock = Hlc(1_700_000_001_000, 0, "abcd"),
            clocks = emptyMap(),
            dirty = true,
            lastSyncedSeq = 0L,
        )

        val back = SketchRecords.fromPayload(SketchRecords.toPayload(row, createdAt = row.sketch.createdAt))

        assertEquals(row.sketch, back?.sketch)
    }

    /**
     * The acceptance criterion this class exists to satisfy: `toPayload` must carry the row's own
     * clock and per-field clocks onto the wire rather than minting `Hlc.ZERO`. A zero row clock sent
     * against a clean, previously-clocked remote row is `Merge.merge`'s `ROLLBACK_SUSPECTED` guard
     * (`!local.dirty && remote.rowClock < local.record.rowClock`) -- the sketch would be silently
     * dropped and never converge.
     */
    @Test
    fun `toPayload carries the row's real clocks, not the zero clock`() {
        val rowClock = Hlc(1_700_000_050_000, 3, "nodea")
        val fieldClocks = mapOf(
            FieldClocks.ANCHOR to Hlc(1_700_000_010_000, 0, "nodea"),
            FieldClocks.STROKES to Hlc(1_700_000_050_000, 3, "nodea"),
        )
        val row = SketchRow(
            sketch = sketch(),
            rowClock = rowClock,
            clocks = fieldClocks,
            dirty = true,
            lastSyncedSeq = 0L,
        )

        val payload = SketchRecords.toPayload(row, createdAt = row.sketch.createdAt)

        assertEquals("the row clock must not be minted as zero", rowClock, payload.rowClock)
        assertEquals("the per-field clocks must cross the wire, not an empty map", fieldClocks, payload.clocks)
    }

    /**
     * `anchor` and `strokes` are independently editable -- see `SketchEntity`'s KDoc -- so a payload
     * carrying only one clock for both would let a later text-reflow re-stamp of `anchor` silently
     * discard a concurrent drawing edit on `strokes`. This pins that they survive as two distinct
     * entries, not one collapsed clock.
     */
    @Test
    fun `anchor and strokes keep independent clocks across the round trip`() {
        val anchorClock = Hlc(1_700_000_010_000, 0, "nodea")
        val strokesClock = Hlc(1_700_000_099_000, 1, "nodea")
        val row = SketchRow(
            sketch = sketch(),
            rowClock = strokesClock,
            clocks = mapOf(FieldClocks.ANCHOR to anchorClock),
            dirty = true,
            lastSyncedSeq = 0L,
        )

        val payload = SketchRecords.toPayload(row, createdAt = row.sketch.createdAt)

        assertEquals(anchorClock, payload.clocks[FieldClocks.ANCHOR])
        // strokes is absent from the map on purpose -- it sits at the row clock, and an absent
        // entry falls back to it; see NoteRecords' own KDoc for the same convention.
        assertNull(payload.clocks[FieldClocks.STROKES])
    }

    @Test
    fun `an unparseable anchor refuses the record rather than defaulting`() {
        // Substituting 0 would silently move someone's drawing to the top of the note, on every
        // device, with nothing anywhere saying why.
        val payload = payloadWith(anchor = "not-a-number")
        assertNull(SketchRecords.fromPayload(payload))
    }

    private fun sketch(
        id: String = "s1",
        noteId: String = "n1",
        anchor: Int = 0,
        order: Int = 0,
    ) = SketchData(
        id = id,
        noteId = noteId,
        anchor = anchor,
        order = order,
        strokes = "1|3277x4096|ff2c1ab0,24:0,0;120,40",
        createdAt = 1_700_000_000_000,
        updatedAt = 1_700_000_001_000,
    )

    /** A well-formed sketch payload, with [anchor] substituted for the column's real value. */
    private fun payloadWith(anchor: String): RecordPayload {
        val row = SketchRow(
            sketch = sketch(),
            rowClock = Hlc.ZERO,
            clocks = emptyMap(),
            dirty = false,
            lastSyncedSeq = 0L,
        )
        val valid = SketchRecords.toPayload(row, createdAt = row.sketch.createdAt)
        return valid.copy(fields = valid.fields + (PayloadFields.ANCHOR to anchor))
    }
}
