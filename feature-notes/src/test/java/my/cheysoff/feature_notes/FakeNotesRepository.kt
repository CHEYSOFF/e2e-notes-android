package my.cheysoff.feature_notes

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import my.cheysoff.core_domain.model.Folder
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NotesSortOrder
import my.cheysoff.core_domain.repository.NotesRepository

/**
 * Hand-written [NotesRepository] whose emissions are driven by the test, not by a database.
 *
 * Written by hand rather than mocked on purpose. What these tests are about is *timing*: the row
 * Room echoes back after a write, the row that is still stale because a sibling UPDATE has not
 * landed, the write that is still in flight when the user presses back. A stubbed
 * "return this value when called" cannot express any of that. Two facilities do:
 *
 *  - the flows are [MutableStateFlow]s the test writes to, so an emission happens exactly when the
 *    test says it happens and carries exactly the row the test says the database holds;
 *  - [gate] parks a named suspend call until [release], so a test can hold one write open and
 *    observe what the rest of the ViewModel does around it.
 *
 * Every call is appended to [calls] as `"name(arg, …)"`, which is what most assertions read: the
 * questions being asked are "did an upsert happen at all", "was it one write or two", and "did the
 * targeted UPDATE run instead of the upsert".
 */
internal class FakeNotesRepository : NotesRepository {

    /** The row [getNoteById] hands out. Null means "no such note (or it is in Trash)". */
    val noteById = MutableStateFlow<Note?>(null)

    val folders = MutableStateFlow<List<Folder>>(emptyList())
    val deletedNotes = MutableStateFlow<List<Note>>(emptyList())
    val deletedFolders = MutableStateFlow<List<Folder>>(emptyList())

    /**
     * One list per sort order, because the production code re-subscribes on an order change rather
     * than re-sorting in memory (see `NotesListViewModel.sortedNotes`). Giving each order its own
     * flow is what lets a test tell "resubscribed" from "reused the old subscription": only a
     * genuine re-subscription can deliver the other list.
     */
    private val notesByOrder = mutableMapOf<NotesSortOrder, MutableStateFlow<List<Note>>>()

    /** Every order [getNotes] was actually COLLECTED for, in order. One entry per subscription. */
    val ordersSubscribed = mutableListOf<NotesSortOrder>()

    fun notesFor(order: NotesSortOrder): MutableStateFlow<List<Note>> =
        notesByOrder.getOrPut(order) { MutableStateFlow(emptyList()) }

    /** Every repository call in the order it was made. */
    val calls = mutableListOf<String>()

    /** The full [Note] handed to each [saveNote], so tests can assert on the columns written. */
    val savedNotes = mutableListOf<Note>()
    val savedFolders = mutableListOf<Folder>()

    /** The `now` [purgeExpiredTrash] was called with, or null if it was never called. */
    var purgeExpiredNow: Long? = null
        private set
    var purgeExpiredResult: Int = 0

    // --- suspension gates -----------------------------------------------------------------------

    private val gates = mutableMapOf<String, CompletableDeferred<Unit>>()

    /**
     * Make the next (and every subsequent) call to the repository method [name] park until
     * [release]. The call is still RECORDED first, so a test can assert the write was issued while
     * holding it open — that is how "a write is in flight" is expressed here.
     */
    fun gate(name: String) {
        gates[name] = CompletableDeferred()
    }

    /** Let a [gate]d method through. Safe to call when nothing is gated. */
    fun release(name: String) {
        gates.remove(name)?.complete(Unit)
    }

    /** Releases every gate, so a test can leave no coroutine parked behind it. */
    fun releaseAll() {
        gates.keys.toList().forEach(::release)
    }

    private suspend fun gated(name: String) {
        gates[name]?.await()
    }

    // --- NotesRepository ------------------------------------------------------------------------

    override fun getNotes(sortOrder: NotesSortOrder): Flow<List<Note>> =
        notesFor(sortOrder).onStart { ordersSubscribed += sortOrder }

    override fun getNoteById(id: String): Flow<Note?> = noteById

    override suspend fun saveNote(note: Note) {
        calls += "saveNote(${note.id})"
        savedNotes += note
        gated("saveNote")
    }

    override suspend fun deleteNote(id: String) {
        calls += "deleteNote($id)"
        gated("deleteNote")
    }

    override suspend fun restoreNote(id: String) {
        calls += "restoreNote($id)"
        gated("restoreNote")
    }

    override suspend fun purgeNote(id: String) {
        calls += "purgeNote($id)"
        gated("purgeNote")
    }

    override fun getDeletedNotes(): Flow<List<Note>> = deletedNotes

    override suspend fun setNoteFolder(noteId: String, folderId: String?) {
        calls += "setNoteFolder($noteId, $folderId)"
        gated("setNoteFolder")
    }

    override suspend fun setNoteFavorite(noteId: String, isFavorite: Boolean) {
        calls += "setNoteFavorite($noteId, $isFavorite)"
        gated("setNoteFavorite")
    }

    override suspend fun setNotePinned(noteId: String, isPinned: Boolean) {
        calls += "setNotePinned($noteId, $isPinned)"
        gated("setNotePinned")
    }

    override fun getFolders(): Flow<List<Folder>> = folders

    override suspend fun saveFolder(folder: Folder) {
        calls += "saveFolder(${folder.id})"
        savedFolders += folder
        gated("saveFolder")
    }

    override suspend fun deleteFolder(id: String) {
        calls += "deleteFolder($id)"
        gated("deleteFolder")
    }

    override suspend fun restoreFolder(id: String) {
        calls += "restoreFolder($id)"
        gated("restoreFolder")
    }

    override suspend fun purgeFolder(id: String) {
        calls += "purgeFolder($id)"
        gated("purgeFolder")
    }

    override fun getDeletedFolders(): Flow<List<Folder>> = deletedFolders

    override suspend fun purgeExpiredTrash(now: Long): Int {
        calls += "purgeExpiredTrash"
        purgeExpiredNow = now
        gated("purgeExpiredTrash")
        return purgeExpiredResult
    }

    // --- assertion helpers ----------------------------------------------------------------------

    /** How many calls to [saveNote] (the full-row upsert) have been made. */
    fun upsertCount(): Int = savedNotes.size

    /** Calls whose name is [name], e.g. `callsNamed("setNoteFavorite")`. */
    fun callsNamed(name: String): List<String> = calls.filter { it.startsWith("$name(") }
}
