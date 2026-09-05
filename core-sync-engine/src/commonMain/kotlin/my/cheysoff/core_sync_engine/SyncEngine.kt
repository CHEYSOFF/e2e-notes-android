package my.cheysoff.core_sync_engine

import kotlinx.coroutines.sync.Mutex
import my.cheysoff.core_domain.sync.Hlc
import my.cheysoff.core_domain.sync.Merge
import my.cheysoff.core_domain.sync.MergeResult
import my.cheysoff.core_domain.sync.RecordSize
import my.cheysoff.core_domain.sync.RecordType
import my.cheysoff.core_domain.sync.RejectReason
import my.cheysoff.core_domain.sync.SyncRecord

/**
 * The sync coordinator: one pass is pull, then push, then — if the push merged anything — round
 * again.
 *
 * ## What this is
 *
 * The loop `docs/design/e2e-sync-phase3-plan.md` §3 describes, and nothing else. It owns the
 * *sequencing* of a pass and the bookkeeping that makes a torn pass resumable; it owns no merge
 * rules at all. Every decision about what a record *means* is `Merge.merge`'s, called from exactly
 * one place ([applyIncoming]) for both a pulled record and the version a `409` hands back — because
 * a second, subtly different merge for the conflict case is how the two drift apart, and the plan
 * says so by name.
 *
 * ## Pure, for the same reason `Merge` is
 *
 * No Android, no Room, no HTTP, no clock, no I/O. Storage is [SyncStore] and the server is
 * [SyncTransport], both defined here and both implemented outside. That is what lets `Replica` in
 * the convergence harness drive **this class** across thousands of seeded schedules on the JVM in
 * about a second, instead of the harness re-implementing a push/pull loop that then has to be
 * trusted to agree with this one. Two implementations of a loop whose job is to agree is the
 * failure this project keeps meeting; there is only one here.
 *
 * ## The three things a pass must survive
 *
 *  - **Process death, anywhere.** The engine holds nothing across passes: the cursor, the dirty
 *    set and the halt all live in [SyncStore], so a fresh engine over the same store is the same
 *    engine. The cursor is written only after the records below it have been applied, and a record
 *    re-delivered is `MergeResult.NoChange`, so a torn pass costs one repeat and nothing else.
 *  - **A push the server committed and the client never heard.** The row stays dirty against a
 *    stale `baseSeq`, the next push takes a `409`, and the conflicting version is this device's
 *    own. The merge of a record against itself is a no-op — in particular it must not read as a
 *    contested body and mint a conflict copy — and the engine's job is only to feed it back through
 *    the same call as a pull rather than inventing a "this is my own record" branch.
 *  - **A server that has gone backwards.** Halts, loudly, in both the places it is visible: the
 *    record-level `ROLLBACK_SUSPECTED` and the whole-server `cursor_ahead_of_server`. Never resets
 *    the cursor; §8's F7 is explicit that against clean rows that is indistinguishable from "the
 *    account is empty" and the next pass is a mass delete.
 *
 * ## What it deliberately does not do
 *
 * It does not schedule itself, hold a coroutine scope, watch the unlock state, or decide when a
 * pass is worth running — those are the app's (§7). It does not open envelopes or compute blinded
 * ids; see [SyncTransport]. It does not mint clocks: the only clock it touches is a remote one it
 * feeds to [ClockObserver], because a device whose generator has not seen an incoming clock can
 * mint its next write *below* a record it has already accepted.
 *
 * @param batchLimit how many records go in one push. The server refuses a batch over 64.
 * @param pageLimit how many records one `changesSince` asks for.
 * @param maxRounds how many pull/push rounds one [runPass] will do. A round happens only when the
 *   push merged something, so the cap is a guard against a pathological peer rather than a normal
 *   limit; §3 puts it at 3.
 */
class SyncEngine(
    private val store: SyncStore,
    private val transport: SyncTransport,
    private val clock: ClockObserver,
    private val retryPlan: RetryPlan = RetryPlan(RetryJitter.RANDOM),
    private val batchLimit: Int = DEFAULT_BATCH_LIMIT,
    private val pageLimit: Int = DEFAULT_PAGE_LIMIT,
    private val maxRounds: Int = DEFAULT_MAX_ROUNDS,
) {

    init {
        require(batchLimit in 1..MAX_BATCH_LIMIT) { "a push batch is 1..$MAX_BATCH_LIMIT items" }
        require(pageLimit >= 1) { "a page holds at least one record" }
        require(maxRounds >= 1) { "a pass does at least one round" }
    }

    /**
     * One pass at a time.
     *
     * Two overlapping passes would both read the dirty rows and both push them, and the second
     * would take a `409` against the first (§7). `tryLock` rather than `lock`: the caller that lost
     * the race is a timer, and its next tick is a better time to sync than the instant a pass ends.
     */
    private val pass = Mutex()

    /**
     * Records this session has already seen [TransportFault.REJECTED], keyed by type, uuid **and
     * row clock**, so a permanently-too-large record is not re-sealed and re-uploaded on every
     * pass while nothing about it has changed.
     *
     * In-memory and bounded, deliberately: this is the interim state before a server understands
     * attachments, not a permanent record of failure, so it needs no schema and no migration.
     * Keying on the row clock rather than just the identity is what makes editing the record (a
     * new clock) or restarting the process (an empty set again) both retry it -- the two ordinary
     * "something changed, try again" triggers, for free. Insertion-ordered so the oldest entry is
     * what [rememberRejected] evicts once the bound is hit, which is the entry least likely to
     * still be relevant.
     */
    private val rejectedThisSession = LinkedHashSet<String>()

    /**
     * Pull, push, and round again while the push merged something.
     *
     * The product entry point. Never throws: every failure is a [SyncOutcome].
     */
    suspend fun runPass(): SyncOutcome = exclusively {
        var stats = PassStats.NONE
        repeat(maxRounds) {
            val pulled = pull()
            stats += pulled.stats
            pulled.stop?.let { return@exclusively it.withStats(stats) }

            val pushed = push()
            stats += pushed.stats
            pushed.stop?.let { return@exclusively it.withStats(stats) }

            // Another round only if the push merged something, because that merged row is a version
            // the server does not have yet and the next pull may well be arguing with it. A push
            // that merely succeeded has nothing left to say.
            if (pushed.stats.conflicts == 0) return@exclusively SyncOutcome.Completed(stats)
        }
        SyncOutcome.Completed(stats)
    }

    /**
     * Apply everything the server has that this device has not seen, and stop.
     *
     * Half of a pass. It exists as its own entry point for two reasons that are worth stating,
     * because a method that only tests use is a method that rots: a pull-only refresh is a real
     * product gesture, and the convergence harness needs to produce interleavings that a full pass
     * cannot — a push without a preceding pull is the only way to force the `409` path, which is
     * exactly the path a full pass is designed to avoid.
     */
    suspend fun pullOnce(): SyncOutcome = exclusively {
        val pulled = pull()
        pulled.stop?.withStats(pulled.stats) ?: SyncOutcome.Completed(pulled.stats)
    }

    /** Send this device's dirty rows, and stop. See [pullOnce] for why this is public. */
    suspend fun pushOnce(): SyncOutcome = exclusively {
        val pushed = push()
        pushed.stop?.withStats(pushed.stats) ?: SyncOutcome.Completed(pushed.stats)
    }

    /**
     * Runs [body] under the pass lock, having first refused to run at all if the engine is halted.
     *
     * The halt is read from the store on **every** entry rather than cached, because the events
     * that cause one outlive the process. An engine that forgot its halt on restart would resume
     * syncing against precisely the server it refused to trust.
     */
    private suspend inline fun exclusively(body: () -> SyncOutcome): SyncOutcome {
        if (!pass.tryLock()) return SyncOutcome.AlreadyRunning
        try {
            store.halt()?.let { return SyncOutcome.Halted(it, PassStats.NONE) }
            return body()
        } finally {
            pass.unlock()
        }
    }

    // ── Pull ───────────────────────────────────────────────────────────────────────────────────

    /**
     * Applies every record the server has past the stored cursor.
     *
     * ## The cursor rule
     *
     * The cursor is persisted **after** the records below it have been applied, never before. That
     * ordering is the whole of the crash argument: dying between the two costs one re-delivery,
     * which the merge answers with `NoChange`, whereas the other order silently skips records.
     *
     * ## An unreadable record does not stop the world, and does not get past the cursor
     *
     * §8's F1 asks for both halves at once — count it, skip it, and *do not advance the cursor past
     * it*. So the readable records on the page are still applied (their merges are idempotent, so
     * re-applying them next pass is free), the committed cursor freezes at the record before the
     * first fault, and paging stops. Nothing beyond a fault is ever mistaken for delivered.
     */
    private suspend fun pull(): Phase {
        var stats = PassStats.NONE
        // A generation behind means this device paged past record types it did not implement at
        // the time, so its cursor sits beyond records it never stored. One pull from 0 recovers
        // them. That is safe against the rollback guard for a reason worth keeping next to the
        // call that does it: `changesSince` serves head versions only, never history, so every
        // record this replay sees is either the version this device already holds -- equal clocks,
        // so `remote.rowClock < local.rowClock` in the merge's guard is false -- or a newer one, an
        // ordinary apply. Neither trips `RejectReason.ROLLBACK_SUSPECTED`. That reject exists for a
        // server that has stopped being trustworthy, where an emptied account would read as "delete
        // everything"; here the client is asking for the replay and the server is known-good.
        val storedVersion = store.dataVersion()
        val rebaselining = storedVersion < DATA_VERSION
        val startCursor = if (rebaselining) 0L else store.cursor()
        var committable = startCursor
        var frozen = false
        var since = startCursor

        while (true) {
            val page = try {
                transport.changesSince(since, pageLimit)
            } catch (failure: SyncTransportException) {
                return finishPull(committable, startCursor, stats, stop(failure), storedVersion)
            }

            for (incoming in page.records) {
                stats = stats.copy(received = stats.received + 1)
                when (incoming) {
                    is IncomingRecord.Faulted -> when (incoming.fault) {
                        RecordFault.UNREADABLE -> {
                            stats = stats.copy(unreadable = stats.unreadable + 1)
                            frozen = true
                            if (stats.unreadable > UNREADABLE_RECORD_LIMIT) {
                                return finishPull(
                                    committable, startCursor, stats,
                                    halt(HaltReason.RECORDS_UNREADABLE), storedVersion,
                                )
                            }
                        }

                        RecordFault.MISLABELLED -> return finishPull(
                            committable, startCursor, stats,
                            halt(HaltReason.RECORD_MISLABELLED), storedVersion,
                        )

                        RecordFault.UNSUPPORTED_PAYLOAD_VERSION -> return finishPull(
                            committable, startCursor, stats,
                            halt(HaltReason.UNSUPPORTED_PAYLOAD_VERSION), storedVersion,
                        )

                        // The one fault the cursor may pass. A record this build cannot represent
                        // would not have been stored even if it had been accepted, so nothing is
                        // lost by moving on -- and freezing here is what halted a device whose
                        // only problem was being one version behind. Task 7's re-baseline is how
                        // it recovers these once it understands them.
                        RecordFault.UNKNOWN_TYPE -> {
                            stats = stats.copy(ignored = stats.ignored + 1)
                            if (!frozen) committable = incoming.seq
                        }
                    }

                    is IncomingRecord.Opened -> {
                        val applied =
                            applyIncoming(incoming.record, incoming.seq, incoming.createdAt)
                        stats += applied.stats
                        applied.stop?.let {
                            return finishPull(committable, startCursor, stats, it, storedVersion)
                        }
                        if (!frozen) committable = incoming.seq
                    }
                }
            }

            // Paging stops at the first fault: past it the cursor cannot advance, so fetching more
            // would apply records this pass can never record having seen.
            if (frozen || !page.hasMore) break
            // A page that did not move the cursor forward would be requested again identically. The
            // server's contract makes that impossible -- `changes(since)` returns only seqs above
            // `since` -- so this is not a retry, it is a refusal to spin against a peer that is not
            // honouring it.
            val end = page.records.lastOrNull()?.seq ?: break
            if (end <= since) break
            since = end
        }
        return finishPull(committable, startCursor, stats, null, storedVersion)
    }

    /**
     * Persists the cursor if it moved, records the generation on a completed pull, and packages
     * the phase's result.
     *
     * @param storedVersion the generation [store] reported at the *start* of this pull, before
     *   anything below could have changed it. Threaded in as a parameter rather than re-read here:
     *   the engine runs one pass at a time under [pass], but a field would be pass state that
     *   outlives the pass that owns it, and re-reading the store would race whatever [saveCursor]
     *   just did.
     */
    private suspend fun finishPull(
        committable: Long,
        startCursor: Long,
        stats: PassStats,
        stop: SyncOutcome?,
        storedVersion: Int,
    ): Phase {
        // Written even when the phase is stopping: everything below `committable` was applied, and
        // an unwritten cursor after a halt would replay the whole account on the next re-baseline.
        // Only forwards, and only if it moved -- a store write per empty page is pure noise.
        if (committable > startCursor) store.saveCursor(committable)
        // Fires on ANY completed pull whose stored generation differs from this build's, not only
        // a re-baselining one (`storedVersion < DATA_VERSION`). A build's first-ever pull for an
        // account leaves the generation unrecorded -- Android's column defaults to 0, and the
        // desktop's has no row yet at all -- and gating this solely on `rebaselining` left that
        // state permanently unrecorded on every platform: nothing else in production ever calls
        // `saveDataVersion`, so a device that reached "unrecorded" this way stayed unrecorded
        // forever, and could never detect a future generation bump. `!=` closes that, at the cost
        // of one redundant write the one time a build's own generation happens to already match
        // what was stored -- which never loses information, only repeats it.
        //
        // Still gated on the pull having completed (`stop == null`): a re-baseline cut short by a
        // dropped connection has to run again in full, and recording the generation here would
        // tell the device it had caught up while the records the interruption cost it are still
        // behind its cursor.
        if (stop == null && storedVersion != DATA_VERSION) store.saveDataVersion(DATA_VERSION)
        return Phase(stats, stop)
    }

    // ── Push ───────────────────────────────────────────────────────────────────────────────────

    /**
     * Sends every dirty row, in batches, and handles what comes back.
     *
     * ## `baseSeq` and the two rules that go with it
     *
     * The envelope is built from the row **as it is at the moment the batch is assembled**, and
     * that version is remembered. §3.2's two rules are then both about the gap between then and the
     * acknowledgement: `dirty` may be cleared only if the row has not moved (or the user's edit,
     * made while the push was in flight, is dropped with no error), and `lastSyncedSeq` must be
     * written either way (or the next push sends a stale `baseSeq` and takes a guaranteed `409`).
     * Both live in [SyncStore.acknowledgePush], as one transaction, because separately they are two
     * chances to write half of it.
     *
     * ## A `409` is data
     *
     * It carries the blocking version inline, and that version goes through [applyIncoming] — the
     * same call a pulled record takes. The row stays dirty and the *next* pass pushes the merged
     * result; pushing it again inside this loop would race the same conflict a second time.
     */
    private suspend fun push(): Phase {
        var stats = PassStats.NONE
        val pending = store.dirtyRecords()
        if (pending.isEmpty()) return Phase(stats, null)

        // A record this session already watched the server refuse for its bytes is skipped before
        // it is even sealed. Nothing about it can have changed -- the row clock is part of the
        // key, so an edit would already have missed this set -- and re-sending the identical bytes
        // costs a full re-seal and upload of, potentially, a multi-megabyte attachment every single
        // pass for no different outcome. Still counted as rejected: the signal must not disappear
        // just because the network call it used to cost did.
        val (known, sendable) = pending.partition { rejectionKeyOf(it.record) in rejectedThisSession }
        stats = stats.copy(rejected = stats.rejected + known.size)
        if (sendable.isEmpty()) return Phase(stats, null)

        for (batch in batches(sendable)) {
            // Keyed by identity rather than read back by position: the transport contract allows a
            // response in any order, and an engine that trusted the order would acknowledge one row
            // with another row's seq -- a lost update that looks like nothing at all.
            val sentByKey = batch.associateBy { keyOf(it.record.type, it.record.uuid) }
            val requests = batch.map { row ->
                PushRequest(
                    type = row.record.type,
                    uuid = row.record.uuid,
                    baseSeq = row.lastSyncedSeq,
                    record = row.record,
                )
            }

            val response = try {
                transport.push(requests)
            } catch (failure: SyncTransportException) {
                // A batch of one that the server will refuse again for the same bytes is the one
                // failure that must not end the pass. The row stays dirty -- nothing is lost -- and
                // every other batch still goes. Attribution is only sound for a batch of one, which
                // is why anything large enough to provoke this was put in one.
                //
                // It is NOT retried next pass: `rememberRejected` holds it for the session, so an
                // edit or a restart is what tries it again. A server upgraded mid-session therefore
                // goes unnoticed until one of those happens, which is the price of not re-uploading
                // a megabyte a minute against a server that is certain to refuse it.
                if (failure.fault == TransportFault.REJECTED && batch.size == 1) {
                    stats = stats.copy(rejected = stats.rejected + 1)
                    rememberRejected(batch.single().record)
                    continue
                }
                return Phase(stats, stop(failure))
            }

            for (ack in response.results) {
                val sent = sentByKey[keyOf(ack.type, ack.uuid)] ?: continue
                when (ack) {
                    is PushAck.Accepted -> {
                        stats = stats.copy(pushed = stats.pushed + 1)
                        store.acknowledgePush(
                            type = ack.type,
                            uuid = ack.uuid,
                            // The clock of the version that was actually SENT, not the row's clock
                            // now. They differ exactly when the user edited during the push, which
                            // is the one case rule 1 exists for.
                            sealedRowClock = sent.record.rowClock,
                            seq = ack.seq,
                            contentBaseline = Baselines.advance(
                                previous = sent.contentBaseline,
                                agreed = sent.record,
                            ),
                        )
                    }

                    is PushAck.Conflicted -> {
                        stats = stats.copy(conflicts = stats.conflicts + 1)
                        // A conflict with no version attached is legal and leaves nothing to merge.
                        // The row stays dirty and the next pull fetches the blocking version the
                        // ordinary way, which is slower and always correct.
                        val current = ack.current ?: continue
                        // No `remoteCreatedAt` on this route, and it provably cannot matter: a
                        // push conflict is the server refusing a row THIS device sent, so the row
                        // is in this store already and its `createdAt` is set. The value is only
                        // ever consulted for a record being seen for the first time.
                        val applied = applyIncoming(current, ack.currentSeq, remoteCreatedAt = null)
                        stats += applied.stats
                        applied.stop?.let { return Phase(stats, it) }
                    }
                }
            }
        }
        return Phase(stats, null)
    }

    /** [rejectedThisSession]'s key: identity plus row clock, so an edit is a different key. */
    private fun rejectionKeyOf(record: SyncRecord): String =
        "${keyOf(record.type, record.uuid)}:${record.rowClock}"

    /**
     * Remembers [record] as rejected for the rest of this session, evicting the oldest entry first
     * if [MAX_REMEMBERED_REJECTIONS] is already reached.
     *
     * The bound exists so a pathological account -- or an attacker who can make this device dirty
     * records faster than it can learn they are hopeless -- cannot grow this set without limit.
     * Losing the oldest entry only means that one record is re-sent once more than strictly
     * necessary; it is never lost track of forever, because the row stays dirty either way.
     */
    private fun rememberRejected(record: SyncRecord) {
        val key = rejectionKeyOf(record)
        rejectedThisSession.remove(key) // re-insert at the end, so it reads as the most recent
        rejectedThisSession.add(key)
        if (rejectedThisSession.size > MAX_REMEMBERED_REJECTIONS) {
            val oldest = rejectedThisSession.iterator()
            oldest.next()
            oldest.remove()
        }
    }

    /**
     * Splits [pending] into batches that respect three limits at once: [batchLimit] items,
     * [PUSH_BYTE_BUDGET] estimated bytes, and "a record above [LARGE_RECORD_BYTES] is alone".
     *
     * A batch is never empty. A record whose estimate exceeds the whole budget still gets its own
     * batch rather than being dropped: the server may well accept it, and if it does not, the
     * single-item batch is what makes the refusal attributable.
     */
    private fun batches(pending: List<StoredRecord>): List<List<StoredRecord>> {
        val result = mutableListOf<List<StoredRecord>>()
        var current = mutableListOf<StoredRecord>()
        var currentBytes = 0

        fun flush() {
            if (current.isNotEmpty()) {
                result += current
                current = mutableListOf()
                currentBytes = 0
            }
        }

        for (row in pending) {
            val size = RecordSize.estimateBytes(row.record)
            if (size > LARGE_RECORD_BYTES) {
                // Alone, whatever else is pending -- attribution requires it, and a batch of one
                // can never itself violate the byte budget's spirit even when it exceeds the number.
                flush()
                result += listOf(row)
                continue
            }
            if (current.isNotEmpty() &&
                (current.size >= batchLimit || currentBytes + size > PUSH_BYTE_BUDGET)
            ) {
                flush()
            }
            current += row
            currentBytes += size
        }
        flush()
        return result
    }

    // ── The one merge call ─────────────────────────────────────────────────────────────────────

    /**
     * Merges one incoming record and writes what the merge decided.
     *
     * **The only call to `Merge.merge` in this module**, and it is reached both by a pulled record
     * and by the version a rejected push handed back. The plan asks for exactly one path for the
     * two (§3.2 rule 3) and this is it; a "the server rejected us" branch that merged slightly
     * differently is how the two of them come to disagree about the same pair of records.
     *
     * @param seq the server version this record is. Recorded on the row **whatever the merge
     *   decided**, including `NoChange`, because the row's next push has to be built on the version
     *   the server actually holds.
     */
    private suspend fun applyIncoming(
        remote: SyncRecord,
        seq: Long,
        remoteCreatedAt: Long?,
    ): Phase {
        val stored = store.load(remote.type, remote.uuid)
        val result = Merge.merge(stored?.asLocalRecord(), remote)

        // Fold every clock this device is shown into the generator, and do it before any early
        // return. A generator that has not seen an incoming clock can mint the next local write
        // *below* a record already accepted, and a row whose clock went backwards loses to its own
        // older version on the next sync. That is true even of a record that was rejected: the
        // server holds it, so this device's writes have to sort above it.
        clock.observe(remote.rowClock)

        return when (result) {
            is MergeResult.Rejected -> Phase(
                PassStats.NONE,
                halt(
                    when (result.reason) {
                        RejectReason.ROLLBACK_SUSPECTED -> HaltReason.SERVER_ROLLED_BACK
                        RejectReason.IDENTITY_MISMATCH -> HaltReason.RECORD_IDENTITY_MISMATCH
                    }
                ),
            )

            MergeResult.NoChange -> {
                store.recordSeen(
                    type = remote.type,
                    uuid = remote.uuid,
                    seq = seq,
                    contentBaseline = Baselines.advance(stored?.contentBaseline, remote),
                )
                Phase(PassStats(unchanged = 1), null)
            }

            is MergeResult.Applied -> {
                write(
                    result.record, result.dirty, seq, stored?.contentBaseline, remote,
                    copy = null, remoteCreatedAt = remoteCreatedAt,
                )
                Phase(PassStats(applied = 1), null)
            }

            is MergeResult.ConflictCopy -> {
                // Insert the copy only if it is not already here. Its uuid is derived from the
                // losing body, so the same conflict resolved twice -- by a re-delivered record on
                // this device, or by the mirror-image merge on the other one -- names the same
                // copy, and overwriting would clobber one the user had since edited.
                val existing = store.load(RecordType.NOTE, result.copy.uuid)
                write(
                    result.record, result.dirty, seq, stored?.contentBaseline, remote,
                    copy = if (existing == null) result.copy else null,
                    remoteCreatedAt = remoteCreatedAt,
                )
                Phase(PassStats(applied = 1, conflictCopies = if (existing == null) 1 else 0), null)
            }
        }
    }

    private suspend fun write(
        merged: SyncRecord,
        dirty: Boolean,
        seq: Long,
        previousBaseline: Hlc?,
        agreed: SyncRecord,
        copy: SyncRecord?,
        remoteCreatedAt: Long?,
    ) {
        store.applyMerged(
            MergedWrite(
                record = merged,
                dirty = dirty,
                seq = seq,
                // The baseline advances against the record that ARRIVED, not the merged result.
                // The two devices agreed on the incoming version; they have never agreed on a
                // three-way merge only this one has performed, and treating that as published is
                // how an unpushed body stops earning its conflict copy.
                contentBaseline = Baselines.advance(previousBaseline, agreed),
                conflictCopy = copy,
                // Used only if this device has no row for the record yet. The conflict copy is a
                // NEW record minted here, so it is not covered by this and keeps taking its own
                // creation time from the body it preserves.
                remoteCreatedAt = remoteCreatedAt,
            )
        )
    }

    // ── Stopping ───────────────────────────────────────────────────────────────────────────────

    /** Records a halt and packages it, so a caller reads one expression rather than two steps. */
    private suspend fun halt(reason: HaltReason): SyncOutcome {
        store.recordHalt(reason)
        return SyncOutcome.Halted(reason, PassStats.NONE)
    }

    /**
     * What a transport failure costs.
     *
     * Two of them are halts and the rest are waits, and the split is not arbitrary: a revoked
     * device and a rolled-back server are both states that no amount of retrying leaves, and both
     * need a person.
     */
    private suspend fun stop(failure: SyncTransportException): SyncOutcome = when (failure.fault) {
        TransportFault.CURSOR_AHEAD_OF_SERVER -> halt(HaltReason.SERVER_ROLLED_BACK)
        TransportFault.DEVICE_REVOKED -> halt(HaltReason.DEVICE_REVOKED)
        else -> SyncOutcome.Deferred(
            fault = failure.fault,
            retryAfterMillis = retryPlan.retryAfterMillis(failure.fault, failure.retryAfterMillis),
            stats = PassStats.NONE,
        )
    }

    /**
     * One half of a pass: what it did, and what stopped it if anything did.
     *
     * [stop] carries a `PassStats` of its own that is always empty, because the phase's counts are
     * accumulated separately and merged in by [withStats]. That split exists so a phase can be
     * abandoned from any depth without every early return having to reconstruct the running total.
     */
    private class Phase(val stats: PassStats, val stop: SyncOutcome?)

    companion object {

        /**
         * The record-format generation this build implements. Bump it in the same commit that adds
         * a record type or changes a payload's shape, and never otherwise: every device that has
         * pulled under a lower number re-pulls its whole account once, which is cheap for a small
         * library and is not free for a large one.
         */
        const val DATA_VERSION = 2

        /** The server refuses a batch of more than 64 items. */
        const val MAX_BATCH_LIMIT = 64

        /** A full batch, because a push is one round trip and the rows are already in hand. */
        const val DEFAULT_BATCH_LIMIT = MAX_BATCH_LIMIT

        /** `:core-sync-net`'s own default page size. */
        const val DEFAULT_PAGE_LIMIT = 32

        /** §3: cap at three pull/push rounds per pass. */
        const val DEFAULT_MAX_ROUNDS = 3

        /**
         * How many records may fail to open in one pass before the engine stops.
         *
         * One is a corrupt row and the engine works around it. Past a handful the likely cause is
         * that this device cannot read the account at all — the wrong ARK, or the fork a second
         * `generateArk()` creates — and continuing means the user finds out weeks later.
         */
        const val UNREADABLE_RECORD_LIMIT = 5

        /**
         * How many estimated payload bytes one push batch may carry.
         *
         * 3 MiB against the server's 8 MiB `maxRequestBytes`: base64 takes 3 MiB to 4 MiB and the
         * JSON around it is small, so the margin is a factor of two. That margin is what lets
         * [RecordSize] be an estimate rather than an exact count.
         */
        const val PUSH_BYTE_BUDGET = 3 * 1024 * 1024

        /**
         * A record estimating above this is pushed in a batch of its own.
         *
         * Not a performance tuning knob -- an attribution mechanism. A `400` names no item, so the
         * only way to learn *which* record a server refused is to have sent one. Every record that
         * could plausibly be refused for its size travels alone, which makes
         * [TransportFault.REJECTED] attributable and makes skipping it safe.
         *
         * 128 KiB is half the 256 KiB envelope cap of a server that has not been upgraded for
         * attachments yet (spec §4), not the cap itself. A record estimating just under the cap
         * would otherwise still ride in a shared batch, and its real envelope -- estimate plus
         * JSON scaffolding plus rounding up to the padding bucket -- can cross the cap while it is
         * still sharing one, which is a `400` that is not attributable and defers the whole batch
         * instead of skipping the one record. Halving leaves enough headroom that padding,
         * scaffolding and any residual estimate error cannot carry a record over the line while it
         * still has company.
         */
        const val LARGE_RECORD_BYTES = 128 * 1024

        /**
         * How many rejected-record keys [rejectedThisSession] holds at once.
         *
         * A few hundred is enormous for what this guards against -- an interim state before a
         * server understands attachments, on an account with at most a handful of devices -- and
         * small enough that the set is never a memory concern.
         */
        const val MAX_REMEMBERED_REJECTIONS = 256

        private fun keyOf(type: RecordType, uuid: String): String = "${type.wireKey}:$uuid"
    }
}

/**
 * Told about every clock this device is shown.
 *
 * `HlcGenerator::observe` is the implementation; this is a seam rather than the class itself so
 * that the engine's dependency is exactly "somewhere to report a remote clock" and not "the thing
 * that mints local clocks". The engine never mints one — the only record it creates is a conflict
 * copy, whose clock is the losing body's — and taking the generator would leave that guarantee to a
 * reader's inspection instead of to the type.
 */
fun interface ClockObserver {

    /** Called for every remote record the engine merges, accepted or not. */
    fun observe(seen: Hlc)
}

/** Replaces the empty stats a stop was built with by the phase's running total. */
private fun SyncOutcome.withStats(stats: PassStats): SyncOutcome = when (this) {
    is SyncOutcome.Halted -> SyncOutcome.Halted(reason, stats)
    is SyncOutcome.Deferred -> SyncOutcome.Deferred(fault, retryAfterMillis, stats)
    else -> this
}
