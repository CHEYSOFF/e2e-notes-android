package my.cheysoff.feature_auth.util

import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import my.cheysoff.core_crypto.SecureUnlockManager
import javax.inject.Inject
import javax.inject.Singleton

/** How an attempt to turn biometric unlock ON finished. */
sealed interface BiometricEnrollResult {
    /** A biometric wrap of the database passphrase is now stored. */
    data object Enabled : BiometricEnrollResult

    /**
     * The prompt closed without a successful match — the user pressed Cancel, backed out, or the
     * system reported an error (including lockout after too many failed attempts). Nothing was
     * stored.
     */
    data object Cancelled : BiometricEnrollResult

    /**
     * The biometric Keystore key could not be provisioned or initialised at all, so no prompt was
     * ever shown. Nothing was stored.
     */
    data object Unavailable : BiometricEnrollResult

    /**
     * The biometric match succeeded but the passphrase could not be wrapped with the unlocked
     * cipher — most plausibly because the session was no longer unlocked (there is no passphrase
     * in memory to wrap), or the Keystore key was invalidated between init and use. Nothing was
     * stored.
     */
    data object Failed : BiometricEnrollResult
}

/**
 * The ONE path that turns biometric unlock on.
 *
 * Enabling biometric unlock means encrypting the in-memory database passphrase with a Keystore
 * key that a biometric match unlocks, so it needs three things in order: an ENCRYPT-mode cipher
 * from [SecureUnlockManager.biometricEncryptCipher], a successful `BiometricPrompt` carrying that
 * cipher in a `CryptoObject`, and a live unlocked passphrase for
 * [SecureUnlockManager.enableBiometric] to wrap. Two screens need that sequence — the one-shot
 * enrollment offered right after the PIN is first set up, and the settings screen's toggle — and
 * a second implementation of it is how the two would end up storing subtly different state.
 *
 * This class holds no state; it is a [Singleton] only so Hilt hands out one instance rather than
 * because sharing matters.
 *
 * [onResult] is invoked on the main thread, from the prompt's own callback. It is called exactly
 * once per [enroll] call, including on the paths that never show a prompt.
 */
@Singleton
class BiometricEnroller @Inject constructor(
    private val secureUnlockManager: SecureUnlockManager,
) {

    /**
     * Show the enrollment prompt on [activity] and, on success, store the biometric wrap.
     *
     * The caller is responsible for having checked that biometrics are usable at all
     * (`AuthRepository.getBiometricAuthStatus()`); this method does not, because a stale check is
     * no substitute for the prompt's own answer and every failure below is already reported
     * rather than thrown.
     */
    fun enroll(activity: FragmentActivity, onResult: (BiometricEnrollResult) -> Unit) {
        val cipher = try {
            secureUnlockManager.biometricEncryptCipher()
        } catch (e: Exception) {
            // Key generation or Cipher.init failed (no usable Keystore, a strongbox/attestation
            // failure on some OEMs, biometrics removed since the last check). Biometric unlock is
            // always optional, so this is reported, never thrown.
            onResult(BiometricEnrollResult.Unavailable)
            return
        }

        val manager = BiometricAuthManager(
            fragmentActivity = activity,
            onSuccess = { result ->
                // doFinal runs INSIDE the prompt's main-thread callback. A key invalidated between
                // init and use, or a session that re-locked while the prompt was up (there is then
                // no passphrase to wrap and enableBiometric throws), surfaces here — uncaught, it
                // would crash the app during an entirely optional opt-in.
                val unlocked = result.cryptoObject?.cipher
                val stored = unlocked != null && runCatching {
                    secureUnlockManager.enableBiometric(unlocked)
                }.isSuccess
                onResult(
                    if (stored) BiometricEnrollResult.Enabled else BiometricEnrollResult.Failed
                )
            },
            // A single non-match: the prompt stays open for another try, so this is not yet an
            // outcome. The eventual outcome still arrives via onSuccess or onError.
            onFailed = { },
            onError = { _, _ -> onResult(BiometricEnrollResult.Cancelled) },
        )
        manager.authenticate(
            title = "Mañana",
            subtitle = "Enable biometric unlock",
            negativeButtonText = "Cancel",
            cryptoObject = BiometricPrompt.CryptoObject(cipher),
        )
    }
}
