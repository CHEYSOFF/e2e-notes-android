package my.cheysoff.feature_settings

import my.cheysoff.core_crypto.domain.BiometricAuthenticationStatus
import my.cheysoff.feature_settings.model.biometricRowInteractive
import my.cheysoff.feature_settings.model.biometricRowSubtitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiometricRowTest {

    // ── Interactivity ───────────────────────────────────────────────────────

    @Test
    fun `turning it off is always possible, whatever the device reports`() {
        // The whole point of the toggle is that biometric access can be revoked. Disabling only
        // deletes local state, so no device condition may block it — including the conditions
        // that block ENABLING it.
        BiometricAuthenticationStatus.entries.forEach { status ->
            assertTrue(
                "should stay operable while enabled, status=$status",
                biometricRowInteractive(status, enabled = true),
            )
        }
        assertTrue(biometricRowInteractive(status = null, enabled = true))
    }

    @Test
    fun `turning it on needs the device to be ready`() {
        assertTrue(biometricRowInteractive(BiometricAuthenticationStatus.READY, enabled = false))
    }

    @Test
    fun `turning it on is blocked by every non-ready status`() {
        BiometricAuthenticationStatus.entries
            .filter { it != BiometricAuthenticationStatus.READY }
            .forEach { status ->
                assertFalse(
                    "should not offer enrollment, status=$status",
                    biometricRowInteractive(status, enabled = false),
                )
            }
    }

    @Test
    fun `an unprobed device offers nothing yet`() {
        // Null means the platform has not answered. Guessing "ready" would put up a prompt that
        // cannot succeed; guessing "unavailable" would be a claim we cannot make yet.
        assertFalse(biometricRowInteractive(status = null, enabled = false))
    }

    // ── Copy ────────────────────────────────────────────────────────────────

    @Test
    fun `every combination has a non-blank subtitle`() {
        val statuses = BiometricAuthenticationStatus.entries + null
        statuses.forEach { status ->
            listOf(true, false).forEach { enabled ->
                assertTrue(
                    "blank subtitle for status=$status enabled=$enabled",
                    biometricRowSubtitle(status, enabled).isNotBlank(),
                )
            }
        }
    }

    @Test
    fun `an unprobed device says so rather than guessing`() {
        assertEquals("Checking…", biometricRowSubtitle(status = null, enabled = false))
        assertEquals("Checking…", biometricRowSubtitle(status = null, enabled = true))
    }

    @Test
    fun `when it is on, the subtitle does not talk about the hardware`() {
        // Once a wrap exists, the device's current status is beside the point — the switch is
        // there to remove the wrap, and "no biometrics enrolled" next to an ON switch reads as
        // a contradiction.
        BiometricAuthenticationStatus.entries.forEach { status ->
            val subtitle = biometricRowSubtitle(status, enabled = true)
            assertTrue("status leaked into the enabled subtitle: $subtitle", subtitle.startsWith("On"))
        }
    }

    @Test
    fun `the unavailable bucket does not claim the device lacks hardware`() {
        // BiometricAuthenticator maps every unrecognised BiometricManager code to NOT_AVAILABLE
        // (security update required, unsupported, status unknown), so this string must not assert
        // anything about the hardware itself.
        val subtitle = biometricRowSubtitle(
            BiometricAuthenticationStatus.NOT_AVAILABLE,
            enabled = false,
        )
        assertFalse(subtitle.contains("hardware"))
        assertTrue(subtitle.contains("isn't available"))
    }

    @Test
    fun `each off-state reason reads differently`() {
        // A user who cannot turn this on deserves to know which of the reasons applies; three
        // identical strings would be worse than one.
        val reasons = listOf(
            BiometricAuthenticationStatus.READY,
            BiometricAuthenticationStatus.AVAILABLE_BUT_NOT_ENROLLED,
            BiometricAuthenticationStatus.TEMPORARY_NOT_AVAILABLE,
            BiometricAuthenticationStatus.NOT_AVAILABLE,
        ).map { biometricRowSubtitle(it, enabled = false) }
        assertEquals(reasons.size, reasons.toSet().size)
    }
}
