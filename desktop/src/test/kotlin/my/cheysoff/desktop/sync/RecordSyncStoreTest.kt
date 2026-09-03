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
 * One test: the regression pin for the bug fixed in `RoomSyncStore.dataVersion()`, where a device
 * that had pulled at least once but never had `saveDataVersion` called reported generation 0
 * instead of the current one. That bug lived in the phone's `?:`, which does not fire on the 0 its
 * `NOT NULL DEFAULT 0` column leaves behind -- `RecordSyncStore`'s `data_version` column has no
 * default and genuinely stays `NULL` after `saveCursor`, so its `?:` already fired correctly. This
 * test exists so the two platforms' agreement on `SyncStore`'s contract stays checked on both
 * sides, not just the one that had a bug.
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
    fun `a device that has pulled but never recorded a version reports the current generation`() = runTest {
        syncStore.saveCursor(12L)

        assertEquals(SyncEngine.DATA_VERSION, syncStore.dataVersion())
    }
}
