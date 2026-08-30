package my.cheysoff.feature_settings.model

import androidx.fragment.app.FragmentActivity
import my.cheysoff.core_domain.model.NotesSortOrder

sealed interface SettingsIntent {
    data class SetShowGreetings(val enabled: Boolean) : SettingsIntent
    data class SetShowDailyPhrases(val enabled: Boolean) : SettingsIntent
    data class SetShowStats(val enabled: Boolean) : SettingsIntent

    data class SortOrderSelected(val order: NotesSortOrder) : SettingsIntent

    /**
     * Turning the biometric switch on or off.
     *
     * Turning it ON has to show a `BiometricPrompt`, and `BiometricPrompt` must be hosted by a
     * [FragmentActivity], so the activity is carried on the intent exactly as
     * `AuthScreenIntent.EnableBiometric` does. It is read and discarded synchronously by the
     * prompt; it is never retained in state.
     */
    data class SetBiometricEnabled(
        val enabled: Boolean,
        val activity: FragmentActivity,
    ) : SettingsIntent
}
