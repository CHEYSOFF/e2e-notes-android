package my.cheysoff.core_store

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import my.cheysoff.core_domain.model.Folder
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NotesSortOrder
import my.cheysoff.core_domain.model.TrashPolicy
import my.cheysoff.core_domain.repository.NotesRepository
import my.cheysoff.core_domain.sync.FieldClocks
import my.cheysoff.core_domain.sync.Hlc
import my.cheysoff.core_domain.sync.HlcGenerator
import my.cheysoff.core_domain.sync.RecordType
import my.cheysoff.core_domain.sync.SyncRecord

/**
 * [NotesRepository] over a [RecordStore]: the same interface Android implements over Room, backed
 * by a table of sealed envelopes.
 *
 * ## The same interface, deliberately
 *
 * Every screen this app has is written against `NotesRepository`, and a second interface for the
 * Apple build would mean every one of those screens forking. Implementing the existing one instead
 * costs the two things below, and they are worth naming because a reader will otherwise assume they
 * are bugs.
 *
 * ### Everything happens in memory
 *
 * `getNotes(sortOrder)` cannot become an `ORDER BY`, and "notes that are not in Trash" cannot become
 * a `WHERE`, because both fields are inside the ciphertext. So a read is: fetch every row, open it,
 * filter, sort. `Records.sq` argues why that trade is the right one for a notes app and where it
 * would stop being right.
 *
 * ### A write is read-modify-write
 *
 * A local edit has to stamp the fields it changed with a fresh clock and leave the rest alone —
 * which means knowing what the row said before. That is one extra decrypt per write. The
 * alternative, stamping every field on every save, would make pinning a note look like an edit of
 * its body, and the merge would then let the pin overwrite a newer remote body. Field-level clocks
 * exist precisely to stop that, and paying one read per write is what buys it.
 *
 * ## Clocks
 *
 * Every write mints one clock from [clock] and stamps the row with it; the fields that did not
 * change keep the clocks they had. `FieldClocks` documents the sparse convention this relies on: a
 * field absent from the map is at the row clock, and `stamp` is what maintains that.
 *
 * The wall clock is injected as [now] rather than read here, for the same reason
 * `NotesRepository.purgeExpiredTrash` takes its own `now`: a repository that reads the clock is a
 * repository whose Trash expiry cannot be tested.
 */
class RecordNotesRepository(
    private val store: RecordStore,
    private val clock: HlcGenerator,
    private val now: () -> Long,
) : NotesRepository {

    // -------------------------------------------------------------------------------------
    // Reads
    // -------------------------------------------------------------------------------------

    override fun getNotes(sortOrder: NotesSortOrder): Flow<List<Note>> =
        notes().map { notes -> notes.filterNot { it.isDeleted }.sortedWith(comparatorFor(sortOrder)) }

    override fun getNoteById(id: String): Flow<Note?> =
        // Null for a soft-deleted note as well as for an unknown one. The interface says so, and it
        // is what stops the editor opening a note the user has thrown away.
        notes().map { notes -> notes.firstOrNull { it.id == id && !it.isDeleted } }

    override fun getDeletedNotes(): Flow<List<Note>> = notes().map { notes ->
        notes.filter { it.isDeleted }.sortedWith(
            compareByDescending<Note> { it.deletedAt ?: 0L }.thenBy { it.id }
        )
    }

    override fun getFolders(): Flow<List<Folder>> = folders().map { folders ->
        folders.filterNot { it.isDeleted }
            // Case-insensitive, matching what the Room implementation's `ORDER BY name COLLATE
            // NOCASE` does. `lowercase()` is the portable spelling of that; a locale-sensitive
            // comparison would put the same folders in a different order on two devices.
            .sortedWith(compareBy<Folder> { it.name.lowercase() }.thenBy { it.id })
    }

    override fun getDeletedFolders(): Flow<List<Folder>> = folders().map { folders ->
        folders.filter { it.isDeleted }.sortedWith(
            compareByDescending<Folder> { it.deletedAt ?: 0L }.thenBy { it.id }
        )
    }

    // -------------------------------------------------------------------------------------
    // Note writes
    // -------------------------------------------------------------------------------------

    override suspend fun saveNote(note: Note) {
        val existing = store.load(RecordType.NOTE, note.id)
        // A note created on this device keeps the `createdAt` it was created with; a note that
        // already exists keeps the one already stored, because `created` has no clock and cannot be
        // merged. Taking the incoming value would let a stale editor state rewrite it.
        val createdAt = existing?.createdAt ?: note.createdAt
        writeNote(note, existing, createdAt)
    }

    override suspend fun deleteNote(id: String) = updateNote(id) { note ->
        // A tombstone, not a deletion. The row stays, carrying its content, pin, favourite and
        // folder, so Restore is lossless -- and because the protocol has no delete, a row removed
        // here would be resurrected by the next pull from a peer that still has it.
        //
        // Already in Trash: left exactly as it is, rather than re-stamped with a new `deletedAt`.
        // Re-stamping would restart the thirty-day retention clock, so a stray second call from a
        // UI that double-fired would keep a note the user threw away a month ago.
        if (note.isDeleted) note else note.copy(isDeleted = true, deletedAt = now())
    }

    override suspend fun restoreNote(id: String) = updateNote(id) { note ->
        if (!note.isDeleted) note else note.copy(isDeleted = false, deletedAt = null)
    }

    override suspend fun purgeNote(id: String) {
        store.purge(listOf(RecordType.NOTE to id))
    }

    override suspend fun setNoteFolder(noteId: String, folderId: String?) =
        updateNote(noteId) { it.copy(folderId = folderId) }

    override suspend fun setNoteFavorite(noteId: String, isFavorite: Boolean) =
        updateNote(noteId) { it.copy(isFavorite = isFavorite) }

    override suspend fun setNotePinned(noteId: String, isPinned: Boolean) =
        updateNote(noteId) { it.copy(isPinned = isPinned) }

    // -------------------------------------------------------------------------------------
    // Folder writes
    // -------------------------------------------------------------------------------------

    override suspend fun saveFolder(folder: Folder) {
        val existing = store.load(RecordType.FOLDER, folder.id)
        val createdAt = existing?.createdAt ?: folder.createdAt
        val stamp = clock.next(now())
        val record = NoteRecords.toRecord(folder, stamp, emptyMap())
        store.put(
            record = stampFields(existing?.record, record, stamp),
            createdAt = createdAt,
        )
    }

    override suspend fun deleteFolder(id: String) {
        val existing = store.load(RecordType.FOLDER, id) ?: return
        val stamp = clock.next(now())
        val deletedAt = now()

        val folder = NoteRecords.toFolder(existing.record, existing.createdAt)
        val tombstone = NoteRecords.toRecord(
            folder.copy(isDeleted = true, deletedAt = deletedAt, updatedAt = deletedAt),
            stamp,
            emptyMap(),
        )
        val writes = mutableListOf(
            RecordStore.Write(
                record = stampFields(existing.record, tombstone, stamp),
                createdAt = existing.createdAt,
            )
        )

        // Its notes are unfiled, and the interface's KDoc records what that costs: restoring the
        // folder brings it back EMPTY, because nothing remembers which notes were in it and by
        // then the user may have re-filed some by hand. Leaving them pointing at a hidden folder
        // would be worse -- they would vanish from every folder view with no way to get them back.
        val open = store.records().first()
        open.asSequence()
            .filter { it.record.type == RecordType.NOTE }
            .map { it to NoteRecords.toNote(it.record, it.createdAt) }
            .filter { (_, note) -> note.folderId == id }
            .forEach { (stored, note) ->
                val unfiled = NoteRecords.toRecord(note.copy(folderId = null), stamp, emptyMap())
                writes += RecordStore.Write(
                    record = stampFields(stored.record, unfiled, stamp),
                    createdAt = stored.createdAt,
                )
            }

        // One transaction. A half-applied version leaves notes filed under a folder the UI no
        // longer shows, which is a note the user cannot find.
        store.putAll(writes)
    }

    override suspend fun restoreFolder(id: String) {
        val existing = store.load(RecordType.FOLDER, id) ?: return
        val folder = NoteRecords.toFolder(existing.record, existing.createdAt)
        if (!folder.isDeleted) return
        val stamp = clock.next(now())
        val restored = NoteRecords.toRecord(
            folder.copy(isDeleted = false, deletedAt = null),
            stamp,
            emptyMap(),
        )
        store.put(stampFields(existing.record, restored, stamp), existing.createdAt)
    }

    override suspend fun purgeFolder(id: String) {
        store.purge(listOf(RecordType.FOLDER to id))
    }

    override suspend fun purgeExpiredTrash(now: Long): Int {
        val open = store.records().first()
        val expired = open.mapNotNull { stored ->
            val deleted = stored.record.valueOf(FieldClocks.DELETED).parts
            val isDeleted =
                my.cheysoff.core_domain.sync.SyncValues.toBoolean(deleted.getOrNull(0))
            val deletedAt = deleted.getOrNull(1)?.toLongOrNull()
            // A tombstone with no stamp never expires, which is the safe direction: it stays
            // visible in Trash and the user can delete it by hand. `FieldValue` keeps the flag and
            // the stamp together for exactly this reason.
            if (isDeleted && deletedAt != null && TrashPolicy.isExpired(deletedAt, now)) {
                stored.record.type to stored.record.uuid
            } else {
                null
            }
        }
        if (expired.isEmpty()) return 0
        store.purge(expired)
        return expired.size
    }

    // -------------------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------------------

    private fun notes(): Flow<List<Note>> = store.records().map { records ->
        records.filter { it.record.type == RecordType.NOTE }
            .map { NoteRecords.toNote(it.record, it.createdAt) }
    }

    private fun folders(): Flow<List<Folder>> = store.records().map { records ->
        records.filter { it.record.type == RecordType.FOLDER }
            .map { NoteRecords.toFolder(it.record, it.createdAt) }
    }

    /**
     * Reads a note, applies [change], writes it back — and does nothing at all for an id that is
     * not there.
     *
     * Silence rather than an exception, because every caller is a UI gesture on a row the UI is
     * showing, and a note that has since been purged by a Trash sweep is a race the user should not
     * see a crash for. It matches what a Room `UPDATE … WHERE id = :id` does with no matching row.
     */
    private suspend fun updateNote(id: String, change: (Note) -> Note) {
        val existing = store.load(RecordType.NOTE, id) ?: return
        val note = NoteRecords.toNote(existing.record, existing.createdAt)
        val changed = change(note)
        // A gesture that changes nothing writes nothing. That is not an optimisation: a write
        // stamps a new row clock and sets `dirty`, so an un-pin on an already-unpinned note, or a
        // restore of a note that is not in Trash, would queue a version for the server whose only
        // content is a newer clock -- and on the receiving device that clock would win over a real
        // concurrent edit made in the same moment.
        //
        // `saveNote` deliberately does NOT do this. It is the editor's explicit save, the caller
        // has decided a save happened, and it is the one path where advancing the row clock with no
        // field change is the intended behaviour.
        if (changed == note) return
        writeNote(changed, existing, existing.createdAt)
    }

    private suspend fun writeNote(note: Note, existing: OpenRecord?, createdAt: Long) {
        val stamp = clock.next(now())
        val record = NoteRecords.toRecord(note, stamp, emptyMap())
        store.put(stampFields(existing?.record, record, stamp), createdAt)
    }

    /**
     * [next], with the field clocks a partial write leaves behind.
     *
     * Fields whose value changed take [stamp]; the rest keep whatever clock they had, expressed in
     * the sparse form `FieldClocks` defines. `stamp`'s `previousRowClock` is null exactly when the
     * row is being created, which is the case where every field is legitimately at the new clock
     * and the map is legitimately empty.
     */
    private fun stampFields(previous: SyncRecord?, next: SyncRecord, stamp: Hlc): SyncRecord {
        val serialized = FieldClocks.stamp(
            previousSerialized = previous?.let { FieldClocks.serialize(it.fieldClocks) }.orEmpty(),
            previousRowClock = previous?.rowClock,
            allFields = next.type.fields,
            touched = NoteRecords.changedFields(previous, next),
            newClock = stamp,
        )
        return next.copy(fieldClocks = FieldClocks.parse(serialized)).normalized()
    }

    private fun comparatorFor(order: NotesSortOrder): Comparator<Note> = when (order) {
        // `thenBy { it.id }` on each: two notes saved in the same millisecond would otherwise sort
        // differently between two emissions of the same list, which reads as the list flickering.
        NotesSortOrder.RECENTLY_EDITED ->
            compareByDescending<Note> { it.updatedAt }.thenBy { it.id }
        NotesSortOrder.NEWEST_CREATED ->
            compareByDescending<Note> { it.createdAt }.thenBy { it.id }
        NotesSortOrder.TITLE_ASC ->
            compareBy<Note> { it.title.lowercase() }.thenBy { it.id }
    }
}
