package my.cheysoff.core_data.data.sync

import androidx.room.withTransaction
import my.cheysoff.core_data.data.local.FolderDao
import my.cheysoff.core_data.data.local.NoteDao
import my.cheysoff.core_data.data.local.NoteDatabase
import my.cheysoff.core_data.data.local.SketchDao
import my.cheysoff.core_data.data.local.SketchEntity
import my.cheysoff.core_data.data.local.SyncStateDao
import my.cheysoff.core_domain.sync.FieldClocks
import my.cheysoff.core_domain.sync.Hlc
import my.cheysoff.core_domain.sync.RecordType
import my.cheysoff.core_sync_engine.HaltReason
import my.cheysoff.core_sync_engine.MergedWrite
import my.cheysoff.core_sync_engine.StoredRecord
import my.cheysoff.core_sync_engine.SyncEngine
import my.cheysoff.core_sync_engine.SyncStore

/**
 * `SyncStore` over Room: everything the sync engine keeps between passes, in the encrypted
 * database it already has.
 *
 * ## One account, one store
 *
 * [accountId] is a constructor argument rather than something read per call, because a store bound
 * to the wrong account is the failure `SyncStateEntity` warns about at length: a cursor from
 * another account is ahead of nothing, so pulling from it skips that account's whole history and
 * leaves the device convinced it is up to date. Binding it once, at the point the Account Root Key
 * is known, means no method here can be called for an account this object was not built for.
 *
 * ## Atomicity
 *
 * `SyncStore`'s contract is that **each method is one transaction**, and that is what the engine's
 * crash-safety argument rests on. Three of them are a single SQL statement and are therefore atomic
 * without help — [saveCursor], [recordSeen] and [acknowledgePush], the last being §3.2's two rules
 * as one conditional `UPDATE` (see `NoteDao.acknowledgeNotePush`, which is where the argument is).
 * [applyMerged] is the one that genuinely writes several rows, and it takes `withTransaction`: a
 * merged row written without its `lastSyncedSeq`, or a winner written without the conflict copy
 * that holds the body it displaced, is worse than neither being written.
 *
 * ## What it deliberately does not do
 *
 * It makes almost no decisions. Which record wins is `Merge`'s, when to move the cursor is
 * `SyncEngine`'s, and whether to sync at all is the app's. Nearly everything here is storage, and
 * two of the three places where it looks like more — the forwards-only cursor and the
 * first-halt-wins rule — are enforcements of rules stated elsewhere, put in SQL so that no future
 * caller can get them wrong.
 *
 * The one genuine exception is [reconcileAgainstNote], in [applyMerged]'s SKETCH branch: whether an
 * arriving sketch is stored live or corrected to a tombstone depends on this device's own local
 * state (the sketch's note), which `Merge` cannot see — a merge compares two versions of the *same*
 * record, and this is a rule about *two different* records. See that method's KDoc, and Task 7's
 * `RoomNotesRepository.deleteNote` for the other half of the same rule.
 */
class RoomSyncStore(
    private val database: NoteDatabase,
    private val noteDao: NoteDao,
    private val folderDao: FolderDao,
    private val sketchDao: SketchDao,
    private val syncStateDao: SyncStateDao,
    private val accountId: String,
    private val wallClock: () -> Long = System::currentTimeMillis,
) : SyncStore {

    override suspend fun cursor(): Long = syncStateDao.get(accountId)?.cursor ?: 0L

    /**
     * Forwards only, and that guard is in the SQL rather than here — see
     * [SyncStateDao.advanceCursor]. The engine never calls this with a seq below the stored one, so
     * the guard is not for the engine; it is for the next caller.
     */
    override suspend fun saveCursor(seq: Long) =
        syncStateDao.advanceCursor(accountId, seq, wallClock())

    override suspend fun load(type: RecordType, uuid: String): StoredRecord? = when (type) {
        RecordType.NOTE -> noteDao.noteRow(uuid)?.let { note ->
            StoredRecord(
                record = RecordRows.toRecord(note),
                dirty = note.dirty,
                lastSyncedSeq = note.lastSyncedSeq,
                // `''` is "no agreement recorded", which is not the same as an agreement at the
                // zero clock: the merge reads null as "fall back to the conservative rule" and a
                // real clock as "anything at or below this is an ancestor". Parsing `''` into
                // `Hlc.ZERO` would tell it every body it has ever held was already published.
                contentBaseline = Hlc.parse(note.contentSyncedHlc),
            )
        }

        RecordType.FOLDER -> folderDao.folderRow(uuid)?.let { folder ->
            StoredRecord(
                record = RecordRows.toRecord(folder),
                dirty = folder.dirty,
                lastSyncedSeq = folder.lastSyncedSeq,
                // A folder has no body, so it can never conflict-copy and has no baseline column.
                contentBaseline = null,
            )
        }

        RecordType.SKETCH -> sketchDao.sketchRow(uuid)?.let { sketch ->
            StoredRecord(
                record = RecordRows.toRecord(sketch),
                dirty = sketch.dirty,
                lastSyncedSeq = sketch.lastSyncedSeq,
                // A sketch has no body, so it can never conflict-copy and has no baseline column
                // -- the same reasoning FOLDER's branch above gives.
                contentBaseline = null,
            )
        }
    }

    /**
     * Every dirty row of both tables, oldest row clock first.
     *
     * The two tables are read in their own ordered queries and merged here rather than in a
     * `UNION` — Room cannot map one query onto two entity types, and a `UNION` over the columns
     * they share would drop the ones they do not. The sort is redone over the merged list because
     * two individually-ordered lists concatenated are not an ordered list, and the order is part of
     * the contract: it is what makes a week offline push as a history rather than a shuffle, and
     * what makes a failing convergence seed replay identically.
     *
     * The tie-break is `(type, uuid)` and is total, which matters more than which order it picks:
     * two rows minted in the same millisecond by the same generator cannot happen, but two rows
     * carrying the zero clock — everything migrated in before v7 — tie on all three components.
     */
    override suspend fun dirtyRecords(): List<StoredRecord> {
        val notes = noteDao.dirtyNotes().map { note ->
            StoredRecord(
                record = RecordRows.toRecord(note),
                dirty = true,
                lastSyncedSeq = note.lastSyncedSeq,
                contentBaseline = Hlc.parse(note.contentSyncedHlc),
            )
        }
        val folders = folderDao.dirtyFolders().map { folder ->
            StoredRecord(
                record = RecordRows.toRecord(folder),
                dirty = true,
                lastSyncedSeq = folder.lastSyncedSeq,
                contentBaseline = null,
            )
        }
        val sketches = sketchDao.dirtySketches().map { sketch ->
            StoredRecord(
                record = RecordRows.toRecord(sketch),
                dirty = true,
                lastSyncedSeq = sketch.lastSyncedSeq,
                contentBaseline = null,
            )
        }
        return (notes + folders + sketches).sortedWith(
            compareBy({ it.record.rowClock }, { it.record.type }, { it.record.uuid }),
        )
    }

    /**
     * The merged row, its bookkeeping, and any conflict copy — one transaction.
     *
     * The full-row path (`applyRemoteNote`/`applyRemoteFolder`), never `upsertNote`: that method's
     * conflict branch deliberately refuses to write `isFavorite`, `isDeleted` and `deletedAt`, so a
     * remote delete routed through it would be dropped and the note would come back from the dead
     * on every device (§5.6).
     */
    override suspend fun applyMerged(write: MergedWrite): Unit = database.withTransaction {
        when (write.record.type) {
            RecordType.NOTE -> {
                noteDao.applyRemoteNote(
                    RecordRows.toNoteEntity(
                        record = write.record,
                        createdAt = RecordRows.createdAtFor(
                            existing = noteDao.noteRow(write.record.uuid)?.createdAt,
                            remote = write.remoteCreatedAt,
                            record = write.record,
                        ),
                        dirty = write.dirty,
                        lastSyncedSeq = write.seq,
                        contentBaseline = write.contentBaseline,
                    )
                )
            }

            RecordType.FOLDER -> {
                folderDao.applyRemoteFolder(
                    RecordRows.toFolderEntity(
                        record = write.record,
                        createdAt = RecordRows.createdAtFor(
                            existing = folderDao.folderRow(write.record.uuid)?.createdAt,
                            remote = write.remoteCreatedAt,
                            record = write.record,
                        ),
                        dirty = write.dirty,
                        lastSyncedSeq = write.seq,
                    )
                )
            }

            RecordType.SKETCH -> {
                val incoming = RecordRows.toSketchEntity(
                    record = write.record,
                    createdAt = RecordRows.createdAtFor(
                        existing = sketchDao.sketchRow(write.record.uuid)?.createdAt,
                        remote = write.remoteCreatedAt,
                        record = write.record,
                    ),
                    dirty = write.dirty,
                    lastSyncedSeq = write.seq,
                )
                sketchDao.upsertSketch(reconcileAgainstNote(incoming))
            }
        }

        write.conflictCopy?.let { copy ->
            noteDao.applyRemoteNote(
                RecordRows.toNoteEntity(
                    record = copy,
                    // The copy is new here by construction — the engine only sets it when no record
                    // with that uuid exists — so there is nothing to preserve and its own
                    // `updatedAt` is the honest creation time. `remote` is null for the same
                    // reason: the incoming record's creation time belongs to the record it
                    // arrived as, not to a copy this device is minting from a losing body.
                    createdAt = RecordRows.createdAtFor(existing = null, remote = null, record = copy),
                    dirty = true,
                    // It has never been on the server, which the server reads as "must not exist".
                    lastSyncedSeq = 0L,
                    contentBaseline = null,
                )
            )
        }
    }

    /**
     * Deletion by reconciliation, not cascade — the other half of the rule
     * `RoomNotesRepository.deleteNote` enacts for a delete performed *on this device*. This is for
     * a sketch that *arrives* pointing at a note this device already has an opinion about.
     *
     * Three cases, and getting the last two right is the whole point (Task 7):
     *
     * 1. **[entity] already says deleted.** Nothing to reconcile — trust the wire, unchanged. This
     *    is also what a sketch cascaded by a sketch-aware deleter looks like on arrival: its own
     *    tombstone, already correct.
     * 2. **The note is unknown locally** ([NoteDao.noteRow] returns null). Keep [entity] exactly as
     *    it arrived. Records arrive in `seq` order, not dependency order, so an unknown note is not
     *    evidence of anything — it may land later in this same pull or a later one. Treating
     *    "unknown" as "deleted" here would discard a drawing over an ordering accident, which is
     *    the trap this method exists to avoid.
     * 3. **The note is known and tombstoned, but [entity] arrived live anyway.** This is the case a
     *    cascade cannot reach: a build with no sketch support deletes a note, pushes only the
     *    note's tombstone, and the sketch — never re-pushed — later arrives (e.g. this is a new
     *    device's first pull, or a stale device flushes an old dirty sketch after the delete) still
     *    claiming to be live. [entity] is corrected to a tombstone before it is ever stored, and
     *    marked dirty so the correction is pushed back — a tombstone this device does not also
     *    publish converges nowhere.
     *
     * The DELETED field's clock is reset to [entity]'s own row clock rather than left at whatever
     * (older, live) value the wire carried, or bumped to some new clock this store has no generator
     * to mint: the row clock is already, by construction, at least as new as every field on the
     * record, so asserting DELETED as of that same instant invents no information and keeps the
     * per-field-clock invariant — "an entry absent from `fieldHlc` is at the row clock" — honest.
     *
     * **This is the one place in the codebase where two devices can independently produce the same
     * clock for the same field on two different values.** Two devices that each reconcile the same
     * incoming record deterministically stamp DELETED at that record's own row clock — not a freshly
     * minted one — so `Merge.takeGreater`'s "should be unreachable" equal-clock branch is genuinely
     * reachable here, across devices, rather than only in theory. It still converges, because
     * `FieldValue("1", ts) > FieldValue("0", null)` lexically and the tombstone wins either way — but
     * that is a property of the tiebreak's total order, not of this method, and a future change to
     * that tiebreak (e.g. deciding equal clocks some other way) would reintroduce delete/undelete
     * ping-pong between devices for exactly this record shape.
     */
    private suspend fun reconcileAgainstNote(entity: SketchEntity): SketchEntity {
        if (entity.isDeleted) return entity
        val note = noteDao.noteRow(entity.noteId) ?: return entity
        if (!note.isDeleted) return entity

        return entity.copy(
            isDeleted = true,
            deletedAt = note.deletedAt ?: entity.updatedAt,
            dirty = true,
            fieldHlc = FieldClocks.stamp(
                previousSerialized = entity.fieldHlc,
                previousRowClock = entity.rowHlc(),
                allFields = FieldClocks.SKETCH_FIELDS,
                touched = setOf(FieldClocks.DELETED),
                newClock = entity.rowHlc(),
            ),
        )
    }

    override suspend fun recordSeen(
        type: RecordType,
        uuid: String,
        seq: Long,
        contentBaseline: Hlc?,
    ) = when (type) {
        RecordType.NOTE ->
            noteDao.recordNoteSeen(uuid, seq, contentBaseline?.toString().orEmpty())

        RecordType.FOLDER -> folderDao.recordFolderSeen(uuid, seq)

        RecordType.SKETCH -> sketchDao.recordSketchSeen(uuid, seq)
    }

    /**
     * §3.2's two rules, as the one conditional `UPDATE` that is the only way to make them one
     * event. `NoteDao.acknowledgeNotePush` carries the argument; nothing is decided here.
     */
    override suspend fun acknowledgePush(
        type: RecordType,
        uuid: String,
        sealedRowClock: Hlc,
        seq: Long,
        contentBaseline: Hlc?,
    ) = when (type) {
        RecordType.NOTE -> noteDao.acknowledgeNotePush(
            id = uuid,
            seq = seq,
            sealedMs = sealedRowClock.ms,
            sealedCounter = sealedRowClock.counter,
            sealedNode = sealedRowClock.node,
            contentSyncedHlc = contentBaseline?.toString().orEmpty(),
        )

        RecordType.FOLDER -> folderDao.acknowledgeFolderPush(
            id = uuid,
            seq = seq,
            sealedMs = sealedRowClock.ms,
            sealedCounter = sealedRowClock.counter,
            sealedNode = sealedRowClock.node,
        )

        RecordType.SKETCH -> sketchDao.acknowledgeSketchPush(
            uuid = uuid,
            seq = seq,
            sealedMs = sealedRowClock.ms,
            sealedCounter = sealedRowClock.counter,
            sealedNode = sealedRowClock.node,
        )
    }

    /**
     * The stored halt, or null while the engine is healthy.
     *
     * A stored name this build does not recognise is **still a halt**, and is reported as the
     * reason with the widest response — a rolled-back server needs an explicit re-baseline, which
     * is the correct thing to demand of a halt whose cause this build cannot name. Reading it as
     * "healthy" would be an older build quietly resuming a sync a newer one had stopped.
     */
    override suspend fun halt(): HaltReason? {
        val stored = syncStateDao.get(accountId)?.haltReason.orEmpty()
        if (stored.isEmpty()) return null
        return HaltReason.entries.firstOrNull { it.name == stored } ?: HaltReason.SERVER_ROLLED_BACK
    }

    /** Idempotent, keeping the first reason — the guard is in [SyncStateDao.recordHalt]. */
    override suspend fun recordHalt(reason: HaltReason) =
        syncStateDao.recordHalt(accountId, reason.name)

    override suspend fun clearHalt() = syncStateDao.clearHalt(accountId)

    /**
     * The raw stored value, with only a genuinely absent row falling back to
     * [SyncEngine.DATA_VERSION] — **not** a stored `0`.
     *
     * An earlier version of this method also masked `0`, on the theory that it was
     * indistinguishable from "no row" and so had to mean the same thing. That was wrong, and it is
     * why [SyncEngine]'s generation write never fired for a device that reached `0` honestly:
     * `advanceCursor`'s INSERT never names `dataVersion`, so *every* device's first completed pull
     * leaves this column at its own `NOT NULL DEFAULT 0` -- a real, meaningful "pulled once,
     * generation unrecorded, therefore behind" -- and masking it to [SyncEngine.DATA_VERSION] told
     * the engine that state was already current, so it never wrote anything to correct it. A
     * device could sit at that masked `0` forever.
     *
     * `0` can never be a genuine *recorded* generation -- [SyncEngine.DATA_VERSION] starts at 1
     * and only increases -- so returning it here loses nothing a caller could have written; it
     * only stops pretending an unrecorded row is a current one.
     */
    override suspend fun dataVersion(): Int =
        syncStateDao.dataVersion(accountId) ?: SyncEngine.DATA_VERSION

    override suspend fun saveDataVersion(version: Int) =
        syncStateDao.saveDataVersion(accountId, version)
}
