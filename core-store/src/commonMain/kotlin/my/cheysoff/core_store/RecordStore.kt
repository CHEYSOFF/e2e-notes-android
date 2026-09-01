package my.cheysoff.core_store

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import my.cheysoff.core_crypto.sync.AccountKeys
import my.cheysoff.core_crypto.sync.BlindedRecordId
import my.cheysoff.core_crypto.sync.RecordEnvelope
import my.cheysoff.core_domain.sync.RecordType
import my.cheysoff.core_domain.sync.SyncRecord
import my.cheysoff.core_store.db.RecordDatabase
import my.cheysoff.core_sync_net.wire.RecordPayload

/**
 * One row of the `records` table, opened.
 *
 * [createdAt] rides alongside the record rather than inside it — see `RecordPayload`'s KDoc for
 * why `created` is not a clocked field.
 */
class OpenRecord(
    val record: SyncRecord,
    val createdAt: Long,
    /** True when this device holds a version the server has not acknowledged. */
    val dirty: Boolean,
    /** The `seq` of the version this device last agreed with; `0` = never sent. */
    val lastSyncedSeq: Long,
)

/**
 * The sealed record store: reads and writes rows of `records`, sealing on the way in and opening on
 * the way out.
 *
 * ## What this class is, in one sentence
 *
 * It is the only place in the Apple build that holds plaintext notes and ciphertext at the same
 * time, which is what makes it the only place worth auditing for the property the whole store
 * exists for: **nothing reaches SQLite except a sealed envelope**.
 *
 * ## Why it needs the account keys, and what follows from that
 *
 * `K_id` turns a `(recType, uuid)` into the blinded ID a row is filed under, and `K_content` is the
 * root the per-record AEAD key comes from. So a store instance is only constructible after unlock,
 * and there is no "read the notes without the key" path to forget to close. `AccountKeys` is held
 * by reference and not copied, per its own contract; the caller owns its lifetime and calling
 * `destroy()` on it while a store is live will make every subsequent read fail to open — which is
 * the correct behaviour for a locked app, but it is a lifecycle the caller has to mean.
 *
 * ## Why every read opens every row
 *
 * Because the table has nothing else to read. `Records.sq` sets out the trade at length: a row is a
 * sealed envelope, so `WHERE is_deleted = 0` cannot exist and filtering happens after decryption.
 * `RecordNotesRepository` is written on top of that assumption.
 *
 * ## Records that will not open
 *
 * Dropped from the result rather than thrown on, and this is a deliberate difference from how the
 * *sync* path must behave. A record arriving from the server that does not open is evidence about
 * the server and `SyncTransport` requires it to be reported as `Faulted` so the engine can halt. A
 * row already on this device that does not open is a damaged file or a key that no longer matches,
 * and the useful behaviour for a notes list is to show the notes that are readable rather than
 * refuse to open the app. [unopenable] is how a caller finds out it happened.
 */
class RecordStore(
    private val database: RecordDatabase,
    private val keys: AccountKeys,
    /**
     * Where SQLite work happens.
     *
     * Injected rather than defaulted to `Dispatchers.IO`, which does not exist on Kotlin/Native in
     * the form the JVM has it, and because the tests need a deterministic one.
     */
    private val dispatcher: CoroutineDispatcher,
) {

    /**
     * How many rows the last read could not open.
     *
     * A count and not a list, because the identifying information about an unreadable row is
     * exactly the information that cannot be recovered from it. Surfaced so that "some notes are
     * missing" is answerable rather than mysterious.
     */
    var unopenable: Int = 0
        private set

    /** Every readable record, re-emitted whenever the table changes. */
    fun records(): Flow<List<OpenRecord>> =
        database.recordsQueries.selectAll()
            .asFlow()
            .mapToList(dispatcher)
            .map { rows ->
                var failed = 0
                val out = ArrayList<OpenRecord>(rows.size)
                for (row in rows) {
                    val opened = open(row.blinded_id, row.envelope, row.dirty, row.last_synced_seq)
                    if (opened == null) failed++ else out += opened
                }
                unopenable = failed
                out
            }

    /** One record, or null if it is absent or unreadable. */
    suspend fun load(type: RecordType, uuid: String): OpenRecord? = withContext(dispatcher) {
        val blindedId = blindedId(type, uuid)
        val row = database.recordsQueries.selectOne(blindedId).executeAsOneOrNull() ?: return@withContext null
        open(row.blinded_id, row.envelope, row.dirty, row.last_synced_seq)
    }

    /**
     * Seals [record] and writes it.
     *
     * [dirty] defaults to true because every caller in this module is a local edit, and a local
     * edit the server has not seen is the definition of dirty. The sync engine, when it lands, is
     * the only caller that will ever pass false.
     */
    suspend fun put(
        record: SyncRecord,
        createdAt: Long,
        dirty: Boolean = true,
        lastSyncedSeq: Long = 0L,
    ) = withContext(dispatcher) {
        val blindedId = blindedId(record.type, record.uuid)
        val envelope = RecordEnvelope.seal(
            kContent = keys.kContent,
            blindedId = blindedId,
            payload = RecordPayload.encode(record, createdAt),
        )
        database.recordsQueries.upsert(
            blinded_id = blindedId,
            envelope = envelope,
            dirty = if (dirty) 1L else 0L,
            last_synced_seq = lastSyncedSeq,
        )
    }

    /**
     * Writes several records as one transaction.
     *
     * The one caller that needs it is deleting a folder, which must move the folder to Trash and
     * unfile its notes together or not at all — `NotesRepository.deleteFolder` states that as a
     * requirement, and a half-applied version leaves notes pointing at a folder the UI no longer
     * shows.
     */
    suspend fun putAll(writes: List<Write>) = withContext(dispatcher) {
        // The sealing is done outside the transaction on purpose: it is the expensive part (one
        // HKDF and one AES-GCM per record, over a 4 KiB bucket) and holding a SQLite write
        // transaction open across it would block every reader for no reason.
        val sealed = writes.map { write ->
            val blindedId = blindedId(write.record.type, write.record.uuid)
            Triple(
                blindedId,
                RecordEnvelope.seal(
                    kContent = keys.kContent,
                    blindedId = blindedId,
                    payload = RecordPayload.encode(write.record, write.createdAt),
                ),
                write,
            )
        }
        database.transaction {
            sealed.forEach { (blindedId, envelope, write) ->
                database.recordsQueries.upsert(
                    blinded_id = blindedId,
                    envelope = envelope,
                    dirty = if (write.dirty) 1L else 0L,
                    last_synced_seq = write.lastSyncedSeq,
                )
            }
        }
    }

    /**
     * Destroys rows outright.
     *
     * Not what "delete a note" means. A user-facing delete is a tombstone — a record with
     * `isDeleted = 1` that stays and is pushed — because the protocol has no delete and a row that
     * simply vanished here would be resurrected by the next pull from a peer that still has it.
     * This is only for a purge, where the row is gone for good on this device by the user's
     * explicit instruction.
     */
    suspend fun purge(ids: List<Pair<RecordType, String>>) = withContext(dispatcher) {
        database.transaction {
            ids.forEach { (type, uuid) ->
                database.recordsQueries.deleteOne(blindedId(type, uuid))
            }
        }
    }

    /** One entry of [putAll]. */
    class Write(
        val record: SyncRecord,
        val createdAt: Long,
        val dirty: Boolean = true,
        val lastSyncedSeq: Long = 0L,
    )

    private fun blindedId(type: RecordType, uuid: String): String =
        BlindedRecordId.compute(keys.kId, type.wireKey, uuid)

    /**
     * Opens one row.
     *
     * The blinded ID is **recomputed** from the opened payload and compared, which is §4's third
     * check and is the same check the sync path owes. It is not redundant with the AEAD: the
     * associated data binds the envelope to the ID it was filed under, but nothing else binds the
     * `uuid` and `recType` *inside* the payload to that ID. Without this, a row could be moved to
     * another row's key by anything with write access to the file and would open perfectly well
     * under the wrong identity.
     */
    private fun open(
        blindedId: String,
        envelope: ByteArray,
        dirty: Long,
        lastSyncedSeq: Long,
    ): OpenRecord? {
        val payload = RecordEnvelope.open(keys.kContent, blindedId, envelope) ?: return null
        val decoded = RecordPayload.decode(payload) as? RecordPayload.Decoded.Ok ?: return null
        val expected = blindedId(decoded.record.type, decoded.record.uuid)
        if (expected != blindedId) return null
        return OpenRecord(
            record = decoded.record,
            createdAt = decoded.createdAt,
            dirty = dirty != 0L,
            lastSyncedSeq = lastSyncedSeq,
        )
    }
}
