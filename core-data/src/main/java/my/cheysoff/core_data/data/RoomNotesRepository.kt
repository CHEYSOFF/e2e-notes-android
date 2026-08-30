package my.cheysoff.core_data.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import my.cheysoff.core_data.data.local.FolderDao
import my.cheysoff.core_data.data.local.NoteDao
import my.cheysoff.core_data.data.local.NoteDatabase
import my.cheysoff.core_data.data.local.toDomain
import my.cheysoff.core_domain.model.Folder
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NotesSortOrder
import my.cheysoff.core_domain.model.TrashPolicy
import my.cheysoff.core_domain.repository.NotesRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomNotesRepository @Inject constructor(
    private val noteDao: NoteDao,
    private val folderDao: FolderDao,
    private val database: NoteDatabase,
) : NotesRepository {

    // The order is picked here rather than sorted in memory: each order is its own verified
    // @Query, so SQLite does the work and the caller just re-subscribes when the user changes it.
    // All three exclude soft-deleted rows; see NoteDao.
    override fun getNotes(sortOrder: NotesSortOrder): Flow<List<Note>> {
        val entities = when (sortOrder) {
            NotesSortOrder.RECENTLY_EDITED -> noteDao.getNotesByUpdatedAt()
            NotesSortOrder.NEWEST_CREATED -> noteDao.getNotesByCreatedAt()
            NotesSortOrder.TITLE_ASC -> noteDao.getNotesByTitle()
        }
        return entities.map { list -> list.map { it.toDomain() } }
    }

    override fun getNoteById(id: String): Flow<Note?> {
        return noteDao.getNoteById(id).map { it?.toDomain() }
    }

    override suspend fun saveNote(note: Note) {
        // One write, no read: the upsert preserves/initializes createdAt and refreshes updatedAt.
        noteDao.upsertNote(
            id = note.id,
            title = note.title,
            content = note.content,
            contentFormat = note.contentFormat.storageValue,
            checklist = note.checklist,
            isPinned = note.isPinned,
            folderId = note.folderId,
            timestamp = System.currentTimeMillis(),
        )
    }

    override suspend fun deleteNote(id: String) {
        // Soft: the row stays, flagged and stamped, until the user restores it or the retention
        // window runs out. The hard DELETE lives in purgeNote.
        noteDao.softDeleteNote(id, System.currentTimeMillis())
    }

    override suspend fun restoreNote(id: String) {
        noteDao.restoreNote(id)
    }

    override suspend fun purgeNote(id: String) {
        noteDao.purgeNote(id)
    }

    override fun getDeletedNotes(): Flow<List<Note>> {
        return noteDao.getDeletedNotes().map { list -> list.map { it.toDomain() } }
    }

    override suspend fun setNoteFolder(noteId: String, folderId: String?) {
        noteDao.setNoteFolder(noteId, folderId)
    }

    override suspend fun setNoteFavorite(noteId: String, isFavorite: Boolean) {
        noteDao.setNoteFavorite(noteId, isFavorite)
    }

    override suspend fun setNotePinned(noteId: String, isPinned: Boolean) {
        noteDao.setNotePinned(noteId, isPinned)
    }

    override fun getFolders(): Flow<List<Folder>> {
        return folderDao.getFolders().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun saveFolder(folder: Folder) {
        // Same shape as saveNote: one upsert that owns name/color/updatedAt and leaves createdAt
        // and the tombstone columns alone. The Folder's own createdAt/updatedAt/isDeleted/deletedAt
        // are NOT passed through — callers build a Folder from what the edit dialog collected, so
        // those fields would arrive at their defaults and wipe the stored values.
        folderDao.upsertFolder(
            id = folder.id,
            name = folder.name,
            colorArgb = folder.colorArgb,
            timestamp = System.currentTimeMillis(),
        )
    }

    override suspend fun deleteFolder(id: String) {
        // Unfile the folder's notes, then flag the folder — atomically, so a failure can't leave
        // notes pointing at a folder that is no longer in the chip row.
        //
        // The notes are unfiled rather than remembered, which is the pre-existing behaviour the
        // confirm dialog already describes ("its N notes will move to All"). Restoring the folder
        // therefore brings back an empty folder; see restoreFolder.
        val now = System.currentTimeMillis()
        database.withTransaction {
            noteDao.clearFolder(id, now)
            folderDao.softDeleteFolder(id, now)
        }
    }

    override suspend fun restoreFolder(id: String) {
        folderDao.restoreFolder(id)
    }

    override suspend fun purgeFolder(id: String) {
        folderDao.purgeFolder(id)
    }

    override fun getDeletedFolders(): Flow<List<Folder>> {
        return folderDao.getDeletedFolders().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun purgeExpiredTrash(now: Long): Int {
        val threshold = TrashPolicy.purgeThreshold(now)
        // One transaction so the two deletes are one observable step. They are independent — a
        // folder's notes were unfiled when it was trashed, so neither table references the other —
        // but a single transaction also means Room fires one invalidation instead of two, and the
        // Trash list re-renders once.
        return database.withTransaction {
            noteDao.purgeNotesDeletedBefore(threshold) +
                folderDao.purgeFoldersDeletedBefore(threshold)
        }
    }
}
