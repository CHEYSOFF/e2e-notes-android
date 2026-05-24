package my.cheysoff.notes

import android.app.Application
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import my.cheysoff.core_crypto.EncryptionManager
import javax.inject.Inject

@HiltAndroidApp
class MainApplication : Application() {

    @Inject
    lateinit var encryptionManager: EncryptionManager

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var preWarmJob: Job? = null

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

        // Pre-warm the passphrase on a background thread
        preWarmJob = applicationScope.launch {
            encryptionManager.preWarmPassphrase()
        }

        // Observe app lifecycle to clear sensitive data when going to background
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                // App in foreground: allow the passphrase to be cached again.
                encryptionManager.onForeground()
            }

            override fun onStop(owner: LifecycleOwner) { // todo lock the app when in background
                // App moved to background. Cancel any in-flight pre-warm BEFORE clearing so it
                // can't repopulate the cache after we've zeroed it.
                preWarmJob?.cancel()
                Log.d("MainApplication", "App moved to background, clearing passphrase cache")
                encryptionManager.clearCache()
            }
        })
    }
}
