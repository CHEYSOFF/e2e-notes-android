package my.cheysoff.notes.sync

import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import my.cheysoff.core_crypto.SecureUnlockManager
import my.cheysoff.core_domain.sync.PeriodicSyncPolicy
import my.cheysoff.core_domain.sync.SyncController
import my.cheysoff.core_domain.sync.SyncPassState
import my.cheysoff.core_domain.sync.SyncTrigger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs a sync pass every [PeriodicSyncPolicy.INTERVAL_MS] for as long as the app is unlocked.
 *
 * ## The gap this closes
 *
 * [SyncOnUnlock] covers "the user came back", and a pull-to-refresh covers "the user asked". Both
 * are things this device did. Neither covers the other device: a note written on the laptop reached
 * a phone that was sitting open on the notes list only when its owner happened to pull the list
 * down, and until then the phone showed the perfectly accurate result of its last pass. The app is
 * telling the truth and the user concludes it is broken, which is not a gap a status line can talk
 * its way out of.
 *
 * ## Why `collectLatest` is the whole lock story
 *
 * The loop lives *inside* the collector, so locking — which sets `unlocked` to false — cancels it
 * outright, and unlocking starts a fresh one. There is no flag to get wrong and no way for a timer
 * to outlive the keys it needs, because the thing that cancels it is the same emission the rest of
 * the app locks on. A locked app has no timer at all.
 *
 * ## Why it waits first
 *
 * [delay] before the pass, not after: an unlock has just triggered one through [SyncOnUnlock], and
 * a loop that fired immediately would double every unlock.
 *
 * ## Why `syncNow` and not `requestSync`
 *
 * The suspending call makes the interval a gap between passes rather than between *starts*. On a
 * bad connection a pass can take longer than the interval, and `requestSync` would let ticks pile
 * up behind it — each one arriving to find the previous still running, doing nothing but keeping a
 * coroutine busy. Sleeping after the pass ends is self-limiting by construction.
 */
@Singleton
class PeriodicSync @Inject constructor(
    private val secureUnlock: SecureUnlockManager,
    private val controller: SyncController,
    @ApplicationScope private val scope: CoroutineScope,
) {

    /** Begins watching. Called once, from `MainApplication.onCreate`. */
    fun start() {
        scope.launch { periodicSyncLoop(secureUnlock.unlocked, controller) }
    }
}

/**
 * The loop itself, taking the unlock signal as a flow rather than the manager that owns it.
 *
 * `SecureUnlockManager` is a final class wrapping the Keystore, so it cannot be substituted in a
 * unit test — and the behaviour worth testing here (waits before the first pass, keeps going,
 * skips a tick while one is running, stops dead on lock) is all timing, which needs virtual time
 * and no Android at all. Taking the flow instead of the manager is what makes that possible, and
 * it leaves [PeriodicSync] as pure wiring: no branch of its own, nothing to get wrong twice.
 */
internal suspend fun periodicSyncLoop(
    unlocked: Flow<Boolean>,
    controller: SyncController,
    intervalMs: Long = PeriodicSyncPolicy.INTERVAL_MS,
) {
    unlocked.collectLatest { isUnlocked ->
        if (!isUnlocked) return@collectLatest
        while (true) {
            delay(intervalMs)
            val current = controller.state.value
            val shouldRun = PeriodicSyncPolicy.shouldRun(
                passRunning = current is SyncPassState.Running,
                halted = current is SyncPassState.Halted,
            )
            if (shouldRun) controller.syncNow(SyncTrigger.PERIODIC)
        }
    }
}
