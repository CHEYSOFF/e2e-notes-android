package my.cheysoff.feature_auth.model

import androidx.fragment.app.FragmentActivity

sealed class AuthScreenIntent {
    /** Fired once when the screen appears: decide set-PIN vs enter-PIN vs biometric landing. */
    data object Initialize : AuthScreenIntent()

    /** A keypad digit was pressed. */
    data class Digit(val value: Char) : AuthScreenIntent()

    /** Remove the last entered digit. */
    data object Backspace : AuthScreenIntent()

    /** On the biometric landing: drop to the PIN keypad. */
    data object UsePinInstead : AuthScreenIntent()

    /** Dismiss the keypad sheet (back to the biometric landing, or Confirm→Create). */
    data object DismissSheet : AuthScreenIntent()

    /** Tap the biometric Unlock button on the landing. */
    data class BiometricUnlock(val activity: FragmentActivity) : AuthScreenIntent()

    /** Enroll biometric after first PIN setup (dispatched in response to RequestBiometricEnroll). */
    data class EnableBiometric(val activity: FragmentActivity) : AuthScreenIntent()
}
