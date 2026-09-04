package my.cheysoff.core_sync_engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SyncEngine.DATA_VERSION] gets bumped in the same commit as every new record type.
 *
 * Not decoration. A device that pulls sketches while still at generation 1 skips them (the
 * `RecordFault.UNKNOWN_TYPE` branch `SyncEngine.pull` documents), and without a bump it would
 * never re-baseline to pick them up -- the exact hole PR #101 exists to close.
 */
class DataVersionTest {

    @Test
    fun `the data generation was bumped with the sketch record type`() {
        assertEquals(2, SyncEngine.DATA_VERSION)
    }
}
