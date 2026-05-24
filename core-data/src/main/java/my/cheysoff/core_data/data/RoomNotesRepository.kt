package my.cheysoff.core_data.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import my.cheysoff.core_data.data.local.NoteDao
import my.cheysoff.core_data.data.local.toDomain
import my.cheysoff.core_data.data.local.toEntity
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.repository.NotesRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomNotesRepository @Inject constructor(
    private val noteDao: NoteDao
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
        noteDao.insertNote(note.toEntity())
    }

    override suspend fun deleteNote(id: String) {
        noteDao.deleteNote(id)
    }
}
