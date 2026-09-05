package my.cheysoff.core_data.data.sync

import my.cheysoff.core_data.data.local.NoteDatabase
import my.cheysoff.core_domain.sync.RecordType
import my.cheysoff.core_sync_engine.ClockObserver
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds a [RoomSyncStore] for one account, and answers the two questions about rows that the
 * `SyncStore` interface has no business carrying.
 *
 * ## Why the app asks for this and not for the database
 *
 * `:app` owns the sync *session* — the account keys, the HTTP client, the enrolment — and needs a
 * store bound to the account it just derived. It does not need Room, and giving it Room would put
 * `androidx.room` on the classpath of the module that also holds the UI graph, for the sake of two
 * lines. This is the whole of what it needs instead, and `NoteDatabase` stays behind the module
 * that owns it.
 *
 * Injected as a `dagger.Lazy` by its caller, because constructing it opens the encrypted database
 * and that is only possible after an unlock.
 *
 * ## [syncClock] is the same instance `DefaultSyncController` observes its engine with
 *
 * `SyncClock` is a `@Singleton`, so this and `DefaultSyncController` share one generator process
 * -wide. [RoomSyncStore] mints a clock of its own in exactly one place — reconciling a note's
 * sketches when its tombstone arrives — and that clock has to reach the same generator
 * `DefaultSyncController` feeds from `SyncEngine`'s remote clocks, or a local write minted after a
 * pass could go below a tombstone this device just wrote. See [RoomSyncStore]'s `clockObserver`
 * parameter.
 */
@Singleton
class SyncStoreFactory @Inject constructor(
    private val database: NoteDatabase,
    private val syncClock: SyncClock,
) {

    /** The store for [accountId]. Cheap; a new one per pass is the intended shape. */
    fun create(accountId: String): RoomSyncStore = RoomSyncStore(
        database = database,
        noteDao = database.noteDao,
        folderDao = database.folderDao,
        sketchDao = database.sketchDao,
        attachmentDao = database.attachmentDao,
        syncStateDao = database.syncStateDao,
        accountId = accountId,
        clockObserver = ClockObserver { syncClock.observe(it) },
    )

    /**
     * A row's `createdAt`, for the push side of the codec.
     *
     * `SyncRecord` does not carry `createdAt` — `FieldClocks` does not clock it — but the payload
     * format does, and the desktop's decoder refuses a payload without it. So the value has to come
     * from the row rather than from the record, and this is the narrowest way to ask for it.
     */
    suspend fun createdAtOf(type: RecordType, uuid: String): Long? = when (type) {
        RecordType.NOTE -> database.noteDao.noteRow(uuid)?.createdAt
        RecordType.FOLDER -> database.folderDao.folderRow(uuid)?.createdAt
        RecordType.SKETCH -> database.sketchDao.sketchRow(uuid)?.createdAt
        RecordType.ATTACHMENT -> database.attachmentDao.attachmentRow(uuid)?.createdAt
    }

    /**
     * A row's `meta`, for the push side of the codec. Empty for every record type that has no such
     * column, which is every type but `ATTACHMENT`.
     *
     * The same shape as [createdAtOf] and for the same structural reason -- `SyncRecord` does not
     * model the column, the payload has it, so the value has to come off the row -- but a different
     * substantive one. `meta` is a reserved opaque escape hatch (`PayloadFields.META`) that this
     * build only ever writes `""` into, and asking the row rather than writing `""` here is what
     * stops this build from erasing a caption a newer build put there, on every record it pushes.
     *
     * Reads the full row for what is a small string, which is the one place in this class that
     * costs something: `attachmentRow` selects `bytes` too, so pushing one attachment reads its
     * megabyte twice (once here, once through `dirtyRecords`). Acceptable at eight rows a pass
     * (`DIRTY_ATTACHMENT_PAGE`); if that page ever grows, this wants its own projection.
     */
    suspend fun metaOf(type: RecordType, uuid: String): String = when (type) {
        RecordType.NOTE, RecordType.FOLDER, RecordType.SKETCH -> ""
        RecordType.ATTACHMENT -> database.attachmentDao.attachmentRow(uuid)?.meta.orEmpty()
    }

    /** The pre-first-pull snapshot. See [SyncSnapshot] for when it does anything and why. */
    fun takeSnapshotOnce(accountId: String) {
        SyncSnapshot(database).takeOnce(accountId)
    }
}
