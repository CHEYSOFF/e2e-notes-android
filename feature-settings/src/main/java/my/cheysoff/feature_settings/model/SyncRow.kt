package my.cheysoff.feature_settings.model

/**
 * What the Sync section says about itself, derived from the four facts it depends on.
 *
 * Split out of the composable for the same reason [biometricRowSubtitle] is: it is the only real
 * branching on this screen, getting it wrong misleads someone about where their notes are going,
 * and a pure function can be unit-tested (`SyncRowTest`) instead of eyeballed on a device.
 *
 * ## The one thing this must never say
 *
 * There is no sync engine. Nothing pushes, nothing pulls, nothing merges — that work is not in
 * this build. So no state here claims a sync happened, succeeded, or is up to date. The best case
 * is [SyncStatus.READY], and it is worded to say exactly what is true: the transport is wired and
 * configured, and nothing is using it yet. A "Synced ✓" on a screen where no byte has ever been
 * synced is the single most damaging lie this app could tell, because it is the one a user would
 * act on by trusting a second device to hold their notes.
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

    /** A check is in flight. */
    CHECKING,

    /** Paired, with a usable server address. Nothing has been synced, because nothing can be. */
    READY,

    /** The last check did not reach the server. Says nothing about the address being wrong. */
    UNREACHABLE,
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
 */
fun syncStatus(
    paired: Boolean?,
    storedUrl: String?,
    storedUrlUsable: Boolean,
    checking: Boolean,
    lastCheckFailed: Boolean,
): SyncStatus = when {
    // Order matters. "Checking" is reported before anything else only once the prerequisites are
    // known to hold, so a check cannot appear to be running on an unpaired device.
    paired == null -> SyncStatus.UNKNOWN
    !paired -> SyncStatus.NOT_PAIRED
    storedUrl == null -> SyncStatus.NO_SERVER
    !storedUrlUsable -> SyncStatus.SERVER_UNUSABLE
    checking -> SyncStatus.CHECKING
    lastCheckFailed -> SyncStatus.UNREACHABLE
    else -> SyncStatus.READY
}

/**
 * The line shown under the Sync card's title. Always non-blank.
 *
 * Every string is a statement about state this screen can actually observe. None of them uses the
 * word "synced".
 */
fun syncStatusLine(status: SyncStatus): String = when (status) {
    SyncStatus.UNKNOWN -> "Checking…"

    SyncStatus.NOT_PAIRED ->
        "Not paired. Pair a device before setting a server."

    SyncStatus.NO_SERVER ->
        "Paired. No server address set, so nothing can be sent anywhere."

    SyncStatus.SERVER_UNUSABLE ->
        "The stored server address isn't usable. Enter it again."

    SyncStatus.CHECKING -> "Checking the server…"

    // Two clauses, both true and both load-bearing. The first says the wiring is complete so the
    // user knows there is nothing left for them to do; the second says no data has moved, so they
    // do not mistake this for a working backup.
    SyncStatus.READY ->
        "Ready — syncing isn't built yet, so nothing is uploaded."

    // "Couldn't reach", not "sync failed": no sync was attempted, only a health check.
    SyncStatus.UNREACHABLE ->
        "Couldn't reach that server the last time it was checked."
}

/** Whether the "Check server" action can be run: only when there is something to check against. */
fun syncCheckAvailable(status: SyncStatus): Boolean =
    status == SyncStatus.READY || status == SyncStatus.UNREACHABLE
