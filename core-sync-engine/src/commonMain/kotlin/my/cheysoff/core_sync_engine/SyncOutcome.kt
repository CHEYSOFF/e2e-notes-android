package my.cheysoff.core_sync_engine

/**
 * What one call into [SyncEngine] did.
 *
 * Returned, never thrown. A sync pass runs behind the user's back on a timer, so every failure it
 * can have is a thing the scheduler has to make a decision about — wait, try again, or stop asking
 * — and an exception crossing this boundary would make that decision by accident.
 */
sealed interface SyncOutcome {

    /**
     * The pass ran to completion. [stats] says whether anything actually moved; a completed pass
     * that moved nothing is the normal steady state, not a failure.
     */
    class Completed(val stats: PassStats) : SyncOutcome

    /**
     * The pass stopped early on something that is expected to clear by itself.
     *
     * @param fault what stopped it.
     * @param retryAfterMillis how long the caller should wait before the next pass, **including the
     *   engine's jitter**. Zero means "no opinion, use the ordinary schedule". See [RetryPlan] for
     *   why a `429` must never be honoured to the millisecond.
     * @param stats what the pass had already done before it stopped. A deferred pass is not a
     *   rolled-back one: everything it applied or pushed stayed applied and pushed.
     */
    class Deferred(
        val fault: TransportFault,
        val retryAfterMillis: Long,
        val stats: PassStats,
    ) : SyncOutcome

    /**
     * The engine has stopped and will not run again until the halt is cleared.
     *
     * Every reason in [HaltReason] is one where continuing loses data. The response is a deliberate
     * user-facing re-baseline, never an automatic recovery — §8's F7 is explicit that silently
     * resetting the cursor is indistinguishable from "the account is empty" and that the next pass
     * would be a mass delete.
     */
    class Halted(val reason: HaltReason, val stats: PassStats) : SyncOutcome

    /**
     * Another pass is already running and this call did nothing.
     *
     * Two overlapping passes would both read the dirty rows and both push them, and the second
     * would take a `409` against the first (§7). The engine refuses rather than queues, because the
     * caller that lost the race is a 60-second timer whose next tick is a better time to sync than
     * "immediately after the pass that is already doing it".
     */
    data object AlreadyRunning : SyncOutcome
}

/**
 * Why the engine stopped for good.
 *
 * Each of these is a `halt` in the plan's §8 table. None of them is retried, and none of them is
 * repaired automatically: the two that a server can cause mean the server is not the one this
 * device agreed with, and the two that it cannot mean this build is not the one that wrote the
 * data.
 */
enum class HaltReason {

    /**
     * The server handed back a version older than one this device already holds on a clean row, or
     * refused the cursor as being ahead of its own high-water mark. **F7.**
     *
     * Both are what a server restored from a backup looks like, from the two places it is visible.
     * The record-level half is `RejectReason.ROLLBACK_SUSPECTED`; the whole-server half is
     * `409 cursor_ahead_of_server`. They are one reason here because they are one event and the
     * response is the same: stop, and make a human decide.
     */
    SERVER_ROLLED_BACK,

    /**
     * A record opened, but its payload's own `(recType, uuid)` does not hash to the blinded id it
     * arrived under. **F3.**
     *
     * A server cannot produce this without the ARK, so it is a client bug. Halting makes it a bug
     * report instead of a slow corruption.
     */
    RECORD_MISLABELLED,

    /**
     * A payload carried a version this build does not know. **F2.**
     *
     * Refusing to sync is the whole point: decoding what this build recognises and pushing the
     * result back is how a newer device's fields are deleted by an older one, silently.
     */
    UNSUPPORTED_PAYLOAD_VERSION,

    /**
     * More records than [SyncEngine.UNREADABLE_RECORD_LIMIT] would not open in one pass. **F1.**
     *
     * One unreadable record is a corrupt row and the engine works around it. A stream of them means
     * this device cannot read the account — the wrong ARK, or a fork after a second `generateArk()`
     * — and grinding on is how the user finds out weeks later.
     */
    RECORDS_UNREADABLE,

    /** `403 device_revoked`. Nothing this device does will work again until it is re-paired. */
    DEVICE_REVOKED,

    /**
     * The merge refused a record as not being the record it was looked up as — a different uuid, or
     * the same uuid under a different type.
     *
     * `RejectReason.IDENTITY_MISMATCH`, which the merge documents as a caller bug rather than
     * anything a server can cause. It is here because the caller is this engine.
     */
    RECORD_IDENTITY_MISMATCH,
}

/**
 * What one pass did, in counts.
 *
 * Every field is something a bug would change. [applied] and [pushed] are how a caller knows
 * whether to run again; [conflicts] and [conflictCopies] are how the convergence harness proves a
 * sweep actually reached the branches it claims to test; [unreadable] is the one that has to be
 * surfaced to a human before it becomes a halt.
 */
data class PassStats(
    /** Records the server offered, faulted ones included. */
    val received: Int = 0,
    /** Merges that wrote a row. */
    val applied: Int = 0,
    /** Merges that decided nothing had to be written — the idempotence branch. */
    val unchanged: Int = 0,
    /** Rows the server accepted. */
    val pushed: Int = 0,
    /** Pushes the server refused with `409`, each of which was merged rather than dropped. */
    val conflicts: Int = 0,
    /** Bodies preserved as new notes rather than discarded. */
    val conflictCopies: Int = 0,
    /** Records that would not open and were skipped. The cursor did not advance past them. */
    val unreadable: Int = 0,
    /**
     * Records skipped because this build does not implement their type. The cursor DID advance
     * past these — unlike [unreadable] — so they will not be offered again.
     *
     * Counted rather than dropped silently because "your other device is writing things this one
     * cannot show you" is a fact a person may need, and because a number that is quietly always
     * non-zero is how a rollout mistake stays invisible.
     */
    val ignored: Int = 0,
) {

    /**
     * True when this pass changed something, anywhere.
     *
     * The quiescence test: a caller loops passes while this is true. [unchanged] is deliberately
     * excluded — a pass that only re-applied records it already had has, by definition, moved
     * nothing, and counting it would make the loop run forever.
     */
    val moved: Boolean get() = applied > 0 || pushed > 0 || conflicts > 0

    /** The two passes' counts added together, for a caller that runs several rounds. */
    operator fun plus(other: PassStats): PassStats = PassStats(
        received = received + other.received,
        applied = applied + other.applied,
        unchanged = unchanged + other.unchanged,
        pushed = pushed + other.pushed,
        conflicts = conflicts + other.conflicts,
        conflictCopies = conflictCopies + other.conflictCopies,
        unreadable = unreadable + other.unreadable,
        ignored = ignored + other.ignored,
    )

    companion object {
        /** A pass that did nothing. */
        val NONE = PassStats()
    }
}
