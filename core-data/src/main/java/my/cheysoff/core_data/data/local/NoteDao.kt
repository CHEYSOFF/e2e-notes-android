package my.cheysoff.core_data.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes")
    fun getNotes(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    fun getNoteById(id: String): Flow<NoteEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: NoteEntity)

    /**
     * Single-statement upsert: a new note gets createdAt = updatedAt = [timestamp]; an existing
     * note keeps its createdAt (initializing it from [timestamp] only if it's the legacy 0) and
     * refreshes updatedAt. Avoids a separate read on every autosave.
     */
    @Query(
        """
        INSERT INTO notes (id, title, content, isPinned, isFavorite, folderId, createdAt, updatedAt)
        VALUES (:id, :title, :content, :isPinned, :isFavorite, :folderId, :timestamp, :timestamp)
        ON CONFLICT(id) DO UPDATE SET
            title = excluded.title,
            content = excluded.content,
            isPinned = excluded.isPinned,
            isFavorite = excluded.isFavorite,
            folderId = excluded.folderId,
            updatedAt = excluded.updatedAt,
            createdAt = CASE WHEN notes.createdAt = 0 THEN excluded.createdAt ELSE notes.createdAt END
        """
    )
    suspend fun upsertNote(
        id: String,
        title: String,
        content: String,
        isPinned: Boolean,
        isFavorite: Boolean,
        folderId: String?,
        timestamp: Long,
    )

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteNote(id: String)
}
