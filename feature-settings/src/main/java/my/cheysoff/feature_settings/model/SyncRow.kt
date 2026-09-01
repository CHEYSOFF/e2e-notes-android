package my.cheysoff.feature_settings.model

import my.cheysoff.core_domain.sync.SyncPassState

/**
 * What the Sync section says about itself, derived from the facts it depends on.
 *
 * Split out of the composable for the same reason [biometricRowSubtitle] is: it is the only real
 * branching on this screen, getting it wrong misleads someone about where their notes are going,
 * and a pure function can be unit-tested (`SyncRowTest`) instead of eyeballed on a device.
 *
 * ## The one thing this must never say
 *
 * Sync is real now — the engine runs on unlock and on pull-to-refresh — so the old blanket rule
 * ("no state may describe anything as synced, because nothing is") no longer fits. What replaces it
 * is narrower and harder: **every line reports an event that happened, never a state of the
 * world.** "Sent 3, received 2" is a fact about the last pass and can be checked. "Synced", "up to
 * date" and "backed up" are claims about where the user's notes *are*, and this screen cannot know
 * that: a pass that completed says nothing about the notes written since, about the other device
 * that has been offline for a week, or about whether the server still holds what it acknowledged.
 *
 * That distinction is the whole of the rule, and it is the one a person would act on by trusting a
 * second device to hold their notes. `SyncRowTest` enforces it on every state.
 */
enum class SyncStatus {

    /** The pairing fact has not been read back yet. Nothing is claimed until it has. */
    UNKNOWN,

    /** No pairing has completed on this device, so there is no account to sync with. */
    NOT_PAIRED,

    /** Paired, but no server address has been stored. */
    NO_SERVER,

    /**
     * A server address is stored, but it no longer validates — a value written by a build with a
     * different rule, or edited outside the app. Distinct from [NO_SERVER] because the user's
     * setting is not empty and telling them it is would send them looking in the wrong place.
     */
    SERVER_UNUSABLE,

    /** A server check is in flight. */
    CHECKING,

    /**
     * The engine has stopped and will not run again without a person. Outranks everything below it,
     * because every other line would read as "things are fine" while nothing is syncing at all.
     */
    HALTED,

    /** A sync pass is running right now. */
    SYNCING,

    /**
     * A pass could not start because something about this device's enrolment is missing — the piece
     * the prerequisite states above do not cover, such as an account claimed by another device.
     */
    CANNOT_SYNC,

    /** The last pass stopped early on something expected to clear by itself. */
    SYNC_INTERRUPTED,

    /** A pass ran to completion. The line reports what it moved, and only that. */
    SYNC_RAN,

    /** The last check did not reach the server. Says nothing about the address being wrong. */
    UNREACHABLE,

    /** Configured, and nothing has been attempted yet in this session. */
    READY,
}

/**
 * Work out the section's state.
 *
 * @param paired whether a pairing has completed, or null while that is still being read.
 * @param storedUrl the persisted server address, or null when none is set. Only ever the value
 *   that came back from the repository — never the text in the field, which is a draft.
 * @param storedUrlUsable whether [storedUrl] still passes [checkSyncServerUrl]. Ignored when
 *   [storedUrl] is null.
 * @param checking whether a server check is in flight right now.
 * @param lastCheckFailed whether the most recent completed check failed. Cleared whenever the
 *   stored address changes, since a failure describes an address rather than the app.
 * @param sync what the sync engine's last attempt did.
 */
fun syncStatus(
    paired: Boolean?,
    storedUrl: String?,
    storedUrlUsable: Boolean,
    checking: Boolean,
    lastCheckFailed: Boolean,
    sync: SyncPassState = SyncPassState.Idle,
): SyncStatus = when {
    // Order matters. Prerequisites first, so a check can never appear to be running on an unpaired
    // device. Then the halt, because it is the one state where the engine is not going to try
    // again. Then whatever is happening right now. Then the things that are wrong. And **last** the
    // two states that mean nothing is wrong — because a completed pass is a fact about a moment
    // that has passed, and letting it outrank a check the user just ran and watched fail would
    // leave the section reading "the last sync sent 3" over a server that is not answering.
    paired == null -> SyncStatus.UNKNOWN
    !paired -> SyncStatus.NOT_PAIRED
    storedUrl == null -> SyncStatus.NO_SERVER
    !storedUrlUsable -> SyncStatus.SERVER_UNUSABLE
    sync is SyncPassState.Halted -> SyncStatus.HALTED
    checking -> SyncStatus.CHECKING
    sync is SyncPassState.Running -> SyncStatus.SYNCING
    sync is SyncPassState.Unavailable -> SyncStatus.CANNOT_SYNC
    sync is SyncPassState.Deferred -> SyncStatus.SYNC_INTERRUPTED
    lastCheckFailed -> SyncStatus.UNREACHABLE
    sync is SyncPassState.Completed -> SyncStatus.SYNC_RAN
    else -> SyncStatus.READY
}

/**
 * The line shown under the Sync card's title. Always non-blank.
 *
 * Every string is a statement about something this screen can actually observe — a configuration it
 * read, or a pass that finished and reported counts. None of them describes where the user's notes
 * are.
 *
 * @param sync the same value handed to [syncStatus]; the three states that report an event read
 *   their detail out of it, so the words and the numbers cannot drift apart.
 */
fun syncStatusLine(status: SyncStatus, sync: SyncPassState = SyncPassState.Idle): String = when (status) {
    SyncStatus.UNKNOWN -> "Checking…"

    SyncStatus.NOT_PAIRED ->
        "Not paired. Pair a device before setting a server."

    SyncStatus.NO_SERVER ->
        "Paired. No server address set, so nothing can be sent anywhere."

    SyncStatus.SERVER_UNUSABLE ->
        "The stored server address isn't usable. Enter it again."

    SyncStatus.CHECKING -> "Checking the server…"

    SyncStatus.SYNCING -> "Syncing…"

    // The engine's own sentence, which names the thing a person has to decide. None of the halt
    // reasons clears by itself, so "try again later" would be false for every one of them.
    SyncStatus.HALTED -> (sync as? SyncPassState.Halted)?.message
        ?: "Syncing has stopped and won't start again on its own."

    SyncStatus.CANNOT_SYNC -> (sync as? SyncPassState.Unavailable)?.message
        ?: "Sync can't run yet."

    SyncStatus.SYNC_INTERRUPTED -> {
        val why = (sync as? SyncPassState.Deferred)?.message ?: "It stopped early."
        "$why Anything already sent stayed sent, and it will try again."
    }

    // The only state that describes an outcome, and it describes the *pass*, not the account.
    SyncStatus.SYNC_RAN -> lastPassLine(sync)

    // "Couldn't reach", not "sync failed": no sync was attempted, only a health check.
    SyncStatus.UNREACHABLE ->
        "Couldn't reach that server the last time it was checked."

    // Two clauses, both true. The first says the wiring is complete so the user knows there is
    // nothing left for them to do; the second says when a pass happens, which is a schedule rather
    // than a promise about what is on the server.
    SyncStatus.READY ->
        "Ready. A sync runs when you unlock and when you pull the notes list down."
}

/**
 * What the last completed pass moved.
 *
 * Counts rather than adjectives. A pass that moved nothing is the ordinary steady state and says
 * so; it deliberately does **not** say "everything is up to date", because a pass that found
 * nothing to do proves only that this device and the server agreed at that moment — not that a
 * second device has caught up, and not that the notes written since have gone anywhere.
 */
private fun lastPassLine(sync: SyncPassState): String {
    val summary = (sync as? SyncPassState.Completed)?.summary ?: return "A sync finished."
    val parts = buildList {
        if (summary.pushed > 0) add("sent ${summary.pushed}")
        if (summary.applied > 0) add("applied ${summary.applied}")
        if (summary.conflictCopies > 0) add("kept ${summary.conflictCopies} conflicting copy")
        if (summary.unreadable > 0) add("couldn't read ${summary.unreadable}")
    }
    if (parts.isEmpty()) return "The last sync had nothing to send or receive."
    return "The last sync ${parts.joinToString(", ")}."
}

/** Whether the "Check server" action can be run: only when there is something to check against. */
fun syncCheckAvailable(status: SyncStatus): Boolean = when (status) {
    SyncStatus.UNKNOWN, SyncStatus.NOT_PAIRED, SyncStatus.NO_SERVER,
    SyncStatus.SERVER_UNUSABLE, SyncStatus.CHECKING, SyncStatus.SYNCING,
    -> false
    // A halted engine is still worth pointing at a server: "is this address answering?" is exactly
    // the question someone asks first, and the check is unauthenticated so it cannot make a halt
    // worse.
    else -> true
}
