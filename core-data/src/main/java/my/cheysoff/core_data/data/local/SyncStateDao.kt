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
     * Moves the cursor to [seq] — **forwards only**, in one statement.
     *
     * The monotonicity is in the `CASE`, not in the caller. §8's F7 is the reason: a cursor that
     * went backwards would re-deliver the whole account, and against rows that are already clean
     * that is indistinguishable from "this account is empty" — the next pass would read a full
     * library as a mass delete. Making it structurally impossible costs one `CASE` and removes an
     * entire class of catastrophe from every future caller, including the ones not written yet.
     *
     * A re-baseline still exists; it is [forget], which drops the row rather than lowering a value
     * in it, so the difference between "start again" and "we are at zero" stays visible.
     *
     * `INSERT … ON CONFLICT` rather than `@Upsert` so the guard and the insert are the same
     * statement: an `@Upsert` of a whole entity would need a read first, and the read-then-write
     * pair is where a lowered cursor would slip in.
     */
    @Query(
        """
        INSERT INTO sync_state (accountId, cursor, lastPullAt, haltReason)
        VALUES (:accountId, :seq, :nowMs, '')
        ON CONFLICT(accountId) DO UPDATE SET
            cursor = CASE WHEN sync_state.cursor < excluded.cursor
                          THEN excluded.cursor ELSE sync_state.cursor END,
            lastPullAt = excluded.lastPullAt
        """
    )
    suspend fun advanceCursor(accountId: String, seq: Long, nowMs: Long)

    /**
     * Records that the engine has halted, keeping the **first** reason.
     *
     * The first one is the one that explains the rest: a rolled-back server produces a cascade of
     * record-level rejections, and overwriting the reason with the last of them would report the
     * symptom. `haltReason = ''` is the healthy state, so the guard is "only if empty".
     */
    @Query(
        """
        INSERT INTO sync_state (accountId, cursor, lastPullAt, haltReason)
        VALUES (:accountId, 0, 0, :reason)
        ON CONFLICT(accountId) DO UPDATE SET
            haltReason = CASE WHEN sync_state.haltReason = '' THEN excluded.haltReason
                              ELSE sync_state.haltReason END
        """
    )
    suspend fun recordHalt(accountId: String, reason: String)

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
