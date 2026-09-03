package my.cheysoff.core_sync_engine

import my.cheysoff.core_domain.sync.FieldClocks
import my.cheysoff.core_domain.sync.FieldValue
import my.cheysoff.core_domain.sync.Hlc
import my.cheysoff.core_domain.sync.RecordType
import my.cheysoff.core_domain.sync.SyncRecord
import my.cheysoff.core_domain.sync.SyncValues

/**
 * Fixtures and doubles for the engine's own tests.
 *
 * The convergence harness next door drives the engine through a simulated *device*, which is the
 * right shape for the properties it checks and the wrong one for a rule like "the cursor is not
 * persisted past a record that would not open" — that needs a page built by hand, containing
 * exactly the fault under test. So this file exists, and everything in it is deliberately dumber
 * than `ReplicaStore`: these doubles record what they were asked to do and nothing else, because a
 * double with behaviour of its own is a second implementation to be wrong about.
 */

/** An [Hlc], with the two components a test usually does not care about defaulted. */
fun hlc(ms: Long, counter: Int = 0, node: String = "a"): Hlc = Hlc(ms, counter, node)

/** A complete, valid note record. */
fun note(
    uuid: String = "n1",
    rowClock: Hlc = hlc(1),
    fieldClocks: Map<String, Hlc> = emptyMap(),
    title: String = "",
    content: String = "",
    deleted: Boolean = false,
    deletedAt: Long? = null,
): SyncRecord = SyncRecord(
    type = RecordType.NOTE,
    uuid = uuid,
    rowClock = rowClock,
    fieldClocks = fieldClocks,
    fields = mapOf(
        FieldClocks.TITLE to FieldValue.of(title),
        FieldClocks.CONTENT to FieldValue.of(content, "html"),
        FieldClocks.CHECKLIST to FieldValue.of(""),
        FieldClocks.PINNED to FieldValue.of(SyncValues.FALSE),
        FieldClocks.FAVORITE to FieldValue.of(SyncValues.FALSE),
        FieldClocks.FOLDER to FieldValue.of(null),
        FieldClocks.UPDATED_AT to FieldValue.of("0"),
        FieldClocks.DELETED to FieldValue.of(SyncValues.of(deleted), deletedAt?.toString()),
    ),
).validate()

/** A stored row, with the bookkeeping most tests do not vary. */
fun stored(
    record: SyncRecord,
    dirty: Boolean = false,
    lastSyncedSeq: Long = 0L,
    contentBaseline: Hlc? = null,
): StoredRecord = StoredRecord(record, dirty, lastSyncedSeq, contentBaseline)

/** The single-part value of [field], for the assertions that read one column back. */
fun SyncRecord.text(field: String): String? = valueOf(field).parts.first()

/**
 * An in-memory [SyncStore] that also remembers, in order, every write it was asked to make.
 *
 * The order matters more than it looks. "The cursor is persisted only after the records below it
 * have been applied" is a claim about sequence and nothing else, and [writes] is the only way to
 * check it that a reordering would actually break.
 */
class RecordingStore(
    private val rows: MutableMap<String, StoredRecord> = LinkedHashMap(),
) : SyncStore {

    /** One line per write, in the order they happened. */
    val writes = mutableListOf<String>()

    var cursor: Long = 0L
        private set

    var halt: HaltReason? = null
        private set

    override suspend fun cursor(): Long = cursor

    override suspend fun saveCursor(seq: Long) {
        cursor = seq
        writes += "cursor=$seq"
    }

    override suspend fun load(type: RecordType, uuid: String): StoredRecord? = rows[key(type, uuid)]

    override suspend fun dirtyRecords(): List<StoredRecord> = rows.values
        .filter { it.dirty }
        .sortedWith(compareBy({ it.record.rowClock }, { it.record.uuid }))

    override suspend fun applyMerged(write: MergedWrite) {
        rows[key(write.record.type, write.record.uuid)] = StoredRecord(
            record = write.record,
            dirty = write.dirty,
            lastSyncedSeq = write.seq,
            contentBaseline = write.contentBaseline,
        )
        writes += "apply=${write.record.uuid} dirty=${write.dirty} seq=${write.seq}"
        write.conflictCopy?.let { copy ->
            rows[key(RecordType.NOTE, copy.uuid)] =
                StoredRecord(copy, dirty = true, lastSyncedSeq = 0L, contentBaseline = null)
            writes += "copy=${copy.uuid}"
        }
    }

    override suspend fun recordSeen(
        type: RecordType,
        uuid: String,
        seq: Long,
        contentBaseline: Hlc?,
    ) {
        val row = rows.getValue(key(type, uuid))
        rows[key(type, uuid)] = StoredRecord(row.record, row.dirty, seq, contentBaseline)
        writes += "seen=$uuid seq=$seq"
    }

    override suspend fun acknowledgePush(
        type: RecordType,
        uuid: String,
        sealedRowClock: Hlc,
        seq: Long,
        contentBaseline: Hlc?,
    ) {
        val row = rows.getValue(key(type, uuid))
        val moved = row.record.rowClock != sealedRowClock
        rows[key(type, uuid)] = StoredRecord(row.record, moved, seq, contentBaseline)
        writes += "ack=$uuid seq=$seq dirty=$moved"
    }

    override suspend fun halt(): HaltReason? = halt

    override suspend fun recordHalt(reason: HaltReason) {
        if (halt == null) halt = reason
        writes += "halt=$reason"
    }

    override suspend fun clearHalt() {
        halt = null
        writes += "halt=cleared"
    }

    /** Puts a row in place without going through the engine, for a test's starting state. */
    fun put(row: StoredRecord) {
        rows[key(row.record.type, row.record.uuid)] = row
    }

    /** A row by uuid, assuming it is a note. */
    fun noteRow(uuid: String): StoredRecord? = rows[key(RecordType.NOTE, uuid)]

    /** Every row this store holds. */
    fun rows(): Map<String, StoredRecord> = rows

    private fun key(type: RecordType, uuid: String) = "${type.wireKey}:$uuid"
}

/**
 * A [SyncTransport] driven by a script.
 *
 * [pages] are answered in order, one per `changesSince`; the last one is repeated if the engine
 * asks again, which only happens when a test built one with `hasMore = true` and meant it.
 * [pushResponses] work the same way. Anything in [failures] keyed by call index is thrown instead.
 */
class ScriptedTransport(
    private val pages: List<ChangePage> = emptyList(),
    private val pushResponses: List<PushResponse> = emptyList(),
    private val onChanges: (Int) -> Unit = {},
    private val onPush: (Int) -> Unit = {},
) : SyncTransport {

    /** Every `(since, limit)` this transport was asked for. */
    val pulls = mutableListOf<Pair<Long, Int>>()

    /** Every batch this transport was handed. */
    val pushes = mutableListOf<List<PushRequest>>()

    override suspend fun changesSince(since: Long, limit: Int): ChangePage {
        onChanges(pulls.size)
        pulls += since to limit
        if (pages.isEmpty()) return ChangePage(emptyList(), hasMore = false)
        return pages[minOf(pulls.size - 1, pages.size - 1)]
    }

    override suspend fun push(items: List<PushRequest>): PushResponse {
        onPush(pushes.size)
        pushes += items
        if (pushResponses.isEmpty()) return PushResponse(emptyList())
        return pushResponses[minOf(pushes.size - 1, pushResponses.size - 1)]
    }
}

/**
 * A transport over a fixed change stream that honours `since` and `limit` the way the server does.
 *
 * Needed for the resume tests, and only for those: a scripted page list cannot answer "what does
 * this engine see when it starts again from the cursor it managed to write", which is the whole
 * question a crash test asks.
 *
 * @param failChangesAtCall the zero-based `changesSince` call that throws instead of answering.
 */
class PagingTransport(
    private val stream: List<IncomingRecord>,
    private val failChangesAtCall: Int? = null,
) : SyncTransport {

    val pulls = mutableListOf<Long>()

    override suspend fun changesSince(since: Long, limit: Int): ChangePage {
        if (pulls.size == failChangesAtCall) {
            pulls += since
            throw SyncTransportException(TransportFault.NETWORK, "the connection dropped")
        }
        pulls += since
        val page = stream.filter { it.seq > since }.sortedBy { it.seq }.take(limit)
        return ChangePage(page, hasMore = page.size == limit)
    }

    override suspend fun push(items: List<PushRequest>): PushResponse = PushResponse(emptyList())
}

/**
 * A transport that refuses every push of [blocking]'s record and accepts everything else.
 *
 * The only way to reach the engine's round cap without also modelling a peer that is lying about
 * something else.
 */
class AlwaysConflictTransport(private val blocking: SyncRecord) : SyncTransport {

    var pulls = 0
        private set

    var pushes = 0
        private set

    override suspend fun changesSince(since: Long, limit: Int): ChangePage {
        pulls++
        return ChangePage(emptyList(), hasMore = false)
    }

    override suspend fun push(items: List<PushRequest>): PushResponse {
        pushes++
        return PushResponse(
            items.map { item ->
                if (item.uuid == blocking.uuid) {
                    PushAck.Conflicted(item.type, item.uuid, blocking, currentSeq = 1L)
                } else {
                    PushAck.Accepted(item.type, item.uuid, seq = 1L)
                }
            }
        )
    }
}

/** Every clock the engine reported, in order. */
class RecordingClock : ClockObserver {
    val seen = mutableListOf<Hlc>()
    override fun observe(seen: Hlc) {
        this.seen += seen
    }
}

/** A page of already-opened records at seqs `1..n`. */
fun openedPage(vararg records: SyncRecord, firstSeq: Long = 1L, hasMore: Boolean = false): ChangePage =
    ChangePage(
        records = records.mapIndexed { index, record ->
            IncomingRecord.Opened(seq = firstSeq + index, record = record, createdAt = null)
        },
        hasMore = hasMore,
    )
