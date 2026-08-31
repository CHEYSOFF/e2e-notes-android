package my.cheysoff.feature_auth

import my.cheysoff.feature_auth.model.AuthInitSnapshot
import my.cheysoff.feature_auth.model.AuthKeypad
import my.cheysoff.feature_auth.model.AuthMode
import my.cheysoff.feature_auth.model.AuthScreenState
import my.cheysoff.feature_auth.model.BufferWipe
import my.cheysoff.feature_auth.model.SubmitDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The auth keypad's decision logic, exercised as the pure component it now is — no Android, no
 * Keystore, no coroutines, so every case below is a plain function call.
 *
 * Two layers are tested here:
 *
 *  1. The individual transitions, asserted directly on [AuthKeypad].
 *  2. Whole journeys, driven through [KeypadDriver] — a buffer + state holder that applies each
 *     decision exactly the way `AuthViewModel` does. The driver is what makes "the buffer is empty
 *     after a mismatch" an assertable fact: a transition that failed to demand a wipe would leave
 *     digits in the driver's array, and the test would see them.
 */
class AuthKeypadTest {

    /** The NUL the ViewModel wipes with; an unwritten or wiped slot must equal this. */
    private val nul = '\u0000'

    private val pinLength = AuthScreenState.PIN_LENGTH

    private fun state(
        mode: AuthMode = AuthMode.ENTER_PIN,
        pinLength: Int = 0,
        isLoading: Boolean = false,
        lockoutSecondsRemaining: Int = 0,
        error: String? = null,
        canDismissSheet: Boolean = false,
        isMigration: Boolean = false,
    ) = AuthScreenState(
        mode = mode,
        pinLength = pinLength,
        isMigration = isMigration,
        canDismissSheet = canDismissSheet,
        lockoutSecondsRemaining = lockoutSecondsRemaining,
        isLoading = isLoading,
        error = error,
    )

    // ---------------------------------------------------------------------------------------
    // Digit entry bounds
    // ---------------------------------------------------------------------------------------

    @Test
    fun `digits are appended one slot at a time up to the pin length`() {
        for (count in 0 until pinLength) {
            val accepted = AuthKeypad.onDigit(state(pinLength = count), count)
            assertNotNull("digit $count must be accepted", accepted)
            assertEquals(count, accepted!!.writeIndex)
            assertEquals(count + 1, accepted.state.pinLength)
        }
    }

    @Test
    fun `a digit past the pin length is ignored`() {
        // The buffer is exactly PIN_LENGTH chars; accepting a 7th would be an out-of-bounds write.
        assertNull(AuthKeypad.onDigit(state(pinLength = pinLength), pinLength))
        assertNull(AuthKeypad.onDigit(state(pinLength = pinLength), pinLength + 1))
    }

    @Test
    fun `only the last digit asks for a submit`() {
        for (count in 0 until pinLength - 1) {
            assertFalse(
                "digit ${count + 1} of $pinLength must not submit",
                AuthKeypad.onDigit(state(pinLength = count), count)!!.submit,
            )
        }
        assertTrue(AuthKeypad.onDigit(state(pinLength = pinLength - 1), pinLength - 1)!!.submit)
    }

    @Test
    fun `typing clears a stale error`() {
        val accepted = AuthKeypad.onDigit(state(error = "Incorrect PIN"), 0)!!
        assertNull(accepted.state.error)
    }

    // ---------------------------------------------------------------------------------------
    // Backspace bounds
    // ---------------------------------------------------------------------------------------

    @Test
    fun `backspace at zero length is a no-op`() {
        // Not "back out of the mode", not "clear index -1" — nothing at all.
        assertNull(AuthKeypad.onBackspace(state(pinLength = 0), 0))
    }

    @Test
    fun `backspace clears the slot the last digit went into`() {
        val accepted = AuthKeypad.onBackspace(state(pinLength = 3), 3)!!
        assertEquals(2, accepted.clearIndex)
        assertEquals(2, accepted.state.pinLength)
    }

    @Test
    fun `backspace leaves a standing error alone`() {
        // Taking a digit back is not the same gesture as starting a fresh attempt, so the verdict
        // on the previous attempt stays on screen.
        val accepted = AuthKeypad.onBackspace(state(pinLength = 2, error = "Incorrect PIN"), 2)!!
        assertEquals("Incorrect PIN", accepted.state.error)
    }

    // ---------------------------------------------------------------------------------------
    // The lockout countdown
    // ---------------------------------------------------------------------------------------

    @Test
    fun `digits are rejected while a lockout countdown is running`() {
        assertNull(AuthKeypad.onDigit(state(lockoutSecondsRemaining = 30), 0))
        assertNull(AuthKeypad.onDigit(state(pinLength = 3, lockoutSecondsRemaining = 1), 3))
    }

    @Test
    fun `backspace is rejected while a lockout countdown is running`() {
        assertNull(AuthKeypad.onBackspace(state(pinLength = 3, lockoutSecondsRemaining = 30), 3))
    }

    @Test
    fun `input resumes the moment the lockout countdown reaches zero`() {
        assertNotNull(AuthKeypad.onDigit(state(lockoutSecondsRemaining = 0), 0))
    }

    @Test
    fun `a lockout does not trap the user in the keypad sheet`() {
        // Typing is refused during a lockout, but backing out to the biometric landing is not —
        // the countdown is a rate limit on attempts, not on navigation.
        val decision = AuthKeypad.onDismiss(
            state(mode = AuthMode.ENTER_PIN, lockoutSecondsRemaining = 30),
            biometricLandingAvailable = true,
        )
        assertNotNull(decision)
        assertEquals(AuthMode.BIOMETRIC, decision!!.state.mode)
    }

    // ---------------------------------------------------------------------------------------
    // The "Checking..." window — the guard that all three buffer-touching paths must share
    // ---------------------------------------------------------------------------------------

    @Test
    fun `every buffer touching entry point is refused while a derivation is in flight`() {
        // The invariant the ViewModel used to assert only in a comment ("MUST match the isLoading
        // guard in onDigit/onBackspace"). While the KDF holds the PIN on a background thread, a
        // write would corrupt the derivation and a wipe would zero it mid-flight; in CONFIRM_PIN
        // that persists a wrap for a PIN the user can never type again.
        for (mode in AuthMode.entries) {
            val inFlight = state(mode = mode, pinLength = 3, isLoading = true)
            assertNull("onDigit accepted input in $mode", AuthKeypad.onDigit(inFlight, 3))
            assertNull("onBackspace accepted input in $mode", AuthKeypad.onBackspace(inFlight, 3))
            assertNull(
                "onDismiss accepted input in $mode",
                AuthKeypad.onDismiss(inFlight, biometricLandingAvailable = true),
            )
            assertFalse(AuthKeypad.acceptsKeypadInput(inFlight))
            assertFalse(AuthKeypad.canAbandonEntry(inFlight))
        }
    }

    @Test
    fun `starting a verification closes the keypad to further input`() {
        // The whole chain, not just the flag: submitting really does produce a state that refuses
        // the next tap.
        val checking = AuthKeypad.onVerificationStarted(state(mode = AuthMode.ENTER_PIN, pinLength = pinLength))
        assertTrue(checking.isLoading)
        assertNull(AuthKeypad.onDigit(checking, 0))
        assertNull(AuthKeypad.onBackspace(checking, 0))
        assertNull(AuthKeypad.onDismiss(checking, biometricLandingAvailable = true))
    }

    @Test
    fun `starting a verification clears the previous attempt's error`() {
        val checking = AuthKeypad.onVerificationStarted(state(error = "Incorrect PIN"))
        assertNull(checking.error)
    }

    // ---------------------------------------------------------------------------------------
    // Submit routing
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a full buffer in set-pin mode is held for confirmation`() {
        val decision = AuthKeypad.onSubmit(state(mode = AuthMode.SET_PIN, pinLength = pinLength))
        assertTrue(decision is SubmitDecision.HoldForConfirm)
        val next = (decision as SubmitDecision.HoldForConfirm).state
        assertEquals(AuthMode.CONFIRM_PIN, next.mode)
        assertEquals(0, next.pinLength)
        // Confirm is the one keypad step with somewhere to go back to.
        assertTrue(next.canDismissSheet)
    }

    @Test
    fun `a full buffer routes to the right side of the flow`() {
        assertEquals(SubmitDecision.ConfirmPin, AuthKeypad.onSubmit(state(mode = AuthMode.CONFIRM_PIN)))
        assertEquals(SubmitDecision.EnterPin, AuthKeypad.onSubmit(state(mode = AuthMode.ENTER_PIN)))
    }

    @Test
    fun `submit is inert in the modes that own no keypad`() {
        assertEquals(SubmitDecision.None, AuthKeypad.onSubmit(state(mode = AuthMode.LOADING)))
        assertEquals(SubmitDecision.None, AuthKeypad.onSubmit(state(mode = AuthMode.BIOMETRIC)))
    }

    // ---------------------------------------------------------------------------------------
    // Mismatched confirmation
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a mismatched confirmation returns to set-pin with the error`() {
        val outcome = AuthKeypad.onConfirmMismatch(state(mode = AuthMode.CONFIRM_PIN, canDismissSheet = true))
        assertEquals(AuthMode.SET_PIN, outcome.state.mode)
        assertEquals(AuthKeypad.PIN_MISMATCH_ERROR, outcome.state.error)
        // Back at the bottom of the stack: there is nothing behind first-run PIN entry.
        assertFalse(outcome.state.canDismissSheet)
    }

    @Test
    fun `a mismatched confirmation drops both copies of the pin`() {
        // PIN alone would not be enough: the held first entry has to go too, or the next
        // confirmation is compared against a PIN from an attempt the user has already abandoned.
        val outcome = AuthKeypad.onConfirmMismatch(state(mode = AuthMode.CONFIRM_PIN))
        assertEquals(BufferWipe.PIN_AND_FIRST, outcome.wipe)
        assertEquals(0, outcome.state.pinLength)
    }

    // ---------------------------------------------------------------------------------------
    // Dismissing the sheet
    // ---------------------------------------------------------------------------------------

    @Test
    fun `dismissing the confirm step goes back to set-pin and drops both copies`() {
        val outcome = AuthKeypad.onDismiss(
            state(mode = AuthMode.CONFIRM_PIN, pinLength = 3, canDismissSheet = true),
            biometricLandingAvailable = false,
        )!!
        assertEquals(AuthMode.SET_PIN, outcome.state.mode)
        assertEquals(BufferWipe.PIN_AND_FIRST, outcome.wipe)
        assertEquals(0, outcome.state.pinLength)
        // A cancel is not a failure: no error is raised.
        assertNull(outcome.state.error)
    }

    @Test
    fun `dismissing the keypad returns to the biometric landing`() {
        val outcome = AuthKeypad.onDismiss(
            state(mode = AuthMode.ENTER_PIN, pinLength = 4, canDismissSheet = true),
            biometricLandingAvailable = true,
        )!!
        assertEquals(AuthMode.BIOMETRIC, outcome.state.mode)
        assertEquals(BufferWipe.PIN, outcome.wipe)
        assertEquals(0, outcome.state.pinLength)
        assertFalse(outcome.state.canDismissSheet)
    }

    @Test
    fun `dismissing the keypad is refused when there is no landing behind it`() {
        // Without a biometric landing the keypad IS the screen; dismissing would leave nothing up.
        assertNull(
            AuthKeypad.onDismiss(
                state(mode = AuthMode.ENTER_PIN, pinLength = 4),
                biometricLandingAvailable = false,
            ),
        )
    }

    @Test
    fun `the modes at the bottom of the stack cannot be dismissed`() {
        for (mode in listOf(AuthMode.LOADING, AuthMode.BIOMETRIC, AuthMode.SET_PIN)) {
            assertNull(
                "$mode should not be dismissable",
                AuthKeypad.onDismiss(state(mode = mode), biometricLandingAvailable = true),
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // Opening the screen
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a first run opens on set-pin with nothing to dismiss back to`() {
        val outcome = AuthKeypad.onInitialize(
            state(mode = AuthMode.LOADING),
            AuthInitSnapshot(pinSet = false, isMigration = false, biometricReady = false, lockoutRemaining = 0L),
        )
        assertEquals(AuthMode.SET_PIN, outcome.state.mode)
        assertFalse(outcome.state.canDismissSheet)
        assertFalse(outcome.state.isMigration)
    }

    @Test
    fun `a migration opens on set-pin flagged as a migration`() {
        val outcome = AuthKeypad.onInitialize(
            state(mode = AuthMode.LOADING),
            AuthInitSnapshot(pinSet = false, isMigration = true, biometricReady = false, lockoutRemaining = 0L),
        )
        assertEquals(AuthMode.SET_PIN, outcome.state.mode)
        assertTrue(outcome.state.isMigration)
    }

    @Test
    fun `a returning user lands on biometric when it is ready and on the keypad when it is not`() {
        val withBiometric = AuthKeypad.onInitialize(
            state(mode = AuthMode.LOADING),
            AuthInitSnapshot(pinSet = true, isMigration = false, biometricReady = true, lockoutRemaining = 0L),
        )
        assertEquals(AuthMode.BIOMETRIC, withBiometric.state.mode)

        val withoutBiometric = AuthKeypad.onInitialize(
            state(mode = AuthMode.LOADING),
            AuthInitSnapshot(pinSet = true, isMigration = false, biometricReady = false, lockoutRemaining = 0L),
        )
        assertEquals(AuthMode.ENTER_PIN, withoutBiometric.state.mode)
        // The keypad is the landing in this case, so there is nothing behind it either.
        assertFalse(withoutBiometric.state.canDismissSheet)
    }

    @Test
    fun `use pin instead opens a keypad that can be dismissed back to the landing`() {
        val outcome = AuthKeypad.onUsePinInstead(state(mode = AuthMode.BIOMETRIC))
        assertEquals(AuthMode.ENTER_PIN, outcome.state.mode)
        assertTrue(outcome.state.canDismissSheet)
        assertEquals(BufferWipe.PIN, outcome.wipe)
    }

    // ---------------------------------------------------------------------------------------
    // Journeys, driven with a real buffer
    // ---------------------------------------------------------------------------------------

    @Test
    fun `set-pin then a mismatch resets and lets the user start over`() {
        val driver = KeypadDriver(mode = AuthMode.SET_PIN)

        driver.type("123456")
        assertEquals(AuthMode.CONFIRM_PIN, driver.state.mode)
        assertEquals(0, driver.state.pinLength)

        driver.type("123457")
        assertEquals(AuthMode.SET_PIN, driver.state.mode)
        assertEquals(AuthKeypad.PIN_MISMATCH_ERROR, driver.state.error)

        // The retry must start from nothing. A surviving digit here would silently prepend itself
        // to the next PIN — the user would set a PIN they never typed.
        assertEquals(0, driver.state.pinLength)
        assertTrue("pin buffer survived the mismatch", driver.pinBufferIsZeroed())
        assertNull("first-entry copy survived the mismatch", driver.firstPin)

        // And the retry itself behaves like a fresh first entry.
        driver.type("999999")
        assertEquals(AuthMode.CONFIRM_PIN, driver.state.mode)
        driver.type("999999")
        assertTrue("a matching pair should reach the KDF", driver.state.isLoading)
    }

    @Test
    fun `cancelling the confirm step leaves no pin behind`() {
        val driver = KeypadDriver(mode = AuthMode.SET_PIN)
        driver.type("123456")
        driver.type("12")

        driver.dismiss()

        assertEquals(AuthMode.SET_PIN, driver.state.mode)
        assertEquals(0, driver.state.pinLength)
        assertTrue("pin buffer survived the cancel", driver.pinBufferIsZeroed())
        assertNull("first-entry copy survived the cancel", driver.firstPin)
    }

    @Test
    fun `cancelling back to the biometric landing leaves no pin behind`() {
        val driver = KeypadDriver(mode = AuthMode.ENTER_PIN, biometricLandingAvailable = true)
        driver.type("1234")

        driver.dismiss()

        assertEquals(AuthMode.BIOMETRIC, driver.state.mode)
        assertTrue("pin buffer survived the cancel", driver.pinBufferIsZeroed())
    }

    @Test
    fun `taps land nowhere once the sixth digit has been submitted`() {
        val driver = KeypadDriver(mode = AuthMode.ENTER_PIN, biometricLandingAvailable = true)
        driver.type("123456")

        // The "Checking..." window: the buffer has been handed to the KDF, so nothing may touch it.
        assertTrue(driver.state.isLoading)
        assertFalse("a digit was accepted mid-derivation", driver.type("7"))
        assertFalse("a backspace was accepted mid-derivation", driver.backspace())
        assertFalse("a dismiss was accepted mid-derivation", driver.dismiss())
    }

    @Test
    fun `the seventh digit never reaches the buffer`() {
        val driver = KeypadDriver(mode = AuthMode.SET_PIN)
        driver.type("12345")
        assertTrue(driver.type("6"))
        // The sixth digit moved the flow on to CONFIRM_PIN and emptied the buffer, so a seventh
        // tap is simply the first digit of the confirmation rather than an overflow.
        assertEquals(AuthMode.CONFIRM_PIN, driver.state.mode)
        assertEquals(0, driver.state.pinLength)
    }

    @Test
    fun `backspace walks the buffer back down to empty and then stops`() {
        val driver = KeypadDriver(mode = AuthMode.ENTER_PIN)
        driver.type("12345")

        repeat(5) { assertTrue(driver.backspace()) }
        assertEquals(0, driver.state.pinLength)
        assertTrue("backspace left digits behind", driver.pinBufferIsZeroed())
        assertFalse("backspace on an empty buffer should be ignored", driver.backspace())
    }

    /**
     * A PIN buffer plus screen state, applying [AuthKeypad]'s decisions exactly the way
     * `AuthViewModel` does — same write/wipe order, same routing.
     *
     * It stands in for the ViewModel's coroutine and Keystore work so journeys can be driven on the
     * JVM. What it proves is the *obligations* the decisions carry: a transition that stopped
     * demanding a wipe would leave digits in [pinBuffer] here, exactly as it would in the real
     * buffer. It does not, and cannot, prove the ViewModel's own array handling — that stays in the
     * ViewModel with the secret, by design.
     */
    private inner class KeypadDriver(
        mode: AuthMode,
        private val biometricLandingAvailable: Boolean = false,
    ) {
        var state: AuthScreenState = state(mode = mode)
            private set

        private val pinBuffer = CharArray(pinLength)
        private var pinCount = 0

        var firstPin: CharArray? = null
            private set

        /** True when every slot of the PIN buffer holds NUL, i.e. nothing readable is left in it. */
        fun pinBufferIsZeroed(): Boolean = pinBuffer.all { it == nul }

        /** @return false when the tap was ignored. */
        fun digit(c: Char): Boolean {
            val accepted = AuthKeypad.onDigit(state, pinCount) ?: return false
            pinBuffer[accepted.writeIndex] = c
            pinCount = accepted.writeIndex + 1
            state = accepted.state
            if (accepted.submit) submit()
            return true
        }

        /** @return false as soon as one of the digits is ignored. */
        fun type(digits: String): Boolean = digits.all { digit(it) }

        /** @return false when the tap was ignored. */
        fun backspace(): Boolean {
            val accepted = AuthKeypad.onBackspace(state, pinCount) ?: return false
            pinBuffer[accepted.clearIndex] = nul
            pinCount = accepted.clearIndex
            state = accepted.state
            return true
        }

        /** @return false when the dismiss was ignored. */
        fun dismiss(): Boolean {
            val outcome = AuthKeypad.onDismiss(state, biometricLandingAvailable) ?: return false
            abandon(outcome.wipe, outcome.state)
            return true
        }

        private fun submit() {
            when (val decision = AuthKeypad.onSubmit(state)) {
                is SubmitDecision.HoldForConfirm -> {
                    firstPin?.fill(nul)
                    firstPin = pinBuffer.copyOf(pinCount)
                    reset()
                    state = decision.state
                }

                SubmitDecision.ConfirmPin -> {
                    val first = firstPin
                    val matches = first != null && first.size == pinCount &&
                        (0 until pinCount).all { first[it] == pinBuffer[it] }
                    if (!matches) {
                        val outcome = AuthKeypad.onConfirmMismatch(state)
                        abandon(outcome.wipe, outcome.state)
                    } else {
                        // The ViewModel hands a copy to the KDF here; the driver only needs the
                        // state change, which is what closes the keypad to further input.
                        state = AuthKeypad.onVerificationStarted(state)
                        reset()
                    }
                }

                SubmitDecision.EnterPin -> {
                    state = AuthKeypad.onVerificationStarted(state)
                    reset()
                }

                SubmitDecision.None -> Unit
            }
        }

        /** Mirrors `AuthViewModel.applyAbandon`: wipe what the decision names, then publish. */
        private fun abandon(wipe: BufferWipe, next: AuthScreenState) {
            when (wipe) {
                BufferWipe.PIN -> reset()
                BufferWipe.PIN_AND_FIRST -> {
                    reset()
                    firstPin?.fill(nul)
                    firstPin = null
                }
            }
            state = next
        }

        private fun reset() {
            pinBuffer.fill(nul)
            pinCount = 0
        }
    }
}
