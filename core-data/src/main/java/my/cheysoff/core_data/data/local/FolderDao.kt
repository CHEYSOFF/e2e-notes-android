package my.cheysoff.core_data.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** The sync columns work exactly as they do on `notes`; see [NoteDao]'s class KDoc for the rules. */
@Dao
interface FolderDao {
    /**
     * Folders the chip row shows. `WHERE isDeleted = 0` for the same reason as the note reads:
     * deleting a folder now leaves its row in place so Trash can restore it, and without the
     * filter that row would keep rendering as a chip.
     */
    @Query("SELECT * FROM folders WHERE isDeleted = 0 ORDER BY name COLLATE NOCASE")
    fun getFolders(): Flow<List<FolderEntity>>

    /**
     * Create-or-update, replacing the old `@Insert(OnConflictStrategy.REPLACE)`.
     *
     * REPLACE is a DELETE followed by an INSERT, so from v6 on it would have wiped createdAt (and
     * the tombstone columns) every time the user renamed or recolored a folder. This upsert writes
     * only the two fields the edit dialog owns, plus updatedAt; createdAt is initialized once and
     * then kept, exactly as upsertNote does it.
     *
     * isDeleted/deletedAt are deliberately absent from the conflict branch: renaming a folder must
     * not pull it back out of Trash. Only [restoreFolder] does that — and that same rule is why a
     * merged remote record must go through [applyRemoteFolder] instead of here.
     *
     * `lastSyncedSeq` is set only by the insert, for the reason spelled out on `NoteDao.upsertNote`.
     */
    @Query(
        """
        INSERT INTO folders (id, name, colorArgb, createdAt, updatedAt, isDeleted, deletedAt, hlcMs, hlcCounter, hlcNode, fieldHlc, dirty, lastSyncedSeq)
        VALUES (:id, :name, :colorArgb, :timestamp, :timestamp, 0, NULL, :hlcMs, :hlcCounter, :hlcNode, :fieldHlc, 1, 0)
        ON CONFLICT(id) DO UPDATE SET
            name = excluded.name,
            colorArgb = excluded.colorArgb,
            updatedAt = excluded.updatedAt,
            createdAt = CASE WHEN folders.createdAt = 0 THEN excluded.createdAt ELSE folders.createdAt END,
            hlcMs = excluded.hlcMs,
            hlcCounter = excluded.hlcCounter,
            hlcNode = excluded.hlcNode,
            fieldHlc = excluded.fieldHlc,
            dirty = 1
        """
    )
    suspend fun upsertFolder(
        id: String,
        name: String,
        colorArgb: Long?,
        timestamp: Long,
        hlcMs: Long,
        hlcCounter: Int,
        hlcNode: String,
        fieldHlc: String,
    )

    /**
     * Writes a folder in full, sync columns included — the merge engine's path, and the mirror of
     * `NoteDao.applyRemoteNote`. See that method for why it cannot be [upsertFolder].
     */
    @Upsert
    suspend fun applyRemoteFolder(folder: FolderEntity)

    /** The sync columns of one folder, or null if there is no such row. */
    @Query("SELECT hlcMs, hlcCounter, hlcNode, fieldHlc FROM folders WHERE id = :id")
    suspend fun rowClock(id: String): RowClock?

    /** The highest row clock in this table; see `NoteDao.highestRowClock` for what it is for. */
    @Query("SELECT hlcMs, hlcCounter, hlcNode, fieldHlc FROM folders ORDER BY hlcMs DESC, hlcCounter DESC LIMIT 1")
    suspend fun highestRowClock(): RowClock?

    /**
     * Sends a folder to Trash. `AND isDeleted = 0` keeps a repeat delete from re-stamping
     * deletedAt and restarting the retention window — same rule as softDeleteNote, and, as there,
     * it governs the clock too: a statement that matches nothing changed nothing.
     */
    @Query(
        "UPDATE folders SET isDeleted = 1, deletedAt = :timestamp, " +
            "hlcMs = :hlcMs, hlcCounter = :hlcCounter, hlcNode = :hlcNode, fieldHlc = :fieldHlc, dirty = 1 " +
            "WHERE id = :id AND isDeleted = 0"
    )
    suspend fun softDeleteFolder(
        id: String,
        timestamp: Long,
        hlcMs: Long,
        hlcCounter: Int,
        hlcNode: String,
        fieldHlc: String,
    )

    /**
     * Brings a folder back out of Trash. It comes back with no notes in it: deleting it unfiled
     * them, and nothing records which ones they were.
     */
    @Query(
        "UPDATE folders SET isDeleted = 0, deletedAt = NULL, " +
            "hlcMs = :hlcMs, hlcCounter = :hlcCounter, hlcNode = :hlcNode, fieldHlc = :fieldHlc, dirty = 1 " +
            "WHERE id = :id"
    )
    suspend fun restoreFolder(
        id: String,
        hlcMs: Long,
        hlcCounter: Int,
        hlcNode: String,
        fieldHlc: String,
    )

    /** The real DELETE. Irreversible. */
    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun purgeFolder(id: String)

    /** Folders in Trash, newest-deleted first; an unstamped row sorts last (NULL is lowest). */
    @Query("SELECT * FROM folders WHERE isDeleted = 1 ORDER BY deletedAt DESC, id ASC")
    fun getDeletedFolders(): Flow<List<FolderEntity>>

    /** Mirror of purgeNotesDeletedBefore; see that query for why both stamp guards are spelled out. */
    @Query(
        "DELETE FROM folders " +
            "WHERE isDeleted = 1 AND deletedAt IS NOT NULL AND deletedAt > 0 AND deletedAt <= :threshold"
    )
    suspend fun purgeFoldersDeletedBefore(threshold: Long): Int
}
