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

    // ── Deletion by reconciliation, not cascade. See SketchEntity's KDoc and Task 7. ────────────

    /**
     * Every live sketch anchored under [noteId] — the set `RoomNotesRepository.deleteNote` walks
     * to tombstone them one by one, each with its own clock bump, in the same transaction as the
     * note's own tombstone. Full rows, not just ids: the caller needs each one's current clocks to
     * compute the fieldHlc its tombstone should carry.
     */
    @Query("SELECT * FROM sketches WHERE noteId = :noteId AND isDeleted = 0")
    suspend fun activeSketchesForNote(noteId: String): List<SketchEntity>

    /**
     * Sends one sketch to Trash. Mirrors `NoteDao.softDeleteNote` exactly, `isDeleted = 0` guard
     * included: a second delete of an already-trashed sketch must not re-stamp `deletedAt` or mint
     * a clock for a write that changed nothing.
     */
    @Query(
        "UPDATE sketches SET isDeleted = 1, deletedAt = :timestamp, " +
            "hlcMs = :hlcMs, hlcCounter = :hlcCounter, hlcNode = :hlcNode, fieldHlc = :fieldHlc, dirty = 1 " +
            "WHERE uuid = :uuid AND isDeleted = 0"
    )
    suspend fun softDeleteSketch(
        uuid: String,
        timestamp: Long,
        hlcMs: Long,
        hlcCounter: Int,
        hlcNode: String,
        fieldHlc: String,
    )

    /**
     * Mirrors `NoteDao.purgeNotesDeletedBefore`: the hard DELETE for trashed sketches whose
     * retention window has passed. `RoomNotesRepository.purgeExpiredTrash` calls this alongside the
     * note and folder purges so a tombstoned sketch never outlives the note it was tombstoned with —
     * a sketch stamped `isDeleted` independently of its note (Task 7) must also be purged
     * independently, on the same threshold, or it leaks forever once its note is gone.
     */
    @Query(
        "DELETE FROM sketches " +
            "WHERE isDeleted = 1 AND deletedAt IS NOT NULL AND deletedAt > 0 AND deletedAt <= :threshold"
    )
    suspend fun purgeSketchesDeletedBefore(threshold: Long): Int

    /**
     * Every sketch under [noteId] tombstoned at [deletedAt] or later — the set
     * `RoomNotesRepository.restoreNote` un-tombstones.
     *
     * `deleteNote` stamps every sketch it cascades to with the note's own `deletedAt` (one deletion
     * event, one wall-clock instant), so an exact match already finds "tombstoned BY THIS deletion"
     * on a single device. But the note's tombstone and each sketch's tombstone are independently
     * clocked records: a note deleted concurrently on two devices can merge to a note `deletedAt`
     * that is earlier than a sketch tombstoned by that very same event on the other device, because
     * each record's DELETED field merges on its own clock. `>=` catches that case too, and a sketch
     * the user deleted individually *before* the note was ever deleted still carries a `deletedAt`
     * strictly earlier than the note's own and is correctly excluded either way — restoring the
     * note must not resurrect it.
     */
    @Query("SELECT * FROM sketches WHERE noteId = :noteId AND isDeleted = 1 AND deletedAt >= :deletedAt")
    suspend fun sketchesDeletedAtForNote(noteId: String, deletedAt: Long): List<SketchEntity>

    /**
     * The hard DELETE for every sketch under [noteId], live or tombstoned. `RoomNotesRepository
     * .purgeNote` calls this in the same transaction as the note's own row delete.
     *
     * Unlike the tombstone cascade above (deletion by reconciliation, never `ON DELETE CASCADE`),
     * a purge is not a sync event the other device needs to hear about — it is the local, once-
     * unsynced row simply ceasing to exist. Leaving a live sketch behind here is exactly the bug
     * this closes: `noteId` would then name a note that no longer exists, and nothing else in the
     * schema (there is no foreign key, by design — see this file's own KDoc) would ever notice or
     * clean it up. No `isDeleted` guard, deliberately: a tombstoned sketch under this note would
     * otherwise survive the note's own purge and wait out `purgeSketchesDeletedBefore` on its own,
     * which is needless once the note itself is gone for good.
     */
    @Query("DELETE FROM sketches WHERE noteId = :noteId")
    suspend fun purgeSketchesForNote(noteId: String)

    /**
     * Brings one sketch back out of Trash. Mirrors `NoteDao.restoreNote`: clears the tombstone,
     * bumps the clock and marks the row dirty so the un-delete is pushed — a restore the other
     * device never hears about is not a restore.
     */
    @Query(
        "UPDATE sketches SET isDeleted = 0, deletedAt = NULL, " +
            "hlcMs = :hlcMs, hlcCounter = :hlcCounter, hlcNode = :hlcNode, fieldHlc = :fieldHlc, dirty = 1 " +
            "WHERE uuid = :uuid"
    )
    suspend fun restoreSketch(
        uuid: String,
        hlcMs: Long,
        hlcCounter: Int,
        hlcNode: String,
        fieldHlc: String,
    )
}
