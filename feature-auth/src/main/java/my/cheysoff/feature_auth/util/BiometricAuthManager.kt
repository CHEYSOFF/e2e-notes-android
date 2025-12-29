package my.cheysoff.feature_auth.util

import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Manages the biometric authentication prompt.
 *
 * @param fragmentActivity The activity that will host the biometric prompt.
 * @param onSuccess Callback for when authentication is successful.
 * @param onFailed Called when the fingerprint or faceId is presented but verification fails.
 * @param onError Called when the system cannot display the fingerprint/faceId dialog.
 */
class BiometricAuthManager(
    private val fragmentActivity: FragmentActivity,
    private val onSuccess: () -> Unit,
    private val onFailed: () -> Unit,
    private val onError: (errorCode: Int, errString: CharSequence) -> Unit
) {

    private val biometricPrompt: BiometricPrompt

    init {
        val executor = ContextCompat.getMainExecutor(fragmentActivity)
        biometricPrompt = BiometricPrompt(
            fragmentActivity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    onError(errorCode, errString)
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    onFailed()
                }
            }
        )
    }

    fun showBiometricPrompt(
        title: String,
        subtitle: String,
        negativeButtonText: String
    ) {
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(negativeButtonText)
            .build()
        biometricPrompt.authenticate(promptInfo)
    }
}