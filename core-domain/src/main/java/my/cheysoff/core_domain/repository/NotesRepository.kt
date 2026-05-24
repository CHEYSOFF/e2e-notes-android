package my.cheysoff.core_domain.repository

import kotlinx.coroutines.flow.Flow
import my.cheysoff.core_domain.model.Folder
import my.cheysoff.core_domain.model.Note

interface NotesRepository {
    fun getNotes(): Flow<List<Note>>
    fun getNoteById(id: String): Flow<Note?>
    suspend fun saveNote(note: Note)
    suspend fun deleteNote(id: String)

    fun getFolders(): Flow<List<Folder>>
    suspend fun saveFolder(folder: Folder)
    suspend fun deleteFolder(id: String)
}
