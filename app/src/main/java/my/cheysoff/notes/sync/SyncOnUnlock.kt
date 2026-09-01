package my.cheysoff.notes.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import my.cheysoff.core_crypto.SecureUnlockManager
import my.cheysoff.core_domain.sync.SyncController
import my.cheysoff.core_domain.sync.SyncTrigger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts a sync pass every time the app is unlocked.
 *
 * ## Why the unlock and not the notes screen
 *
 * Unlock is the moment every precondition becomes true at once: the passphrase is in memory, the
 * Account Root Key is unwrapped, `hlcNode` has been recomputed, and the database can be opened. It
 * is also the only moment that is guaranteed to happen — `MainApplication.onStop` locks on every
 * backgrounding, so returning to the app always passes through here, whichever screen the user
 * lands on. A trigger hung off the notes list would miss the user who opens the app straight into
 * the editor from a notification.
 *
 * ## One pass per unlock, not one per emission
 *
 * `SecureUnlockManager.unlocked` is a `StateFlow`, which conflates equal values by itself — so the
 * filter on `true` sees exactly the `false → true` transitions, and no `distinctUntilChanged` is
 * needed (applying one to a `StateFlow` is a no-op and is deprecated for saying so). The first
 * emission a collector gets is the current value, which is `false` at process start: this is
 * registered from `MainApplication.onCreate`, before any unlock can have happened.
 *
 * ## It does not decide anything else
 *
 * Whether a pass can actually run — locked, unpaired, no server, not enrolled — is
 * `DefaultSyncController`'s, checked per pass. This class holds no policy at all, which is what
 * keeps "when do we sync" in one place instead of two that can disagree.
 */
@Singleton
class SyncOnUnlock @Inject constructor(
    private val secureUnlock: SecureUnlockManager,
    private val controller: SyncController,
    @ApplicationScope private val scope: CoroutineScope,
) {

    /**
     * Begins watching. Called once, from `MainApplication.onCreate`.
     *
     * Idempotent by construction rather than by a flag: it is a `@Singleton` and there is exactly
     * one call site, which is the shape that makes a second collector impossible instead of merely
     * unlikely.
     */
    fun start() {
        scope.launch {
            secureUnlock.unlocked
                .filter { it }
                .collect { controller.requestSync(SyncTrigger.UNLOCK) }
        }
    }
}
