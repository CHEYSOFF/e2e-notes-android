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
import my.cheysoff.feature_auth.model.AbandonEntry
import my.cheysoff.feature_auth.model.AuthInitSnapshot
import my.cheysoff.feature_auth.model.AuthKeypad
import my.cheysoff.feature_auth.model.AuthMode
import my.cheysoff.feature_auth.model.AuthScreenIntent
import my.cheysoff.feature_auth.model.AuthScreenState
import my.cheysoff.feature_auth.model.BufferWipe
import my.cheysoff.feature_auth.model.SubmitDecision
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
                AuthInitSnapshot(
                    pinSet = pinSet,
                    isMigration = if (pinSet) false else secureUnlockManager.needsMigration(),
                    biometricReady = pinSet && secureUnlockManager.isBiometricEnabled() &&
                        authRepository.getBiometricAuthStatus() == BiometricAuthenticationStatus.READY,
                    lockoutRemaining = if (pinSet) secureUnlockManager.lockoutRemainingMillis() else 0L,
                )
            }

            // biometricReady already implies pinSet (see the probe above), so assigning it
            // unconditionally is the same value the returning-user branch used to assign on its
            // own; and 0 is the only lockoutRemaining a PIN-less install can report, so the ticker
            // below still cannot start on a first run.
            biometricLandingAvailable = snapshot.biometricReady
            applyAbandon(AuthKeypad.onInitialize(_state.value, snapshot))
            if (snapshot.lockoutRemaining > 0) startLockoutTicker(snapshot.lockoutRemaining)
        }
    }

    private fun onUsePinInstead() = applyAbandon(AuthKeypad.onUsePinInstead(_state.value))

    /**
     * Whether the sheet may be dismissed — and where to — is [AuthKeypad.onDismiss]'s decision,
     * which shares its in-flight guard with the digit and backspace paths instead of restating it.
     * That guard is the one that matters here: while a PIN operation is in flight, pinBuffer has
     * been handed to setupPin/unlockWithPin on a background thread and PBEKeySpec has not
     * necessarily cloned it yet; wiping it mid-derivation would score a correct PIN as a failure,
     * or (in CONFIRM_PIN) persist a wrap derived from a half-zeroed PIN, which locks the user out
     * of their database forever.
     */
    private fun onDismissSheet() {
        applyAbandon(AuthKeypad.onDismiss(_state.value, biometricLandingAvailable) ?: return)
    }

    private fun onDigit(c: Char) {
        // Decided against the buffer's own count, not the dots': the two diverge during the
        // "Checking..." window, and the buffer's count is the one that must bound the write.
        val accepted = AuthKeypad.onDigit(_state.value, pinCount) ?: return
        pinBuffer[accepted.writeIndex] = c
        pinCount = accepted.writeIndex + 1
        _state.value = accepted.state
        if (accepted.submit) submit()
    }

    private fun onBackspace() {
        val accepted = AuthKeypad.onBackspace(_state.value, pinCount) ?: return
        pinBuffer[accepted.clearIndex] = NUL
        pinCount = accepted.clearIndex
        _state.value = accepted.state
    }

    private fun submit() {
        when (val decision = AuthKeypad.onSubmit(_state.value)) {
            is SubmitDecision.HoldForConfirm -> {
                // The confirm step gets its own copy, taken BEFORE the buffer is wiped, so the
                // comparison later reads a snapshot rather than the live UI-owned array.
                firstPin?.fill(NUL)
                firstPin = pinBuffer.copyOf(pinCount)
                resetBuffer()
                _state.value = decision.state
            }

            SubmitDecision.ConfirmPin -> confirmPin()
            SubmitDecision.EnterPin -> enterPin()
            SubmitDecision.None -> Unit
        }
    }

    private fun confirmPin() {
        val first = firstPin
        val matches = first != null && first.size == pinCount &&
            (0 until pinCount).all { first[it] == pinBuffer[it] }

        if (!matches) {
            applyAbandon(AuthKeypad.onConfirmMismatch(_state.value))
            return
        }

        _state.value = AuthKeypad.onVerificationStarted(_state.value)
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
        _state.value = AuthKeypad.onVerificationStarted(_state.value)
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

    /**
     * Carry out an [AbandonEntry]: zero the secret buffers it names, THEN publish its state.
     *
     * The order is the point. Every state an [AbandonEntry] carries reports `pinLength = 0`, and
     * that claim has to be true of the actual buffer before anything can observe it. Routing every
     * exit path through this one function is also what keeps the wipes uniform: [BufferWipe] has
     * no "wipe nothing" member, so a transition cannot be added that quietly skips them.
     */
    private fun applyAbandon(outcome: AbandonEntry) {
        when (outcome.wipe) {
            BufferWipe.PIN -> resetBuffer()
            BufferWipe.PIN_AND_FIRST -> {
                resetBuffer()
                firstPin?.fill(NUL); firstPin = null
            }
        }
        _state.value = outcome.state
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
