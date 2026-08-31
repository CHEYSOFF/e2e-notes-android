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
import my.cheysoff.core_domain.repository.SyncSettingsRepository
import my.cheysoff.core_domain.sync.SyncServerCheck
import my.cheysoff.core_domain.sync.SyncTransportStatus
import my.cheysoff.feature_auth.util.BiometricEnrollResult
import my.cheysoff.feature_auth.util.BiometricEnroller
import my.cheysoff.feature_settings.model.SettingsIntent
import my.cheysoff.feature_settings.model.SettingsScreenState
import my.cheysoff.feature_settings.model.SyncServerUrlCheck
import my.cheysoff.feature_settings.model.checkSyncServerUrl
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val syncSettingsRepository: SyncSettingsRepository,
    private val syncTransportStatus: SyncTransportStatus,
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

        // The stored server address is mirrored the same way, and for the stronger version of the
        // same reason: this row's status line makes a claim about where notes could be sent, so it
        // must describe what is on disk and never what is in the field. The draft is left alone
        // while the user is mid-edit — see serverUrlDirty.
        syncSettingsRepository.serverUrl
            .onEach { stored ->
                _state.update {
                    it.copy(
                        serverUrl = stored,
                        // Re-validated on the way out of storage rather than assumed: the same
                        // check :app applies when it builds the endpoint, so the screen and the
                        // transport agree about whether a stored address is usable.
                        serverUrlUsable = stored == null ||
                            checkSyncServerUrl(stored) is SyncServerUrlCheck.Ok,
                        serverUrlDraft = if (it.serverUrlDirty) it.serverUrlDraft else stored ?: "",
                        // A check result describes one address. When the address changes the old
                        // result stops meaning anything, so it goes rather than lingering next to
                        // a server it was never about.
                        lastCheckFailed = false,
                        serverCheckNotice = null,
                    )
                }
            }
            .launchIn(viewModelScope)

        refreshBiometricState()
        // Also run on every SettingsIntent.ScreenEntered. Reading it here too means the answer is
        // already in flight before the first composition dispatches that intent, and the duplicate
        // costs one KeyStore.containsAlias call.
        refreshPairedState()
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

            is SettingsIntent.ServerUrlChanged -> _state.update {
                // The error belongs to the text that produced it, so it is dropped on the next
                // keystroke rather than left under a field the user has already started fixing.
                it.copy(serverUrlDraft = intent.text, serverUrlDirty = true, serverUrlError = null)
            }

            SettingsIntent.ScreenEntered -> refreshPairedState()
            SettingsIntent.SaveServerUrl -> saveServerUrl()
            SettingsIntent.ClearServerUrl -> clearServerUrl()
            SettingsIntent.CheckServer -> checkServer()
        }
    }

    /**
     * Validate the draft and persist it, or show why it was refused.
     *
     * Nothing is written on the refusal path — an invalid address never reaches disk, which is why
     * `SyncStatus.SERVER_UNUSABLE` is a guard against a file written by something else rather than
     * a state this screen can produce.
     *
     * What is stored is the *normalised* string the check hands back, not the raw text, so the
     * stored value and the transport's `baseUrl` are identical and a stray trailing slash cannot
     * make the same server look like two.
     */
    private fun saveServerUrl() {
        when (val check = checkSyncServerUrl(_state.value.serverUrlDraft)) {
            is SyncServerUrlCheck.Rejected ->
                _state.update { it.copy(serverUrlError = check.message) }

            is SyncServerUrlCheck.Ok -> viewModelScope.launch {
                syncSettingsRepository.setServerUrl(check.normalized)
                // The normalised form is put in the field here rather than left to the flow
                // collector above. The collector cannot be relied on for it: it refuses to touch
                // a dirty draft, and `serverUrlDirty` is still true while this write is in
                // flight, so an emission that arrives first is ignored -- and once the value is
                // stored, a later write of the same string is swallowed by distinctUntilChanged
                // and no second emission ever comes. Without this the field would keep showing
                // the raw text (a typed "https://host/" against a stored "https://host"), which
                // leaves Save on screen forever offering to save what is already saved.
                _state.update {
                    it.copy(
                        serverUrlDraft = check.normalized,
                        serverUrlDirty = false,
                        serverUrlError = null,
                    )
                }
            }
        }
    }

    /** Forget the stored address. The field is emptied to match what is now on disk. */
    private fun clearServerUrl() {
        viewModelScope.launch {
            syncSettingsRepository.setServerUrl(null)
            _state.update {
                it.copy(serverUrlDraft = "", serverUrlDirty = false, serverUrlError = null)
            }
        }
    }

    /**
     * The screen's one network call.
     *
     * `checkServer()` decides for itself whether there is anything to check — it returns
     * [SyncServerCheck.NotConfigured] without touching the network when the device is unpaired or
     * the stored address is missing or unusable — so this cannot be made to send a packet by
     * tapping fast. The UI hides the action in those states anyway; this is the belt to that
     * braces.
     */
    private fun checkServer() {
        if (_state.value.serverCheckBusy) return
        _state.update { it.copy(serverCheckBusy = true, serverCheckNotice = null) }
        viewModelScope.launch {
            // No withContext here: SyncTransportStatus does its own I/O off the main thread and
            // says so, and wrapping it again would only hide where that decision is made.
            val result = syncTransportStatus.checkServer()
            _state.update {
                it.copy(
                    serverCheckBusy = false,
                    lastCheckFailed = result is SyncServerCheck.Unreachable,
                    serverCheckNotice = when (result) {
                        // Says what /healthz actually proves, and no more. It is unauthenticated,
                        // so it says nothing about this device being enrolled or about anything
                        // syncing -- and nothing does sync, because there is no engine yet.
                        SyncServerCheck.Reachable ->
                            "The server answered. Nothing was sent or signed in to."

                        is SyncServerCheck.Unreachable -> result.message
                        is SyncServerCheck.NotConfigured -> result.message
                    },
                )
            }
        }
    }

    /**
     * Read whether this device has paired.
     *
     * A Keystore lookup, so it happens off the main thread and `paired` stays null — rendered as
     * "Checking…" — until it answers. Run from `init` and again on every
     * [SettingsIntent.ScreenEntered], which is what keeps it correct after a trip to the pairing
     * screen; that intent's comment explains why once is not enough.
     */
    private fun refreshPairedState() {
        viewModelScope.launch {
            val paired = syncTransportStatus.isPaired()
            _state.update { it.copy(paired = paired) }
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
