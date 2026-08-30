package my.cheysoff.feature_auth.ui

import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.cheysoff.core_crypto.SecureUnlockManager
import my.cheysoff.core_crypto.UnlockResult
import my.cheysoff.core_crypto.domain.AuthRepository
import my.cheysoff.core_crypto.domain.BiometricAuthenticationStatus
import my.cheysoff.feature_auth.model.AuthMode
import my.cheysoff.feature_auth.model.AuthScreenIntent
import my.cheysoff.feature_auth.model.AuthScreenState
import my.cheysoff.feature_auth.util.BiometricAuthManager
import my.cheysoff.feature_auth.util.BiometricEnroller
import javax.crypto.Cipher
import javax.inject.Inject
import kotlin.math.ceil

/**
 * The zero char used to wipe PIN buffers. Named rather than written inline: a raw NUL typed into a
 * char literal makes this whole file binary to git, which silently hides it from every code review.
 */
private const val NUL = '\u0000'

sealed class AuthEvent {
    /** Auth succeeded (PIN/biometric) — navigate into the app. */
    data object NavigationToNotesList : AuthEvent()

    /** PIN was just set up and biometric is available — the screen should launch the enroll prompt. */
    data object RequestBiometricEnroll : AuthEvent()
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val secureUnlockManager: SecureUnlockManager,
    private val authRepository: AuthRepository,
    private val biometricEnroller: BiometricEnroller,
) : ViewModel() {

    private val _state = MutableStateFlow(AuthScreenState())
    val state = _state.asStateFlow()

    private val _events = Channel<AuthEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val pinLength = AuthScreenState.PIN_LENGTH

    /** The PIN being entered. Zeroed after every use; never copied into UI state. */
    private val pinBuffer = CharArray(pinLength)
    private var pinCount = 0

    /** First entry during set-PIN, held until confirm. Zeroed after the compare. */
    private var firstPin: CharArray? = null

    private var lockoutJob: Job? = null

    /** True when a biometric landing is reachable, so the keypad sheet can be dismissed back to it. */
    private var biometricLandingAvailable = false

    /**
     * Guards [initialize] to a single run. This ViewModel is nav-scoped and outlives activity
     * recreation, but the screen's LaunchedEffect re-fires on every recreation (e.g. rotation).
     * Re-running would wipe a half-entered PIN — and worse, resetBuffer() would zero [pinBuffer]
     * while it is being read by an in-flight setupPin/unlockWithPin on a background thread.
     */
    private var initialized = false

    fun processIntent(intent: AuthScreenIntent) {
        when (intent) {
            is AuthScreenIntent.Initialize -> initialize()
            is AuthScreenIntent.Digit -> onDigit(intent.value)
            is AuthScreenIntent.Backspace -> onBackspace()
            is AuthScreenIntent.UsePinInstead -> onUsePinInstead()
            is AuthScreenIntent.DismissSheet -> onDismissSheet()
            is AuthScreenIntent.BiometricUnlock -> startBiometricUnlock(intent.activity)
            is AuthScreenIntent.EnableBiometric -> startBiometricEnroll(intent.activity)
        }
    }

    private fun initialize() {
        if (initialized) return
        initialized = true

        viewModelScope.launch {
            // These first touch the Keystore master key and decrypt the prefs file — disk-backed
            // work that must not run on the main thread (ANR risk on a cold start after install
            // or restore). Read off-thread, then apply the result on Main.
            val snapshot = withContext(Dispatchers.IO) {
                val pinSet = secureUnlockManager.isPinSet()
                InitSnapshot(
                    pinSet = pinSet,
                    isMigration = if (pinSet) false else secureUnlockManager.needsMigration(),
                    biometricReady = pinSet && secureUnlockManager.isBiometricEnabled() &&
                        authRepository.getBiometricAuthStatus() == BiometricAuthenticationStatus.READY,
                    lockoutRemaining = if (pinSet) secureUnlockManager.lockoutRemainingMillis() else 0L,
                )
            }

            resetBuffer()
            if (!snapshot.pinSet) {
                _state.update {
                    it.copy(
                        mode = AuthMode.SET_PIN,
                        isMigration = snapshot.isMigration,
                        pinLength = 0,
                        error = null,
                        // First-run PIN entry has nothing to dismiss back to.
                        canDismissSheet = false,
                    )
                }
                return@launch
            }

            biometricLandingAvailable = snapshot.biometricReady
            _state.update {
                it.copy(
                    mode = if (snapshot.biometricReady) AuthMode.BIOMETRIC else AuthMode.ENTER_PIN,
                    pinLength = 0,
                    error = null,
                    canDismissSheet = false,
                )
            }
            if (snapshot.lockoutRemaining > 0) startLockoutTicker(snapshot.lockoutRemaining)
        }
    }

    /** Result of the off-thread startup probe in [initialize]. */
    private data class InitSnapshot(
        val pinSet: Boolean,
        val isMigration: Boolean,
        val biometricReady: Boolean,
        val lockoutRemaining: Long,
    )

    private fun onUsePinInstead() {
        resetBuffer()
        _state.update {
            it.copy(mode = AuthMode.ENTER_PIN, pinLength = 0, error = null, canDismissSheet = true)
        }
    }

    private fun onDismissSheet() {
        // MUST match the isLoading guard in onDigit/onBackspace. While a PIN operation is in
        // flight, pinBuffer has been handed to setupPin/unlockWithPin on a background thread and
        // PBEKeySpec has not necessarily cloned it yet; resetBuffer() here would zero the PIN
        // mid-derivation — scoring a correct PIN as a failure, or (in CONFIRM_PIN) persisting a
        // wrap derived from a half-zeroed PIN, which locks the user out of their database forever.
        if (_state.value.isLoading) return

        when (_state.value.mode) {
            AuthMode.ENTER_PIN -> if (biometricLandingAvailable) {
                resetBuffer()
                _state.update {
                    it.copy(mode = AuthMode.BIOMETRIC, pinLength = 0, error = null, canDismissSheet = false)
                }
            }

            AuthMode.CONFIRM_PIN -> {
                resetBuffer()
                firstPin?.fill(NUL); firstPin = null
                _state.update {
                    it.copy(mode = AuthMode.SET_PIN, pinLength = 0, error = null, canDismissSheet = false)
                }
            }

            else -> Unit
        }
    }

    private fun onDigit(c: Char) {
        val s = _state.value
        if (s.isLoading || s.lockoutSecondsRemaining > 0) return
        if (pinCount >= pinLength) return
        pinBuffer[pinCount++] = c
        _state.update { it.copy(pinLength = pinCount, error = null) }
        if (pinCount == pinLength) submit()
    }

    private fun onBackspace() {
        val s = _state.value
        if (s.isLoading || s.lockoutSecondsRemaining > 0) return
        if (pinCount == 0) return
        pinCount--
        pinBuffer[pinCount] = NUL
        _state.update { it.copy(pinLength = pinCount) }
    }

    private fun submit() {
        when (_state.value.mode) {
            AuthMode.SET_PIN -> {
                firstPin?.fill(NUL)
                firstPin = pinBuffer.copyOf(pinCount)
                resetBuffer()
                _state.update {
                    it.copy(mode = AuthMode.CONFIRM_PIN, pinLength = 0, error = null, canDismissSheet = true)
                }
            }

            AuthMode.CONFIRM_PIN -> confirmPin()
            AuthMode.ENTER_PIN -> enterPin()
            else -> Unit
        }
    }

    private fun confirmPin() {
        val first = firstPin
        val matches = first != null && first.size == pinCount &&
            (0 until pinCount).all { first[it] == pinBuffer[it] }

        if (!matches) {
            firstPin?.fill(NUL); firstPin = null
            resetBuffer()
            _state.update {
                it.copy(
                    mode = AuthMode.SET_PIN,
                    pinLength = 0,
                    error = "PINs didn't match. Try again.",
                    canDismissSheet = false,
                )
            }
            return
        }

        _state.update { it.copy(isLoading = true, error = null) }
        // Hand the KDF its OWN copy and release the UI buffer up front. pinBuffer is UI-owned and
        // could be zeroed by a dismiss or an activity recreation while the derivation is still
        // running on a background thread; deriving from a snapshot makes that harmless instead of
        // persisting a wrap from a half-zeroed PIN (an unenterable PIN = permanent data loss).
        val setupCopy = pinBuffer.copyOf(pinCount)
        resetBuffer()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.Default) { secureUnlockManager.setupPin(setupCopy) }
            } finally {
                setupCopy.fill(NUL)
            }
            firstPin?.fill(NUL); firstPin = null
            _state.update { it.copy(isLoading = false) }

            val canEnroll = authRepository.getBiometricAuthStatus() == BiometricAuthenticationStatus.READY
            if (canEnroll) _events.send(AuthEvent.RequestBiometricEnroll)
            else _events.send(AuthEvent.NavigationToNotesList)
        }
    }

    private fun enterPin() {
        _state.update { it.copy(isLoading = true, error = null) }
        // Same snapshot discipline as confirmPin: the KDF must not read the UI-owned buffer.
        val unlockCopy = pinBuffer.copyOf(pinCount)
        resetBuffer()
        viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.Default) { secureUnlockManager.unlockWithPin(unlockCopy) }
            } finally {
                unlockCopy.fill(NUL)
            }
            _state.update { it.copy(isLoading = false, pinLength = 0) }
            when (result) {
                is UnlockResult.Success -> _events.send(AuthEvent.NavigationToNotesList)
                is UnlockResult.WrongPin -> {
                    _state.update { it.copy(error = "Incorrect PIN") }
                    if (result.lockoutMillis > 0) startLockoutTicker(result.lockoutMillis)
                }
                is UnlockResult.LockedOut -> startLockoutTicker(result.remainingMillis)
            }
        }
    }

    private fun startBiometricUnlock(activity: FragmentActivity) {
        val cipher: Cipher = try {
            secureUnlockManager.biometricDecryptCipher()
        } catch (e: KeyPermanentlyInvalidatedException) {
            secureUnlockManager.disableBiometric()
            resetBuffer()
            _state.update {
                it.copy(mode = AuthMode.ENTER_PIN, pinLength = 0, error = "Biometrics changed — enter your PIN.")
            }
            return
        } catch (e: Exception) {
            _state.update { it.copy(error = "Biometric unavailable — use your PIN.") }
            return
        }

        showPrompt(
            activity = activity,
            cipher = cipher,
            subtitle = "Unlock your notes",
            onSuccess = { result ->
                // doFinal runs INSIDE the prompt's main-thread callback. A key invalidated between
                // init and doFinal, or a stale bio_ct written under a previous key, surfaces here as
                // IllegalBlockSizeException/AEADBadTagException — uncaught, that crashes the app.
                val unlocked = result.cryptoObject?.cipher
                val ok = unlocked != null && runCatching {
                    secureUnlockManager.unlockWithBiometric(unlocked)
                }.getOrDefault(false)

                if (ok) {
                    viewModelScope.launch { _events.send(AuthEvent.NavigationToNotesList) }
                } else {
                    // The stored wrap is unusable — drop it so we don't offer a dead Unlock button
                    // again, and fall back to the PIN.
                    runCatching { secureUnlockManager.disableBiometric() }
                    biometricLandingAvailable = false
                    resetBuffer()
                    _state.update {
                        it.copy(
                            mode = AuthMode.ENTER_PIN,
                            pinLength = 0,
                            canDismissSheet = false,
                            error = "Biometric unlock failed — use your PIN.",
                        )
                    }
                }
            },
            onCancel = { /* user dismissed; stay on screen */ },
        )
    }

    private fun startBiometricEnroll(activity: FragmentActivity) {
        // Delegated to the shared enroller, which is also what the settings screen's biometric
        // toggle calls. Enrollment here is a one-shot offer made straight after the PIN is set,
        // and it is entirely optional, so EVERY outcome — enabled, cancelled, unavailable,
        // failed — continues into the app rather than holding the user on the auth screen. That
        // is why the result is deliberately not inspected; the settings screen, where the user
        // asked for this specifically, is the surface that reports what happened.
        biometricEnroller.enroll(activity) {
            viewModelScope.launch { _events.send(AuthEvent.NavigationToNotesList) }
        }
    }

    private fun showPrompt(
        activity: FragmentActivity,
        cipher: Cipher,
        subtitle: String,
        onSuccess: (BiometricPrompt.AuthenticationResult) -> Unit,
        onCancel: () -> Unit,
    ) {
        val manager = BiometricAuthManager(
            fragmentActivity = activity,
            onSuccess = onSuccess,
            onFailed = { /* a single non-match; the prompt stays open for retry */ },
            onError = { _, _ -> onCancel() },
        )
        manager.authenticate(
            title = "Mañana",
            subtitle = subtitle,
            negativeButtonText = "Cancel",
            cryptoObject = BiometricPrompt.CryptoObject(cipher),
        )
    }

    private fun startLockoutTicker(millis: Long) {
        lockoutJob?.cancel()
        lockoutJob = viewModelScope.launch {
            var remaining = ceil(millis / 1000.0).toInt()
            while (remaining > 0) {
                _state.update { it.copy(lockoutSecondsRemaining = remaining) }
                delay(1000)
                remaining--
            }
            _state.update { it.copy(lockoutSecondsRemaining = 0, error = null) }
        }
    }

    private fun resetBuffer() {
        pinBuffer.fill(NUL)
        pinCount = 0
    }

    override fun onCleared() {
        super.onCleared()
        pinBuffer.fill(NUL)
        firstPin?.fill(NUL)
    }
}
