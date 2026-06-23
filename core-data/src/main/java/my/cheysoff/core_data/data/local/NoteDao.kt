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
     * Single-statement upsert (avoids a read on every autosave). A new note gets
     * createdAt = updatedAt = [timestamp] and isFavorite = false. An existing note keeps its
     * createdAt (initializing the legacy 0) AND its isFavorite — the editor/save path doesn't own
     * those fields, so they're never clobbered — while title/content/isPinned/folderId/updatedAt
     * are updated. (Toggling favorite, when added, should use a dedicated update.)
     */
    @Query(
        """
        INSERT INTO notes (id, title, content, checklist, isPinned, isFavorite, folderId, createdAt, updatedAt)
        VALUES (:id, :title, :content, :checklist, :isPinned, 0, :folderId, :timestamp, :timestamp)
        ON CONFLICT(id) DO UPDATE SET
            title = excluded.title,
            content = excluded.content,
            checklist = excluded.checklist,
            isPinned = excluded.isPinned,
            folderId = excluded.folderId,
            updatedAt = excluded.updatedAt,
            createdAt = CASE WHEN notes.createdAt = 0 THEN excluded.createdAt ELSE notes.createdAt END
        """
    )
    suspend fun upsertNote(
        id: String,
        title: String,
        content: String,
        checklist: String,
        isPinned: Boolean,
        folderId: String?,
        timestamp: Long,
    )

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteNote(id: String)

    @Query("UPDATE notes SET folderId = :folderId WHERE id = :noteId")
    suspend fun setNoteFolder(noteId: String, folderId: String?)

    @Query("UPDATE notes SET folderId = NULL WHERE folderId = :folderId")
    suspend fun clearFolder(folderId: String)
}
