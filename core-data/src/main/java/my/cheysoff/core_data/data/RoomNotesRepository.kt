package my.cheysoff.core_data.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import my.cheysoff.core_data.data.local.FolderDao
import my.cheysoff.core_data.data.local.NoteDao
import my.cheysoff.core_data.data.local.toDomain
import my.cheysoff.core_data.data.local.toEntity
import my.cheysoff.core_domain.model.Folder
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.repository.NotesRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomNotesRepository @Inject constructor(
    private val noteDao: NoteDao,
    private val folderDao: FolderDao,
) : NotesRepository {

    override fun getNotes(): Flow<List<Note>> {
        return noteDao.getNotes().map { entities ->
            entities.map { it.toDomain() }
        }
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
            isPinned = note.isPinned,
            folderId = note.folderId,
            timestamp = System.currentTimeMillis(),
        )
    }

    override suspend fun deleteNote(id: String) {
        noteDao.deleteNote(id)
    }

    override fun getFolders(): Flow<List<Folder>> {
        return folderDao.getFolders().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun saveFolder(folder: Folder) {
        folderDao.insertFolder(folder.toEntity())
    }

    override suspend fun deleteFolder(id: String) {
        folderDao.deleteFolder(id)
    }
}
