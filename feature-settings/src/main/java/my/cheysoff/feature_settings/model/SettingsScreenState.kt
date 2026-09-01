package my.cheysoff.feature_settings.model

import androidx.compose.runtime.Immutable
import my.cheysoff.core_crypto.domain.BiometricAuthenticationStatus
import my.cheysoff.core_domain.model.NotesSortOrder
import my.cheysoff.core_domain.sync.SyncPassState

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

    // --- Sync ---

    /**
     * Whether this device has completed a pairing, or null until the (off-main-thread) Keystore
     * read has answered. Null renders as "Checking…" rather than as "not paired" — see
     * [syncStatus].
     */
    val paired: Boolean? = null,

    /**
     * The **persisted** sync server address, or null when none is set.
     *
     * Mirrored from the repository, exactly as [sortOrder] is: it changes only when a write
     * succeeded, so the status line can never describe an address that was not actually stored.
     */
    val serverUrl: String? = null,

    /**
     * Whether [serverUrl] still passes validation. False only for a value the current rule would
     * refuse, which the screen itself will not write — see `SyncStatus.SERVER_UNUSABLE`.
     */
    val serverUrlUsable: Boolean = true,

    /** What is in the text field. A draft: it is not stored until Save is tapped. */
    val serverUrlDraft: String = "",

    /**
     * True once the field has been edited and not yet saved or cleared. While it is true, an
     * incoming repository emission leaves the field alone rather than overwriting what is being
     * typed.
     */
    val serverUrlDirty: Boolean = false,

    /** Why the last Save was refused, or null. Cleared on the next keystroke. */
    val serverUrlError: String? = null,

    /** True while the "Check server" round trip is in flight. */
    val serverCheckBusy: Boolean = false,

    /**
     * Whether the most recent completed check failed to reach the server. Reset whenever the
     * stored address changes, because a failure describes one address.
     */
    val lastCheckFailed: Boolean = false,

    /** One-line outcome of the last check, shown beneath the card. Null before any check. */
    val serverCheckNotice: String? = null,

    /**
     * What the sync engine's last attempt did, mirrored from `SyncController.state`.
     *
     * Distinct from [lastCheckFailed] and [serverCheckBusy], which are about the unauthenticated
     * health check the user asks for by tapping a button. This is about the engine, which runs on
     * its own — and the two must not be collapsed, because "the address answers" and "your notes
     * moved" are different facts and only one of them is worth acting on.
     */
    val sync: SyncPassState = SyncPassState.Idle,

    // --- About ---

    /** e.g. "1.0 (1)". Empty only in a preview/default state. */
    val appVersion: String = "",
)
