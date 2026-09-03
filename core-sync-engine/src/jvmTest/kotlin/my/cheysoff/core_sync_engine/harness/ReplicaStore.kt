package my.cheysoff.core_sync_engine.harness

import my.cheysoff.core_domain.sync.FieldClocks
import my.cheysoff.core_domain.sync.Hlc
import my.cheysoff.core_domain.sync.RecordType
import my.cheysoff.core_domain.sync.SyncRecord
import my.cheysoff.core_sync_engine.HaltReason
import my.cheysoff.core_sync_engine.MergedWrite
import my.cheysoff.core_sync_engine.StoredRecord
import my.cheysoff.core_sync_engine.SyncEngine
import my.cheysoff.core_sync_engine.SyncStore

/**
 * One simulated device's database: a map of rows, a cursor and a halt.
 *
 * ## What it faithfully reproduces, and what it does not
 *
 * It reproduces the **contract** of [SyncStore] — every method is one atomic step, the cursor is a
 * server `seq`, and `acknowledgePush` clears `dirty` only when the row has not moved. It reproduces
 * none of Room: no transactions that can actually fail, no SQLCipher, no invalidation race, no
 * suspension between a read and its write. A green convergence run therefore says something about
 * the engine and nothing about the persistence layer, exactly as
 * `e2e-sync-open-questions.md` §3 sets out.
 *
 * @param useBaselines whether this device's schema records a `content` baseline. `false` is the
 *   schema as it stands at v7 (decision D7) and is modelled by storing nothing rather than by
 *   hiding what is stored, because a column that does not exist cannot be read back by accident.
 */
class ReplicaStore(
    private val name: String,
    private val useBaselines: Boolean,
) : SyncStore {

    private val rows = LinkedHashMap<String, StoredRecord>()
    private var cursor = 0L
    private var halted: HaltReason? = null

    /** Named `storedDataVersion`, not `dataVersion`, so it does not collide with the method. */
    var storedDataVersion: Int = SyncEngine.DATA_VERSION

    // ── The SyncStore contract ─────────────────────────────────────────────────────────────────

    override suspend fun cursor(): Long = cursor

    override suspend fun saveCursor(seq: Long) {
        cursor = seq
    }

    override suspend fun load(type: RecordType, uuid: String): StoredRecord? = rows[keyOf(type, uuid)]

    override suspend fun dirtyRecords(): List<StoredRecord> = rows.entries
        .filter { it.value.dirty }
        // Oldest row clock first, ties broken on the key: the order the store contract requires,
        // and the reason a failing seed replays identically.
        .sortedWith(compareBy({ it.value.record.rowClock }, { it.key }))
        .map { it.value }

    override suspend fun applyMerged(write: MergedWrite) {
        val key = keyOf(write.record.type, write.record.uuid)
        val previous = rows[key]
        rows[key] = StoredRecord(
            record = write.record.normalized(),
            dirty = write.dirty,
            lastSyncedSeq = write.seq,
            contentBaseline = baseline(write.contentBaseline),
        )
        write.conflictCopy?.let { copy ->
            rows[keyOf(RecordType.NOTE, copy.uuid)] = StoredRecord(
                record = copy.normalized(),
                dirty = true,
                // It has never been on the server, which the server reads as "must not exist".
                lastSyncedSeq = 0L,
                contentBaseline = null,
            )
        }
        checkNoUnpushedBodyWasDiscarded(previous, write.record)
    }

    override suspend fun recordSeen(
        type: RecordType,
        uuid: String,
        seq: Long,
        contentBaseline: Hlc?,
    ) {
        val key = keyOf(type, uuid)
        val row = requireNotNull(rows[key]) { "$name was told it had seen a record it does not hold" }
        rows[key] = StoredRecord(
            record = row.record,
            dirty = row.dirty,
            lastSyncedSeq = seq,
            contentBaseline = baseline(contentBaseline),
        )
    }

    override suspend fun acknowledgePush(
        type: RecordType,
        uuid: String,
        sealedRowClock: Hlc,
        seq: Long,
        contentBaseline: Hlc?,
    ) {
        val key = keyOf(type, uuid)
        val row = requireNotNull(rows[key]) { "$name acknowledged a push of a record it does not hold" }
        rows[key] = StoredRecord(
            record = row.record,
            // §3.2 rule 1: the user may have typed while the push was in flight. `WHERE hlcMs = …
            // AND hlcCounter = …` matches nothing when the row moved, so `dirty` stays set and the
            // next pass sends the newer version.
            dirty = row.record.rowClock != sealedRowClock,
            // §3.2 rule 2: written either way, or the next push takes a guaranteed 409 for nothing.
            lastSyncedSeq = seq,
            contentBaseline = baseline(contentBaseline),
        )
    }

    override suspend fun halt(): HaltReason? = halted

    override suspend fun recordHalt(reason: HaltReason) {
        if (halted == null) halted = reason
    }

    override suspend fun clearHalt() {
        halted = null
    }

    override suspend fun dataVersion(): Int = storedDataVersion

    override suspend fun saveDataVersion(version: Int) {
        storedDataVersion = version
    }

    // ── What the harness needs on top ──────────────────────────────────────────────────────────

    /** Every row, for the local write path and the convergence assertions. */
    fun rows(): Map<String, StoredRecord> = rows

    /** The local write path's only way in. */
    fun write(key: String, row: StoredRecord) {
        rows[key] = row
    }

    /**
     * Rewinds the cursor so the whole account is delivered again.
     *
     * Not a [SyncStore] method, because nothing in the engine may ever do this: §8's F7 is that
     * resetting a cursor is indistinguishable from "the account is empty". It is here because
     * re-delivery is the idempotence property's only instrument.
     */
    fun rewindCursor() {
        cursor = 0L
    }

    /** The cursor, for a failure message. */
    fun cursorNow(): Long = cursor

    /** Whether the engine has stopped. */
    fun haltedWith(): HaltReason? = halted

    private fun baseline(value: Hlc?): Hlc? = if (useBaselines) value else null

    /**
     * The invariant the conflict-copy rule exists to provide, checked on **every** write of every
     * seed: a body this device holds and has not published is never replaced without still being
     * somewhere.
     *
     * Stated as an assertion rather than a test case because the interesting instances of it are
     * the ones a random schedule finds, not the ones a fixture author thinks of. A violation throws
     * immediately, so the seed that produced it is the one the runner prints.
     *
     * The test is "some row still holds that body", not "a conflict copy was written in this call".
     * They differ when the copy is already here — the same conflict resolved twice names the same
     * copy, and the engine then writes nothing — and the body is just as safe either way.
     */
    private fun checkNoUnpushedBodyWasDiscarded(previous: StoredRecord?, merged: SyncRecord) {
        if (previous == null) return
        if (previous.record.type != RecordType.NOTE) return
        if (!previous.dirty) return

        val body = previous.record.valueOf(FieldClocks.CONTENT)
        // An empty body is not text, so losing it is not a loss. `Merge.isEmptyBody` applies the
        // same exemption for the same reason.
        if (body.parts.firstOrNull().isNullOrEmpty()) return
        if (merged.valueOf(FieldClocks.CONTENT) == body) return

        // If the body was already on the server it is an ancestor and losing it costs nothing; the
        // baseline is the only thing that can say so.
        val baseline = previous.contentBaseline
        if (baseline != null && previous.record.clockOf(FieldClocks.CONTENT) <= baseline) return

        val preserved = rows.values.any {
            it.record.type == RecordType.NOTE && it.record.valueOf(FieldClocks.CONTENT) == body
        }
        check(preserved) {
            "$name discarded an unpublished body: '$body' was replaced by " +
                "'${merged.valueOf(FieldClocks.CONTENT)}' and is nowhere on this device"
        }
    }

    companion object {
        /**
         * The harness's stand-in for `HMAC(K_id, recType ‖ ":" ‖ uuid)` — the same function
         * [FakeServer.keyOf] uses, so a row is filed locally under the name it is filed under
         * remotely.
         */
        fun keyOf(type: RecordType, uuid: String): String = "${type.wireKey}:$uuid"
    }
}
