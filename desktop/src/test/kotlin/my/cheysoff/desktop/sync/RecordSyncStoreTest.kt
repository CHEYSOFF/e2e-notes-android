package my.cheysoff.desktop.sync

import kotlinx.coroutines.test.runTest
import my.cheysoff.core_crypto.sync.AccountRootKey
import my.cheysoff.core_sync_codec.RecordCodec
import my.cheysoff.core_sync_engine.SyncEngine
import my.cheysoff.desktop.store.RecordStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * `RecordSyncStore.dataVersion()` against a real [RecordStore].
 *
 * The final review's Finding 1 caught a second dead path here alongside the phone's: a device
 * that had pulled at least once but never had `saveDataVersion` called reported the *current*
 * generation rather than the genuinely-unrecorded one, because `store.dataVersion(accountId)`
 * returns `null` for two different states -- no row at all, and a row whose `data_version` column
 * is `NULL` -- and the old `?: SyncEngine.DATA_VERSION` could not tell them apart. That masked the
 * exact state `SyncEngine`'s generation write exists to correct, on every desktop account, always
 * -- this is "the device the whole release exists to protect", per the finding.
 *
 * `hasSyncStateRow` is what makes the distinction possible: a `null` column *with* a row means
 * "pulled, unrecorded, behind" (reads as `0`); `null` with *no* row means "never pulled" (reads as
 * current, since the next pull starts at 0 and fetches everything anyway).
 */
class RecordSyncStoreTest {

    private val keys = AccountRootKey.derive(AccountRootKey.generateArk())
    private val codec = RecordCodec(keys)
    private lateinit var store: RecordStore
    private lateinit var syncStore: RecordSyncStore
    private val account = "acct-1"

    @Before fun setUp() {
        store = RecordStore.inMemory("sync-store-${UUID.randomUUID()}")
        syncStore = RecordSyncStore(store, codec, account)
    }

    @After fun tearDown() = store.close()

    @Test
    fun `a device that has never pulled reports the current generation`() = runTest {
        assertEquals(SyncEngine.DATA_VERSION, syncStore.dataVersion())
    }

    @Test
    fun `a device that has pulled but never recorded a version reports zero, not the current generation`() = runTest {
        syncStore.saveCursor(12L)

        assertEquals(0, syncStore.dataVersion())
    }
}
