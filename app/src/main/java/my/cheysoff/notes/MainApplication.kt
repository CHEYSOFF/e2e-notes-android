package my.cheysoff.notes

import android.app.Application
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import my.cheysoff.core_crypto.SecureUnlockManager
import my.cheysoff.notes.sync.ApplicationScope
import my.cheysoff.notes.sync.SyncOnUnlock
import javax.inject.Inject

@HiltAndroidApp
class MainApplication : Application() {

    // dagger.Lazy so Application.onCreate doesn't construct the manager (and with it the Keystore
    // MasterKey) on the main thread. It is first resolved on background/lock, well after startup.
    @Inject
    lateinit var secureUnlockManager: dagger.Lazy<SecureUnlockManager>

    /**
     * The unlock-triggered sync pass.
     *
     * `dagger.Lazy` for the same reason [secureUnlockManager] is, and more so: resolving it builds
     * the whole sync graph — the Keystore-backed signer, the device identity key, the transport
     * provider — and every one of those touches disk. It is resolved on [appScope] below rather
     * than in `onCreate`'s own frame, so none of that happens on the main thread during startup.
     */
    @Inject
    lateinit var syncOnUnlock: dagger.Lazy<SyncOnUnlock>

    /**
     * The app-scoped coroutine scope. Object construction only — no disk, no Keystore — so unlike
     * the two above it can be injected directly.
     */
    @Inject
    @ApplicationScope
    lateinit var appScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        // The whole app is an encrypted database — if SQLCipher's native lib can't load there's
        // no graceful degradation. Fail fast with a clear cause instead of letting Room crash
        // later at DB-open with a confusing error.
        try {
            System.loadLibrary("sqlcipher")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("MainApplication", "Failed to load SQLCipher native library", e)
            throw IllegalStateException(
                "SQLCipher native library failed to load; the encrypted database cannot be opened.",
                e
            )
        }

        // Re-lock when the app goes to the background: drop the in-memory passphrase so returning
        // requires re-authentication. The nav layer observes SecureUnlockManager.unlocked and routes
        // back to the auth screen. (No startup pre-warm: the passphrase only exists post-unlock.)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                Log.d("MainApplication", "App backgrounded; locking (dropping passphrase)")
                secureUnlockManager.get().lock()
            }
        })

        // Sync is foreground-only and starts at the unlock, which is the moment every precondition
        // becomes true at once: the passphrase is in memory, the account key is unwrapped, and the
        // database can be opened. Registered after the lock observer above, deliberately — the two
        // are the same policy read from both ends, and nothing here weakens the lock.
        //
        // Resolved inside the launch rather than on this frame, so that building the sync graph
        // (Keystore, device identity, preferences) happens on Dispatchers.IO. There is nothing to
        // sync until an unlock, which is by definition later than this.
        appScope.launch { syncOnUnlock.get().start() }
    }
}
