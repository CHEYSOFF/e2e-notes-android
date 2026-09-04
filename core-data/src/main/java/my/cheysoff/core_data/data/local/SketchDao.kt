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
     * Writes a sketch **in full**, sync columns included — the only statement that writes this
     * table. `@Upsert` rather than `@Insert(onConflict = REPLACE)` for the same reason
     * `NoteDao.applyRemoteNote` gives: REPLACE deletes and reinserts, which would not matter for a
     * row with no other table pointing at it, but would still discard whatever the caller did not
     * think to pass — @Upsert updates the existing row in place instead. The caller (currently only
     * `RoomSketchesRepository`) owns every column, including the clocks.
     */
    @Upsert
    suspend fun upsertSketch(sketch: SketchEntity)
}
