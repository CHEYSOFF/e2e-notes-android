package my.cheysoff.feature_auth.model

/**
 * The auth screen's keypad decision logic, extracted out of `AuthViewModel` so it can be exercised
 * on the JVM with plain JUnit — no Android, no Keystore, no coroutines.
 *
 * The split is deliberate and narrow:
 *
 *  - **Here**: which taps are accepted, what [AuthScreenState] each accepted tap produces, which
 *    mode a completed PIN submits into, and — crucially — *which secret buffers the caller must
 *    zero* before publishing that state ([BufferWipe]).
 *  - **In the ViewModel**: the `CharArray` PIN buffer itself, its zeroing, the KDF calls, the
 *    lockout ticker, and the biometric prompt. The secret never enters this file, and no function
 *    below has anywhere to put one — every return type carries an index or a state, never a digit.
 *
 * The reason for the split is the failure mode this logic guards against. The PIN buffer is handed
 * to a background KDF and zeroed in a `finally`; if a keypad tap were accepted *while that
 * derivation is in flight*, it would mutate the array the KDF is reading. In CONFIRM_PIN that
 * persists a database wrap derived from a half-written PIN — a PIN the user can never enter again,
 * with no reset path. The three entry points that touch the buffer therefore have to agree on one
 * guard, and until now they agreed only by three hand-written `if`s that nothing compared. Here
 * they all read [isDerivationInFlight], and `AuthKeypadTest` asserts the agreement.
 */
object AuthKeypad {

    /** Shown after a mismatched confirmation. Named so the test asserts the same string the UI shows. */
    const val PIN_MISMATCH_ERROR = "PINs didn't match. Try again."

    /**
     * True while a PIN has been handed to `setupPin`/`unlockWithPin` on a background thread — the
     * "Checking…" window, roughly the length of one PBKDF2 derivation.
     *
     * This is the single source of the guard that [onDigit], [onBackspace] and [onDismiss] all
     * share. It is the ONE predicate whose meaning is "the buffer is not ours to touch right now",
     * so any new entry point that writes to or zeroes the buffer must consult it too.
     */
    fun isDerivationInFlight(state: AuthScreenState): Boolean = state.isLoading

    /**
     * True when a keypad tap (digit or backspace) may be acted on: no derivation in flight, and no
     * lockout countdown running.
     *
     * The lockout half is deliberately NOT part of [canAbandonEntry] — a locked-out user must
     * still be able to back out of the sheet, they just cannot type.
     */
    fun acceptsKeypadInput(state: AuthScreenState): Boolean =
        !isDerivationInFlight(state) && state.lockoutSecondsRemaining <= 0

    /**
     * True when the entry in progress may be thrown away (sheet dismissed, mode changed).
     *
     * Abandoning means zeroing the buffer, so this shares [isDerivationInFlight] with the typing
     * guard rather than restating it.
     */
    fun canAbandonEntry(state: AuthScreenState): Boolean = !isDerivationInFlight(state)

    /**
     * A digit tap.
     *
     * @param pinCount how many digits the ViewModel's buffer currently holds. Passed explicitly
     *   rather than read from `state.pinLength`: the two are equal whenever a tap is accepted, but
     *   they diverge during the "Checking…" window (the buffer is emptied the moment the KDF gets
     *   its copy, while the dots stay lit until the result arrives), and the buffer's own count is
     *   the one that must bound the write.
     * @return null when the tap is ignored — no buffer write and no state change.
     */
    fun onDigit(state: AuthScreenState, pinCount: Int): DigitAccepted? {
        if (!acceptsKeypadInput(state)) return null
        // At capacity: further digits are dropped rather than overwriting or growing the buffer.
        if (pinCount >= state.pinMaxLength) return null

        val filled = pinCount + 1
        return DigitAccepted(
            writeIndex = pinCount,
            // Typing clears a stale error ("Incorrect PIN", "PINs didn't match") so the user is not
            // reading a verdict on an entry they have already moved past.
            state = state.copy(pinLength = filled, error = null),
            // The keypad has no separate confirm key: the last digit IS the submit.
            submit = filled == state.pinMaxLength,
        )
    }

    /**
     * A backspace tap.
     *
     * @return null when the tap is ignored, including the empty-buffer case — there is no digit to
     *   clear, and the mode is NOT backed out of (that is [onDismiss]'s job).
     */
    fun onBackspace(state: AuthScreenState, pinCount: Int): BackspaceAccepted? {
        if (!acceptsKeypadInput(state)) return null
        if (pinCount == 0) return null

        val remaining = pinCount - 1
        return BackspaceAccepted(
            clearIndex = remaining,
            // The error is deliberately left standing here: backing a digit out is not the same
            // gesture as starting a fresh attempt.
            state = state.copy(pinLength = remaining),
        )
    }

    /**
     * Dismissing the keypad sheet.
     *
     * @param biometricLandingAvailable true when a biometric landing exists to fall back to; when
     *   it does not, ENTER_PIN is the only surface there is and the sheet stays put.
     * @return null when the dismiss is ignored.
     */
    fun onDismiss(state: AuthScreenState, biometricLandingAvailable: Boolean): AbandonEntry? {
        if (!canAbandonEntry(state)) return null

        return when (state.mode) {
            AuthMode.ENTER_PIN -> if (biometricLandingAvailable) {
                AbandonEntry(
                    wipe = BufferWipe.PIN,
                    state = state.copy(
                        mode = AuthMode.BIOMETRIC,
                        pinLength = 0,
                        error = null,
                        canDismissSheet = false,
                    ),
                )
            } else {
                null
            }

            // Backing out of the confirmation step means the first entry is abandoned too,
            // otherwise the next confirmation would be compared against a PIN the user has
            // already walked away from.
            AuthMode.CONFIRM_PIN -> AbandonEntry(
                wipe = BufferWipe.PIN_AND_FIRST,
                state = state.copy(
                    mode = AuthMode.SET_PIN,
                    pinLength = 0,
                    error = null,
                    canDismissSheet = false,
                ),
            )

            // LOADING has nothing to dismiss; BIOMETRIC and SET_PIN are already the bottom of
            // their own stacks (first-run PIN entry has nowhere to go back to).
            else -> null
        }
    }

    /**
     * What a completed PIN means in the current mode. Called only when [DigitAccepted.submit] is
     * set, i.e. exactly once per full buffer.
     */
    fun onSubmit(state: AuthScreenState): SubmitDecision = when (state.mode) {
        // First half of the set-PIN pair: no I/O yet, just hold it and ask again.
        AuthMode.SET_PIN -> SubmitDecision.HoldForConfirm(
            state.copy(
                mode = AuthMode.CONFIRM_PIN,
                pinLength = 0,
                error = null,
                // Unlike SET_PIN, the confirm step has somewhere to go back to.
                canDismissSheet = true,
            ),
        )

        AuthMode.CONFIRM_PIN -> SubmitDecision.ConfirmPin
        AuthMode.ENTER_PIN -> SubmitDecision.EnterPin

        // LOADING/BIOMETRIC own no keypad, so they can never hold a full buffer.
        else -> SubmitDecision.None
    }

    /**
     * The confirmation did not match the first entry: back to SET_PIN, error shown, and BOTH
     * copies of the PIN dropped so the retry starts from nothing.
     *
     * Returning to SET_PIN with a populated buffer would be the worst of the failure modes here —
     * the next six digits would land on top of the old ones and the pair the user believes they
     * typed would not be the pair that gets persisted.
     */
    fun onConfirmMismatch(state: AuthScreenState): AbandonEntry = AbandonEntry(
        wipe = BufferWipe.PIN_AND_FIRST,
        state = state.copy(
            mode = AuthMode.SET_PIN,
            pinLength = 0,
            error = PIN_MISMATCH_ERROR,
            canDismissSheet = false,
        ),
    )

    /** Entering the "Checking…" window. From here until the KDF returns, every tap is refused. */
    fun onVerificationStarted(state: AuthScreenState): AuthScreenState =
        state.copy(isLoading = true, error = null)

    /**
     * "Use PIN instead" on the biometric landing. The landing stays behind the sheet, so this is
     * the one keypad entry that starts out dismissable.
     */
    fun onUsePinInstead(state: AuthScreenState): AbandonEntry = AbandonEntry(
        wipe = BufferWipe.PIN,
        state = state.copy(
            mode = AuthMode.ENTER_PIN,
            pinLength = 0,
            error = null,
            canDismissSheet = true,
        ),
    )

    /**
     * The screen's opening state, from the startup probe in [AuthInitSnapshot].
     *
     * Wipes rather than assumes an empty buffer: this runs after an off-thread probe, and the
     * ViewModel latches it to a single run precisely because a second run could otherwise wipe a
     * PIN already being typed.
     */
    fun onInitialize(state: AuthScreenState, snapshot: AuthInitSnapshot): AbandonEntry = AbandonEntry(
        wipe = BufferWipe.PIN,
        state = if (!snapshot.pinSet) {
            state.copy(
                mode = AuthMode.SET_PIN,
                isMigration = snapshot.isMigration,
                pinLength = 0,
                error = null,
                // First-run PIN entry has nothing to dismiss back to.
                canDismissSheet = false,
            )
        } else {
            state.copy(
                mode = if (snapshot.biometricReady) AuthMode.BIOMETRIC else AuthMode.ENTER_PIN,
                pinLength = 0,
                error = null,
                canDismissSheet = false,
            )
        },
    )
}

/**
 * Which secret buffers the caller must zero before publishing an [AbandonEntry]'s state.
 *
 * There is no "wipe nothing" member on purpose: every transition modelled as an [AbandonEntry] is
 * one that walks away from a PIN in progress, so the type makes the wipe impossible to forget when
 * a new exit path is added.
 */
enum class BufferWipe {
    /** Zero the in-progress PIN buffer. */
    PIN,

    /** Zero the in-progress PIN buffer AND the held first entry from the set-PIN pair. */
    PIN_AND_FIRST,
}

/**
 * A transition that abandons the PIN in progress: perform [wipe], then publish [state].
 *
 * The order matters — the state says "0 digits entered" and the buffer must actually be empty by
 * the time anything can act on that.
 */
data class AbandonEntry(
    val wipe: BufferWipe,
    val state: AuthScreenState,
)

/** An accepted digit tap: write the digit at [writeIndex], publish [state], then submit if asked. */
data class DigitAccepted(
    /** Index in the PIN buffer to write into. Always within `0 until pinMaxLength`. */
    val writeIndex: Int,
    val state: AuthScreenState,
    /** True when this digit filled the buffer, so [AuthKeypad.onSubmit] must run. */
    val submit: Boolean,
)

/** An accepted backspace: zero the digit at [clearIndex], then publish [state]. */
data class BackspaceAccepted(
    /** Index in the PIN buffer to overwrite with NUL — the digit being taken back. */
    val clearIndex: Int,
    val state: AuthScreenState,
)

/** What a full PIN buffer means in the mode that produced it. */
sealed interface SubmitDecision {
    /** SET_PIN: keep this entry as the first half of the pair and publish [state] (no I/O). */
    data class HoldForConfirm(val state: AuthScreenState) : SubmitDecision

    /** CONFIRM_PIN: compare against the held first entry, then persist the wrap. */
    data object ConfirmPin : SubmitDecision

    /** ENTER_PIN: derive and attempt to unlock. */
    data object EnterPin : SubmitDecision

    /** The current mode owns no keypad, so there is nothing to submit. */
    data object None : SubmitDecision
}

/**
 * Result of the auth screen's startup probe, read off the main thread before [AuthKeypad.onInitialize]
 * turns it into a state. Every field is a plain value so the decision itself needs no Keystore.
 */
data class AuthInitSnapshot(
    /** True when a PIN wrap already exists, i.e. this is a returning user. */
    val pinSet: Boolean,

    /** True when the missing PIN is because of a legacy-passphrase migration rather than a fresh install. */
    val isMigration: Boolean,

    /** True when a usable biometric wrap AND usable hardware exist. Implies [pinSet]. */
    val biometricReady: Boolean,

    /** Milliseconds left on a lockout window carried over from a previous session; 0 when free. */
    val lockoutRemaining: Long,
)
