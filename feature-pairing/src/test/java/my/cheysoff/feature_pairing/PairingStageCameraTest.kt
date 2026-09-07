package my.cheysoff.feature_pairing

import my.cheysoff.feature_pairing.ui.PairingStage
import my.cheysoff.feature_pairing.ui.needsCamera
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every stage that shows a viewfinder must say so.
 *
 * `PairingScreen` gates its camera-permission effect on this. While it was a two-branch `is` check
 * that omitted [PairingStage.ScanningInvite], the phone-to-desktop flow sat on "Asking for
 * permission to use the camera…" forever: the effect returned early, so nothing asked for the
 * permission and nothing re-checked it, and granting it by hand in Settings did not help either.
 */
class PairingStageCameraTest {

    @Test
    fun `scanning the other phone's offer needs the camera`() {
        assertTrue(PairingStage.ScanningOffer(lastHint = null).needsCamera())
    }

    @Test
    fun `scanning their sealed reply needs the camera`() {
        assertTrue(PairingStage.ScanningSeal(secondsRemaining = 60, lastHint = null).needsCamera())
    }

    @Test
    fun `scanning a desktop invite needs the camera`() {
        assertTrue(PairingStage.ScanningInvite(lastHint = null).needsCamera())
    }

    @Test
    fun `showing a code does not need the camera`() {
        assertFalse(PairingStage.ShowingOffer(code = "x", secondsRemaining = 60).needsCamera())
    }

    @Test
    fun `the role chooser does not need the camera`() {
        assertFalse(PairingStage.ChoosingRole.needsCamera())
    }
}
