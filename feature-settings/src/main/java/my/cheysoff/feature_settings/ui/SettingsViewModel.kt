package my.cheysoff.feature_settings.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.cheysoff.core_crypto.SecureUnlockManager
import my.cheysoff.core_crypto.domain.AuthRepository
import my.cheysoff.core_domain.model.AppInfo
import my.cheysoff.core_domain.repository.SettingsRepository
import my.cheysoff.feature_auth.util.BiometricEnrollResult
import my.cheysoff.feature_auth.util.BiometricEnroller
import my.cheysoff.feature_settings.model.SettingsIntent
import my.cheysoff.feature_settings.model.SettingsScreenState
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val secureUnlockManager: SecureUnlockManager,
    private val authRepository: AuthRepository,
    private val biometricEnroller: BiometricEnroller,
    appInfo: AppInfo,
) : ViewModel() {

    private val _state = MutableStateFlow(
        SettingsScreenState(appVersion = "${appInfo.versionName} (${appInfo.versionCode})")
    )
    val state = _state.asStateFlow()

    init {
        // Header settings and sort order are already exposed as flows, so those rows are a pure
        // mirror: the intent writes through the repository and the new value arrives back here.
        // Neither is echoed into state at the point of the tap, so those switches cannot show a
        // value that was not actually persisted. (The biometric row cannot work this way — the
        // secure-unlock store is not a flow — so it re-reads instead; see refreshBiometricState.)
        combine(
            settingsRepository.headerSettings,
            settingsRepository.notesSortOrder,
        ) { header, order -> header to order }
            .onEach { (header, order) ->
                _state.update {
                    it.copy(
                        showGreetings = header.showGreetings,
                        showDailyPhrases = header.showDailyPhrases,
                        showStats = header.showStats,
                        sortOrder = order,
                    )
                }
            }
            .launchIn(viewModelScope)

        refreshBiometricState()
    }

    fun onIntent(intent: SettingsIntent) {
        when (intent) {
            is SettingsIntent.SetShowGreetings ->
                viewModelScope.launch { settingsRepository.setShowGreetings(intent.enabled) }

            is SettingsIntent.SetShowDailyPhrases ->
                viewModelScope.launch { settingsRepository.setShowDailyPhrases(intent.enabled) }

            is SettingsIntent.SetShowStats ->
                viewModelScope.launch { settingsRepository.setShowStats(intent.enabled) }

            is SettingsIntent.SortOrderSelected ->
                viewModelScope.launch { settingsRepository.setNotesSortOrder(intent.order) }

            is SettingsIntent.SetBiometricEnabled ->
                if (intent.enabled) enableBiometric(intent) else disableBiometric()
        }
    }

    /**
     * Read the biometric facts. Both calls are disk/platform-backed — `isBiometricEnabled()` is
     * the first touch of the EncryptedSharedPreferences file in this process if the user got in by
     * PIN on a previous launch, and `getBiometricAuthStatus()` queries the platform
     * BiometricManager — so neither belongs on the main thread. Until the answer lands,
     * `biometricStatus` stays null and the row renders as "Checking…".
     */
    private fun refreshBiometricState() {
        viewModelScope.launch {
            val (enabled, status) = withContext(Dispatchers.IO) {
                secureUnlockManager.isBiometricEnabled() to authRepository.getBiometricAuthStatus()
            }
            _state.update {
                it.copy(biometricEnabled = enabled, biometricStatus = status)
            }
        }
    }

    /**
     * Turn biometric unlock ON via the shared enroller — the same sequence the post-PIN-setup
     * offer runs.
     *
     * This works from here precisely because the settings screen is only reachable while the app
     * is unlocked: wrapping needs the database passphrase, and the passphrase exists in memory
     * only between an unlock and the next re-lock. If the session were to re-lock while the
     * prompt is up, the wrap would fail and the enroller reports [BiometricEnrollResult.Failed]
     * rather than crashing — but the nav layer would already be routing back to the auth screen
     * by then, so that message is not something the user is expected to read.
     */
    private fun enableBiometric(intent: SettingsIntent.SetBiometricEnabled) {
        if (_state.value.biometricBusy) return
        _state.update { it.copy(biometricBusy = true, biometricNotice = null) }

        biometricEnroller.enroll(intent.activity) { result ->
            // Invoked on the main thread by the prompt's own callback.
            _state.update {
                it.copy(
                    biometricBusy = false,
                    // Only Enabled changes the stored state; every other outcome leaves the
                    // previous value, which for this path is always false.
                    biometricEnabled = if (result is BiometricEnrollResult.Enabled) true
                    else it.biometricEnabled,
                    biometricNotice = when (result) {
                        BiometricEnrollResult.Enabled -> null
                        BiometricEnrollResult.Cancelled -> null // the user chose to back out
                        BiometricEnrollResult.Unavailable ->
                            "Couldn't set up the biometric key on this device."
                        BiometricEnrollResult.Failed ->
                            "Biometric unlock couldn't be turned on. Try again."
                    },
                )
            }
            // The device's own status can change as a side effect of the prompt (e.g. too many
            // failed attempts puts biometrics into a temporary lockout), so re-read rather than
            // leave a stale "Ready" on screen.
            refreshBiometricState()
        }
    }

    /**
     * Turn biometric unlock OFF: drop the stored wrap and delete the Keystore key.
     *
     * Irreversible only in the sense that turning it back on runs a fresh enrollment against a
     * newly generated key — no note data is touched, and the PIN wrap is untouched, so the notes
     * remain openable.
     */
    private fun disableBiometric() {
        if (_state.value.biometricBusy) return
        _state.update { it.copy(biometricBusy = true, biometricNotice = null) }
        viewModelScope.launch {
            // commit-writes the prefs file and deletes a Keystore entry: disk work, off-main.
            val ok = withContext(Dispatchers.IO) {
                runCatching { secureUnlockManager.disableBiometric() }.isSuccess
            }
            _state.update {
                it.copy(
                    biometricBusy = false,
                    biometricNotice = if (ok) null else "Couldn't turn biometric unlock off.",
                )
            }
            // Re-read rather than assume: on the failure path the wrap may well still be there,
            // and the switch must show what is actually stored.
            refreshBiometricState()
        }
    }
}
