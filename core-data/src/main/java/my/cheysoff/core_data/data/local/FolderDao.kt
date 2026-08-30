package my.cheysoff.core_data.data.local

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

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
     * not pull it back out of Trash. Only [restoreFolder] does that.
     */
    @Query(
        """
        INSERT INTO folders (id, name, colorArgb, createdAt, updatedAt, isDeleted, deletedAt)
        VALUES (:id, :name, :colorArgb, :timestamp, :timestamp, 0, NULL)
        ON CONFLICT(id) DO UPDATE SET
            name = excluded.name,
            colorArgb = excluded.colorArgb,
            updatedAt = excluded.updatedAt,
            createdAt = CASE WHEN folders.createdAt = 0 THEN excluded.createdAt ELSE folders.createdAt END
        """
    )
    suspend fun upsertFolder(id: String, name: String, colorArgb: Long?, timestamp: Long)

    /**
     * Sends a folder to Trash. `AND isDeleted = 0` keeps a repeat delete from re-stamping
     * deletedAt and restarting the retention window — same rule as softDeleteNote.
     */
    @Query("UPDATE folders SET isDeleted = 1, deletedAt = :timestamp WHERE id = :id AND isDeleted = 0")
    suspend fun softDeleteFolder(id: String, timestamp: Long)

    /**
     * Brings a folder back out of Trash. It comes back with no notes in it: deleting it unfiled
     * them, and nothing records which ones they were.
     */
    @Query("UPDATE folders SET isDeleted = 0, deletedAt = NULL WHERE id = :id")
    suspend fun restoreFolder(id: String)

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
