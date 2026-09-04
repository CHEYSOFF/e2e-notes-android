package my.cheysoff.core_data.data.sync

import androidx.room.withTransaction
import my.cheysoff.core_data.data.local.FolderDao
import my.cheysoff.core_data.data.local.NoteDao
import my.cheysoff.core_data.data.local.NoteDatabase
import my.cheysoff.core_data.data.local.NoteEntity
import my.cheysoff.core_data.data.local.SketchDao
import my.cheysoff.core_data.data.local.SketchEntity
import my.cheysoff.core_data.data.local.SyncStateDao
import my.cheysoff.core_domain.sync.FieldClocks
import my.cheysoff.core_domain.sync.Hlc
import my.cheysoff.core_domain.sync.RecordType
import my.cheysoff.core_sync_engine.ClockObserver
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
 * The genuine exceptions are [reconcileAgainstNote] and [tombstoneLiveSketchesOf], the two halves
 * of one rule enacted in both directions `applyMerged` can see it from: whether a sketch is live or
 * tombstoned depends on this device's own local state of *a different record* (the sketch's note),
 * which `Merge` cannot see — a merge compares two versions of the *same* record. The SKETCH branch
 * runs [reconcileAgainstNote] when a sketch arrives pointing at a note this device already knows is
 * gone; the NOTE branch runs [tombstoneLiveSketchesOf] when a note arrives and turns out to be the
 * one transitioning into deleted, for the sketches this device already holds live under it. See
 * both methods' KDoc, and Task 7's `RoomNotesRepository.deleteNote` for the local-delete cascade
 * these two mirror on the sync-arrival side.
 */
class RoomSyncStore(
    private val database: NoteDatabase,
    private val noteDao: NoteDao,
    private val folderDao: FolderDao,
    private val sketchDao: SketchDao,
    private val syncStateDao: SyncStateDao,
    private val accountId: String,
    /**
     * Told about every clock [tombstoneLiveSketchesOf] mints, for the same reason
     * `DefaultSyncController` builds one for [SyncEngine] itself: a clock this device writes but
     * never shows to its own generator can be minted below on the very next local write, which then
     * loses to the record it was supposed to supersede. Deliberately **not defaulted** — a no-op
     * default is exactly how a caller forgets to wire the real one and the hazard comes back silent.
     * `SyncStoreFactory` passes the same [my.cheysoff.core_data.data.sync.SyncClock] instance
     * `DefaultSyncController` feeds its engine, so both paths teach the one generator every clock
     * either of them mints.
     */
    private val clockObserver: ClockObserver,
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
                val existing = noteDao.noteRow(write.record.uuid)
                val merged = RecordRows.toNoteEntity(
                    record = write.record,
                    createdAt = RecordRows.createdAtFor(
                        existing = existing?.createdAt,
                        remote = write.remoteCreatedAt,
                        record = write.record,
                    ),
                    dirty = write.dirty,
                    lastSyncedSeq = write.seq,
                    contentBaseline = write.contentBaseline,
                )
                noteDao.applyRemoteNote(merged)
                // Only on the transition into deleted -- see [tombstoneLiveSketchesOf]'s KDoc for
                // why re-stamping on every merged note write is the trap this guard exists to avoid.
                if (merged.isDeleted && existing?.isDeleted != true) {
                    tombstoneLiveSketchesOf(merged)
                }
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

    /**
     * The other direction of [reconcileAgainstNote]'s rule: a **note** arrives and turns out to be
     * deleted, and this device already holds one or more of its sketches live. Called from
     * [applyMerged]'s NOTE branch only on the transition into deleted (see the guard there) —
     * never on every merged note write, or every sketch under every synced note would be
     * re-stamped and left permanently dirty.
     *
     * **Why this exists at all.** The desktop has no sketch-aware delete path — it tombstones a
     * note and nothing else — so it is a *permanently* sketch-unaware deleter, not merely a build
     * that has not shipped the feature yet. Without this, a note deleted there would leave every
     * device that already held its sketches live stuck with an orphan: never shown (no UI reaches
     * a sketch under a deleted note), never tombstoned, and therefore never reaped by
     * `RoomNotesRepository.purgeExpiredTrash`.
     *
     * **`deletedAt` is shared with [note], its own clock is not.** Matches
     * `RoomNotesRepository.deleteNote`'s cascade exactly, and for the same reason: every sketch
     * tombstoned by this one note reconciliation died at the same wall-clock instant — one
     * deletion event — which is also the exact value `RoomNotesRepository.restoreNote` compares
     * against (`SketchDao.sketchesDeletedAtForNote`, `>=`) to tell "tombstoned by this delete"
     * apart from "already deleted beforehand". A sketch's row clock, in contrast, is its own
     * per-record sync history and must still move forward in it.
     *
     * **Why the clock is a fresh bump, not [note]'s row clock or the sketch's own unchanged one.**
     * [reconcileAgainstNote] can safely reuse the arriving *sketch's own* row clock for its DELETED
     * field because that clock is, by construction, at least as new as every field already on that
     * same record. Nothing here is true of a *different* record: [note]'s row clock says nothing
     * about how far any particular sketch's own history has already moved (a sketch can hold a
     * locally-unsynced edit newer than the note's incoming tombstone), so reusing it — or, worse,
     * leaving the sketch's row clock untouched — could fail to advance past the sketch's current
     * clock at all, which is exactly what "own clock bump ... so it propagates" in the brief rules
     * out: a write that does not strictly advance a row's clock is not guaranteed to beat what
     * another device already has for it. [bumpedClock] mints a value strictly greater than the
     * sketch's own prior clock, chaining through [floor] so that two sketches under the same note
     * — even ones that started at an identical clock — still end up with distinct clocks, the same
     * property `deleteNote`'s per-sketch `stamp()` calls give for the local cascade.
     *
     * This does not disturb [reconcileAgainstNote]'s own documented property (the reachable
     * equal-clock branch that converges only via `Merge.takeGreater`'s lexical tiebreak): that
     * property is about clocks [reconcileAgainstNote] mints for an *arriving* sketch, and this
     * method never touches it. A strictly-advancing bump also cannot itself create a new instance
     * of that reachable branch, since equal clocks are exactly what it is built to avoid.
     *
     * **Every bumped clock is fed to [clockObserver] before it is written.** A bump derived purely
     * from the sketch's own prior clock (see [bumpedClock]) is guaranteed to beat that one row's
     * history, but says nothing about the process-wide `SyncClock`/`HlcGenerator` this device mints
     * its *next local write, of anything,* from. Without this, that generator has never been shown
     * the tombstone's clock, and — exactly as `SyncEngine`'s own `ClockObserver` KDoc warns for a
     * remote clock — it could mint a later write (say, `restoreNote`'s own un-tombstone) *below*
     * this tombstone. `restoreSketch`'s `UPDATE` has no clock guard, so that would still look like a
     * successful local restore; the damage would only surface on another device's `Merge`, which
     * could favour the still-higher tombstone clock and leave the drawing silently dead there.
     */
    private suspend fun tombstoneLiveSketchesOf(note: NoteEntity) {
        var floor: Hlc? = null
        sketchDao.activeSketchesForNote(note.id).forEach { sketch ->
            val current = sketch.rowHlc()
            val base = floor?.takeIf { it > current } ?: current
            val bumped = bumpedClock(base)
            floor = bumped
            clockObserver.observe(bumped)
            sketchDao.softDeleteSketch(
                uuid = sketch.uuid,
                // The note's own wall-clock instant, not the sketch's own -- see this method's
                // KDoc.
                timestamp = note.deletedAt ?: sketch.updatedAt,
                hlcMs = bumped.ms,
                hlcCounter = bumped.counter,
                hlcNode = bumped.node,
                fieldHlc = FieldClocks.stamp(
                    previousSerialized = sketch.fieldHlc,
                    previousRowClock = current,
                    allFields = FieldClocks.SKETCH_FIELDS,
                    touched = setOf(FieldClocks.DELETED),
                    newClock = bumped,
                ),
            )
        }
    }

    /**
     * The next clock strictly after [current], by the textbook HLC send rule
     * ([HlcGenerator.next]'s own algorithm) — but derived from [current] itself rather than minted
     * by a generator, because [RoomSyncStore] has no HLC generator of its own (unlike
     * `RoomNotesRepository`, which mints through the device's injected `SyncClock`): the clocks it
     * writes either arrive already decided by a merge, or — here — are reuses of an existing valid
     * clock, never fresh readings attributed to this device.
     *
     * [current]'s own node is kept rather than replaced, since node has no bearing on whether this
     * value is *greater* than what it replaces — [Hlc]'s ordering is `(ms, counter, node)`, and
     * `node` only breaks a tie between two clocks whose `(ms, counter)` already match, which never
     * happens here: this always produces a value strictly greater in `(ms, counter)` alone.
     */
    private fun bumpedClock(current: Hlc): Hlc {
        val now = wallClock()
        return when {
            now > current.ms -> Hlc(ms = now, counter = 0, node = current.node)
            current.counter == Int.MAX_VALUE -> Hlc(ms = current.ms + 1, counter = 0, node = current.node)
            else -> Hlc(ms = current.ms, counter = current.counter + 1, node = current.node)
        }
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
