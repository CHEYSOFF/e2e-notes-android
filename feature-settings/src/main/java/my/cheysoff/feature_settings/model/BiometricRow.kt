package my.cheysoff.feature_settings.model

import my.cheysoff.core_crypto.domain.BiometricAuthenticationStatus

/**
 * The copy and the interactivity of the settings screen's "Biometric unlock" row, derived from
 * the two facts the row depends on: whether a biometric wrap is currently stored, and what the
 * platform says about biometrics on this device.
 *
 * Split out of the composable because it is the only part of this screen with real branching, and
 * because getting a security control's label wrong is worse than getting a layout wrong. Pure
 * functions, so they are unit-tested (BiometricRowTest) rather than eyeballed on a device.
 *
 * A null [status] means "not probed yet": reading it touches the platform BiometricManager, which
 * the ViewModel does off the main thread, so the row is briefly in a state where the answer is
 * genuinely unknown and must not be guessed at.
 */

/** Sub-line under the row's title. Always non-blank. */
fun biometricRowSubtitle(
    status: BiometricAuthenticationStatus?,
    enabled: Boolean,
): String = when {
    status == null -> "Checking…"

    // Already on: whatever the device reports right now, the wrap exists and the switch is the
    // way to remove it. Describing the hardware here would be beside the point.
    enabled -> "On. Your PIN still works."

    status == BiometricAuthenticationStatus.READY ->
        "Off. Unlocking needs your PIN every time."

    status == BiometricAuthenticationStatus.AVAILABLE_BUT_NOT_ENROLLED ->
        "No biometrics are enrolled on this device."

    status == BiometricAuthenticationStatus.TEMPORARY_NOT_AVAILABLE ->
        "Biometric hardware is unavailable right now."

    // NOT_AVAILABLE is also the bucket BiometricAuthenticator puts every unrecognised
    // BiometricManager code into (security update required, unsupported, status unknown), so this
    // string deliberately does NOT claim the device lacks the hardware — it only claims what is
    // certain, which is that we cannot offer it.
    else -> "Biometric unlock isn't available on this device."
}

/**
 * Whether the row's switch can be operated.
 *
 * Turning it OFF is allowed unconditionally once it is on: that only deletes local state (the
 * stored wrap and the Keystore key) and never needs the hardware to cooperate. Leaving it stuck
 * on because the sensor happens to be busy would be the worse failure — the user would have no
 * way to revoke biometric access to their notes.
 */
fun biometricRowInteractive(
    status: BiometricAuthenticationStatus?,
    enabled: Boolean,
): Boolean = when {
    enabled -> true
    status == null -> false
    else -> status == BiometricAuthenticationStatus.READY
}
