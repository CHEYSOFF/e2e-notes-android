package my.cheysoff.core_data.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * See `NoteDao`'s KDoc on the sync columns for the rules every write here follows: bump the row
 * clock and set `dirty = 1` on every write that changes anything, and let `RoomSketchesRepository`
 * — not SQL — decide what `fieldHlc` should say, because that needs the row's previous clocks.
 */
@Dao
interface SketchDao {

    /**
     * Sketches anchored under one note, visible ones only, in drawing order.
     *
     * `sortOrder ASC, uuid ASC`: [SketchData.order][my.cheysoff.core_domain.model.SketchData.order]
     * is only unique per anchor, by design (see that field's KDoc — ties break by id), so two
     * sketches can legitimately share a `sortOrder`. Without the `uuid` tie-break, SQLite leaves
     * their relative order unspecified, and two devices holding the same rows could each show them
     * in a different order forever, since neither view is wrong.
     */
    @Query("SELECT * FROM sketches WHERE noteId = :noteId AND isDeleted = 0 ORDER BY sortOrder ASC, uuid ASC")
    fun getSketchesByNoteId(noteId: String): Flow<List<SketchEntity>>

    /**
     * One row in full, tombstones included — the read a write path needs before it can decide what
     * changed. See `NoteDao.noteRow` for why a tombstone must not be filtered out here.
     */
    @Query("SELECT * FROM sketches WHERE uuid = :uuid")
    suspend fun sketchRow(uuid: String): SketchEntity?

    /** The sync columns of one row, or null if there is no such row. See [RowClock]. */
    @Query("SELECT hlcMs, hlcCounter, hlcNode, fieldHlc FROM sketches WHERE uuid = :uuid")
    suspend fun rowClock(uuid: String): RowClock?

    /**
     * The highest row clock across every sketch, or null if the table is empty — the durable seed
     * `RoomSketchesRepository` reads once per process. See `NoteDao.highestRowClock` for the full
     * argument; it applies here unchanged.
     */
    @Query("SELECT hlcMs, hlcCounter, hlcNode, fieldHlc FROM sketches ORDER BY hlcMs DESC, hlcCounter DESC LIMIT 1")
    suspend fun highestRowClock(): RowClock?

    /**
     * Writes a sketch **in full**, sync columns included — the path both a local save
     * ([RoomSketchesRepository][my.cheysoff.core_data.data.RoomSketchesRepository]) and a merged
     * remote record ([RoomSyncStore][my.cheysoff.core_data.data.sync.RoomSyncStore]) take. `@Upsert`
     * rather than `@Insert(onConflict = REPLACE)` for the same reason `NoteDao.applyRemoteNote`
     * gives: REPLACE deletes and reinserts, which would not matter for a row with no other table
     * pointing at it, but would still discard whatever the caller did not think to pass — @Upsert
     * updates the existing row in place instead. The caller owns every column, including the
     * clocks.
     *
     * Unlike notes and folders there is no separate "merged" write path here (no
     * `applyRemoteSketch`): a sketch has no editor-owned partial-update statement to keep distinct
     * from it, because nothing yet writes one field of a sketch without the others — the same is
     * true of [FolderDao.applyRemoteFolder], which is exactly this method's shape.
     */
    @Upsert
    suspend fun upsertSketch(sketch: SketchEntity)

    // ── What the sync engine reads and writes. The mirror of NoteDao's/FolderDao's block. ──────

    /** Every sketch the server has not acknowledged, oldest row clock first. */
    @Query("SELECT * FROM sketches WHERE dirty = 1 ORDER BY hlcMs ASC, hlcCounter ASC, hlcNode ASC, uuid ASC")
    suspend fun dirtySketches(): List<SketchEntity>

    /**
     * §3.2's two rules as one statement — see `NoteDao.acknowledgeNotePush` for the argument in
     * full.
     *
     * A sketch has no `contentSyncedHlc`: it can never produce a conflict copy (only notes have a
     * body worth preserving that way — see `RoomSyncStore.applyMerged`), so `Baselines.advance`
     * has nothing to advance for one, exactly as for a folder.
     */
    @Query(
        """
        UPDATE sketches SET
            lastSyncedSeq = :seq,
            dirty = CASE
                WHEN hlcMs = :sealedMs AND hlcCounter = :sealedCounter AND hlcNode = :sealedNode
                THEN 0 ELSE dirty END
        WHERE uuid = :uuid
        """
    )
    suspend fun acknowledgeSketchPush(
        uuid: String,
        seq: Long,
        sealedMs: Long,
        sealedCounter: Int,
        sealedNode: String,
    )

    /** `NoteDao.recordNoteSeen` for a sketch: the seq, and nothing else. */
    @Query("UPDATE sketches SET lastSyncedSeq = :seq WHERE uuid = :uuid")
    suspend fun recordSketchSeen(uuid: String, seq: Long)
}
