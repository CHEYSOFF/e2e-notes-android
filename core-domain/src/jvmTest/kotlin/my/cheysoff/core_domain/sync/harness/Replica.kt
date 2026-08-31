package my.cheysoff.core_domain.sync.harness

import my.cheysoff.core_domain.sync.ConflictCopies
import my.cheysoff.core_domain.sync.FieldClocks
import my.cheysoff.core_domain.sync.FieldValue
import my.cheysoff.core_domain.sync.Hlc
import my.cheysoff.core_domain.sync.HlcGenerator
import my.cheysoff.core_domain.sync.LocalRecord
import my.cheysoff.core_domain.sync.Merge
import my.cheysoff.core_domain.sync.MergeResult
import my.cheysoff.core_domain.sync.RecordType
import my.cheysoff.core_domain.sync.SyncRecord
import my.cheysoff.core_domain.sync.SyncValues

/**
 * One simulated device: a record store, a clock, a cursor, and the push/pull loop reduced to the
 * parts that can make the merge wrong.
 *
 * ## What it faithfully reproduces
 *
 * The local write path, because that is what produces the clocks the merge then reasons about. A
 * write here does what `RoomNotesRepository` does: allocate **one** stamp per user action from a
 * single `HlcGenerator`, recompute `fieldHlc` through the real `FieldClocks.stamp` (round-tripped
 * through the real `serialize`/`parse`, so a bug in either shows up here as a merge failure), and
 * set `dirty`. A harness that stamped its own clocks would be testing the merge against clocks no
 * device ever produces.
 *
 * It also reproduces the loop's bookkeeping from `e2e-sync-phase3-plan.md` §3: pull before push,
 * the cursor advancing only after a record is applied, `lastSyncedSeq` recorded on every server
 * version this device has seen — including one it merged rather than accepted — and a `409` fed
 * back through the same merge call as a pulled record.
 *
 * ## What it does not reproduce, and must not be read as covering
 *
 * Room, SQLCipher, transactions, the invalidation race `SingleNoteViewModel` documents, the
 * Android lifecycle, real HTTP, and real clock steps. Those are named in
 * `e2e-sync-open-questions.md` §3 as the things the simulation cannot reach, and a green run here
 * says nothing about any of them.
 *
 * @param node this device's HLC node pseudonym; the tie-breaker two replicas writing in the same
 *   millisecond come apart on.
 * @param useBaselines whether the replica records a `content` baseline for the merge. Both modes
 *   are run: `true` is what closing decision D7 would buy, `false` is the schema as it stands at
 *   v7, and the point of running both is that convergence must hold either way while the number of
 *   conflict copies must not.
 * @param onMerge called for every merge, so a test can count `409`s, count conflict copies, or
 *   assert an invariant across a whole run.
 */
class Replica(
    val name: String,
    val node: String,
    private val server: FakeServer,
    private val useBaselines: Boolean = true,
    private var wallMs: Long = 1_000L,
    private val onMerge: (MergeObservation) -> Unit = {},
) {

    /** A local row: the record, plus the three things only this device knows about it. */
    data class Row(
        val record: SyncRecord,
        val dirty: Boolean,
        val lastSyncedSeq: Long,
        val contentBaseline: Hlc?,
    )

    /** What happened in one merge, for a test to count or assert on. */
    data class MergeObservation(
        val replica: String,
        val fromConflict: Boolean,
        val result: MergeResult,
    )

    private val rows = LinkedHashMap<String, Row>()
    private val generator = HlcGenerator { node }

    /** The server seq this device has pulled up to. */
    var cursor: Long = 0L
        private set

    /**
     * Set when a merge refused a record, which by `e2e-sync-phase3-plan.md` §8 F7 halts the whole
     * engine and requires an explicit user re-baseline. A halted replica does nothing further, so
     * a test that injects a rollback must not also expect convergence.
     */
    var halted: Boolean = false
        private set

    /** Every record this device holds, normalised so two replicas' snapshots are comparable. */
    fun snapshot(): Map<String, SyncRecord> =
        rows.mapValues { (_, row) -> row.record.normalized() }

    /** True while anything is waiting to be pushed. Half of the quiescence test. */
    fun hasDirtyRows(): Boolean = rows.values.any { it.dirty }

    /** The row for a record, or null. */
    fun row(type: RecordType, uuid: String): Row? = rows[server.keyOf(type, uuid)]

    // ── The device clock ───────────────────────────────────────────────────────────────────────

    /**
     * Moves this device's wall clock by [deltaMs], which may be **negative**.
     *
     * A negative step is the interesting one: a manual change, an NTP correction, a dual-boot. The
     * generator absorbs it by keeping the last physical value and advancing its counter, so the
     * clocks it mints still increase — and the merge, which only ever compares clocks, is never
     * shown a record from this device that is older than one it already accepted.
     */
    fun advanceClock(deltaMs: Long) {
        wallMs += deltaMs
    }

    // ── Local writes, mirroring RoomNotesRepository ────────────────────────────────────────────

    /** `saveNote`: the editor's save. Touches everything a save writes, `updatedAt` included. */
    fun saveNote(uuid: String, title: String, body: String) = write(
        type = RecordType.NOTE,
        uuid = uuid,
        touched = SAVE_NOTE_FIELDS,
    ) { fields, stamp ->
        fields[FieldClocks.TITLE] = FieldValue.of(title)
        fields[FieldClocks.CONTENT] = FieldValue.of(body, "html")
        fields[FieldClocks.UPDATED_AT] = FieldValue.of(stamp.toString())
    }

    /** `setNotePinned`: a metadata gesture. Does NOT touch `updatedAt` — PR #32's rule. */
    fun setPinned(uuid: String, pinned: Boolean) = write(
        type = RecordType.NOTE,
        uuid = uuid,
        touched = setOf(FieldClocks.PINNED),
    ) { fields, _ -> fields[FieldClocks.PINNED] = FieldValue.of(SyncValues.of(pinned)) }

    /** `setNoteFavorite`. Also leaves `updatedAt` alone. */
    fun setFavorite(uuid: String, favorite: Boolean) = write(
        type = RecordType.NOTE,
        uuid = uuid,
        touched = setOf(FieldClocks.FAVORITE),
    ) { fields, _ -> fields[FieldClocks.FAVORITE] = FieldValue.of(SyncValues.of(favorite)) }

    /** `setNoteFolder`. Also leaves `updatedAt` alone. */
    fun setFolder(uuid: String, folderId: String?) = write(
        type = RecordType.NOTE,
        uuid = uuid,
        touched = setOf(FieldClocks.FOLDER),
    ) { fields, _ -> fields[FieldClocks.FOLDER] = FieldValue.of(folderId) }

    /**
     * `clearFolderForNote`: unfiling during a folder delete. The one mass edit that DOES bump
     * `updatedAt`, and the reason `updatedAt` cannot simply ride along with `content`.
     */
    fun clearFolder(uuid: String) = write(
        type = RecordType.NOTE,
        uuid = uuid,
        touched = setOf(FieldClocks.FOLDER, FieldClocks.UPDATED_AT),
    ) { fields, stamp ->
        fields[FieldClocks.FOLDER] = FieldValue.of(null)
        fields[FieldClocks.UPDATED_AT] = FieldValue.of(stamp.toString())
    }

    /** `softDeleteNote`. The tombstone flag and its stamp move together, as one value. */
    fun deleteNote(uuid: String) = write(
        type = RecordType.NOTE,
        uuid = uuid,
        touched = setOf(FieldClocks.DELETED),
    ) { fields, stamp ->
        fields[FieldClocks.DELETED] = FieldValue.of(SyncValues.TRUE, stamp.toString())
    }

    /** `restoreNote`: clears the stamp with the flag, so the next delete starts a new window. */
    fun restoreNote(uuid: String) = write(
        type = RecordType.NOTE,
        uuid = uuid,
        touched = setOf(FieldClocks.DELETED),
    ) { fields, _ -> fields[FieldClocks.DELETED] = FieldValue.of(SyncValues.FALSE, null) }

    /** `upsertFolder`. */
    fun saveFolder(uuid: String, folderName: String, colorArgb: Long?) = write(
        type = RecordType.FOLDER,
        uuid = uuid,
        touched = SAVE_FOLDER_FIELDS,
    ) { fields, stamp ->
        fields[FieldClocks.NAME] = FieldValue.of(folderName)
        fields[FieldClocks.COLOR] = FieldValue.of(colorArgb?.toString())
        fields[FieldClocks.UPDATED_AT] = FieldValue.of(stamp.toString())
    }

    /** `softDeleteFolder`. */
    fun deleteFolder(uuid: String) = write(
        type = RecordType.FOLDER,
        uuid = uuid,
        touched = setOf(FieldClocks.DELETED),
    ) { fields, stamp ->
        fields[FieldClocks.DELETED] = FieldValue.of(SyncValues.TRUE, stamp.toString())
    }

    /**
     * One user action: allocate one clock, recompute the field clocks on top of the row's previous
     * ones, apply the change, mark the row dirty.
     *
     * The field-clock recomputation goes through `FieldClocks.serialize` and `parse` rather than
     * manipulating the map directly. It costs nothing and it means the harness is exercising the
     * same string the `fieldHlc` column stores — so an encoding bug that would corrupt a real row
     * shows up here as a convergence failure rather than passing unnoticed because the harness
     * kept its clocks in a nicer form than the database does.
     */
    private fun write(
        type: RecordType,
        uuid: String,
        touched: Set<String>,
        mutate: (fields: MutableMap<String, FieldValue>, wallMs: Long) -> Unit,
    ) {
        val key = server.keyOf(type, uuid)
        val existing = rows[key]
        val stampMs = wallMs
        val clock = generator.next(stampMs)

        val fields = LinkedHashMap(existing?.record?.fields ?: defaultFields(type, stampMs))
        mutate(fields, stampMs)

        val nextClocks = FieldClocks.parse(
            FieldClocks.stamp(
                previousSerialized = FieldClocks.serialize(existing?.record?.fieldClocks ?: emptyMap()),
                previousRowClock = existing?.record?.rowClock,
                allFields = type.fields,
                touched = touched,
                newClock = clock,
            )
        )

        rows[key] = Row(
            record = SyncRecord(
                type = type,
                uuid = uuid,
                rowClock = clock,
                fieldClocks = nextClocks,
                fields = type.fields.associateWith { fields.getValue(it) },
            ).validate().normalized(),
            dirty = true,
            // A brand-new row has never been on the server; an existing one keeps the baseline it
            // was pushed against. Resetting lastSyncedSeq here would tell the server an
            // already-uploaded record must not exist.
            lastSyncedSeq = existing?.lastSyncedSeq ?: 0L,
            contentBaseline = existing?.contentBaseline,
        )
    }

    /** A record's fields before its first write: the same defaults the entities declare. */
    private fun defaultFields(type: RecordType, stampMs: Long): Map<String, FieldValue> = when (type) {
        RecordType.NOTE -> mapOf(
            FieldClocks.TITLE to FieldValue.of(""),
            FieldClocks.CONTENT to FieldValue.of("", "html"),
            FieldClocks.CHECKLIST to FieldValue.of(""),
            FieldClocks.PINNED to FieldValue.of(SyncValues.FALSE),
            FieldClocks.FAVORITE to FieldValue.of(SyncValues.FALSE),
            FieldClocks.FOLDER to FieldValue.of(null),
            FieldClocks.UPDATED_AT to FieldValue.of(stampMs.toString()),
            FieldClocks.DELETED to FieldValue.of(SyncValues.FALSE, null),
        )

        RecordType.FOLDER -> mapOf(
            FieldClocks.NAME to FieldValue.of(""),
            FieldClocks.COLOR to FieldValue.of(null),
            FieldClocks.UPDATED_AT to FieldValue.of(stampMs.toString()),
            FieldClocks.DELETED to FieldValue.of(SyncValues.FALSE, null),
        )
    }

    // ── The sync loop ──────────────────────────────────────────────────────────────────────────

    /** One pass: pull, then push. Returns true if anything moved. See [pull] for why in that order. */
    fun syncOnce(): Boolean {
        val pulled = pull()
        val pushed = push()
        return pulled || pushed
    }

    /**
     * Applies everything the server has that this device has not seen.
     *
     * **Pull before push, always.** Pushing first maximises the number of `409`s, because every
     * conflict the server would report is one this device could have merged a moment earlier.
     *
     * The cursor advances **only after a record has been applied**, which is what makes a crash
     * mid-pull safe: the record is simply pulled and merged again next pass, and an idempotent
     * merge makes that a no-op.
     */
    fun pull(limit: Int = 200): Boolean {
        if (halted) return false
        var moved = false
        for (stored in server.changes(cursor, limit)) {
            val applied = apply(stored.key, stored.record, stored.seq, fromConflict = false)
            if (halted) return moved
            moved = moved || applied
            cursor = stored.seq
        }
        return moved
    }

    /**
     * Rewinds the cursor to 0 and pulls everything again — every record this device has ever
     * applied, delivered a second time.
     *
     * The idempotence property's instrument. It is not a fault injection so much as an ordinary
     * Tuesday: `e2e-sync-phase3-plan.md` §3.3 lists a crash between a merge commit and the cursor
     * write as one of three routine ways a record is re-delivered, and the only thing that makes
     * any of them safe is that re-applying an already-applied record does nothing at all.
     */
    fun replayEverything(): Boolean {
        cursor = 0L
        return pull()
    }

    /**
     * Pushes every dirty row, oldest row clock first.
     *
     * @param dropAckFor record keys whose `ok` response is thrown away **after the server has
     *   committed it** — process death between the two, which
     *   `e2e-sync-phase3-plan.md` §3.3 lists as an ordinary event. The row stays dirty with a stale
     *   `lastSyncedSeq`, so the next push takes a `409` carrying this device's own envelope back,
     *   and the merge of a record against itself has to be a no-op or the row is pushed forever.
     */
    fun push(dropAckFor: Set<String> = emptySet()): Boolean {
        if (halted) return false
        var moved = false
        val pending = rows.entries
            .filter { it.value.dirty }
            .sortedWith(compareBy({ it.value.record.rowClock }, { it.key }))
            .map { it.key to it.value }

        for ((key, row) in pending) {
            // The envelope is sealed from the row as it is right now; §3.2's rule 1 is about the
            // row moving after this point, which the harness reproduces by keeping `sealed`.
            val sealed = row.record
            when (val result = server.put(key, row.lastSyncedSeq, sealed)) {
                is FakeServer.PutResult.Ok -> {
                    moved = true
                    if (key in dropAckFor) continue   // the response never arrived
                    val current = rows.getValue(key)
                    rows[key] = current.copy(
                        // Clear dirty only if the row has not moved since the envelope was sealed
                        // (§3.2 rule 1); otherwise the user's newer edit is silently dropped.
                        dirty = current.record != sealed,
                        // But always record the seq (§3.2 rule 2), or the next push sends a stale
                        // baseSeq and takes a guaranteed 409 for nothing.
                        lastSyncedSeq = result.seq,
                        contentBaseline = advanceBaseline(current.contentBaseline, sealed),
                    )
                }

                is FakeServer.PutResult.Conflict -> {
                    moved = true
                    apply(key, result.record, result.seq, fromConflict = true)
                    if (halted) return moved
                }
            }
        }
        return moved
    }

    /**
     * Merges one incoming record and writes what the merge decided.
     *
     * The same call for a pulled record and for the version a `409` handed back: one merge path,
     * because two would drift apart, and the plan says so by name.
     */
    private fun apply(key: String, remote: SyncRecord, seq: Long, fromConflict: Boolean): Boolean {
        val row = rows[key]
        val localRecord = row?.let {
            LocalRecord(
                record = it.record,
                dirty = it.dirty,
                contentBaseline = if (useBaselines) it.contentBaseline else null,
            )
        }

        val result = Merge.merge(localRecord, remote)
        onMerge(MergeObservation(replica = name, fromConflict = fromConflict, result = result))

        if (localRecord != null) checkNoUnpushedBodyWasDiscarded(localRecord, result)

        // Every clock this device is shown has to be folded into its generator, or the next local
        // write could be minted below a record already accepted — and a row whose clock went
        // backwards loses to its own older version on the next sync.
        generator.observe(remote.rowClock)

        return when (result) {
            is MergeResult.Rejected -> {
                halted = true
                false
            }

            MergeResult.NoChange -> {
                // The data did not move, but this device has now seen server version `seq` and its
                // next push must be built on it.
                rows[key] = requireNotNull(row).copy(
                    lastSyncedSeq = seq,
                    contentBaseline = advanceBaseline(row.contentBaseline, remote),
                )
                false
            }

            is MergeResult.Applied -> {
                store(key, result.record, result.dirty, seq, row, remote)
                true
            }

            is MergeResult.ConflictCopy -> {
                store(key, result.record, result.dirty, seq, row, remote)
                val copyKey = server.keyOf(RecordType.NOTE, result.copy.uuid)
                // Insert only if absent. The copy's uuid is derived from the losing body, so the
                // same conflict resolved twice names the same copy; overwriting would clobber one
                // the user had since edited.
                if (!rows.containsKey(copyKey)) {
                    rows[copyKey] = Row(
                        record = result.copy.normalized(),
                        dirty = true,
                        lastSyncedSeq = 0L,
                        contentBaseline = null,
                    )
                }
                true
            }
        }
    }

    private fun store(
        key: String,
        record: SyncRecord,
        dirty: Boolean,
        seq: Long,
        previous: Row?,
        remote: SyncRecord,
    ) {
        rows[key] = Row(
            record = record.normalized(),
            dirty = dirty,
            lastSyncedSeq = seq,
            contentBaseline = advanceBaseline(previous?.contentBaseline, remote),
        )
    }

    /**
     * The `content` clock of the newest version this device and the server have agreed on.
     *
     * Monotonic: it only ever moves forward, because it marks a point in history below which this
     * device's body is certainly not a new edit. [agreed] is the record the two sides have just
     * agreed on — the record that was pushed successfully, or the one that arrived from the server.
     */
    private fun advanceBaseline(previous: Hlc?, agreed: SyncRecord): Hlc? {
        if (agreed.type != RecordType.NOTE) return null
        val seen = agreed.clockOf(FieldClocks.CONTENT)
        return if (previous == null || seen > previous) seen else previous
    }

    /**
     * The invariant the conflict-copy rule exists to provide, checked on **every** merge of every
     * seed: a body this device holds and has not published is never replaced without being written
     * out somewhere.
     *
     * Stated as an assertion rather than a test case because the interesting instances of it are
     * the ones a random schedule finds, not the ones a fixture author thinks of. A violation
     * throws immediately, so the seed that produced it is the one the runner prints.
     */
    private fun checkNoUnpushedBodyWasDiscarded(local: LocalRecord, result: MergeResult) {
        if (local.record.type != RecordType.NOTE) return
        if (!local.dirty) return

        val body = local.record.valueOf(FieldClocks.CONTENT)
        // An empty body is not text, so losing it is not a loss. Merge applies the same exemption
        // and for the same reason; see `Merge.isEmptyBody`.
        if (body.parts.firstOrNull().isNullOrEmpty()) return
        val merged = when (result) {
            is MergeResult.Applied -> result.record
            is MergeResult.ConflictCopy -> result.record
            else -> return
        }
        if (merged.valueOf(FieldClocks.CONTENT) == body) return

        // The body is being replaced. If it was already on the server it is an ancestor and losing
        // it costs nothing; the baseline is the only thing that can say so.
        val baseline = local.contentBaseline
        val published = baseline != null && local.record.clockOf(FieldClocks.CONTENT) <= baseline
        if (published) return

        val preserved = result is MergeResult.ConflictCopy &&
            result.copy.valueOf(FieldClocks.CONTENT) == body
        check(preserved) {
            "$name discarded an unpublished body: '$body' was replaced by " +
                "'${merged.valueOf(FieldClocks.CONTENT)}' with no conflict copy"
        }
    }

    private companion object {
        /** `RoomNotesRepository.SAVE_NOTE_FIELDS`, verbatim. */
        val SAVE_NOTE_FIELDS = setOf(
            FieldClocks.TITLE,
            FieldClocks.CONTENT,
            FieldClocks.CHECKLIST,
            FieldClocks.PINNED,
            FieldClocks.FOLDER,
            FieldClocks.UPDATED_AT,
        )

        /** `RoomNotesRepository.SAVE_FOLDER_FIELDS`, verbatim. */
        val SAVE_FOLDER_FIELDS = setOf(FieldClocks.NAME, FieldClocks.COLOR, FieldClocks.UPDATED_AT)
    }
}
