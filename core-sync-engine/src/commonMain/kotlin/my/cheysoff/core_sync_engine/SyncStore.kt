package my.cheysoff.core_sync_engine

import my.cheysoff.core_domain.sync.Hlc
import my.cheysoff.core_domain.sync.LocalRecord
import my.cheysoff.core_domain.sync.RecordType
import my.cheysoff.core_domain.sync.SyncRecord

/**
 * Everything the engine keeps between passes, behind one interface.
 *
 * ## Why this exists rather than a Room DAO
 *
 * The engine holds **no state of its own across passes** — no cursor field, no dirty set, no
 * "halted" boolean. Every one of those lives here, and that is what makes the answer to "what
 * happens if the process dies in the middle of a pass" a property of this contract rather than a
 * hope: a fresh engine over the same store resumes, because a fresh engine is the same engine.
 * `SyncEngineTest.aPassInterruptedByProcessDeathResumesToTheSameState` is the check.
 *
 * ## Atomicity, and what it is for
 *
 * Each method here is **one transaction**. That is the contract the engine's crash-safety argument
 * rests on, and the plan states it as "merge inside ONE Room transaction per record" (§3). In
 * particular [applyMerged] writes the merged row, its conflict copy and its bookkeeping together or
 * not at all — a merged row written without its `lastSyncedSeq` is a row that will be pushed
 * against a stale `baseSeq` forever.
 *
 * What is *not* required is a transaction spanning a whole pass. The engine is written so that a
 * torn pass is a pass that is simply resumed, which is a much weaker requirement and the only one a
 * network loop can actually meet.
 */
interface SyncStore {

    /**
     * The server `seq` this device has pulled up to, or `0` before the first pull.
     *
     * A sequence number, never a timestamp. `:core-sync-net`'s `Cursor` type explains at length why
     * that distinction is worth a type; here it is a `Long` because it crosses no wire.
     */
    suspend fun cursor(): Long

    /**
     * Moves the cursor to [seq].
     *
     * Called **only after** every record up to [seq] has been applied by [applyMerged] or
     * [recordSeen]. The engine never calls this with a seq it has not applied, and a store that
     * reordered the two would turn a crash into silently skipped records.
     */
    suspend fun saveCursor(seq: Long)

    /** The row for one record, or null when this device has never seen it. */
    suspend fun load(type: RecordType, uuid: String): StoredRecord?

    /**
     * Every row this device holds that the server does not, in the order they should be pushed:
     * **oldest row clock first**, ties broken deterministically.
     *
     * The order is part of the contract rather than a detail. A device that has been offline for a
     * week pushes the week in the order it happened, so a peer watching the change stream sees a
     * history rather than a shuffle; and a deterministic order is what makes a failing convergence
     * seed replay identically.
     *
     * There is no separate "deleted records" query: a tombstone is an ordinary dirty row carrying
     * `isDeleted = 1`, because the protocol has no delete endpoint.
     */
    suspend fun dirtyRecords(): List<StoredRecord>

    /**
     * Writes what a merge decided, together with the bookkeeping for the server version that
     * caused it.
     *
     * @param write see [MergedWrite]. Everything in it is written in one transaction.
     */
    suspend fun applyMerged(write: MergedWrite)

    /**
     * The merge decided nothing had to be written, but this device has now **seen** server version
     * [seq] and its next push must be built on it.
     *
     * Splitting this from [applyMerged] is not tidiness. `MergeResult.NoChange` is reached by three
     * ordinary production events (§3.3) and in all three the row's data must not be touched, while
     * its `lastSyncedSeq` must be: a row left at a stale `lastSyncedSeq` takes a guaranteed `409`
     * on every subsequent pass, forever.
     */
    suspend fun recordSeen(type: RecordType, uuid: String, seq: Long, contentBaseline: Hlc?)

    /**
     * A push the server accepted — the two rules of §3.2, as one transaction.
     *
     * 1. `dirty` is cleared **only if the row's clock is still [sealedRowClock]**. The user can edit
     *    a note while its push is in flight; clearing `dirty` unconditionally drops that edit
     *    forever, with no error and no way to notice.
     * 2. `lastSyncedSeq` is written **either way**. Skipping it when rule 1's guard fails makes the
     *    next push send a stale `baseSeq` and take a guaranteed `409` for nothing.
     *
     * The guard is the row clock rather than the whole record because that is what the `UPDATE …
     * WHERE hlcMs = :sealedMs AND hlcCounter = :sealedCounter` in the plan compares, and it is
     * sufficient: every local write mints a fresh clock from `HlcGenerator`, so the clock *is* the
     * row's version.
     */
    suspend fun acknowledgePush(
        type: RecordType,
        uuid: String,
        sealedRowClock: Hlc,
        seq: Long,
        contentBaseline: Hlc?,
    )

    /**
     * Why the engine has stopped, or null while it is healthy.
     *
     * Read at the start of every pass and persisted, not held in memory, because the events that
     * cause it — a rolled-back server, a payload from a newer build — are still true after a
     * restart. An engine that forgot its halt on process death would resume syncing against exactly
     * the server it refused to trust.
     */
    suspend fun halt(): HaltReason?

    /**
     * Records that the engine has halted. Idempotent; the **first** reason is the one to keep,
     * because it is the one that explains the rest.
     */
    suspend fun recordHalt(reason: HaltReason)

    /**
     * Forgets the halt, so the next pass runs instead of refusing.
     *
     * ## This repairs nothing, and must not be described as if it did
     *
     * Every [HaltReason] is a condition the engine cannot fix: a server restored from a backup, a
     * payload from a newer build, a device revoked, an ARK that cannot read the account. Clearing
     * the flag does not change any of them. What the next pass does is **detect the same thing
     * again and halt again**, which is the correct outcome and the reason this is safe to offer.
     *
     * It exists because the alternative is worse. Before it, a halt was permanent: a person who
     * updated the app after an [HaltReason.UNSUPPORTED_PAYLOAD_VERSION], or re-paired after a
     * [HaltReason.DEVICE_REVOKED], had fixed the cause and still had no way to tell the engine to
     * look again — short of reinstalling and losing the local library. A dead end with a fixed
     * cause is a worse failure than a halt that can be retried.
     *
     * So the honest framing, and the one the UI copy is held to: this is "look again", not "it's
     * fine now". Callers must not clear a halt on the engine's behalf, on a timer, or as part of
     * error recovery — only when a person asked.
     */
    suspend fun clearHalt()

    /**
     * The format generation this device last completed a pull under, or [SyncEngine.DATA_VERSION]
     * when nothing has been recorded.
     *
     * The default matters: a store with no row for this account has never pulled, so its next pull
     * starts at 0 and fetches everything anyway. Reporting `0` there would send it through a
     * re-baseline that could not possibly find anything it had missed.
     */
    suspend fun dataVersion(): Int

    /** Records that a pull completed under [version]. Written only after a completed pass. */
    suspend fun saveDataVersion(version: Int)
}

/**
 * One local row: the record, plus the three things only this device knows about it.
 *
 * Deliberately a superset of `LocalRecord`, which the merge takes: `LocalRecord` carries only what
 * the *merge* is allowed to reason about, and `lastSyncedSeq` is not part of that. Handing the
 * merge a type with a `seq` on it is how a merge rule ends up quietly depending on transport
 * bookkeeping.
 */
class StoredRecord(
    val record: SyncRecord,
    /** True when this device holds a version the server has not acknowledged. */
    val dirty: Boolean,
    /** The `seq` of the version this device last agreed with. The CAS baseline. `0` = never sent. */
    val lastSyncedSeq: Long,
    /**
     * The `content` clock of the newest version this device and the server have agreed on, or null
     * when none is recorded.
     *
     * Null is legal and is the schema as it stands (decision D7): the merge then falls back to its
     * conservative conflict-copy rule, which converges but writes copies it did not need. See
     * `LocalRecord.contentBaseline`.
     */
    val contentBaseline: Hlc?,
) {

    /** This row as the merge is allowed to see it. */
    fun asLocalRecord(): LocalRecord = LocalRecord(
        record = record,
        dirty = dirty,
        contentBaseline = contentBaseline,
    )
}

/** Everything one merge decided, for [SyncStore.applyMerged] to write in one transaction. */
class MergedWrite(
    /**
     * The merged row, to be written through the **full-row** write path. Not `upsertNote`, whose
     * conflict branch deliberately refuses `isFavorite`, `isDeleted` and `deletedAt` and would
     * therefore silently drop a remote delete or a remote favourite (§5.6).
     */
    val record: SyncRecord,
    /** Whether the merged row is a version only this device holds, from `MergeResult.dirty`. */
    val dirty: Boolean,
    /** The server version that caused this merge; becomes the row's `lastSyncedSeq`. */
    val seq: Long,
    /** The row's new content baseline. See [StoredRecord.contentBaseline] and [Baselines]. */
    val contentBaseline: Hlc?,
    /**
     * A note holding a body this merge would otherwise have discarded, to be inserted alongside the
     * merged row with `dirty = true` and `lastSyncedSeq = 0`, or null when the merge owed none.
     *
     * The engine only ever sets this when no record with that uuid already exists — the copy's uuid
     * is derived from the losing body, so the same conflict resolved twice names the same copy, and
     * a blind overwrite would clobber one the user had since edited.
     */
    val conflictCopy: SyncRecord?,
    /**
     * The `createdAt` the incoming payload carried, for a store that has no row for this record
     * yet. Null when the payload omitted one, or when the write did not come from a remote record.
     *
     * **Only for a first receipt.** A row that already has a `createdAt` keeps it, always: it is
     * the one column in the schema with no history to fall back on, and the merge does not model it
     * because no write path moves it. This exists so that a device seeing a record for the first
     * time can use the creation time the record actually has, instead of inventing one from
     * `updatedAt` and disagreeing with the device that made it forever (issue #90).
     *
     * It is not a clocked field and must not become one on this route: nothing here contests the
     * value, it is simply carried.
     */
    val remoteCreatedAt: Long? = null,
)
