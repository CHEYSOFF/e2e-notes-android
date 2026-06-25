package my.cheysoff.notes

import android.app.Application
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.HiltAndroidApp
import my.cheysoff.core_crypto.SecureUnlockManager
import javax.inject.Inject

@HiltAndroidApp
class MainApplication : Application() {

    @Inject
    lateinit var secureUnlockManager: SecureUnlockManager

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
                secureUnlockManager.lock()
            }
        })
    }
}
