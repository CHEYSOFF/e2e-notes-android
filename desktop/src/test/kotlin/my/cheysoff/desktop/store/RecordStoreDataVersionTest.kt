package my.cheysoff.desktop.store

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordStoreDataVersionTest {

    @get:Rule val folder = TemporaryFolder()

    private lateinit var store: RecordStore
    private val account = "account-under-test"

    @Before fun setUp() {
        store = RecordStore.open(folder.newFolder("vault").toPath().resolve("records.db"))
    }

    @After fun tearDown() = store.close()

    @Test
    fun `a data version round-trips`() {
        assertNull(store.dataVersion(account))
        // saveDataVersion is an UPDATE, never an upsert (see its doc), so the round trip needs a
        // row first -- i.e. a device that has completed a pull, which is exactly when the engine
        // calls it in production.
        store.saveCursor(account, 5L)
        store.saveDataVersion(account, 2)
        assertEquals(2, store.dataVersion(account))
    }

    /** Same rule as the phone's: recording a version must not invent a cursor. */
    @Test
    fun `saving a data version does not disturb the cursor`() {
        store.saveCursor(account, 12L)
        store.saveDataVersion(account, 2)
        assertEquals(12L, store.cursor(account))
    }

    /**
     * The re-baseline that Task 7 adds pulls from `since = 0`, so its committable seq starts low
     * and climbs back up past whatever the device already held. `saveCursor`'s `MAX(cursor,
     * excluded.cursor)` is what stops that pull from ever writing a cursor behind the one already
     * stored -- this pins that guard directly, independent of the engine that relies on it.
     */
    @Test
    fun `the cursor never moves backwards`() {
        store.saveCursor(account, 12L)
        store.saveCursor(account, 5L)
        assertEquals(12L, store.cursor(account))
    }

    /** A vault written before this column existed must open, not throw. */
    @Test
    fun `a store opened twice keeps its version`() {
        store.saveCursor(account, 5L)
        store.saveDataVersion(account, 2)
        store.close()
        store = RecordStore.open(folder.root.toPath().resolve("vault").resolve("records.db"))
        assertEquals(2, store.dataVersion(account))
    }

    /**
     * `saveDataVersion` is an UPDATE, never an upsert -- see its doc for why a missing row must
     * stay missing. This checks for the row itself, not just what `dataVersion` reads back: a row
     * that genuinely existed with `data_version = NULL` would read back identically through
     * `dataVersion` alone, so only [RecordStore.hasSyncStateRow] can tell the two apart.
     */
    @Test
    fun `saving a data version against a missing row writes nothing and creates no row`() {
        store.saveDataVersion(account, 2)

        assertFalse(
            "an UPDATE against a missing row must not fabricate one",
            store.hasSyncStateRow(account),
        )
    }
}
