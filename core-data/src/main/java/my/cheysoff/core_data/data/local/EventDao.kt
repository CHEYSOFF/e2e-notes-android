package my.cheysoff.core_data.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes for the `events` table, mirroring [AttachmentDao] method for method.
 *
 * The one structural difference is that **nothing here is scoped to a note**. An event is not a
 * child of one, so there is no "events for this note" reconciliation and no tombstone cascade to
 * take part in; [eventsForNote] exists only so a note can show what was written beside it, and
 * deleting that note does not touch these rows.
 *
 * Every read filters `isDeleted = 0`. Tombstones are the sync engine's business and are reached
 * through [eventRow] and [dirtyEvents], never through a screen.
 */
/**
 * How many dirty events one push pass reads. Larger than the attachment page because an event row
 * is a few hundred bytes rather than a mebibyte, and small enough that the read stays bounded.
 */
internal const val DIRTY_EVENT_PAGE = 64

@Dao
interface EventDao {

    /** Every visible event, soonest first. Ties break on `uuid` so two devices agree on the order. */
    @Query("SELECT * FROM events WHERE isDeleted = 0 ORDER BY startAt ASC, uuid ASC")
    fun events(): Flow<List<EventEntity>>

    /**
     * Visible events starting inside `[fromMillis, toMillis)`.
     *
     * A half-open window on `startAt`, which is what the index is for. Note the consequence: an
     * event that began before the window and runs into it is **not** returned. Every caller today
     * asks about days or months an event starts in, and widening this to overlap would need
     * `endAt` in the predicate and a second index; when something needs it, that is the change.
     */
    @Query(
        "SELECT * FROM events WHERE isDeleted = 0 AND startAt >= :fromMillis AND startAt < :toMillis " +
            "ORDER BY startAt ASC, uuid ASC"
    )
    fun eventsBetween(fromMillis: Long, toMillis: Long): Flow<List<EventEntity>>

    /** Visible events written beside [noteId]. Deleting that note does not touch them. */
    @Query("SELECT * FROM events WHERE isDeleted = 0 AND linkedNoteId = :noteId ORDER BY startAt ASC, uuid ASC")
    fun eventsForNote(noteId: String): Flow<List<EventEntity>>

    /** The whole row, tombstones included. The sync store's read. */
    @Query("SELECT * FROM events WHERE uuid = :uuid")
    suspend fun eventRow(uuid: String): EventEntity?

    /** This row's clock without its columns. See `AttachmentDao.rowClock`. */
    @Query("SELECT hlcMs, hlcCounter, hlcNode, fieldHlc FROM events WHERE uuid = :uuid")
    suspend fun rowClock(uuid: String): RowClock?

    /**
     * The highest clock any event row holds, for seeding the generator at startup so a new local
     * write can never be minted below one this device already accepted.
     */
    @Query("SELECT hlcMs, hlcCounter, hlcNode, fieldHlc FROM events ORDER BY hlcMs DESC, hlcCounter DESC LIMIT 1")
    suspend fun highestRowClock(): RowClock?

    /**
     * Insert or replace in full. `@Upsert` rather than `@Insert(REPLACE)` for the reason
     * `AttachmentDao.upsertAttachment` gives: REPLACE is a delete plus an insert, and the caller
     * owns every column here anyway.
     */
    @Upsert
    suspend fun upsertEvent(event: EventEntity)

    /**
     * Up to [limit] rows waiting to be pushed, oldest clock first.
     *
     * Bounded like `dirtyAttachments` and unlike `dirtyNotes`: these rows are small, but a page of
     * them is still built in memory, and the ordering makes the bound deterministic rather than
     * "whichever rows SQLite felt like".
     */
    @Query("SELECT * FROM events WHERE dirty = 1 ORDER BY hlcMs ASC, hlcCounter ASC, hlcNode ASC, uuid ASC LIMIT :limit")
    suspend fun dirtyEvents(limit: Int): List<EventEntity>

    /**
     * Records that the server holds this row, clearing `dirty` **only** if the row still carries
     * the clock that was sealed. A local edit that landed mid-flight leaves it dirty, so the next
     * pass sends it rather than dropping it.
     */
    @Query(
        """
        UPDATE events SET
            lastSyncedSeq = :seq,
            dirty = CASE
                WHEN hlcMs = :sealedMs AND hlcCounter = :sealedCounter AND hlcNode = :sealedNode
                THEN 0 ELSE dirty END
        WHERE uuid = :uuid
        """
    )
    suspend fun acknowledgeEventPush(
        uuid: String,
        seq: Long,
        sealedMs: Long,
        sealedCounter: Int,
        sealedNode: String,
    )

    /** The seq, and nothing else. See `NoteDao.recordNoteSeen`. */
    @Query("UPDATE events SET lastSyncedSeq = :seq WHERE uuid = :uuid")
    suspend fun recordEventSeen(uuid: String, seq: Long)

    /**
     * Tombstones one event, with its own fresh clock and `dirty` set.
     *
     * `AND isDeleted = 0` so deleting twice is a no-op rather than a second write that moves
     * `deletedAt` and mints a clock for a change that did not happen.
     */
    @Query(
        "UPDATE events SET isDeleted = 1, deletedAt = :timestamp, " +
            "hlcMs = :hlcMs, hlcCounter = :hlcCounter, hlcNode = :hlcNode, fieldHlc = :fieldHlc, dirty = 1 " +
            "WHERE uuid = :uuid AND isDeleted = 0"
    )
    suspend fun softDeleteEvent(
        uuid: String,
        timestamp: Long,
        hlcMs: Long,
        hlcCounter: Int,
        hlcNode: String,
        fieldHlc: String,
    )

    /**
     * Destroys tombstoned rows whose `deletedAt` is older than [threshold].
     *
     * Same retention as everything else in Trash, and the same one-directional caution: rows with
     * no stamp are left alone rather than guessed at. See `TrashPolicy`.
     */
    @Query("DELETE FROM events WHERE isDeleted = 1 AND deletedAt IS NOT NULL AND deletedAt < :threshold")
    suspend fun purgeEventsDeletedBefore(threshold: Long): Int
}
