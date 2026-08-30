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
    //
    // Every one of them also carries `WHERE isDeleted = 0`. Delete is soft (see [softDeleteNote]),
    // so the row a user just sent to Trash is still sitting in this table; a query that forgets the
    // filter shows it in the notes list as if nothing happened. The Trash reads below are the ONLY
    // ones that select the other side of that flag.

    /** Recently edited: newest save first. Untouched legacy rows (updatedAt = 0) sort last. */
    @Query("SELECT * FROM notes WHERE isDeleted = 0 ORDER BY updatedAt DESC, createdAt DESC, id ASC")
    fun getNotesByUpdatedAt(): Flow<List<NoteEntity>>

    /** Newest created first. Untouched legacy rows (createdAt = 0) sort last. */
    @Query("SELECT * FROM notes WHERE isDeleted = 0 ORDER BY createdAt DESC, updatedAt DESC, id ASC")
    fun getNotesByCreatedAt(): Flow<List<NoteEntity>>

    /**
     * Title A–Z. NOCASE is the only case-insensitive collation SQLite offers without registering
     * a custom one, and it folds ASCII A–Z only: titles in Cyrillic, Greek, accented Latin or any
     * other script therefore sort by raw code point, which puts their uppercase and lowercase
     * letters in separate runs. That is a real limitation of this order, not a stylistic choice —
     * fixing it needs a custom collation (or an ICU-normalised sort column), which is out of scope.
     *
     * Untitled notes have an empty title, which would otherwise collate first and open the list
     * with a wall of blank cards; `(title = '') ASC` sinks that whole group to the bottom (0 before
     * 1) while leaving the titled notes’ relative order untouched.
     */
    @Query(
        "SELECT * FROM notes WHERE isDeleted = 0 " +
            "ORDER BY (title = '') ASC, title COLLATE NOCASE ASC, id ASC"
    )
    fun getNotesByTitle(): Flow<List<NoteEntity>>

    /**
     * Emits null for a soft-deleted note as well as for an unknown id, which is what keeps a note
     * in Trash out of the editor: SingleNoteViewModel filters nulls out of this flow, so a screen
     * pointed at a trashed id simply never loads.
     */
    @Query("SELECT * FROM notes WHERE id = :id AND isDeleted = 0")
    fun getNoteById(id: String): Flow<NoteEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: NoteEntity)

    /**
     * Single-statement upsert (avoids a read on every autosave). A new note gets
     * createdAt = updatedAt = [timestamp] and isFavorite = false. An existing note keeps its
     * createdAt (initializing the legacy 0) AND its isFavorite — the editor/save path doesn't own
     * those fields, so they're never clobbered — while title/content/isPinned/folderId/updatedAt
     * are updated. (Toggling favorite, when added, should use a dedicated update.)
     *
     * contentFormat travels with content and is overwritten alongside it — the two must never
     * drift apart, or a body would be read back with the wrong parser.
     *
     * The tombstone columns follow the same "not ours to write" rule as isFavorite: a new row is
     * inserted alive, and the conflict branch leaves isDeleted/deletedAt exactly as it found them.
     * So a save that races a delete cannot resurrect the note — only [restoreNote] does that.
     */
    @Query(
        """
        INSERT INTO notes (id, title, content, contentFormat, checklist, isPinned, isFavorite, folderId, createdAt, updatedAt, isDeleted, deletedAt)
        VALUES (:id, :title, :content, :contentFormat, :checklist, :isPinned, 0, :folderId, :timestamp, :timestamp, 0, NULL)
        ON CONFLICT(id) DO UPDATE SET
            title = excluded.title,
            content = excluded.content,
            contentFormat = excluded.contentFormat,
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
        contentFormat: String,
        checklist: String,
        isPinned: Boolean,
        folderId: String?,
        timestamp: Long,
    )

    /**
     * Sends a note to Trash. `AND isDeleted = 0` makes this idempotent in the direction that
     * matters: a second delete of an already-trashed note must not re-stamp deletedAt, which would
     * silently restart its 30-day retention.
     */
    @Query("UPDATE notes SET isDeleted = 1, deletedAt = :timestamp WHERE id = :id AND isDeleted = 0")
    suspend fun softDeleteNote(id: String, timestamp: Long)

    /** Brings a note back out of Trash, clearing the stamp so its next delete starts a new window. */
    @Query("UPDATE notes SET isDeleted = 0, deletedAt = NULL WHERE id = :id")
    suspend fun restoreNote(id: String)

    /** The real DELETE. Irreversible — nothing else in the app removes a note row. */
    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun purgeNote(id: String)

    /**
     * Notes in Trash, newest-deleted first. A row with no stamp sorts last (SQLite ranks NULL below
     * every value, so DESC puts it at the end) rather than jumping to the top.
     */
    @Query("SELECT * FROM notes WHERE isDeleted = 1 ORDER BY deletedAt DESC, id ASC")
    fun getDeletedNotes(): Flow<List<NoteEntity>>

    /**
     * Purges every trashed note stamped at or before [threshold], returning the number of rows
     * destroyed. The threshold comes from TrashPolicy.purgeThreshold(now).
     *
     * `deletedAt > 0` excludes rows with no usable stamp, matching TrashPolicy.isExpired: an
     * unstamped tombstone has no measurable age, so it is kept rather than guessed at. (`> 0` also
     * covers NULL, which fails every comparison, but both guards are written out so the intent
     * survives a future edit.)
     */
    @Query(
        "DELETE FROM notes " +
            "WHERE isDeleted = 1 AND deletedAt IS NOT NULL AND deletedAt > 0 AND deletedAt <= :threshold"
    )
    suspend fun purgeNotesDeletedBefore(threshold: Long): Int

    @Query("UPDATE notes SET folderId = :folderId WHERE id = :noteId")
    suspend fun setNoteFolder(noteId: String, folderId: String?)

    @Query("UPDATE notes SET isFavorite = :isFavorite WHERE id = :noteId")
    suspend fun setNoteFavorite(noteId: String, isFavorite: Boolean)

    @Query("UPDATE notes SET isPinned = :isPinned WHERE id = :noteId")
    suspend fun setNotePinned(noteId: String, isPinned: Boolean)

    /**
     * Unfiles every note in [folderId], as part of deleting that folder.
     *
     * Unlike the three targeted metadata updates above, this one DOES bump updatedAt. Those three
     * are user gestures on a single note that must not reorder a newest-first list (PR #32); this
     * is a mass edit the user did not aim at any note, and leaving it traceless means the change is
     * invisible to anything that reasons about when a note last changed.
     *
     * Deliberately not filtered by isDeleted: a note already in Trash still carries this folderId,
     * and leaving it pointing at a folder row that is itself about to be purged would create a
     * dangling reference the moment either one is restored.
     */
    @Query("UPDATE notes SET folderId = NULL, updatedAt = :timestamp WHERE folderId = :folderId")
    suspend fun clearFolder(folderId: String, timestamp: Long)
}
