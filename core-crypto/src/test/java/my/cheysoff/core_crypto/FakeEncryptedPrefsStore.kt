package my.cheysoff.core_crypto

import android.content.Context
import android.content.SharedPreferences

/**
 * Stands in for [KeystoreEncryptedPrefsStore] in JVM tests.
 *
 * The real store is the one part of the unlock path that cannot run here: Robolectric has no
 * `AndroidKeyStore` provider (`KeyStore.getInstance("AndroidKeyStore")` throws
 * `KeyStoreException: AndroidKeyStore not found`), so `MasterKey.Builder(...).build()` — and with
 * it `EncryptedSharedPreferences.create` — fails before returning anything. That was verified
 * against Robolectric 4.16 at API 35 before this fake was written; it is not an assumption.
 *
 * What is faked is ONLY the encryption. The prefs handed back are a real Robolectric-backed
 * [SharedPreferences] with the same read/write/commit semantics the encrypted file has, so every
 * key, value and edit ordering SecureUnlockManager relies on is exercised for real. Nothing in
 * SecureUnlockManager depends on the file being encrypted — it depends on the file existing,
 * persisting, and throwing when the keyset behind it is unusable, and all three are reproduced
 * here.
 *
 * [failuresQueued] and [failure] reproduce the third of those: an open that fails. That is the
 * behaviour the launch-counter logic exists for and the only way to reach it, since a real
 * Keystore cannot be told to break on demand.
 */
class FakeEncryptedPrefsStore(
    private val context: Context,
) : EncryptedPrefsStore {

    /**
     * Name of the plain prefs file standing in for `secret_shared_prefs`. Deliberately different
     * so that nothing in a test can accidentally assert against the production file name and pass
     * for the wrong reason.
     */
    private val fileName = "fake_secret_shared_prefs"

    /** How many more calls to [open] must throw before one is allowed to succeed. */
    var failuresQueued: Int = 0

    /** Builds the exception [open] throws while [failuresQueued] is positive. */
    var failure: () -> Exception = { java.security.GeneralSecurityException("simulated open failure") }

    /** Total [open] calls, successful or not. SecureUnlockManager's retry budget is counted here. */
    var openCount: Int = 0
        private set

    /** Total [discard] calls. Exactly one of these destroys the user's database. */
    var discardCount: Int = 0
        private set

    override fun open(): SharedPreferences {
        openCount++
        if (failuresQueued > 0) {
            failuresQueued--
            throw failure()
        }
        return prefs()
    }

    override fun discard() {
        discardCount++
        context.deleteSharedPreferences(fileName)
    }

    /**
     * The same [SharedPreferences] instance [open] returns, for tests to seed fixtures into and
     * assert against. Reading it does NOT count as an [open].
     */
    fun prefs(): SharedPreferences = context.getSharedPreferences(fileName, Context.MODE_PRIVATE)

    /**
     * Empty the file without counting a [discard].
     *
     * For @Before, so that a test starts from a genuinely fresh install regardless of what
     * Robolectric does or does not carry between test methods. Kept distinct from [discard] because
     * [discardCount] is an assertion target: exactly one discard is the difference between "the
     * app recovered" and "every note is gone".
     */
    fun clearFile() {
        prefs().edit().clear().commit()
    }
}
