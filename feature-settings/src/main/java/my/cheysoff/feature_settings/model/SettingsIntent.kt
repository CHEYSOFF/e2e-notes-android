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

    // --- Sync ---

    /**
     * The screen entered composition.
     *
     * Re-reads the facts that something *other than this screen* can change — today that is
     * exactly one: whether the device has paired, which the pairing screen this one pushes to can
     * turn from false to true. The ViewModel outlives that trip (it is scoped to the navigation
     * back-stack entry, which stays on the stack), so a value read once in `init` would be stale
     * the moment the user came back from pairing successfully. The composable does not outlive it
     * — Navigation Compose disposes a destination it has navigated away from — so entering
     * composition is the signal.
     */
    data object ScreenEntered : SettingsIntent

    /**
     * A keystroke in the server-address field.
     *
     * Editing stores nothing. The three intents below are deliberately separate from this one so
     * that leaving the screen mid-edit changes no persisted state, and so that an address is only
     * ever written by a deliberate act.
     */
    data class ServerUrlChanged(val text: String) : SettingsIntent

    /** Validate the draft and, if it passes, persist it. */
    data object SaveServerUrl : SettingsIntent

    /**
     * Forget the stored address.
     *
     * Its own intent rather than "save an empty field", so an accidentally cleared field cannot
     * silently un-configure sync.
     */
    data object ClearServerUrl : SettingsIntent

    /**
     * One unauthenticated `GET /healthz` against the stored address.
     *
     * The only thing on this screen that opens a socket, and it only runs when the device is
     * paired and the stored address validates.
     */
    data object CheckServer : SettingsIntent

    /**
     * Forget a recorded halt and try one more pass, because the user asked.
     *
     * Offered only while the engine is actually halted. It repairs nothing -- see
     * `SyncStore.clearHalt` -- and the likely outcome is the same halt again; what it removes is
     * the dead end for a halt whose cause has since been dealt with, such as an app update after an
     * unsupported payload version.
     */
    data object RetryAfterHalt : SettingsIntent
}
