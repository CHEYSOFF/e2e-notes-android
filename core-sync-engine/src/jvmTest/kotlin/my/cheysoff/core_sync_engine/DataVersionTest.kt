package my.cheysoff.core_sync_engine

import my.cheysoff.core_domain.sync.RecordType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SyncEngine.DATA_VERSION] gets bumped in the same commit as every new record type.
 *
 * Not decoration. A device that pulls sketches while still at generation 1 skips them (the
 * `RecordFault.UNKNOWN_TYPE` branch `SyncEngine.pull` documents), and without a bump it would
 * never re-baseline to pick them up -- the exact hole PR #101 exists to close.
 *
 * Written as "one more than the number of types that existed before the first" rather than as a
 * literal, so that adding a `RecordType` without touching the generation fails here rather than
 * shipping. The failure message names the number to write, and the fix is to bump the constant --
 * never to relax this assertion.
 */
class DataVersionTest {

    @Test
    fun `the data generation is bumped with every new record type`() {
        // NOTE and FOLDER shipped together at generation 1; SKETCH made it 2 and ATTACHMENT 3.
        val expected = RecordType.entries.size - 1
        assertEquals(
            "a new RecordType was added without bumping SyncEngine.DATA_VERSION -- every device " +
                "already at the old generation would skip the new type's records and never " +
                "re-baseline to collect them",
            expected,
            SyncEngine.DATA_VERSION,
        )
    }
}
