package my.cheysoff.core_sync_engine.harness

import my.cheysoff.core_domain.sync.FieldClocks
import my.cheysoff.core_domain.sync.SyncRecord
import my.cheysoff.core_sync_engine.PassStats

/**
 * N replicas over one [FakeServer], driven by a [Schedule].
 *
 * This is the machine `e2e-sync-open-questions.md` §3 asks for. It exists because merge
 * correctness cannot be judged by reading: the failures that matter — a non-commutative merge, an
 * apply that is not idempotent, an order dependence that only three replicas expose, a tie two
 * devices break differently — all look perfectly reasonable in the source and only show up as two
 * devices quietly holding different states.
 *
 * ## How a run works
 *
 * 1. Execute the schedule. Writes land on the named replica; `Pull`, `Push` and `CrashDuringPush`
 *    move records across the fake transport.
 * 2. Run every replica to **quiescence** — pull, push, pull, push until nothing is dirty and no
 *    cursor advances. This is what "after the network settles" means, and it is the precondition
 *    of every convergence claim below.
 * 3. Assert the property.
 *
 * ## Reproducibility
 *
 * Everything is a function of the seed: the schedule, the replicas' clocks and their node names.
 * [describe] prints the seed, the schedule and every replica's final state, and every assertion
 * failure carries it. A counterexample that cannot be replayed exactly is not a bug report.
 *
 * @param clockStepMs how far each replica's wall clock moves after every operation. **Zero is the
 *   interesting value**: every replica then writes inside one millisecond, `HlcGenerator` falls
 *   back to its counter, and the two replicas' counters collide constantly — so the node
 *   tie-breaker is exercised on nearly every merge instead of never.
 * @param useBaselines see [Replica]. Both modes must converge.
 */
class Simulation(
    val seed: Long,
    val replicaCount: Int,
    private val clockStepMs: Long = 7L,
    private val useBaselines: Boolean = true,
) {

    val server = FakeServer()

    val replicas: List<Replica> = List(replicaCount) { index ->
        Replica(
            name = "r$index",
            // Fixed-width hex, so the lexicographic node order the tie-breaker uses is also the
            // obvious one when reading a failure. Real nodes are 16 hex characters (`HlcNode`).
            node = "%016x".format(index + 1),
            server = server,
            useBaselines = useBaselines,
            // Staggered start times, so the replicas' wall clocks disagree the way real ones do.
            wallMs = 1_000L + index * 3L,
        )
    }

    /**
     * Every replica's pass counts, added together.
     *
     * These come from the engine's own [PassStats] rather than from a hook the harness wraps around
     * the merge. That is deliberate: a sweep that asserts it reached the conflict-copy branch is
     * asserting it against the number the engine will report in production, so a counter that stops
     * being incremented is a failing test rather than a quietly weaker sweep.
     */
    fun stats(): PassStats = replicas.fold(PassStats.NONE) { total, replica -> total + replica.stats }

    /** Runs every operation in [schedule], in order. */
    fun run(schedule: Schedule) {
        for (op in schedule.ops) {
            val replica = replicas[op.replica]
            when (op) {
                is Op.SaveNote -> replica.saveNote(noteId(op.note), "title-${op.note}", op.body)
                is Op.Pin -> replica.setPinned(noteId(op.note), op.value)
                is Op.Favorite -> replica.setFavorite(noteId(op.note), op.value)
                is Op.SetFolder -> replica.setFolder(noteId(op.note), op.folder?.let(::folderId))
                is Op.ClearFolder -> replica.clearFolder(noteId(op.note))
                is Op.DeleteNote -> replica.deleteNote(noteId(op.note))
                is Op.RestoreNote -> replica.restoreNote(noteId(op.note))
                is Op.SaveFolder -> replica.saveFolder(folderId(op.folder), op.label, op.folder.toLong())
                is Op.DeleteFolder -> replica.deleteFolder(folderId(op.folder))
                is Op.Pull -> replica.pull()
                is Op.Push -> replica.push()
                // The whole batch is committed by the server and none of it is acknowledged, which
                // is the worst case of the crash §3.3 describes rather than a convenient single row.
                is Op.CrashDuringPush -> replica.pushLosingTheAcknowledgement()
                is Op.SkewClock -> replica.advanceClock(op.deltaMs)
            }
            replica.advanceClock(clockStepMs)
        }
    }

    /**
     * Pulls and pushes every replica until nothing moves.
     *
     * Returns the number of rounds it took. Failing to settle is itself a bug — a merge that keeps
     * producing something new from the same inputs is one that will keep two real devices talking
     * to each other forever — so it fails loudly rather than returning a flag.
     */
    fun quiesce(maxRounds: Int = 200): Int = settle(replicas, maxRounds)

    /**
     * [quiesce] with the replicas visited in the opposite order.
     *
     * The commutativity property's instrument: the writes are identical, so the only thing that
     * differs between a forward and a reverse run is **who speaks first**, and therefore which
     * partial merges reach which replica in which order. A merge that is not commutative settles
     * the two runs in different places.
     */
    fun quiesceInReverse(maxRounds: Int = 200): Int = settle(replicas.reversed(), maxRounds)

    private fun settle(order: List<Replica>, maxRounds: Int): Int {
        repeat(maxRounds) { round ->
            var moved = false
            for (replica in order) {
                if (replica.syncOnce()) moved = true
            }
            if (!moved && order.none { it.hasDirtyRows() }) return round + 1
        }
        error("no quiescence after $maxRounds rounds\n${describe()}")
    }

    /**
     * Every replica holds byte-identical state.
     *
     * **The headline property.** Not "the same notes" or "the same bodies" — the same records,
     * field for field and clock for clock, in the normalised form both a merge and a local write
     * produce. Anything less would let two devices agree on what the user sees today and disagree
     * about who wins tomorrow.
     */
    fun assertConverged() {
        val reference = replicas.first()
        val expected = reference.snapshot()
        for (replica in replicas.drop(1)) {
            val actual = replica.snapshot()
            if (actual != expected) {
                error(
                    "replicas ${reference.name} and ${replica.name} did not converge\n" +
                        diff(expected, actual) + "\n" + describe()
                )
            }
        }
    }

    /**
     * How many conflict copies were **written**.
     *
     * Not "how many merges decided one was owed", which is a larger number: the copy's uuid is
     * derived from the losing body, so the same conflict resolved a second time — by a re-delivered
     * record here or by the mirror-image merge on the other device — names a copy that already
     * exists and the engine writes nothing. Copies written is the number the user would count.
     */
    fun conflictCopyCount(): Int = stats().conflictCopies

    /** How many pushes the server refused with a `409`, each of which was merged rather than dropped. */
    fun casConflictCount(): Int = stats().conflicts

    /** How many merges decided nothing had to be written — the idempotence branch. */
    fun noChangeCount(): Int = stats().unchanged

    /** The whole run, for a failure message. */
    fun describe(): String = buildString {
        append("seed=").append(seed)
            .append(" replicas=").append(replicaCount)
            .append(" clockStepMs=").append(clockStepMs)
            .append(" useBaselines=").appendLine(useBaselines)
        replicas.forEach { replica ->
            append("-- ").append(replica.name)
                .append(" cursor=").append(replica.cursor)
                .append(" halted=").appendLine(replica.halted)
            replica.snapshot().toSortedMap().forEach { (key, record) ->
                append("   ").append(key).append(" -> ").appendLine(render(record))
            }
        }
    }

    private fun diff(expected: Map<String, SyncRecord>, actual: Map<String, SyncRecord>): String =
        buildString {
            (expected.keys + actual.keys).sorted().forEach { key ->
                val a = expected[key]
                val b = actual[key]
                if (a != b) {
                    append("   ").appendLine(key)
                    append("     first : ").appendLine(a?.let(::render) ?: "<absent>")
                    append("     second: ").appendLine(b?.let(::render) ?: "<absent>")
                }
            }
        }

    private companion object {
        fun noteId(index: Int): String = "note-$index"
        fun folderId(index: Int): String = "folder-$index"

        /** One record on one line: the row clock, the field clocks, and every value. */
        fun render(record: SyncRecord): String = buildString {
            append("row=").append(record.rowClock)
            append(" clocks=[").append(FieldClocks.serialize(record.fieldClocks)).append(']')
            record.type.fields.forEach { field ->
                append(' ').append(field).append('=')
                append(record.valueOf(field).parts.joinToString("|") { it ?: "<null>" })
            }
        }
    }
}
