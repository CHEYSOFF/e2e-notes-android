package my.cheysoff.desktop.sync

import kotlinx.coroutines.test.runTest
import my.cheysoff.core_crypto.sync.AccountRootKey
import my.cheysoff.core_domain.sync.FieldClocks
import my.cheysoff.core_domain.sync.FieldValue
import my.cheysoff.core_domain.sync.Hlc
import my.cheysoff.core_domain.sync.RecordType
import my.cheysoff.core_domain.sync.SyncRecord
import my.cheysoff.core_domain.sync.SyncValues
import my.cheysoff.core_sync_codec.RecordCodec
import my.cheysoff.core_sync_engine.MergedWrite
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

    // -- applyMerged ------------------------------------------------------------------------------

    /**
     * A second merged write over a row this store already holds.
     *
     * The interesting part is the row that is already there: `put` reads the stored envelope back
     * to recover the columns the merge does not model (`createdAt`, and now `meta`), and a note's
     * payload has no `meta` column at all. Reading it with `RecordPayload.field` -- which requires
     * the column to belong to the type -- throws, so this is the regression pin on indexing the
     * map instead. Nothing else in the suite reaches `applyMerged` twice for one record.
     */
    @Test
    fun `a merged write over an existing note row does not fail on the absent meta column`() = runTest {
        syncStore.applyMerged(merged(note(clock = Hlc(1_000L, 0, "nodea"), body = "first")))
        syncStore.applyMerged(merged(note(clock = Hlc(2_000L, 0, "nodea"), body = "second")))

        val stored = syncStore.load(RecordType.NOTE, "n1")!!
        assertEquals(FieldValue.of("second", "html"), stored.record.valueOf(FieldClocks.CONTENT))
    }

    /**
     * `meta` is opaque to this build and must survive a merged write that carries none.
     *
     * A newer build writes a caption into `meta`; this one pulls the record, merges it, and re-seals
     * it. Nothing in `SyncRecord` models the column, so without the fallback to the stored value
     * the re-seal would write `""` -- and the next push would hand that empty string to every other
     * device, deleting the caption account-wide from a build that never knew it existed.
     */
    @Test
    fun `a merged write with no incoming meta keeps the stored one`() = runTest {
        val caption = "{\"caption\":\"written by a newer build\"}"
        syncStore.applyMerged(
            merged(attachment(Hlc(1_000L, 0, "nodea")), remoteMeta = caption),
        )

        // The merge that follows carries no `meta` -- a push conflict's inline version, say.
        syncStore.applyMerged(
            merged(attachment(Hlc(2_000L, 0, "nodea")), remoteMeta = null),
        )

        assertEquals(caption, syncStore.metaOf(RecordType.ATTACHMENT, "att-1"))
    }

    private fun merged(record: SyncRecord, remoteMeta: String? = null) = MergedWrite(
        record = record,
        dirty = false,
        seq = 1L,
        contentBaseline = null,
        conflictCopy = null,
        remoteCreatedAt = 50L,
        remoteMeta = remoteMeta,
    )

    private fun note(clock: Hlc, body: String) = SyncRecord(
        type = RecordType.NOTE,
        uuid = "n1",
        rowClock = clock,
        fieldClocks = emptyMap(),
        fields = mapOf(
            FieldClocks.TITLE to FieldValue.of("t"),
            FieldClocks.CONTENT to FieldValue.of(body, "html"),
            FieldClocks.CHECKLIST to FieldValue.of(""),
            FieldClocks.PINNED to FieldValue.of(SyncValues.FALSE),
            FieldClocks.FAVORITE to FieldValue.of(SyncValues.FALSE),
            FieldClocks.FOLDER to FieldValue.of(null),
            FieldClocks.UPDATED_AT to FieldValue.of("100"),
            FieldClocks.DELETED to FieldValue.of(SyncValues.FALSE, null),
        ),
    )

    private fun attachment(clock: Hlc) = SyncRecord(
        type = RecordType.ATTACHMENT,
        uuid = "att-1",
        rowClock = clock,
        fieldClocks = emptyMap(),
        fields = mapOf(
            FieldClocks.NOTE_ID to FieldValue.of("n1"),
            FieldClocks.ANCHOR to FieldValue.of("0"),
            FieldClocks.ORDER to FieldValue.of("0"),
            FieldClocks.IMAGE to FieldValue.of("AAEC", "image/webp", "2", "2"),
            FieldClocks.THUMB to FieldValue.of("AAEC", "1", "1"),
            FieldClocks.UPDATED_AT to FieldValue.of("100"),
            FieldClocks.DELETED to FieldValue.of(SyncValues.FALSE, null),
        ),
    )
}
