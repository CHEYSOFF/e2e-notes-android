package my.cheysoff.desktop.store

import my.cheysoff.core_sync_engine.HaltReason
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The halt, as it is actually stored on this platform — and clearing it.
 *
 * Clearing is what turns a halt from terminal into recoverable. It repairs nothing: every reason is
 * a condition the engine cannot fix, so the next pass generally finds the same thing and stops
 * again (asserted in `SyncEngineTest`). What it buys is that a person who dealt with the cause is
 * not left with a dead end whose only exit is a reinstall.
 */
class RecordStoreHaltTest {

    @get:Rule val folder = TemporaryFolder()

    private lateinit var store: RecordStore
    private val account = "account-under-test"

    @Before
    fun setUp() {
        store = RecordStore.open(folder.newFolder("vault").toPath().resolve("records.db"))
    }

    @After
    fun tearDown() {
        store.close()
    }

    @Test
    fun `a recorded halt is readable and clearable`() {
        assertNull(store.halt(account))

        store.recordHalt(account, HaltReason.SERVER_ROLLED_BACK.name)
        assertEquals(HaltReason.SERVER_ROLLED_BACK.name, store.halt(account))

        store.clearHalt(account)
        assertNull("a cleared halt reads as healthy, which is what lets a pass run", store.halt(account))
    }

    /**
     * Clearing must not create the row.
     *
     * A device with no `sync_state` row has never pulled and cannot be halted, so an upsert here
     * would invent a cursor of 0 for an account this device knows nothing about — and a cursor of 0
     * is not inert, it is the value that means "before the first pull".
     */
    @Test
    fun `clearing a halt that was never recorded creates nothing`() {
        store.clearHalt(account)

        assertNull(store.halt(account))
        assertEquals(
            "clearing must not have invented a sync_state row",
            0L,
            store.cursor("an-account-that-never-synced"),
        )
        assertEquals(0L, store.cursor(account))
    }

    @Test
    fun `a halt can be recorded again after being cleared`() {
        store.recordHalt(account, HaltReason.SERVER_ROLLED_BACK.name)
        store.clearHalt(account)

        store.recordHalt(account, HaltReason.DEVICE_REVOKED.name)

        assertEquals(
            "the first-reason guard is scoped to a live halt, not to all of history",
            HaltReason.DEVICE_REVOKED.name,
            store.halt(account),
        )
    }

    @Test
    fun `clearing one account's halt leaves another's alone`() {
        val other = "a-different-account"
        store.recordHalt(account, HaltReason.SERVER_ROLLED_BACK.name)
        store.recordHalt(other, HaltReason.DEVICE_REVOKED.name)

        store.clearHalt(account)

        assertNull(store.halt(account))
        assertEquals(HaltReason.DEVICE_REVOKED.name, store.halt(other))
    }

    @Test
    fun `clearing a halt does not disturb the cursor`() {
        store.saveCursor(account, 12L)
        store.recordHalt(account, HaltReason.SERVER_ROLLED_BACK.name)

        store.clearHalt(account)

        assertEquals(12L, store.cursor(account))
    }
}
