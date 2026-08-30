package my.cheysoff.notes.navigation

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import my.cheysoff.core_crypto.SecureUnlockManager
import javax.inject.Inject

/**
 * Exposes the global unlock state to the nav layer so it can gate the UI back to the auth screen
 * when the app re-locks (e.g. after backgrounding drops the in-memory passphrase).
 */
@HiltViewModel
class AppViewModel @Inject constructor(
    secureUnlockManager: SecureUnlockManager,
) : ViewModel() {
    val unlocked: StateFlow<Boolean> = secureUnlockManager.unlocked
}
