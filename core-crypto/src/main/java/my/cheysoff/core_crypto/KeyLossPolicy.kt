package my.cheysoff.core_crypto

/**
 * Decides, from a failure to open the encrypted prefs, whether the Keystore key protecting them is
 * provably unrecoverable. Pure and deterministic so it can be unit-tested off-device — the same
 * reason [LockoutPolicy] is its own object. The verdict destroys every note when it is wrong in
 * one direction and crash-loops the app when it is wrong in the other, and it used to live inline
 * in SecureUnlockManager with no test at all; both bugs this file exists to prevent were shipped
 * that way.
 */
internal object KeyLossPolicy {

    /**
     * True only for failures that prove, ON FIRST SIGHTING, that retrying could never succeed.
     *
     * This is deliberately NOT a complete list of terminal errors. It is the list of errors whose
     * terminality follows from the type alone; everything else has to earn the same verdict the
     * slow way, by failing several launches in a row (see SecureUnlockManager.openPrefs).
     *
     *  - [javax.crypto.AEADBadTagException]: the GCM tag on the stored Tink keyset does not
     *    authenticate under the key the Keystore now holds. Same bytes, same key, every retry —
     *    deterministic. This is the signature of the case the reset path exists for: a restore
     *    brings back `secret_shared_prefs` but not the non-exportable master key, so
     *    MasterKey.Builder mints a fresh one that cannot unwrap the restored keyset.
     *  - [android.security.keystore.KeyPermanentlyInvalidatedException]: by contract the key is
     *    invalidated for good. Defensive only for the prefs key: Android raises it for auth-bound
     *    keys, and that MasterKey never calls setUserAuthenticationRequired. The auth-bound key in
     *    this app is BiometricKeystoreCipher's, and its caller handles this itself.
     *
     * Notably ABSENT is [java.security.KeyStoreException]. Tink raises it for its terminal
     * "the master key %s exists but is unusable" case — but also for trouble that clears on its
     * own, and the type carries nothing that separates the two. Treating it as terminal wipes
     * notes over a transient fault; treating it as transient (which is what this does) crash-loops
     * until the launch counter concludes otherwise. Matching Tink's message string instead would
     * bind this decision to a library's wording across upgrades.
     *
     * Equally absent is a bare [java.security.GeneralSecurityException]. An earlier version
     * accepted it, and since KeyStoreException, InvalidKeyException and AEADBadTagException all
     * extend it, the predicate collapsed into "any crypto error at all" — and that path deletes
     * the whole database with no retry.
     *
     * Walks the cause chain because these arrive wrapped by Tink.
     */
    fun isProvableKeyLoss(error: Throwable): Boolean {
        var e: Throwable? = error
        // Cause chains are supposed to be acyclic, but a self-referential cause would spin here
        // forever on a path that is already handling corruption, so bound the walk.
        var hops = 0
        while (e != null && hops < MAX_CAUSE_DEPTH) {
            if (e is javax.crypto.AEADBadTagException ||
                e is android.security.keystore.KeyPermanentlyInvalidatedException
            ) return true
            e = e.cause
            hops++
        }
        return false
    }

    private const val MAX_CAUSE_DEPTH = 32
}
