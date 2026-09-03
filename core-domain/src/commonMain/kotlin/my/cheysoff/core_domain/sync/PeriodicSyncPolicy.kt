package my.cheysoff.core_domain.sync

/**
 * When a pass should run on its own, with nobody asking for one.
 *
 * ## Why this exists at all
 *
 * Until now a pass ran on unlock, on a pull-to-refresh, and — on the desktop — a moment after an
 * edit. Every one of those is something *this* device did. So a note written on the phone reached
 * a laptop that was sitting open and unlocked only when its owner happened to touch it, and the
 * laptop meanwhile displayed the successful result of its last pass. That reads exactly like the
 * sync bug it is not, which is the worst kind: the app is telling the truth and the user is
 * reasonably concluding it is broken.
 *
 * ## Why the rule is shared and the state is not
 *
 * The two apps model a pass differently — Android has [SyncPassState], the desktop has its own —
 * and collapsing those was not worth it. What must not differ is *the policy*: how long the gap is,
 * and when a tick is skipped. This repo has already shipped one bug that was two implementations of
 * a single rule disagreeing (see `KeyDerivation`'s KDoc), so each app maps its own state onto the
 * two questions below and neither owns a copy of the answer.
 *
 * ## Foreground and unlocked only — this changes nothing about the lock
 *
 * The loops that call this are started on unlock and cancelled on lock, so a locked app has no
 * timer running. Nothing here relaxes lock-on-background or keeps key material alive a moment
 * longer than it already lives; a periodic pass is just the pass that already existed, asked for by
 * a clock instead of a finger. Background sync remains unbuilt and still needs the ciphertext
 * outbox that §7 of the phase-3 plan describes.
 */
object PeriodicSyncPolicy {

    /**
     * How long to wait after one pass ends before running the next.
     *
     * A minute is chosen against the failure it exists to prevent: two devices open at once, and a
     * note written on one appearing on the other without anyone touching it. Waiting longer makes
     * that feel broken again; waiting less spends a request, a wakeup and a server round trip per
     * device to no purpose, since the overwhelmingly common answer is "nothing changed". Measured
     * from the **end** of the previous pass, never from a wall clock, so a slow pass on a bad
     * connection cannot queue up a backlog of ticks behind itself.
     */
    const val INTERVAL_MS: Long = 60_000L

    /**
     * @param passRunning a pass is in flight. The tick is dropped rather than queued — the pass
     *   already running is doing the work this tick wanted, and a second one would push the same
     *   dirty rows and take a 409 on every one of them.
     * @param halted the engine has stopped and will not run until a person intervenes. Ticking at
     *   it once a minute cannot clear that and only rewrites the same state forever, which is
     *   noise in the logs and, worse, a status line that keeps changing while nothing changes.
     */
    fun shouldRun(passRunning: Boolean, halted: Boolean): Boolean = !passRunning && !halted
}
