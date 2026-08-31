package my.cheysoff.core_sync_engine.harness

import my.cheysoff.core_domain.sync.RecordType
import my.cheysoff.core_domain.sync.SyncRecord

/**
 * An in-process stand-in for the sync server, implementing exactly the parts of its contract the
 * merge engine can be wrong about.
 *
 * ## What it models, and what it deliberately does not
 *
 * It models the two operations the client loop is built on — `GET /v1/changes?since=` and the
 * compare-and-set `POST /v1/records` — plus the two faults that are load-bearing for merge
 * correctness: a rejected push that hands the conflicting version back inline, and a server that
 * has gone backwards.
 *
 * It models **no crypto at all.** Records are stored as plaintext [SyncRecord]s, keyed by a string
 * that stands in for the blinded record id. That is a deliberate limit, and it is the one
 * `e2e-sync-open-questions.md` §3 asks for in as many words: *"the simulation should run over
 * plaintext records so that a convergence failure is never confused with a decryption failure."*
 * Envelope construction, nonces and AAD binding have their own known-answer tests in
 * `core-crypto`; this file has no opinion about them.
 *
 * It also models no HTTP: no TLS, no certificate pinning, no timeouts, no truncated bodies, no
 * `429` and no retry herd. Those failures are real and they are `:core-sync-net`'s to catch. A
 * green run here is not a claim about any of them.
 *
 * ## The seq model
 *
 * One monotonic counter for the whole account, exactly as the real server has. Every accepted
 * write takes the next value, so `changes(since)` returning "every record whose seq is greater
 * than `since`, in seq order" is both the server's contract and a one-line implementation here.
 * A record has one current version; the server keeps no history, which is why a rolled-back server
 * is modelled by [rollbackTo] restoring an earlier snapshot rather than by rewinding a log.
 */
class FakeServer {

    /** One record as the server holds it: the version, and the seq that version was assigned. */
    data class Stored(val key: String, val seq: Long, val record: SyncRecord)

    /** What a push came back with. */
    sealed interface PutResult {
        /** Accepted; the record now has this seq. */
        data class Ok(val seq: Long) : PutResult

        /**
         * Refused, because `baseSeq` was not the record's current seq — the `409` of
         * `e2e-sync-phase3-plan.md` §3.2, with the conflicting version inline.
         *
         * **This is data, not an error.** The client feeds [record] back into the merge exactly as
         * though it had arrived from a pull; there is deliberately no second merge path for the
         * conflict case, because two paths are how the two of them drift apart.
         */
        data class Conflict(val seq: Long, val record: SyncRecord) : PutResult
    }

    private val records = LinkedHashMap<String, Stored>()
    private var nextSeq = 0L

    /** The highest seq this server has ever assigned. A client cursor above it means a rollback. */
    var highWaterMark: Long = 0L
        private set

    /** How many pushes have been refused. Read by the harness to prove the `409` path was hit. */
    var conflictCount: Int = 0
        private set

    /**
     * The key a record is filed under — the harness's stand-in for
     * `HMAC(K_id, recType ‖ ":" ‖ uuid)`.
     *
     * Plaintext on purpose, so a failing seed prints something a human can read. It has the one
     * property the blinded id has that matters to the merge: it is a function of the record type
     * and the uuid and of nothing else, so two devices file the same record under the same name.
     */
    fun keyOf(type: RecordType, uuid: String): String = "${type.wireKey}:$uuid"

    /** Every record changed since [since], in seq order — `GET /v1/changes?since=&limit=`. */
    fun changes(since: Long, limit: Int = 200): List<Stored> =
        records.values.filter { it.seq > since }.sortedBy { it.seq }.take(limit)

    /**
     * A compare-and-set write.
     *
     * [baseSeq] is the seq of the version the client last agreed with, and `0` means "this record
     * has never been on the server", which the server reads as **must not exist**. Anything other
     * than the record's current seq is refused with the current version inline.
     */
    fun put(key: String, baseSeq: Long, record: SyncRecord): PutResult {
        val existing = records[key]
        val currentSeq = existing?.seq ?: 0L
        if (baseSeq != currentSeq) {
            conflictCount++
            // A conflict against a record that does not exist cannot happen: currentSeq is then 0
            // and the only baseSeq that mismatches it is a non-zero one, which means the client
            // believes in a version this server has never had. That is a rolled-back server seen
            // from the other side, and returning the absent record is impossible — so the harness
            // says so rather than inventing an answer.
            val current = existing ?: error("push with baseSeq=$baseSeq against a record this server has never held")
            return PutResult.Conflict(seq = current.seq, record = current.record)
        }
        nextSeq += 1
        highWaterMark = nextSeq
        records[key] = Stored(key = key, seq = nextSeq, record = record)
        return PutResult.Ok(nextSeq)
    }

    /** Everything the server holds, for the harness's convergence assertions. */
    fun snapshot(): Map<String, SyncRecord> = records.mapValues { it.value.record }

    /**
     * A point-in-time copy, so a test can restore it later and simulate a server rolled back to a
     * backup — the one attack the record envelope's associated data never defended against and
     * could not, since a replayed version is exactly the tuple the client sealed.
     */
    fun backup(): Backup = Backup(records.toMap(), nextSeq)

    /**
     * Restores [backup], **keeping [highWaterMark] where it was**.
     *
     * That combination is what a restored-from-backup server actually looks like to a client that
     * has been running: old records, and a client cursor already past anything the server can now
     * produce. Both halves have their own defence — the cursor case is `409 cursor_ahead_of_server`
     * at the transport layer, and the record case is the merge's rollback guard — and the harness
     * exercises the second one.
     */
    fun restore(backup: Backup) {
        records.clear()
        records.putAll(backup.records)
        nextSeq = backup.nextSeq
    }

    /** An opaque point-in-time copy. See [backup]. */
    class Backup internal constructor(
        internal val records: Map<String, Stored>,
        internal val nextSeq: Long,
    )
}
