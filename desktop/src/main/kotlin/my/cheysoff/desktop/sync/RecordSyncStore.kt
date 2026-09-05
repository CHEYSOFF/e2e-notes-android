package my.cheysoff.desktop.sync

import my.cheysoff.core_domain.sync.Hlc
import my.cheysoff.core_domain.sync.RecordType
import my.cheysoff.core_domain.sync.SyncRecord
import my.cheysoff.core_sync_codec.OpenResult
import my.cheysoff.core_sync_codec.PayloadFields
import my.cheysoff.core_sync_codec.RecordCodec
import my.cheysoff.core_sync_codec.SyncRecords
import my.cheysoff.core_sync_engine.HaltReason
import my.cheysoff.core_sync_engine.MergedWrite
import my.cheysoff.core_sync_engine.StoredRecord
import my.cheysoff.core_sync_engine.SyncEngine
import my.cheysoff.core_sync_engine.SyncStore
import my.cheysoff.desktop.store.RecordStore

/**
 * `SyncStore` over the desktop's `records` table.
 *
 * ## The shape difference from the phone, and why it costs so little
 *
 * `RoomSyncStore` reads columns: a note's title is a `TEXT` column and `dirty` sits beside it. Here
 * a row is one sealed envelope, so every read is an AES-GCM open and a JSON parse. That is the
 * trade `RecordStore` was built to make — at-rest security on the desktop equals on-the-wire
 * security — and it is why this class is a translation layer rather than a set of queries.
 *
 * What it deliberately does **not** do is invent a second vocabulary. `RecordPayload` and
 * `SyncRecords` are the shared codec's, the same objects the phone's transport uses; the only thing
 * written here is which of `RecordStore`'s statements each `SyncStore` method maps onto.
 *
 * ## The blinded id is derived, never stored twice
 *
 * `SyncStore` addresses records by `(type, uuid)` and the table is keyed by the blinded id. The map
 * between them is `HMAC(K_id, recType ‖ ":" ‖ uuid)`, which [RecordCodec] computes — so this class
 * recomputes it per call rather than keeping an index. It is one HMAC and it cannot drift; an index
 * would be a second source of truth for a value that is a pure function of the row's own identity.
 *
 * ## Atomicity
 *
 * `SyncStore`'s contract is one transaction per method, and the engine's crash-safety argument rests
 * on it. Four of the six are a single SQL statement and are atomic without help. [applyMerged] is
 * the one that writes two rows — the merged record and its conflict copy — and takes an explicit
 * transaction, because a winner written without the copy holding the body it displaced is worse
 * than neither being written.
 *
 * ## Threading
 *
 * `RecordStore` holds one JDBC connection and does not serialise its callers. A sync pass and a
 * user's edit must therefore not interleave, and they do not: `DesktopSyncController` runs the whole
 * pass inside `RecordNotesRepository.exclusively`, which is the same mutex every write takes.
 */
class RecordSyncStore(
    private val store: RecordStore,
    private val codec: RecordCodec,
    /**
     * The account this store is bound to, as `Base64Url(accountId)`.
     *
     * A constructor argument rather than something read per call, for the reason `RoomSyncStore`
     * gives at length: a cursor from another account is ahead of nothing, so pulling with it skips
     * that account's whole history and leaves the device convinced it is up to date.
     */
    private val accountId: String,
) : SyncStore {

    override suspend fun cursor(): Long = store.cursor(accountId)

    override suspend fun saveCursor(seq: Long) = store.saveCursor(accountId, seq)

    override suspend fun load(type: RecordType, uuid: String): StoredRecord? =
        open(codec.blindedIdOf(type.wireKey, uuid))

    /**
     * Every dirty row, oldest row clock first, ties broken by `(type, uuid)`.
     *
     * The order is part of `SyncStore`'s contract rather than a detail: a device that has been
     * offline for a week pushes the week in the order it happened, so a peer watching the change
     * stream sees a history rather than a shuffle.
     *
     * A row that will not open is **skipped**, not faulted. It is a record this device cannot read
     * — a damaged blob, or one from a newer build — and pushing it would mean re-sealing bytes this
     * build cannot parse. `RecordNotesRepository` counts the same rows at unlock and shows the
     * count; there is nothing this class could add to that except a second, quieter report.
     */
    override suspend fun dirtyRecords(): List<StoredRecord> = store.readAll()
        .filter { it.dirty }
        .mapNotNull { open(it.blindedId) }
        .sortedWith(compareBy({ it.record.rowClock }, { it.record.type }, { it.record.uuid }))

    // A block body rather than an expression one: the last statement below is a `?.let`, so an
    // expression body would infer `Unit?` and fail to override `SyncStore.applyMerged`.
    override suspend fun applyMerged(write: MergedWrite) {
        store.inTransaction {
            put(
                write.record,
                dirty = write.dirty,
                seq = write.seq,
                baseline = write.contentBaseline,
                remoteCreatedAt = write.remoteCreatedAt,
                remoteMeta = write.remoteMeta,
            )
            write.conflictCopy?.let { copy ->
                // `dirty = true` and no `seq`: the copy has never been on the server, which the
                // server reads as "this record must not exist". The engine only sets it when no
                // record with that uuid is present, so there is nothing here to preserve.
                // No `remoteCreatedAt`: the incoming record's creation time belongs to the record
                // it arrived as, not to a copy this device mints from a losing body.
                put(
                    copy, dirty = true, seq = null, baseline = null,
                    remoteCreatedAt = null, remoteMeta = null,
                )
            }
        }
    }

    override suspend fun recordSeen(
        type: RecordType,
        uuid: String,
        seq: Long,
        contentBaseline: Hlc?,
    ) = store.markSeen(codec.blindedIdOf(type.wireKey, uuid), seq, contentBaseline?.toString())

    /**
     * An accepted push, with `dirty` cleared only if the row still holds what was sent.
     *
     * The guard needs the exact envelope that went to the server, and the engine hands back the
     * *clock* of the version it sent rather than the bytes — so the bytes are rebuilt here by
     * re-sealing that clock's record. That is not possible: a re-seal draws a fresh nonce and
     * produces different ciphertext. So the comparison is on the row's own clock instead, read out
     * of the stored envelope, which is exactly what `NoteDao.acknowledgeNotePush` compares.
     */
    override suspend fun acknowledgePush(
        type: RecordType,
        uuid: String,
        sealedRowClock: Hlc,
        seq: Long,
        contentBaseline: Hlc?,
    ) {
        val blindedId = codec.blindedIdOf(type.wireKey, uuid)
        val stored = store.read(blindedId) ?: return
        val unchanged = open(blindedId)?.record?.rowClock == sealedRowClock
        store.acknowledgePush(
            blindedId = blindedId,
            // The row is "unchanged" when its clock still matches the version that was sent. Passing
            // the stored envelope makes the SQL's `envelope = ?` comparison true; passing anything
            // else makes it false and leaves `dirty` alone, which is the branch that saves an edit
            // made while the push was in flight.
            sentEnvelope = if (unchanged) stored.envelope else ByteArray(0),
            seq = seq,
            contentBaseline = contentBaseline?.toString(),
        )
    }

    /**
     * The stored halt, or null while the engine is healthy.
     *
     * A stored name this build does not recognise is **still a halt**, reported as the reason with
     * the widest response. Reading it as "healthy" would be an older build quietly resuming a sync
     * a newer one stopped — `RoomSyncStore` makes the same call for the same reason.
     */
    override suspend fun halt(): HaltReason? {
        val stored = store.halt(accountId) ?: return null
        return HaltReason.entries.firstOrNull { it.name == stored } ?: HaltReason.SERVER_ROLLED_BACK
    }

    override suspend fun recordHalt(reason: HaltReason) = store.recordHalt(accountId, reason.name)

    override suspend fun clearHalt() = store.clearHalt(accountId)

    /**
     * `store.dataVersion(accountId)` returns `null` for two different states — genuinely no row
     * (never pulled), and a row that exists with `data_version` still `NULL` (pulled at least
     * once, generation never recorded) — and only the first may fall back to
     * [SyncEngine.DATA_VERSION]. `RoomSyncStore.dataVersion()` faces the identical ambiguity
     * (there spelled `0` vs. absent-row, since Android's column carries a `NOT NULL DEFAULT 0`)
     * and resolves it the same way: an absent row reads as current, because a device that has
     * never pulled starts its next pull at 0 and fetches everything anyway; anything else reads
     * as `0`, which [SyncEngine] treats as "behind" and corrects on the next completed pull.
     *
     * [RecordStore.hasSyncStateRow] is what makes the distinction possible at all — see its own
     * doc for why `dataVersion` alone cannot.
     */
    override suspend fun dataVersion(): Int =
        store.dataVersion(accountId)
            ?: if (store.hasSyncStateRow(accountId)) 0 else SyncEngine.DATA_VERSION

    override suspend fun saveDataVersion(version: Int) = store.saveDataVersion(accountId, version)

    /**
     * The `createdAt` of a record this device already holds, or null.
     *
     * `SyncRecord` does not carry `createdAt` and the payload does; `SyncRecords` explains why the
     * conversion is lossy in that one direction. This is the lookup `EnvelopeSyncTransport` uses to
     * put the column back on the way out, and null means the device has never seen the record — in
     * which case the transport falls back to the record's own `updatedAt`, which is the convention
     * the receiving side uses too.
     */
    suspend fun createdAtOf(type: RecordType, uuid: String): Long? {
        val blindedId = codec.blindedIdOf(type.wireKey, uuid)
        val row = store.read(blindedId) ?: return null
        val payload = (codec.open(blindedId, row.envelope) as? OpenResult.Ok)?.payload ?: return null
        return payload.field(PayloadFields.CREATED_AT)?.toLongOrNull()
    }

    /**
     * An attachment's opaque `meta`, or `""` for a record type without the column and for a record
     * this device does not hold. Mirrors [createdAtOf]; see `SyncStoreFactory.metaOf` for why the
     * value is asked of the row rather than assumed empty.
     */
    suspend fun metaOf(type: RecordType, uuid: String): String {
        if (type != RecordType.ATTACHMENT) return ""
        return existingMeta(codec.blindedIdOf(type.wireKey, uuid)).orEmpty()
    }

    private fun put(
        record: SyncRecord,
        dirty: Boolean,
        seq: Long?,
        baseline: Hlc?,
        remoteCreatedAt: Long?,
        remoteMeta: String?,
    ) {
        val blindedId = codec.blindedIdOf(record.type.wireKey, record.uuid)
        // The row already on disk first, because `createdAt` is what the notes list sorts on and a
        // merge has no business moving it. Then the value the incoming payload carried, because a
        // record's creation time is a property of the record and not something two devices contest
        // -- it was always sent and merely dropped one step before it was needed (issue #90).
        // `updatedAt` only as a last resort, which is where a locally-minted conflict copy lands;
        // that is the same fallback `ConflictCopies` and the phone's `RecordRows.createdAtFor`
        // choose, so the two platforms agree rather than each inventing something.
        val createdAt = existingCreatedAt(blindedId)
            ?: remoteCreatedAt?.takeIf { it != 0L }
            ?: record.valueOf(my.cheysoff.core_domain.sync.FieldClocks.UPDATED_AT)
                .parts[0]?.toLongOrNull()
            ?: 0L
        // The incoming value when the payload carried one, otherwise whatever the stored
        // envelope already holds. Never a bare `""`: a build that does not understand what is in
        // `meta` must preserve it rather than tidy it away. See `PayloadFields.META`.
        val meta = remoteMeta ?: existingMeta(blindedId).orEmpty()
        val sealed = codec.seal(SyncRecords.toPayload(record, createdAt, meta))
        store.writeMerged(
            blindedId = sealed.blindedId,
            envelope = sealed.envelope,
            dirty = dirty,
            lastSyncedSeq = seq,
            contentBaseline = baseline?.toString(),
        )
    }

    private fun existingCreatedAt(blindedId: String): Long? {
        val row = store.read(blindedId) ?: return null
        val payload = (codec.open(blindedId, row.envelope) as? OpenResult.Ok)?.payload ?: return null
        return payload.field(PayloadFields.CREATED_AT)?.toLongOrNull()
    }

    /**
     * The `meta` the stored envelope for [blindedId] carries, or null when there is no such row,
     * it will not open, or its record type has no such column.
     *
     * Indexed rather than `RecordPayload.field(...)`, which requires the column to belong to the
     * record's type and **throws** when it does not. Every type but `ATTACHMENT` has no `meta`, and
     * this is called on the merged-write path for all of them, so "this record has none" has to be
     * an answer rather than an exception.
     */
    private fun existingMeta(blindedId: String): String? {
        val row = store.read(blindedId) ?: return null
        val payload = (codec.open(blindedId, row.envelope) as? OpenResult.Ok)?.payload ?: return null
        return payload.fields[PayloadFields.META]
    }

    /** One row as the engine sees it, or null when it is absent or will not open. */
    private fun open(blindedId: String): StoredRecord? {
        val row = store.read(blindedId) ?: return null
        val payload = (codec.open(blindedId, row.envelope) as? OpenResult.Ok)?.payload ?: return null
        val record = SyncRecords.fromPayload(payload) ?: return null
        return StoredRecord(
            record = record,
            dirty = row.dirty,
            // `0` is the engine's spelling of "the server has no version of this record"; the
            // column spells it NULL. The two are the same state -- `seq` is the server's own
            // counter and starts at 1 -- and `PushItem.baseSeq` documents 0 as asserting exactly
            // that.
            lastSyncedSeq = row.lastSyncedSeq ?: 0L,
            contentBaseline = row.contentBaseline?.let(Hlc::parse),
        )
    }
}
