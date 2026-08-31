package my.cheysoff.desktop.vault

/**
 * Why this app asks a desktop for a passphrase and a phone for a six-digit PIN.
 *
 * ## The phone
 *
 * On Android the PIN-derived key never guards the passphrase on its own. `PassphraseCipher` wraps
 * the database passphrase under PBKDF2(PIN), and that wrap is stored in `EncryptedSharedPreferences`
 * — a file encrypted under a master key that lives in the hardware Keystore and **cannot be
 * exported**. Copying the file off the device yields ciphertext whose key is not in it. The only
 * way to attack the PIN is to guess it on the device, one attempt at a time, through
 * `LockoutPolicy`. Six digits is enough because 10^6 guesses at one per lockout window is not a
 * search anyone finishes.
 *
 * ## The desktop
 *
 * There is no such anchor here. `vault.json` is an ordinary file: copy it to any machine and every
 * input to the derivation is in your hands. The whole cost of a guess is then the PBKDF2 work
 * factor, and that is a *linear* cost against an exponentially small keyspace.
 *
 * The arithmetic is worth writing down because it is worse than it sounds. PBKDF2-HMAC-SHA256 at
 * `i` iterations costs `2i` SHA-256 compressions per guess. A single current consumer GPU does on
 * the order of 2×10^10 SHA-256/s, so at the Android app's 210 000 iterations it tests roughly
 * 5×10^4 passphrases per second, and at this module's [ITERATIONS] roughly 1.6×10^4. Against a
 * six-digit PIN's 10^6 candidates that is **about twenty seconds** at 210 000 iterations and about
 * a minute at 600 000. Not hours. There is no iteration count that fixes a keyspace that small:
 * making the PIN survive a day of one GPU would need ~10^9 iterations, which is minutes of unlock
 * time on the user's own machine.
 *
 * So the fix is not a bigger work factor, it is a bigger secret — which is what a passphrase is,
 * and why this module has no PIN entry at all.
 *
 * ## What these rules do and do not claim
 *
 * They are **shape** rules, not entropy estimates, and they cannot be otherwise: nothing here can
 * tell "correcthorsebatterystaple" from a user's street address, and pretending to score a
 * passphrase would give a false reading far more often than a useful one. What they do is exclude
 * the two shapes that are provably beyond rescue by any work factor:
 *
 *  - shorter than [MIN_LENGTH] characters, and
 *  - all digits — a PIN with extra digits, which is the exact mistake this design exists to stop.
 *    A 12-digit all-numeric passphrase is 10^12 candidates: about two years on the single GPU
 *    above, and about a week on a rack of fifty. A 12-character passphrase drawn from more than
 *    digits is not automatically safe either, and the first-run screen says so in words rather
 *    than pretending this check has verified anything.
 */
object PassphrasePolicy {

    /**
     * Iterations for the desktop passphrase wrap: 600 000, against the Android app's 210 000.
     *
     * Higher, and here is the reasoning either way, as the two are genuinely close.
     *
     * *For raising it*: on Android the iteration count is the second line of defence — the Keystore
     * and `LockoutPolicy` are the first, and they are the ones doing the work. Here it is the
     * **only** cost an offline attacker pays, so it should be as high as the user will tolerate.
     * 600 000 is also the current OWASP figure for PBKDF2-HMAC-SHA256; 210 000 is the previous one,
     * and the phone is on it because raising it there would mean re-wrapping every existing
     * install's key.
     *
     * *Against raising it*: it buys a factor of 2.9, which is nothing against a weak passphrase and
     * unnecessary against a strong one. The honest claim for this number is narrow — it is not what
     * makes the vault safe, the passphrase is — but it costs one third of a second once per launch
     * and there is no reason to leave it on the table.
     *
     * Measured on the development machine (JDK 17, SunJCE, an ordinary desktop CPU): ~330 ms per
     * derivation. That is the delay between pressing Unlock and the window opening, once.
     *
     * Changing this number does **not** strand an existing vault: `PinWrap` stores the iteration
     * count it was created with and `PassphraseCipher.unwrapWithPin` derives with `wrap.iterations`,
     * so an old vault keeps opening at its old cost and only a re-wrap moves it.
     */
    const val ITERATIONS = 600_000

    /** Minimum passphrase length in characters. */
    const val MIN_LENGTH = 12

    /** Why a proposed passphrase was refused, or [Accepted]. */
    sealed interface Verdict {
        data object Accepted : Verdict
        data object TooShort : Verdict
        data object AllDigits : Verdict
    }

    /**
     * Checks the shape of [passphrase].
     *
     * Takes a `CharArray` rather than a `String` so the caller can zero it: a `String` cannot be
     * cleared and lives in the heap until it is collected, which is the same reason
     * `PassphraseCipher` takes one.
     */
    fun check(passphrase: CharArray): Verdict = when {
        passphrase.size < MIN_LENGTH -> Verdict.TooShort
        // `all` on an empty array is true, but an empty array is already TooShort above.
        passphrase.all { it in '0'..'9' } -> Verdict.AllDigits
        else -> Verdict.Accepted
    }
}
