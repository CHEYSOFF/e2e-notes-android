package my.cheysoff.desktop.app

/**
 * What the workspace shows about syncing.
 *
 * Every case says only what actually happened. There is no "up to date": this app has one, and only
 * one, way of knowing the server holds what this device holds, and that is a pass that completed --
 * so [Done] is phrased as a completed pass and never as a guarantee about the future.
 */
sealed interface DesktopSyncState {

    /** This device is not enrolled on a server, or its stored address no longer validates. */
    data object Unavailable : DesktopSyncState

    /** Enrolled, nothing in flight. */
    data object Idle : DesktopSyncState

    data object Syncing : DesktopSyncState

    /** A pass finished. [applied] is how many records it took from the server. */
    data class Done(val applied: Int) : DesktopSyncState

    /** The server asked for a wait. The engine never sleeps; the next pass is the user's move. */
    data object Deferred : DesktopSyncState

    /**
     * The engine stopped and will not start again on its own.
     *
     * Halts are persisted precisely so they survive a restart, so this is not something to clear by
     * trying again -- it needs a person to understand it. The reason is shown rather than a generic
     * failure, because "the server rolled back" and "this device was revoked" call for very
     * different responses.
     */
    data class Halted(val reason: String) : DesktopSyncState

    /** The pass threw -- almost always the network. Retrying is reasonable. */
    data class Failed(val message: String) : DesktopSyncState
}
