package my.cheysoff.feature_settings.model

import androidx.compose.runtime.Immutable
import my.cheysoff.core_crypto.domain.BiometricAuthenticationStatus
import my.cheysoff.core_domain.model.NotesSortOrder

@Immutable
data class SettingsScreenState(
    // --- Header personalisation (persisted as HeaderSettings) ---
    val showGreetings: Boolean = true,
    val showDailyPhrases: Boolean = true,
    val showStats: Boolean = true,

    /**
     * The notes list's order. This is the SAME persisted preference the list's inline sort pill
     * writes — not a separate "default" that the list may deviate from — so the two surfaces
     * always agree.
     */
    val sortOrder: NotesSortOrder = NotesSortOrder.DEFAULT,

    // --- Security ---

    /** True when a biometric wrap of the database passphrase is stored. */
    val biometricEnabled: Boolean = false,

    /**
     * What the platform reports about biometrics, or null until the (off-main-thread) probe has
     * answered. Null is rendered as "Checking…" rather than assumed — see [biometricRowSubtitle].
     */
    val biometricStatus: BiometricAuthenticationStatus? = null,

    /** True while an enable/disable is in flight, so the switch cannot be double-driven. */
    val biometricBusy: Boolean = false,

    /**
     * One-line outcome shown beneath the biometric row after an attempt that did not simply
     * succeed. Cleared on the next attempt.
     */
    val biometricNotice: String? = null,

    // --- About ---

    /** e.g. "1.0 (1)". Empty only in a preview/default state. */
    val appVersion: String = "",
)
