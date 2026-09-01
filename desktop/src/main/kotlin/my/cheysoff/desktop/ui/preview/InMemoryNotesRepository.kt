package my.cheysoff.desktop.ui.preview

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import my.cheysoff.core_domain.model.Folder
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NotesSortOrder
import my.cheysoff.core_domain.model.TrashPolicy
import my.cheysoff.core_domain.repository.NotesRepository

/**
 * A whole [NotesRepository] in a pair of [MutableStateFlow]s.
 *
 * This exists so the desktop UI can be run, looked at and tested before the encrypted desktop
 * store lands underneath it. It implements the real interface — the same one the Android app uses
 * — so swapping it for the real thing is a change to one construction site and nothing else.
 *
 * It is a faithful fake, not a stub: soft delete, restore, purge, retention expiry and the "folder
 * delete unfiles its notes" transaction all behave the way the interface documents, because a fake
 * that quietly ignores those rules would let the UI be built against behaviour that does not exist.
 *
 * What it deliberately does NOT do is persist. Closing the window loses everything; that is the
 * foundation agent's half.
 */
class InMemoryNotesRepository(
    notes: List<Note> = emptyList(),
    folders: List<Folder> = emptyList(),
    private val now: () -> Long = { System.currentTimeMillis() },
) : NotesRepository {

    private val notesState = MutableStateFlow(notes)
    private val foldersState = MutableStateFlow(folders)

    override fun getNotes(sortOrder: NotesSortOrder): Flow<List<Note>> =
        notesState.map { all -> all.filterNot { it.isDeleted }.sortedWith(comparatorFor(sortOrder)) }

    override fun getNoteById(id: String): Flow<Note?> =
        notesState.map { all -> all.firstOrNull { it.id == id && !it.isDeleted } }

    override suspend fun saveNote(note: Note) {
        notesState.value = notesState.value.upsert(note) { it.id == note.id }
    }

    override suspend fun deleteNote(id: String) = updateNote(id) {
        it.copy(isDeleted = true, deletedAt = now())
    }

    override suspend fun restoreNote(id: String) = updateNote(id) {
        it.copy(isDeleted = false, deletedAt = null)
    }

    override suspend fun purgeNote(id: String) {
        notesState.value = notesState.value.filterNot { it.id == id }
    }

    override fun getDeletedNotes(): Flow<List<Note>> =
        notesState.map { all ->
            all.filter { it.isDeleted }.sortedByDescending { it.deletedAt ?: 0L }
        }

    override suspend fun setNoteFolder(noteId: String, folderId: String?) =
        updateNote(noteId) { it.copy(folderId = folderId) }

    override suspend fun setNoteFavorite(noteId: String, isFavorite: Boolean) =
        updateNote(noteId) { it.copy(isFavorite = isFavorite) }

    override suspend fun setNotePinned(noteId: String, isPinned: Boolean) =
        updateNote(noteId) { it.copy(isPinned = isPinned) }

    override fun getFolders(): Flow<List<Folder>> =
        foldersState.map { all ->
            all.filterNot { it.isDeleted }.sortedBy { it.name.lowercase() }
        }

    override suspend fun saveFolder(folder: Folder) {
        foldersState.value = foldersState.value.upsert(folder) { it.id == folder.id }
    }

    override suspend fun deleteFolder(id: String) {
        // One "transaction": tombstone the folder AND unfile its notes, exactly as the interface
        // says. A fake that only did the first half would let the UI show orphaned notes filed
        // into a folder that no longer exists.
        foldersState.value = foldersState.value.map {
            if (it.id == id) it.copy(isDeleted = true, deletedAt = now()) else it
        }
        notesState.value = notesState.value.map {
            if (it.folderId == id) it.copy(folderId = null) else it
        }
    }

    override suspend fun restoreFolder(id: String) {
        // Comes back empty, as documented: deleteFolder unfiled its notes and nothing recorded
        // which ones they were.
        foldersState.value = foldersState.value.map {
            if (it.id == id) it.copy(isDeleted = false, deletedAt = null) else it
        }
    }

    override suspend fun purgeFolder(id: String) {
        foldersState.value = foldersState.value.filterNot { it.id == id }
    }

    override fun getDeletedFolders(): Flow<List<Folder>> =
        foldersState.map { all ->
            all.filter { it.isDeleted }.sortedByDescending { it.deletedAt ?: 0L }
        }

    override suspend fun purgeExpiredTrash(now: Long): Int {
        // TrashPolicy rather than a bare subtraction: it is the thing that decides an unset or
        // future timestamp resolves to "keep", and a fake that purged those would let the UI be
        // built against a rule the real store does not have.
        val expiredNotes = notesState.value.filter { it.isDeleted && TrashPolicy.isExpired(it.deletedAt, now) }
        val expiredFolders = foldersState.value.filter { it.isDeleted && TrashPolicy.isExpired(it.deletedAt, now) }
        notesState.value = notesState.value - expiredNotes.toSet()
        foldersState.value = foldersState.value - expiredFolders.toSet()
        return expiredNotes.size + expiredFolders.size
    }

    private fun updateNote(id: String, edit: (Note) -> Note) {
        notesState.value = notesState.value.map { if (it.id == id) edit(it) else it }
    }

    private fun <T> List<T>.upsert(value: T, matches: (T) -> Boolean): List<T> =
        if (any(matches)) map { if (matches(it)) value else it } else this + value

    private fun comparatorFor(sortOrder: NotesSortOrder): Comparator<Note> = when (sortOrder) {
        NotesSortOrder.RECENTLY_EDITED -> compareByDescending { it.updatedAt }
        NotesSortOrder.NEWEST_CREATED -> compareByDescending { it.createdAt }
        NotesSortOrder.TITLE_ASC -> compareBy { it.title.lowercase() }
    }
}
