package my.cheysoff.desktop.store

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import my.cheysoff.core_domain.model.Folder
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NotesSortOrder
import my.cheysoff.core_domain.model.TrashPolicy
import my.cheysoff.core_domain.repository.NotesRepository
import my.cheysoff.core_domain.sync.FieldClocks
import my.cheysoff.core_domain.sync.Hlc
import my.cheysoff.core_domain.sync.HlcGenerator
import my.cheysoff.core_domain.sync.RecordType

/** Records that were on disk and could not be turned back into notes, and why. */
data class LoadDiagnostics(
    /** Rows GCM refused. A wrong account key, or a damaged file. */
    val unreadable: Int,
    /** Rows whose payload identity did not match the ID they were filed under. A client bug. */
    val mislabelled: Int,
    /** Rows written by a build this one cannot read without losing fields. */
    val unsupportedVersion: Int,
    /** Rows that decrypted and authenticated but did not parse. */
    val malformed: Int,
) {
    val total: Int get() = unreadable + mislabelled + unsupportedVersion + malformed
}

/**
 * [NotesRepository] over the record store: decrypt everything at unlock, serve reads from memory,
 * re-seal one record per write.
 *
 * This implements the interface the Android app already uses, unchanged. That is deliberate and it
 * is what makes the desktop UI the same code shape as the phone's — the repository is the seam, and
 * a desktop-flavoured variant of it would have made every screen a fork.
 *
 * ## Reads come from a snapshot
 *
 * A `records` row is a sealed blob, so `WHERE title LIKE ?` does not exist here (see [RecordStore]).
 * Every note and folder is decrypted once, at construction, into [state]; the [Flow]s are
 * projections of that. Sorting and Trash filtering are the same rules `NoteDao`'s queries express,
 * transcribed rather than reinvented — the orders below match the `ORDER BY` clauses exactly,
 * including the tie-breakers, because a list that reorders itself when a user moves between devices
 * is a bug the user will report as "my notes jumped around".
 *
 * ## Writes are read-modify-write, under one mutex
 *
 * A write re-seals the whole record, so it must not interleave with another write to the same
 * record — and since a record's field clocks depend on the clocks it already carries, "the same
 * record" is not something the caller can be relied on to know. One mutex over all writes is the
 * cheap correct answer at this scale; the phone reaches the same place with a Room transaction.
 *
 * ## Records that will not open
 *
 * Counted in [diagnostics] and otherwise left completely alone: not deleted, not repaired, not
 * re-sealed. Every one of the four kinds means something is wrong that this class cannot know how
 * to fix, and three of them (mislabelled, unsupported version, malformed) name a record whose
 * plaintext some *other* build can still read. Dropping them would be this device destroying data
 * on the account's behalf.
 */
class RecordNotesRepository private constructor(
    private val store: RecordStore,
    private val codec: RecordCodec,
    private val hlc: HlcGenerator,
    private val clock: () -> Long,
    initial: Snapshot,
    /** What could not be loaded. Zero on a healthy vault; surfaced by the UI when it is not. */
    val diagnostics: LoadDiagnostics,
) : NotesRepository {

    /** Every note and folder this device holds, including the trashed ones. */
    data class Snapshot(val notes: Map<String, NoteRow>, val folders: Map<String, FolderRow>)

    private val state = MutableStateFlow(initial)
    private val mutex = Mutex()

    companion object {
        /** What [saveNote] writes. Mirrors `RoomNotesRepository.SAVE_NOTE_FIELDS`. */
        private val SAVE_NOTE_FIELDS = setOf(
            FieldClocks.TITLE,
            FieldClocks.CONTENT,
            FieldClocks.CHECKLIST,
            FieldClocks.PINNED,
            FieldClocks.FOLDER,
            FieldClocks.UPDATED_AT,
        )

        private val SAVE_FOLDER_FIELDS = setOf(
            FieldClocks.NAME,
            FieldClocks.COLOR,
            FieldClocks.UPDATED_AT,
        )

        /** A soft delete or a restore writes the tombstone and nothing else — not `updatedAt`. */
        private val TOMBSTONE_FIELDS = setOf(FieldClocks.DELETED)

        /** Unfiling during a folder delete DOES bump `updatedAt`. */
        private val CLEAR_FOLDER_FIELDS = setOf(FieldClocks.FOLDER, FieldClocks.UPDATED_AT)

        /**
         * Reads every row, decrypts it, and returns the repository over the result.
         *
         * @param node this device's HLC node — `VaultSession.hlcNode`.
         * @param clock wall-clock milliseconds, injected so the Trash and `updatedAt` rules are
         *   testable without waiting.
         */
        fun load(
            store: RecordStore,
            codec: RecordCodec,
            node: String,
            clock: () -> Long = System::currentTimeMillis,
        ): RecordNotesRepository {
            val notes = mutableMapOf<String, NoteRow>()
            val folders = mutableMapOf<String, FolderRow>()
            var unreadable = 0
            var mislabelled = 0
            var unsupported = 0
            var malformed = 0

            val generator = HlcGenerator { node }

            store.readAll().forEach { row ->
                when (val opened = codec.open(row.blindedId, row.envelope)) {
                    is OpenResult.Unreadable -> unreadable++
                    is OpenResult.Mislabelled -> mislabelled++
                    is OpenResult.UnsupportedVersion -> unsupported++
                    is OpenResult.Malformed -> malformed++
                    is OpenResult.Ok -> {
                        val payload = opened.payload
                        // Every clock this device has ever seen has to be fed to the generator
                        // before it issues one of its own. Without it a machine whose wall clock is
                        // behind the clock in its own records would mint a clock BELOW them, and
                        // the merge would then read this device's newest edit as older than the
                        // version it is replacing.
                        generator.observe(payload.rowClock)
                        payload.clocks.values.forEach(generator::observe)
                        when (payload.recType) {
                            RecordType.NOTE -> NoteRecords.fromPayload(payload)
                                ?.let { notes[it.note.id] = it } ?: malformed++

                            RecordType.FOLDER -> FolderRecords.fromPayload(payload)
                                ?.let { folders[it.folder.id] = it } ?: malformed++
                        }
                    }
                }
            }

            return RecordNotesRepository(
                store = store,
                codec = codec,
                hlc = generator,
                clock = clock,
                initial = Snapshot(notes, folders),
                diagnostics = LoadDiagnostics(unreadable, mislabelled, unsupported, malformed),
            )
        }
    }

    // -------------------------------------------------------------------------------------------
    // Reads
    // -------------------------------------------------------------------------------------------

    override fun getNotes(sortOrder: NotesSortOrder): Flow<List<Note>> = state.map { snapshot ->
        val visible = snapshot.notes.values.map { it.note }.filterNot { it.isDeleted }
        when (sortOrder) {
            // `ORDER BY updatedAt DESC, createdAt DESC, id ASC`
            NotesSortOrder.RECENTLY_EDITED -> visible.sortedWith(
                compareByDescending<Note> { it.updatedAt }.thenByDescending { it.createdAt }.thenBy { it.id },
            )
            // `ORDER BY createdAt DESC, updatedAt DESC, id ASC`
            NotesSortOrder.NEWEST_CREATED -> visible.sortedWith(
                compareByDescending<Note> { it.createdAt }.thenByDescending { it.updatedAt }.thenBy { it.id },
            )
            // `ORDER BY (title = '') ASC, title COLLATE NOCASE ASC, id ASC` -- untitled notes last,
            // then case-insensitive by title. `lowercase()` rather than a Collator: SQLite's
            // NOCASE is ASCII-only and locale-independent, and a locale-sensitive comparison here
            // would sort a Russian user's notes differently from the same notes on their phone.
            NotesSortOrder.TITLE_ASC -> visible.sortedWith(
                compareBy<Note> { it.title.isEmpty() }.thenBy { it.title.lowercase() }.thenBy { it.id },
            )
        }
    }

    override fun getNoteById(id: String): Flow<Note?> = state.map { snapshot ->
        snapshot.notes[id]?.note?.takeUnless { it.isDeleted }
    }

    override fun getDeletedNotes(): Flow<List<Note>> = state.map { snapshot ->
        snapshot.notes.values.map { it.note }.filter { it.isDeleted }
            .sortedWith(compareByDescending<Note> { it.deletedAt ?: 0L }.thenBy { it.id })
    }

    override fun getFolders(): Flow<List<Folder>> = state.map { snapshot ->
        snapshot.folders.values.map { it.folder }.filterNot { it.isDeleted }
            .sortedBy { it.name.lowercase() }
    }

    override fun getDeletedFolders(): Flow<List<Folder>> = state.map { snapshot ->
        snapshot.folders.values.map { it.folder }.filter { it.isDeleted }
            .sortedWith(compareByDescending<Folder> { it.deletedAt ?: 0L }.thenBy { it.id })
    }

    // -------------------------------------------------------------------------------------------
    // Note writes
    // -------------------------------------------------------------------------------------------

    /**
     * Insert-or-update, with the same column ownership as `NoteDao.upsertNote`.
     *
     * **[note]'s `isFavorite`, `isDeleted`, `deletedAt` and `updatedAt` are ignored**, exactly as
     * the phone's statement ignores them, and for the reason its KDoc gives: callers build a [Note]
     * out of what the editor is holding, so those fields arrive at their defaults and would wipe
     * the stored values. `createdAt` is preserved unless it is the legacy 0 sentinel.
     */
    override suspend fun saveNote(note: Note): Unit = write { snapshot ->
        val now = clock()
        val stamp = hlc.next(now)
        val existing = snapshot.notes[note.id]
        val updated = if (existing == null) {
            note.copy(
                createdAt = now,
                updatedAt = now,
                isFavorite = false,
                isDeleted = false,
                deletedAt = null,
            )
        } else {
            existing.note.copy(
                title = note.title,
                content = note.content,
                contentFormat = note.contentFormat,
                checklist = note.checklist,
                isPinned = note.isPinned,
                folderId = note.folderId,
                updatedAt = now,
                createdAt = if (existing.note.createdAt == 0L) now else existing.note.createdAt,
            )
        }
        snapshot.putNote(existing, updated, stamp, SAVE_NOTE_FIELDS)
    }

    /**
     * Soft delete. `isDeleted = 0` guarded, so a second delete does not re-stamp `deletedAt` and
     * silently restart the 30-day retention — the same guard, for the same reason, as
     * `NoteDao.softDeleteNote`. A no-op write mints no clock either: "bump the clock on every write
     * that changed something" is a biconditional.
     */
    override suspend fun deleteNote(id: String): Unit = write { snapshot ->
        val existing = snapshot.notes[id] ?: return@write snapshot
        if (existing.note.isDeleted) return@write snapshot
        val now = clock()
        snapshot.putNote(
            existing,
            existing.note.copy(isDeleted = true, deletedAt = now),
            hlc.next(now),
            TOMBSTONE_FIELDS,
        )
    }

    /**
     * Restore. Deliberately **not** guarded on `isDeleted`, matching `NoteDao.restoreNote`, which
     * says why: restoring a note that is not in Trash is already a no-op on both columns, and the
     * only cost of the missing guard is one redundant re-seal of a record identical to the stored
     * one. Adding the guard here would make the desktop and the phone stamp different clocks for
     * the same user action.
     */
    override suspend fun restoreNote(id: String): Unit = write { snapshot ->
        val existing = snapshot.notes[id] ?: return@write snapshot
        val now = clock()
        snapshot.putNote(
            existing,
            existing.note.copy(isDeleted = false, deletedAt = null),
            hlc.next(now),
            TOMBSTONE_FIELDS,
        )
    }

    /** The hard delete. See [RecordStore.remove] for the resurrection hazard it inherits. */
    override suspend fun purgeNote(id: String): Unit = write { snapshot ->
        val existing = snapshot.notes[id] ?: return@write snapshot
        store.remove(codec.blindedIdOf(NoteRecords.toPayload(existing)))
        snapshot.copy(notes = snapshot.notes - id)
    }

    override suspend fun setNoteFolder(noteId: String, folderId: String?): Unit = write { snapshot ->
        val existing = snapshot.notes[noteId] ?: return@write snapshot
        snapshot.putNote(
            existing,
            existing.note.copy(folderId = folderId),
            hlc.next(clock()),
            setOf(FieldClocks.FOLDER),
        )
    }

    override suspend fun setNoteFavorite(noteId: String, isFavorite: Boolean): Unit = write { snapshot ->
        val existing = snapshot.notes[noteId] ?: return@write snapshot
        snapshot.putNote(
            existing,
            existing.note.copy(isFavorite = isFavorite),
            hlc.next(clock()),
            setOf(FieldClocks.FAVORITE),
        )
    }

    override suspend fun setNotePinned(noteId: String, isPinned: Boolean): Unit = write { snapshot ->
        val existing = snapshot.notes[noteId] ?: return@write snapshot
        snapshot.putNote(
            existing,
            existing.note.copy(isPinned = isPinned),
            hlc.next(clock()),
            setOf(FieldClocks.PINNED),
        )
    }

    // -------------------------------------------------------------------------------------------
    // Folder writes
    // -------------------------------------------------------------------------------------------

    /** Owns `name`, `colorArgb` and `updatedAt`; leaves `createdAt` and the tombstone alone. */
    override suspend fun saveFolder(folder: Folder): Unit = write { snapshot ->
        val now = clock()
        val stamp = hlc.next(now)
        val existing = snapshot.folders[folder.id]
        val updated = if (existing == null) {
            folder.copy(createdAt = now, updatedAt = now, isDeleted = false, deletedAt = null)
        } else {
            existing.folder.copy(
                name = folder.name,
                colorArgb = folder.colorArgb,
                updatedAt = now,
                createdAt = if (existing.folder.createdAt == 0L) now else existing.folder.createdAt,
            )
        }
        snapshot.putFolder(existing, updated, stamp, SAVE_FOLDER_FIELDS)
    }

    /**
     * Unfiles the folder's notes and trashes the folder, under **one** HLC stamp.
     *
     * One stamp for the whole thing because it is one user action: every unfiled note then lands at
     * the same point in the account's history as the folder's tombstone, and a merge on the other
     * device sees one event rather than N+1 unrelated ones. This is the same reasoning, and the same
     * stamp sharing, as `RoomNotesRepository.deleteFolder`.
     */
    override suspend fun deleteFolder(id: String): Unit = write { snapshot ->
        val existing = snapshot.folders[id] ?: return@write snapshot
        if (existing.folder.isDeleted) return@write snapshot
        val now = clock()
        val stamp = hlc.next(now)

        var next = snapshot
        snapshot.notes.values.filter { it.note.folderId == id }.forEach { row ->
            next = next.putNote(
                row,
                row.note.copy(folderId = null, updatedAt = now),
                stamp,
                CLEAR_FOLDER_FIELDS,
            )
        }
        next.putFolder(
            existing,
            existing.folder.copy(isDeleted = true, deletedAt = now),
            stamp,
            TOMBSTONE_FIELDS,
        )
    }

    /** Comes back empty; [deleteFolder] unfiled its notes and nothing recorded which they were. */
    override suspend fun restoreFolder(id: String): Unit = write { snapshot ->
        val existing = snapshot.folders[id] ?: return@write snapshot
        snapshot.putFolder(
            existing,
            existing.folder.copy(isDeleted = false, deletedAt = null),
            hlc.next(clock()),
            TOMBSTONE_FIELDS,
        )
    }

    override suspend fun purgeFolder(id: String): Unit = write { snapshot ->
        val existing = snapshot.folders[id] ?: return@write snapshot
        store.remove(codec.blindedIdOf(FolderRecords.toPayload(existing)))
        snapshot.copy(folders = snapshot.folders - id)
    }

    override suspend fun purgeExpiredTrash(now: Long): Int = mutex.withLock {
        val snapshot = state.value
        val expiredNotes = snapshot.notes.values
            .filter { it.note.isDeleted && TrashPolicy.isExpired(it.note.deletedAt, now) }
        val expiredFolders = snapshot.folders.values
            .filter { it.folder.isDeleted && TrashPolicy.isExpired(it.folder.deletedAt, now) }

        expiredNotes.forEach { store.remove(codec.blindedIdOf(NoteRecords.toPayload(it))) }
        expiredFolders.forEach { store.remove(codec.blindedIdOf(FolderRecords.toPayload(it))) }

        state.value = snapshot.copy(
            notes = snapshot.notes - expiredNotes.map { it.note.id }.toSet(),
            folders = snapshot.folders - expiredFolders.map { it.folder.id }.toSet(),
        )
        expiredNotes.size + expiredFolders.size
    }

    // -------------------------------------------------------------------------------------------
    // Plumbing
    // -------------------------------------------------------------------------------------------

    private suspend fun write(block: (Snapshot) -> Snapshot) {
        mutex.withLock { state.value = block(state.value) }
    }

    /**
     * Re-clocks, re-seals and stores a note, and returns the snapshot with it in.
     *
     * The field clocks go out through `FieldClocks.serialize`/`stamp`/`parse` rather than through a
     * map-shaped copy of the same logic. The round trip through a string is wasted work — and it is
     * the point: `stamp`'s rule (write down the fields you did NOT touch) is subtle enough that a
     * second implementation of it would be a second thing to keep correct, and the merge engine on
     * the other device is reading what this produces.
     */
    private fun Snapshot.putNote(
        existing: NoteRow?,
        note: Note,
        stamp: Hlc,
        touched: Set<String>,
    ): Snapshot {
        val row = NoteRow(
            note = note,
            rowClock = stamp,
            clocks = FieldClocks.parse(
                FieldClocks.stamp(
                    previousSerialized = FieldClocks.serialize(existing?.clocks.orEmpty()),
                    previousRowClock = existing?.rowClock,
                    allFields = FieldClocks.NOTE_FIELDS,
                    touched = touched,
                    newClock = stamp,
                ),
            ),
        )
        val sealed = codec.seal(NoteRecords.toPayload(row))
        store.put(sealed.blindedId, sealed.envelope)
        return copy(notes = notes + (note.id to row))
    }

    private fun Snapshot.putFolder(
        existing: FolderRow?,
        folder: Folder,
        stamp: Hlc,
        touched: Set<String>,
    ): Snapshot {
        val row = FolderRow(
            folder = folder,
            rowClock = stamp,
            clocks = FieldClocks.parse(
                FieldClocks.stamp(
                    previousSerialized = FieldClocks.serialize(existing?.clocks.orEmpty()),
                    previousRowClock = existing?.rowClock,
                    allFields = FieldClocks.FOLDER_FIELDS,
                    touched = touched,
                    newClock = stamp,
                ),
            ),
        )
        val sealed = codec.seal(FolderRecords.toPayload(row))
        store.put(sealed.blindedId, sealed.envelope)
        return copy(folders = folders + (folder.id to row))
    }

}
