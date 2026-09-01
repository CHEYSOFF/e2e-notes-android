package my.cheysoff.core_store

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import my.cheysoff.core_crypto.sync.AccountKeys
import my.cheysoff.core_crypto.sync.AccountRootKey
import my.cheysoff.core_domain.model.Folder
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.sync.HlcGenerator
import my.cheysoff.core_store.db.RecordDatabase

/**
 * A fresh in-memory record database, with the store and repository over it.
 *
 * ## Why this uses a real SQLite and not a fake
 *
 * Because the store's whole claim is about what is written to a database, and a fake would let that
 * claim be checked against the test's own idea of a database rather than against one.
 * `RecordStoreTest` reads the raw `records` rows and asserts on the bytes, which is only meaningful
 * if those bytes went through SQLite's BLOB handling.
 *
 * It is also what makes this module's coverage on this branch mean something: no Apple target can
 * be compiled here, so an in-memory JVM SQLite is where the store gets exercised. `sqlite-jdbc`'s
 * `:memory:` database lives and dies with the driver, so each fixture is genuinely fresh.
 *
 * ## The clock
 *
 * [tick] is a hand-cranked wall clock. Every `Hlc` this fixture mints comes from it, so tests can
 * put two writes in the same millisecond (which is where the HLC counter matters) or a second apart
 * (which is where `updatedAt` ordering matters) without sleeping.
 */
class StoreFixture(
    val dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
) {

    /** A fixed ARK: every derived key in a test is reproducible from this line. */
    val keys: AccountKeys = AccountRootKey.derive(
        ByteArray(32) { index -> index.toByte() }
    )

    val db: RecordDatabase = recordDatabase(IN_MEMORY)

    val store: RecordStore = RecordStore(db, keys, dispatcher)

    /** Wall-clock milliseconds, moved by hand. Starts at a value that is not zero or "now". */
    var wallMillis: Long = 1_700_000_000_000L

    val clock: HlcGenerator = HlcGenerator { "testnode00000001" }

    val repository: RecordNotesRepository =
        RecordNotesRepository(store, clock, now = { wallMillis })

    /** Moves the wall clock forward. */
    fun tick(millis: Long = 1_000L) {
        wallMillis += millis
    }

    fun note(
        id: String,
        title: String = "title-$id",
        content: String = "content-$id",
        folderId: String? = null,
        isPinned: Boolean = false,
        isFavorite: Boolean = false,
    ): Note = Note(
        id = id,
        title = title,
        content = content,
        folderId = folderId,
        isPinned = isPinned,
        isFavorite = isFavorite,
        createdAt = wallMillis,
        updatedAt = wallMillis,
    )

    fun folder(id: String, name: String = "folder-$id"): Folder = Folder(
        id = id,
        name = name,
        createdAt = wallMillis,
        updatedAt = wallMillis,
    )
}
