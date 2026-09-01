package my.cheysoff.core_data.data.sync

import my.cheysoff.core_data.data.local.NoteDatabase
import my.cheysoff.core_domain.sync.RecordType
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
 */
@Singleton
class SyncStoreFactory @Inject constructor(private val database: NoteDatabase) {

    /** The store for [accountId]. Cheap; a new one per pass is the intended shape. */
    fun create(accountId: String): RoomSyncStore = RoomSyncStore(
        database = database,
        noteDao = database.noteDao,
        folderDao = database.folderDao,
        syncStateDao = database.syncStateDao,
        accountId = accountId,
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
    }

    /** The pre-first-pull snapshot. See [SyncSnapshot] for when it does anything and why. */
    fun takeSnapshotOnce(accountId: String) {
        SyncSnapshot(database).takeOnce(accountId)
    }
}
