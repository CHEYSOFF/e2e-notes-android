package my.cheysoff.feature_auth.model

/** Which auth surface is currently shown. */
enum class AuthMode {
    /** Deciding which screen to show (reading PIN/biometric state). */
    LOADING,

    /** Returning user with biometric: crescent "Welcome back" landing + "Use PIN instead". */
    BIOMETRIC,

    /** Keypad sheet up to enter the existing PIN. */
    ENTER_PIN,

    /** First run / migration: choose a new PIN. */
    SET_PIN,

    /** First run / migration: re-enter the new PIN to confirm it. */
    CONFIRM_PIN,
}

/**
 * UI state for the auth screen. The PIN digits themselves never live here — only [pinLength], the
 * number of filled dots — so the secret stays in the ViewModel's zeroable buffer.
 */
data class AuthScreenState(
    val mode: AuthMode = AuthMode.LOADING,
    /** Number of digits entered so far (0..[pinMaxLength]); drives the filled dots. */
    val pinLength: Int = 0,
    val pinMaxLength: Int = PIN_LENGTH,
    /** True when SET_PIN/CONFIRM_PIN is reached because of a legacy-passphrase migration. */
    val isMigration: Boolean = false,
    /** True when the keypad sheet can be dismissed (back to the biometric landing, or Confirm→Create). */
    val canDismissSheet: Boolean = false,
    /** Seconds left on an active lockout window; 0 when not locked out. */
    val lockoutSecondsRemaining: Int = 0,
    val isLoading: Boolean = false,
    val error: String? = null,
) {
    companion object {
        const val PIN_LENGTH = 6
    }
}
