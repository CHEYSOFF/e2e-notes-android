package my.cheysoff.core_data.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/**
 * Reads and writes the single [SyncStateEntity] row for an account.
 *
 * Deliberately tiny. Everything that decides *when* to move the cursor — pull ordering, the
 * `409 cursor_ahead_of_server` halt, the refusal to reset a cursor to 0 — belongs to the sync
 * engine, and none of it is expressible as SQL. This DAO only stores the number.
 */
@Dao
interface SyncStateDao {

    /** The row for [accountId], or null if this device has never pulled on that account. */
    @Query("SELECT * FROM sync_state WHERE accountId = :accountId")
    suspend fun get(accountId: String): SyncStateEntity?

    /**
     * Creates or replaces the row.
     *
     * `@Upsert`, not `@Insert(REPLACE)`. REPLACE is a DELETE followed by an INSERT — the same trap
     * that cost `folders` its `createdAt` before v6 and the reason `NoteDao.insertNote` was
     * deleted in v7. This table has nothing to lose to it today, but it would the moment a column
     * is added, and the next person to add one should not have to notice.
     */
    @Upsert
    suspend fun upsert(state: SyncStateEntity)

    /**
     * Forgets everything about an account's sync position.
     *
     * The re-baseline path: after a server rollback, the engine halts and the user is asked to
     * start again, which means dropping this row rather than quietly setting `cursor = 0`. The
     * difference is not cosmetic — a cursor silently reset to 0 while rows are still `dirty = 0`
     * is indistinguishable from "this account is empty", and the next pass would read a full
     * library as a mass delete.
     */
    @Query("DELETE FROM sync_state WHERE accountId = :accountId")
    suspend fun forget(accountId: String)
}
