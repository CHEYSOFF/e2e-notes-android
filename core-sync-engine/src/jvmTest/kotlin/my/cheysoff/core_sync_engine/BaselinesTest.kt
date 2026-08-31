package my.cheysoff.core_sync_engine

import my.cheysoff.core_domain.sync.FieldClocks
import my.cheysoff.core_domain.sync.FieldValue
import my.cheysoff.core_domain.sync.RecordType
import my.cheysoff.core_domain.sync.SyncRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The one piece of merge state the engine owns: where the `content` ancestor is. */
class BaselinesTest {

    @Test
    fun `the first agreement sets the baseline`() {
        val agreed = note(fieldClocks = mapOf(FieldClocks.CONTENT to hlc(5)), rowClock = hlc(9))

        assertEquals(hlc(5), Baselines.advance(previous = null, agreed = agreed))
    }

    /**
     * Monotonic, because a baseline marks a point in history below which this device's body is
     * certainly not a new edit. A baseline that went backwards would un-know an ancestor and start
     * writing conflict copies for edits that were never contested.
     *
     * Records genuinely do arrive out of order — that is what a pass that took a `409` looks like.
     */
    @Test
    fun `a baseline only ever moves forward`() {
        val older = note(fieldClocks = mapOf(FieldClocks.CONTENT to hlc(3)), rowClock = hlc(9))

        assertEquals(hlc(7), Baselines.advance(previous = hlc(7), agreed = older))
    }

    @Test
    fun `a newer agreement advances it`() {
        val newer = note(fieldClocks = mapOf(FieldClocks.CONTENT to hlc(11)), rowClock = hlc(11))

        assertEquals(hlc(11), Baselines.advance(previous = hlc(7), agreed = newer))
    }

    /** A field absent from the map is at the row clock, which is where a fresh record's body is. */
    @Test
    fun `a record with no explicit content clock is at its row clock`() {
        assertEquals(hlc(4), Baselines.advance(previous = null, agreed = note(rowClock = hlc(4))))
    }

    /**
     * A folder has no body, so it never conflict-copies and a baseline for one would be a value
     * nothing reads.
     */
    @Test
    fun `a folder has no baseline`() {
        val folder = SyncRecord(
            type = RecordType.FOLDER,
            uuid = "f1",
            rowClock = hlc(9),
            fieldClocks = emptyMap(),
            fields = mapOf(
                FieldClocks.NAME to FieldValue.of("work"),
                FieldClocks.COLOR to FieldValue.of(null),
                FieldClocks.UPDATED_AT to FieldValue.of("0"),
                FieldClocks.DELETED to FieldValue.of("0", null),
            ),
        ).validate()

        assertNull(Baselines.advance(previous = hlc(1), agreed = folder))
    }
}
