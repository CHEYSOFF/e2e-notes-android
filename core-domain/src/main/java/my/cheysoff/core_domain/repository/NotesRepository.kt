package my.cheysoff.core_domain.repository

import kotlinx.coroutines.flow.Flow
import my.cheysoff.core_domain.model.Folder
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NotesSortOrder

interface NotesRepository {
    fun getNotes(sortOrder: NotesSortOrder): Flow<List<Note>>
    fun getNoteById(id: String): Flow<Note?>
    suspend fun saveNote(note: Note)
    suspend fun deleteNote(id: String)
    suspend fun setNoteFolder(noteId: String, folderId: String?)
    suspend fun setNoteFavorite(noteId: String, isFavorite: Boolean)
    suspend fun setNotePinned(noteId: String, isPinned: Boolean)

    fun getFolders(): Flow<List<Folder>>
    suspend fun saveFolder(folder: Folder)
    suspend fun deleteFolder(id: String)
}
