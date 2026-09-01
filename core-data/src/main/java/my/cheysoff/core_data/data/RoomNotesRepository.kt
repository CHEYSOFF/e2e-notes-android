package my.cheysoff.core_data.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import my.cheysoff.core_data.data.local.FolderDao
import my.cheysoff.core_data.data.local.FolderEntity
import my.cheysoff.core_data.data.local.NoteDao
import my.cheysoff.core_data.data.local.NoteEntity
import my.cheysoff.core_data.data.local.NoteDatabase
import my.cheysoff.core_data.data.local.RowClock
import my.cheysoff.core_data.data.local.toDomain
import my.cheysoff.core_data.data.sync.SyncClock
import my.cheysoff.core_data.data.sync.SyncStamp
import my.cheysoff.core_domain.model.Folder
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NotesSortOrder
import my.cheysoff.core_domain.model.TrashPolicy
import my.cheysoff.core_domain.repository.NotesRepository
import my.cheysoff.core_domain.sync.FieldClocks
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The repository is where the clock enters.
 *
 * Every mutating method below follows the same three steps, and they are in this order for a
 * reason:
 *
 * 1. **Allocate exactly one [SyncStamp] per user action.** Not per statement — [deleteFolder]
 *    unfiles a folder's notes and trashes the folder, and those are one gesture, so they share one
 *    clock and land at one point in the account's history.
 * 2. **Read the row's previous clocks, inside the transaction.** `FieldClocks.stamp` needs them:
 *    the fields this write does *not* touch keep the clocks they already had, and the only place
 *    those are recorded is the row itself.
 * 3. **Write, stamping the row clock, the recomputed field clocks and `dirty = 1`.**
 *
 * The bare `System.currentTimeMillis()` calls that used to sit inline in `saveNote` and
 * `saveFolder` are gone; [clock] is the seam they became. `purgeExpiredTrash` already took its
 * clock as a parameter and was the pattern for it.
 */
@Singleton
class RoomNotesRepository @Inject constructor(
    private val noteDao: NoteDao,
    private val folderDao: FolderDao,
    private val database: NoteDatabase,
    private val clock: SyncClock,
) : NotesRepository {

    /**
     * Guards the one-off session seed below. A plain flag would let two concurrent first writes
     * both decide to seed, which is harmless but pointless; a mutex also makes "seeded exactly
     * once, before the first stamp is minted" true rather than merely likely.
     */
    private val seedMutex = Mutex()

    @Volatile
    private var seeded = false

    /**
     * Mints the clocks for one write, seeding the generator from the database the first time.
     *
     * The seed is what makes monotonicity survive a process restart. `HlcGenerator` guarantees it
     * only against clocks it has issued or been shown, and a fresh process has been shown nothing
     * — so if the device clock has been wound back since the last session, the first write of this
     * session would mint a clock *below* the ones already on disk. The row's clock would go
     * backwards, and a row whose clock went backwards loses to its own older version the next time
     * it is merged, which destroys the edit silently.
     *
     * The database is the durable high-water mark, because every row carries the clock it was last
     * written at. Two indexless `ORDER BY … LIMIT 1` reads, once per process, is the entire cost.
     */
    private suspend fun stamp(): SyncStamp {
        if (!seeded) {
            seedMutex.withLock {
                if (!seeded) {
                    noteDao.highestRowClock()?.let { clock.observe(it.rowHlc()) }
                    folderDao.highestRowClock()?.let { clock.observe(it.rowHlc()) }
                    seeded = true
                }
            }
        }
        return clock.next()
    }

    /**
     * The `fieldHlc` a note row should carry after a write at [stamp] that touched [touched].
     *
     * [prior] is the row's clock columns as they are *now*, or null if the row does not exist yet
     * — `FieldClocks.stamp` treats those two cases differently and getting them the wrong way
     * round is the difference between "every field is as new as this write" and "only the fields I
     * touched are".
     */
    private fun noteFieldHlc(prior: RowClock?, touched: Set<String>, stamp: SyncStamp): String =
        FieldClocks.stamp(
            previousSerialized = prior?.fieldHlc ?: "",
            previousRowClock = prior?.rowHlc(),
            allFields = FieldClocks.NOTE_FIELDS,
            touched = touched,
            newClock = stamp.hlc,
        )

    /** [noteFieldHlc] for the `folders` table. */
    private fun folderFieldHlc(prior: RowClock?, touched: Set<String>, stamp: SyncStamp): String =
        FieldClocks.stamp(
            previousSerialized = prior?.fieldHlc ?: "",
            previousRowClock = prior?.rowHlc(),
            allFields = FieldClocks.FOLDER_FIELDS,
            touched = touched,
            newClock = stamp.hlc,
        )

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
        val stamp = stamp()
        // The upsert itself is still one statement and still preserves/initializes createdAt; the
        // transaction is here because the field clocks are read-modify-write and the read must not
        // be able to see a different row than the write updates.
        database.withTransaction {
            val prior = noteDao.noteRow(note.id)
            noteDao.upsertNote(
                id = note.id,
                title = note.title,
                content = note.content,
                contentFormat = note.contentFormat.storageValue,
                checklist = note.checklist,
                isPinned = note.isPinned,
                folderId = note.folderId,
                timestamp = stamp.wallMs,
                hlcMs = stamp.hlc.ms,
                hlcCounter = stamp.hlc.counter,
                hlcNode = stamp.hlc.node,
                fieldHlc = noteFieldHlc(prior?.clocks(), savedNoteFields(prior, note), stamp),
            )
        }
    }

    /**
     * Which of `upsertNote`'s fields this particular save actually **changed**.
     *
     * ## Why "wrote" is not the same as "changed"
     *
     * `upsertNote` writes six values every time, because it is one statement and a statement cannot
     * choose its columns. Claiming a clock for all six is what `SAVE_NOTE_FIELDS` used to do, and it
     * makes the note's most recent save assert that *this device decided* every one of those values
     * at that moment — including the ones it merely copied back out of its own stale row.
     *
     * That assertion is false, and the merge believes it. Pin a note on the phone; type a sentence
     * into it on the tablet before the tablet has seen the pin; the tablet's save carries
     * `isPinned = false` at a newer clock and the pin is discarded. That is precisely the gesture
     * §5.2 of the phase-3 plan names as the reason the design is field-level at all, so getting it
     * wrong costs the feature its whole justification. `TwoDeviceSyncTest.a pin on one device and
     * an edit on the other both survive` is the check, and it failed before this existed.
     *
     * `SAVE_NOTE_FIELDS`'s own KDoc already stated the rule — *listing a field here claims a clock
     * for a value this write never set* — and applied it only to the fields the statement omits.
     * This applies it to the fields the statement writes unchanged, which is the same rule.
     *
     * ## The cost
     *
     * One full-row read per save, where there used to be a read of four small columns. It is inside
     * the transaction that is about to write the same row, so it is one extra scan of a page the
     * database has just had to find anyway, and `content` can be large. That is a real cost on the
     * editor's autosave path and it is accepted deliberately: the alternative is comparing only the
     * cheap columns, which would leave `content` always claimed and re-open the same hole for the
     * mirror-image gesture — edit on the phone, save-without-typing on the tablet.
     *
     * `updatedAt` is always in the result. The statement sets it to now unconditionally, and unlike
     * the other five that genuinely is a new value every time.
     */
    private fun savedNoteFields(prior: NoteEntity?, note: Note): Set<String> {
        // A row that does not exist yet has every field at this write's clock, which
        // `FieldClocks.stamp` spells as the empty string whatever it is told; naming them all here
        // keeps the two answers from having to agree by accident.
        if (prior == null) return SAVE_NOTE_FIELDS

        val touched = mutableSetOf(FieldClocks.UPDATED_AT)
        if (prior.title != note.title) touched += FieldClocks.TITLE
        // `content` and `contentFormat` are one value with one clock, so either changing is the
        // value changing. Comparing them separately would be the first step towards clocking them
        // separately, which both `FieldClocks` and `NoteDao.upsertNote` call silent corruption.
        if (prior.content != note.content || prior.contentFormat != note.contentFormat.storageValue) {
            touched += FieldClocks.CONTENT
        }
        if (prior.checklist != note.checklist) touched += FieldClocks.CHECKLIST
        if (prior.isPinned != note.isPinned) touched += FieldClocks.PINNED
        if (prior.folderId != note.folderId) touched += FieldClocks.FOLDER
        return touched
    }

    override suspend fun deleteNote(id: String) {
        // Soft: the row stays, flagged and stamped, until the user restores it or the retention
        // window runs out. The hard DELETE lives in purgeNote.
        val stamp = stamp()
        database.withTransaction {
            val prior = noteDao.rowClock(id)
            noteDao.softDeleteNote(
                id = id,
                timestamp = stamp.wallMs,
                hlcMs = stamp.hlc.ms,
                hlcCounter = stamp.hlc.counter,
                hlcNode = stamp.hlc.node,
                fieldHlc = noteFieldHlc(prior, TOMBSTONE_FIELDS, stamp),
            )
        }
    }

    override suspend fun restoreNote(id: String) {
        val stamp = stamp()
        database.withTransaction {
            val prior = noteDao.rowClock(id)
            noteDao.restoreNote(
                id = id,
                hlcMs = stamp.hlc.ms,
                hlcCounter = stamp.hlc.counter,
                hlcNode = stamp.hlc.node,
                fieldHlc = noteFieldHlc(prior, TOMBSTONE_FIELDS, stamp),
            )
        }
    }

    /**
     * The hard DELETE. Leaves no tombstone, so from another device's point of view the record
     * simply stops changing rather than being deleted.
     *
     * **Deliberately not gated on sync state, and that is a known gap rather than a decision.**
     * Once a purged row has been pushed, another device still holds it and will hand it back on
     * the next pull — a resurrection. Closing that needs the two callers separated: the Trash
     * screen's "delete forever" is a user asking for exactly this, while the editor's discard of a
     * blank note is only safe while the row has never been pushed
     * (`dirty = 1 AND lastSyncedSeq = 0`). Both belong with the engine that can act on the
     * difference; see `docs/design/e2e-sync-open-questions.md` §4, hazards 3 and 4.
     */
    override suspend fun purgeNote(id: String) {
        noteDao.purgeNote(id)
    }

    override fun getDeletedNotes(): Flow<List<Note>> {
        return noteDao.getDeletedNotes().map { list -> list.map { it.toDomain() } }
    }

    override suspend fun setNoteFolder(noteId: String, folderId: String?) {
        val stamp = stamp()
        database.withTransaction {
            val prior = noteDao.rowClock(noteId)
            noteDao.setNoteFolder(
                noteId = noteId,
                folderId = folderId,
                hlcMs = stamp.hlc.ms,
                hlcCounter = stamp.hlc.counter,
                hlcNode = stamp.hlc.node,
                fieldHlc = noteFieldHlc(prior, setOf(FieldClocks.FOLDER), stamp),
            )
        }
    }

    override suspend fun setNoteFavorite(noteId: String, isFavorite: Boolean) {
        val stamp = stamp()
        database.withTransaction {
            val prior = noteDao.rowClock(noteId)
            noteDao.setNoteFavorite(
                noteId = noteId,
                isFavorite = isFavorite,
                hlcMs = stamp.hlc.ms,
                hlcCounter = stamp.hlc.counter,
                hlcNode = stamp.hlc.node,
                fieldHlc = noteFieldHlc(prior, setOf(FieldClocks.FAVORITE), stamp),
            )
        }
    }

    override suspend fun setNotePinned(noteId: String, isPinned: Boolean) {
        val stamp = stamp()
        database.withTransaction {
            val prior = noteDao.rowClock(noteId)
            noteDao.setNotePinned(
                noteId = noteId,
                isPinned = isPinned,
                hlcMs = stamp.hlc.ms,
                hlcCounter = stamp.hlc.counter,
                hlcNode = stamp.hlc.node,
                fieldHlc = noteFieldHlc(prior, setOf(FieldClocks.PINNED), stamp),
            )
        }
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
        val stamp = stamp()
        database.withTransaction {
            val prior = folderDao.folderRow(folder.id)
            folderDao.upsertFolder(
                id = folder.id,
                name = folder.name,
                colorArgb = folder.colorArgb,
                timestamp = stamp.wallMs,
                hlcMs = stamp.hlc.ms,
                hlcCounter = stamp.hlc.counter,
                hlcNode = stamp.hlc.node,
                fieldHlc = folderFieldHlc(prior?.clocks(), savedFolderFields(prior, folder), stamp),
            )
        }
    }

    /**
     * `savedNoteFields` for a folder — read that method; the rule and the reason are identical.
     *
     * The gesture it protects here is renaming a folder on one device while recolouring it on
     * another: two independent values, one statement that writes both, and without this the later
     * save silently reverts the other device's edit.
     */
    private fun savedFolderFields(prior: FolderEntity?, folder: Folder): Set<String> {
        if (prior == null) return SAVE_FOLDER_FIELDS

        val touched = mutableSetOf(FieldClocks.UPDATED_AT)
        if (prior.name != folder.name) touched += FieldClocks.NAME
        if (prior.colorArgb != folder.colorArgb) touched += FieldClocks.COLOR
        return touched
    }

    override suspend fun deleteFolder(id: String) {
        // Unfile the folder's notes, then flag the folder — atomically, so a failure can't leave
        // notes pointing at a folder that is no longer in the chip row.
        //
        // The notes are unfiled rather than remembered, which is the pre-existing behaviour the
        // confirm dialog already describes ("its N notes will move to All"). Restoring the folder
        // therefore brings back an empty folder; see restoreFolder.
        //
        // ONE stamp for the whole thing. Both halves are one user action, so both get the same
        // wall-clock time (as they always did — `now` was already shared) and, now, the same
        // hybrid logical clock. Every unfiled note lands at the same point in the account's
        // history as the folder's tombstone, which is what a merge on the other device needs in
        // order to see them as one event rather than N+1 unrelated ones.
        val stamp = stamp()
        database.withTransaction {
            // One read for every affected note, rather than one per note: each row's new fieldHlc
            // depends on the clocks that row is already carrying. See NoteDao.clearFolderForNote
            // for why this cannot be a single UPDATE any more.
            noteDao.rowClocksInFolder(id).forEach { row ->
                noteDao.clearFolderForNote(
                    noteId = row.id,
                    timestamp = stamp.wallMs,
                    hlcMs = stamp.hlc.ms,
                    hlcCounter = stamp.hlc.counter,
                    hlcNode = stamp.hlc.node,
                    fieldHlc = noteFieldHlc(row.clocks(), CLEAR_FOLDER_FIELDS, stamp),
                )
            }
            val prior = folderDao.rowClock(id)
            folderDao.softDeleteFolder(
                id = id,
                timestamp = stamp.wallMs,
                hlcMs = stamp.hlc.ms,
                hlcCounter = stamp.hlc.counter,
                hlcNode = stamp.hlc.node,
                fieldHlc = folderFieldHlc(prior, TOMBSTONE_FIELDS, stamp),
            )
        }
    }

    override suspend fun restoreFolder(id: String) {
        val stamp = stamp()
        database.withTransaction {
            val prior = folderDao.rowClock(id)
            folderDao.restoreFolder(
                id = id,
                hlcMs = stamp.hlc.ms,
                hlcCounter = stamp.hlc.counter,
                hlcNode = stamp.hlc.node,
                fieldHlc = folderFieldHlc(prior, TOMBSTONE_FIELDS, stamp),
            )
        }
    }

    /** The hard DELETE for a folder. Carries the same un-gated resurrection risk as [purgeNote]. */
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

    private companion object {
        /**
         * The fields `upsertNote` writes.
         *
         * `isFavorite` and the tombstone are absent because the statement genuinely does not write
         * them — the editor does not own those fields. Listing them here would claim a clock for a
         * value this write never set, and the next merge would use that claim to discard the other
         * device's favourite or delete. `createdAt` is absent because `FieldClocks` does not clock
         * it at all; see NOTE_FIELDS.
         */
        val SAVE_NOTE_FIELDS = setOf(
            FieldClocks.TITLE,
            FieldClocks.CONTENT,
            FieldClocks.CHECKLIST,
            FieldClocks.PINNED,
            FieldClocks.FOLDER,
            FieldClocks.UPDATED_AT,
        )

        /** What `upsertFolder` writes. */
        val SAVE_FOLDER_FIELDS = setOf(
            FieldClocks.NAME,
            FieldClocks.COLOR,
            FieldClocks.UPDATED_AT,
        )

        /**
         * What a soft delete or a restore writes, on either table: the tombstone and nothing else.
         *
         * `updatedAt` is NOT in it, because neither statement touches `updatedAt` — trashing a note
         * is not editing it.
         */
        val TOMBSTONE_FIELDS = setOf(FieldClocks.DELETED)

        /** What unfiling a note during a folder delete writes; this one DOES bump `updatedAt`. */
        val CLEAR_FOLDER_FIELDS = setOf(FieldClocks.FOLDER, FieldClocks.UPDATED_AT)
    }
}
