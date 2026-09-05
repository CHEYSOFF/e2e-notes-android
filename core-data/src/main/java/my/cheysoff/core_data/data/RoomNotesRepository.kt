package my.cheysoff.core_data.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import my.cheysoff.core_data.data.local.AttachmentDao
import my.cheysoff.core_data.data.local.AttachmentEntity
import my.cheysoff.core_data.data.local.FolderDao
import my.cheysoff.core_data.data.local.FolderEntity
import my.cheysoff.core_data.data.local.NoteDao
import my.cheysoff.core_data.data.local.NoteEntity
import my.cheysoff.core_data.data.local.NoteDatabase
import my.cheysoff.core_data.data.local.RowClock
import my.cheysoff.core_data.data.local.SketchDao
import my.cheysoff.core_data.data.local.toDomain
import my.cheysoff.core_data.data.sync.SyncClock
import my.cheysoff.core_data.data.sync.SyncStamp
import my.cheysoff.core_domain.model.AttachmentData
import my.cheysoff.core_domain.model.AttachmentPreview
import my.cheysoff.core_domain.model.Folder
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NotesSortOrder
import my.cheysoff.core_domain.model.TrashPolicy
import my.cheysoff.core_domain.repository.AttachmentsRepository
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
    private val sketchDao: SketchDao,
    private val attachmentDao: AttachmentDao,
    private val database: NoteDatabase,
    private val clock: SyncClock,
) : NotesRepository, AttachmentsRepository {

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
                    // deleteNote (below) mints sketch clocks through this same stamp(), so this
                    // repository's seed has to cover `sketches` too: without it, a process whose
                    // very first write is a note deletion would tombstone that note's sketches
                    // using a generator that never observed the table's existing high-water mark,
                    // which is exactly the rewound-clock hazard the class KDoc describes.
                    sketchDao.highestRowClock()?.let { clock.observe(it.rowHlc()) }
                    // Same argument, for `attachments`: deleteNote's cascade below mints attachment
                    // clocks through this same stamp() too.
                    attachmentDao.highestRowClock()?.let { clock.observe(it.rowHlc()) }
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

    /** [noteFieldHlc] for the `sketches` table — used only by [deleteNote]'s cascade below. */
    private fun sketchFieldHlc(prior: RowClock?, touched: Set<String>, stamp: SyncStamp): String =
        FieldClocks.stamp(
            previousSerialized = prior?.fieldHlc ?: "",
            previousRowClock = prior?.rowHlc(),
            allFields = FieldClocks.SKETCH_FIELDS,
            touched = touched,
            newClock = stamp.hlc,
        )

    /** [noteFieldHlc] for the `attachments` table. */
    private fun attachmentFieldHlc(prior: RowClock?, touched: Set<String>, stamp: SyncStamp): String =
        FieldClocks.stamp(
            previousSerialized = prior?.fieldHlc ?: "",
            previousRowClock = prior?.rowHlc(),
            allFields = FieldClocks.ATTACHMENT_FIELDS,
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

    /**
     * Soft-deletes the note, then tombstones its sketches and its attachments — in the same
     * transaction, as separate records with their own tombstones, never through `ON DELETE
     * CASCADE`.
     *
     * **Why reconciliation, not cascade.** A cascade would run only on the device that performed
     * this delete. The other device would still hold the note's tombstone but nothing telling it
     * the note's sketches (or attachments) are gone — and because a dirty child record pushes
     * independently of its note, it would push the still-live copy right back. `SketchEntity`'s and
     * `AttachmentEntity`'s KDocs are why neither table has a foreign key at all. What both an
     * aware and an unaware build can honour instead is: a child record whose note is deleted is
     * *treated* as deleted. This method is that rule enacted by the device that did the deleting;
     * `RoomSyncStore.applyMerged`'s SKETCH branch enacts the same rule for a sketch that *arrives*
     * pointing at a note this device already knows is gone (Task 4's job for attachments).
     *
     * **Each child record gets its own HLC, not the note's — but shares the note's `deletedAt`.**
     * `deleteFolder` shares one whole stamp across many rows because unfiling N notes and trashing
     * the folder is one user gesture the account's history should record as one moment. A sketch's
     * tombstone is that AND something more: each sketch is its own record with its own dirty flag
     * and its own push, so it still needs a real, distinct HLC advance — not a borrowed one, and
     * "own clock bump" is the brief's own wording for exactly that. But the *wall-clock* instant is
     * different: three drawings tombstoned by one note-delete are, honestly, one deletion event,
     * and giving them the note's own `deletedAt` — rather than three slightly different
     * `System.currentTimeMillis()` reads — is what lets [restoreNote] tell "tombstoned BY THIS
     * delete" apart from "the user deleted this sketch individually, earlier": an exact match on
     * `deletedAt` against the note's own, not a heuristic.
     */
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
            sketchDao.activeSketchesForNote(id).forEach { sketch ->
                val sketchStamp = stamp()
                sketchDao.softDeleteSketch(
                    uuid = sketch.uuid,
                    // The note's own wall-clock instant, not the sketch's own — see this method's
                    // KDoc. Everything else (the HLC) is still this write's own.
                    timestamp = stamp.wallMs,
                    hlcMs = sketchStamp.hlc.ms,
                    hlcCounter = sketchStamp.hlc.counter,
                    hlcNode = sketchStamp.hlc.node,
                    fieldHlc = sketchFieldHlc(sketch.clocks(), TOMBSTONE_FIELDS, sketchStamp),
                )
            }
            // Each attachment gets the NOTE's deletedAt but its OWN clock, minted through the shared
            // generator. Reusing the note's clock, or minting locally, both end the same way: a later
            // restoreNote mints below the tombstone, the restore looks right on this device, and the
            // other device keeps the image deleted forever with nothing anywhere saying why. See
            // `docs/design/image-attachments.md` §6 and `AttachmentDao`'s KDoc.
            attachmentDao.activeAttachmentsForNote(id).forEach { attachment ->
                val attachmentStamp = stamp()
                attachmentDao.softDeleteAttachment(
                    uuid = attachment.uuid,
                    timestamp = stamp.wallMs,
                    hlcMs = attachmentStamp.hlc.ms,
                    hlcCounter = attachmentStamp.hlc.counter,
                    hlcNode = attachmentStamp.hlc.node,
                    fieldHlc = attachmentFieldHlc(attachment.clocks(), TOMBSTONE_FIELDS, attachmentStamp),
                )
            }
        }
    }

    /**
     * Restores the note, then un-tombstones exactly the sketches [deleteNote] tombstoned along
     * with it — never every sketch under the note, and never through `ON DELETE CASCADE`'s mirror
     * image either.
     *
     * The note's `deletedAt` is read BEFORE `noteDao.restoreNote` clears it, because that value is
     * the only thing distinguishing "this sketch died when the note did" from "this sketch was
     * already dead beforehand": [SketchDao.sketchesDeletedAtForNote] matches sketches whose own
     * `deletedAt` is at or after it. `>=` rather than `==` because the note's tombstone and each
     * sketch's tombstone are independently clocked records — a note deleted concurrently on two
     * devices can converge to a note `deletedAt` earlier than a sketch tombstoned by that same
     * deletion on the other device, since each record's DELETED field merges on its own clock. A
     * sketch the user deleted individually before the note was ever trashed still carries a
     * strictly earlier `deletedAt` and is correctly left alone either way — restoring the note must
     * not resurrect a drawing the user deliberately deleted.
     *
     * If the note was never actually in Trash, `noteRow(id)?.deletedAt` is null and no sketch query
     * runs at all: `restoreNote`'s own KDoc already notes it carries no `isDeleted` guard and is a
     * harmless no-op-ish re-stamp in that case, and null can never equal a real sketch `deletedAt`
     * regardless, so skipping the query changes nothing but avoids a pointless one.
     */
    override suspend fun restoreNote(id: String) {
        val stamp = stamp()
        database.withTransaction {
            val prior = noteDao.rowClock(id)
            val deletedAt = noteDao.noteRow(id)?.deletedAt
            noteDao.restoreNote(
                id = id,
                hlcMs = stamp.hlc.ms,
                hlcCounter = stamp.hlc.counter,
                hlcNode = stamp.hlc.node,
                fieldHlc = noteFieldHlc(prior, TOMBSTONE_FIELDS, stamp),
            )
            if (deletedAt != null) {
                sketchDao.sketchesDeletedAtForNote(id, deletedAt).forEach { sketch ->
                    val sketchStamp = stamp()
                    sketchDao.restoreSketch(
                        uuid = sketch.uuid,
                        hlcMs = sketchStamp.hlc.ms,
                        hlcCounter = sketchStamp.hlc.counter,
                        hlcNode = sketchStamp.hlc.node,
                        fieldHlc = sketchFieldHlc(sketch.clocks(), TOMBSTONE_FIELDS, sketchStamp),
                    )
                }
                attachmentDao.attachmentsDeletedAtForNote(id, deletedAt).forEach { attachment ->
                    val attachmentStamp = stamp()
                    attachmentDao.restoreAttachment(
                        uuid = attachment.uuid,
                        hlcMs = attachmentStamp.hlc.ms,
                        hlcCounter = attachmentStamp.hlc.counter,
                        hlcNode = attachmentStamp.hlc.node,
                        fieldHlc = attachmentFieldHlc(attachment.clocks(), TOMBSTONE_FIELDS, attachmentStamp),
                    )
                }
            }
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
        database.withTransaction {
            noteDao.purgeNote(id)
            // A blank note discarded with an unsaved drawing still has a live sketch row pointing
            // at it (see SingleNoteViewModel.BackClicked) unless this runs: purgeNote has no FK to
            // cascade through (SketchEntity is deliberately unlinked — see SketchDao's KDoc), so
            // without this call the sketch would survive, live and dirty, under an id that no note
            // will ever hold again.
            sketchDao.purgeSketchesForNote(id)
            // Same argument for attachments: AttachmentEntity is deliberately unlinked too (see
            // AttachmentDao's KDoc), so a blank note discarded with an unsaved photo attached would
            // otherwise leave the attachment behind, live and dirty, under a noteId nothing will
            // ever hold again.
            attachmentDao.purgeAttachmentsForNote(id)
        }
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
        // One transaction so the four deletes are one observable step. They are independent — a
        // folder's notes were unfiled when it was trashed, and a sketch's or attachment's tombstone
        // stamp is its own (see deleteNote), so none of the four tables references another here —
        // but a single transaction also means Room fires one invalidation instead of four, and the
        // Trash list re-renders once.
        //
        // Sketches and attachments MUST be purged on the same pass as notes and folders: each is
        // tombstoned independently of its note and so ages out of Trash independently too. Leaving
        // either out would let a tombstoned child record outlive the note it was purged with — a
        // leak that never self-heals, since nothing else ever purges that table.
        return database.withTransaction {
            noteDao.purgeNotesDeletedBefore(threshold) +
                folderDao.purgeFoldersDeletedBefore(threshold) +
                sketchDao.purgeSketchesDeletedBefore(threshold) +
                attachmentDao.purgeAttachmentsDeletedBefore(threshold)
        }
    }

    // ── Attachments. AttachmentsRepository's own KDoc explains why this lives on a separate ──────
    // ── interface, the same shape sketches already have with SketchesRepository. ─────────────────

    override fun attachmentsOf(noteId: String): Flow<List<AttachmentPreview>> =
        attachmentDao.attachmentPreviewsByNoteId(noteId).map { list -> list.map { it.toDomain() } }

    override suspend fun attachment(id: String): AttachmentData? =
        attachmentDao.attachmentRow(id)?.takeUnless { it.isDeleted }?.toDomain()

    /**
     * Creates or updates an attachment. Mirrors `RoomSketchesRepository.saveSketch` — one stamp,
     * one transaction, the row's prior clocks read inside it so [attachmentTouchedFields] can tell
     * what this write actually changed.
     *
     * ## `attachment.meta` is deliberately ignored
     *
     * The stored row's `meta` is carried forward and the incoming object's is discarded — this
     * method has **no** path that writes a `meta` a caller supplied. That looks like a bug and is
     * the opposite: `meta` is an opaque escape hatch (`AttachmentData.meta`,
     * `PayloadFields.META`) that no code in this build ever sets, so the only value a caller can
     * plausibly be holding is one it read out of a row that a *newer* build wrote. UI code builds
     * an `AttachmentData` out of what an editor has in hand — the same way `upsertNote` ignores
     * `isFavorite` and the tombstone, and for the same reason — so the first caller that rebuilds
     * a row without carrying `meta` forward would blank it on a local edit and then push the
     * blank. Reading it off the row makes that impossible rather than merely discouraged.
     *
     * **A future caption feature must not simply start honouring `attachment.meta` here.** It
     * needs its own write path, so that "this call is deliberately setting `meta`" and "this call
     * happens to be holding a default-constructed one" stay distinguishable. `meta` is also absent
     * from [attachmentTouchedFields] and stays absent: it has no clock of its own and merges at
     * the row clock.
     */
    override suspend fun saveAttachment(attachment: AttachmentData) {
        val stamp = stamp()
        database.withTransaction {
            val prior = attachmentDao.attachmentRow(attachment.id)
            attachmentDao.upsertAttachment(
                AttachmentEntity(
                    uuid = attachment.id,
                    noteId = attachment.noteId,
                    anchor = attachment.anchor,
                    sortOrder = attachment.order,
                    mimeType = attachment.mimeType,
                    width = attachment.width,
                    height = attachment.height,
                    bytes = attachment.bytes,
                    thumbWidth = attachment.thumbWidth,
                    thumbHeight = attachment.thumbHeight,
                    thumbBytes = attachment.thumbBytes,
                    createdAt = attachment.createdAt,
                    updatedAt = attachment.updatedAt,
                    isDeleted = attachment.isDeleted,
                    deletedAt = attachment.deletedAt,
                    // The row's own, never the caller's -- see this method's KDoc.
                    meta = prior?.meta.orEmpty(),
                    hlcMs = stamp.hlc.ms,
                    hlcCounter = stamp.hlc.counter,
                    hlcNode = stamp.hlc.node,
                    fieldHlc = attachmentFieldHlc(prior?.clocks(), attachmentTouchedFields(prior, attachment), stamp),
                    dirty = true,
                    lastSyncedSeq = prior?.lastSyncedSeq ?: 0L,
                )
            )
        }
    }

    /**
     * Which of [AttachmentEntity]'s clocked fields this particular save actually changed — see
     * `savedNoteFields` for why "wrote" and "changed" are not the same question. `mimeType`,
     * `width`, `height` and `bytes` share [FieldClocks.IMAGE]; `thumbWidth`, `thumbHeight` and
     * `thumbBytes` share [FieldClocks.THUMB] — see that constant's KDoc for why the image and its
     * dimensions must move as one field rather than four independent ones.
     */
    private fun attachmentTouchedFields(prior: AttachmentEntity?, attachment: AttachmentData): Set<String> {
        if (prior == null) return FieldClocks.ATTACHMENT_FIELDS

        val touched = mutableSetOf(FieldClocks.UPDATED_AT)
        if (prior.noteId != attachment.noteId) touched += FieldClocks.NOTE_ID
        if (prior.anchor != attachment.anchor) touched += FieldClocks.ANCHOR
        if (prior.sortOrder != attachment.order) touched += FieldClocks.ORDER
        if (prior.mimeType != attachment.mimeType || prior.width != attachment.width ||
            prior.height != attachment.height || !prior.bytes.contentEquals(attachment.bytes)
        ) {
            touched += FieldClocks.IMAGE
        }
        if (prior.thumbWidth != attachment.thumbWidth || prior.thumbHeight != attachment.thumbHeight ||
            !prior.thumbBytes.contentEquals(attachment.thumbBytes)
        ) {
            touched += FieldClocks.THUMB
        }
        if (prior.isDeleted != attachment.isDeleted || prior.deletedAt != attachment.deletedAt) {
            touched += FieldClocks.DELETED
        }
        return touched
    }

    /**
     * Soft-deletes one attachment by id: its own tombstone, its own fresh clock, `dirty` set so it
     * is pushed. Mirrors [deleteNote]'s per-sketch/per-attachment cascade branch and
     * `RoomSketchesRepository.deleteSketch`.
     */
    override suspend fun deleteAttachment(id: String) {
        val stamp = stamp()
        database.withTransaction {
            val prior = attachmentDao.rowClock(id)
            attachmentDao.softDeleteAttachment(
                uuid = id,
                timestamp = stamp.wallMs,
                hlcMs = stamp.hlc.ms,
                hlcCounter = stamp.hlc.counter,
                hlcNode = stamp.hlc.node,
                fieldHlc = attachmentFieldHlc(prior, TOMBSTONE_FIELDS, stamp),
            )
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
