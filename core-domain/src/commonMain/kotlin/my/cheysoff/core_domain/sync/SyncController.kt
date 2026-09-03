package my.cheysoff.core_domain.sync

import kotlinx.coroutines.flow.StateFlow

/**
 * "Sync now", and what the last attempt did — the whole of what a screen is allowed to know about
 * the sync engine.
 *
 * ## Why a `:core-domain` interface rather than the engine itself
 *
 * `:feature-notes` and `:feature-settings` depend on `:core-ui` and `:core-domain` and nothing
 * else, which is why neither of them can see `SyncApi`, the Account Root Key or Room. That is not
 * an accident to be routed around: a pull-to-refresh gesture needs to be able to *ask* for a sync
 * and to *see* what happened, and it needs nothing else. Everything the engine actually requires —
 * an unlocked database, account keys, a server address, a device enrolment — is `:app`'s, in the
 * same way and for the same reasons as `SyncTransportStatus`.
 *
 * ## Two ways in, because there are two callers with different needs
 *
 * [requestSync] is fire-and-forget, for a trigger that has nowhere to wait — an unlock, whose
 * caller is a lifecycle observer that must not block. [syncNow] suspends until the pass ends, for
 * pull-to-refresh, whose entire job is to hold a spinner until there is something to say.
 */
interface SyncController {

    /** What the most recent attempt did, or [SyncPassState.Idle] before there has been one. */
    val state: StateFlow<SyncPassState>

    /**
     * Runs a pass on the app's own scope and returns immediately.
     *
     * Safe to call when a pass is already running, when the app is locked, and when no server is
     * configured; each of those is a state, not an error, and each shows up in [state].
     */
    fun requestSync(trigger: SyncTrigger)

    /** Runs a pass and suspends until it ends, returning the same value [state] then holds. */
    suspend fun syncNow(trigger: SyncTrigger): SyncPassState

    /**
     * Forgets a recorded halt and runs one pass.
     *
     * Only ever in response to a person asking. A halt means the engine found something it cannot
     * fix and stopping was the correct response, so clearing one automatically — on a timer, or as
     * error recovery — would convert a deliberate stop into a loop that halts and resumes forever.
     *
     * The likely result is [SyncPassState.Halted] again, with the same reason, because the cause is
     * usually still there. That is a success for this method: the point is that a halt whose cause
     * *has* been fixed — the app updated after an unsupported payload, the device re-paired after a
     * revocation — is no longer a dead end that needs a reinstall to escape.
     */
    suspend fun clearHaltAndSync(): SyncPassState
}

/**
 * What asked for the pass.
 *
 * Carried so that the two can be told apart where it matters and nowhere else: a manual refresh is
 * the one gesture that deserves to report "nothing to do" to the user, because the user is watching
 * a spinner and waiting to be told something.
 */
enum class SyncTrigger {

    /** The user just unlocked. The first chance this process has had to sync at all. */
    UNLOCK,

    /** The user pulled the notes list down. */
    MANUAL_REFRESH,

    /**
     * A timer, while the app is open and unlocked. Nobody is watching a spinner for this one, so
     * it is the trigger that must never announce that it did nothing.
     */
    PERIODIC,
}

/**
 * The state of the last sync attempt.
 *
 * Every case is a thing that is **true**, and none of them is a promise about where the user's
 * notes are. That is deliberate and it is the rule the settings copy is tested against: a sync
 * status is the only screen in this app whose wrong answer would make someone delete their only
 * copy of something.
 */
sealed interface SyncPassState {

    /** No pass has been attempted in this process. Claims nothing at all. */
    data object Idle : SyncPassState

    /** A pass is running now. */
    data object Running : SyncPassState

    /** A pass ran to completion. [summary] is exactly what it moved, and may be nothing. */
    data class Completed(val summary: SyncPassSummary) : SyncPassState

    /**
     * The pass stopped on something expected to clear by itself — a dropped connection, a rate
     * limit. Whatever it had already done stayed done; [message] says what stopped it.
     */
    data class Deferred(val message: String) : SyncPassState

    /**
     * The engine has stopped and will not run again until a person intervenes.
     *
     * Every reason for this is one where continuing loses data, and none is repaired
     * automatically — see `HaltReason` in `:core-sync-engine`.
     */
    data class Halted(val message: String) : SyncPassState

    /**
     * No pass was attempted, because a precondition is missing: the app is locked, this device is
     * not paired, no server address is set, or this device is not enrolled on the account.
     *
     * Distinct from [Deferred] because nothing was tried and nothing will be until the user does
     * something. [message] says which piece is missing.
     */
    data class Unavailable(val message: String) : SyncPassState
}

/**
 * What one pass moved, in counts.
 *
 * The counts are the whole of what the UI may say about a completed pass. "Synced" is a claim
 * about a state of the world; "sent 3, received 2" is a report of an event that happened, and only
 * the second one can be checked.
 */
data class SyncPassSummary(
    /** Records the server offered, including any that could not be read. */
    val received: Int = 0,
    /** Records that changed something on this device. */
    val applied: Int = 0,
    /** Records the server accepted from this device. */
    val pushed: Int = 0,
    /** Bodies preserved as new notes rather than discarded. */
    val conflictCopies: Int = 0,
    /** Records that would not open. Surfaced because it is the early warning for a halt. */
    val unreadable: Int = 0,
) {
    /** True when the pass moved nothing — the ordinary steady state, not a failure. */
    val quiet: Boolean get() = received == 0 && applied == 0 && pushed == 0
}
