package my.cheysoff.core_data.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    // One @Query per user-selectable order (rather than a single @RawQuery) so Room keeps
    // verifying each statement against the schema at compile time.
    //
    // Every order ends in `id ASC`. Without it the ordering is not total: legacy rows carry
    // updatedAt/createdAt = 0 until their first post-migration save and therefore tie on both
    // timestamp keys, and two untitled notes tie on title. SQLite leaves the relative order of
    // tied rows unspecified, so those notes could visibly reshuffle between emissions of
    // otherwise-unchanged data. `id` is the primary key, hence unique, so appending it makes
    // each order deterministic and stable.

    /** Recently edited: newest save first. Untouched legacy rows (updatedAt = 0) sort last. */
    @Query("SELECT * FROM notes ORDER BY updatedAt DESC, createdAt DESC, id ASC")
    fun getNotesByUpdatedAt(): Flow<List<NoteEntity>>

    /** Newest created first. Untouched legacy rows (createdAt = 0) sort last. */
    @Query("SELECT * FROM notes ORDER BY createdAt DESC, updatedAt DESC, id ASC")
    fun getNotesByCreatedAt(): Flow<List<NoteEntity>>

    /**
     * Title A–Z, case-insensitive (NOCASE folds ASCII only — good enough for the Latin titles
     * this app is written for, and it is the collation SQLite can apply without a custom one).
     * Untitled notes have an empty title and therefore group at the top.
     */
    @Query("SELECT * FROM notes ORDER BY title COLLATE NOCASE ASC, id ASC")
    fun getNotesByTitle(): Flow<List<NoteEntity>>

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

    @Query("UPDATE notes SET isFavorite = :isFavorite WHERE id = :noteId")
    suspend fun setNoteFavorite(noteId: String, isFavorite: Boolean)

    @Query("UPDATE notes SET isPinned = :isPinned WHERE id = :noteId")
    suspend fun setNotePinned(noteId: String, isPinned: Boolean)

    @Query("UPDATE notes SET folderId = NULL WHERE folderId = :folderId")
    suspend fun clearFolder(folderId: String)
}
