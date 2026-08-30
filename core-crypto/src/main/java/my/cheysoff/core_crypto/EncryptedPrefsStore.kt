package my.cheysoff.core_crypto

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The encrypted preferences file that holds every wrap of the database passphrase, behind the
 * narrowest interface that describes it: open it, or throw away.
 *
 * The split is not cosmetic. [SecureUnlockManager.openPrefs] has to decide, from a failure to open
 * this file, whether the Keystore key behind it is gone forever — a verdict that deletes the user's
 * entire database when it says yes and crash-loops the app when it wrongly says no. That decision
 * is ordinary branching over a retry budget, a launch counter and [KeyLossPolicy]; the only reason
 * it was untestable is that it sat in the same method as the two lines that actually touch
 * `MasterKey` and `EncryptedSharedPreferences`, and those need a real hardware Keystore. Naming
 * that boundary separates a policy that can be verified from a mechanism that cannot, the same
 * separation [LockoutPolicy] and [KeyLossPolicy] already make for their own decisions.
 *
 * There is exactly one production implementation, [KeystoreEncryptedPrefsStore]. It holds the
 * `MasterKey`, the file name and the encryption schemes — everything about this file that is a
 * fact about Android rather than a decision this app makes.
 */
interface EncryptedPrefsStore {

    /**
     * Open the encrypted preferences, creating the file and its keyset if they do not exist yet.
     *
     * Throws rather than returning null on failure, because the caller's whole job is to
     * CLASSIFY that failure: [KeyLossPolicy.isProvableKeyLoss] reads the exception type and walks
     * its cause chain, and a null would throw that evidence away.
     */
    fun open(): SharedPreferences

    /**
     * Delete the encrypted preferences file outright.
     *
     * Called only after [SecureUnlockManager] has concluded the keyset is unrecoverable. Every
     * wrap of the database passphrase goes with it, so the database does too — see
     * [SecureUnlockManager.wasStateReset].
     */
    fun discard()
}

/**
 * The real store: `EncryptedSharedPreferences` under an AES256-GCM `MasterKey` from the Android
 * Keystore.
 *
 * The file name and both encryption schemes are the ones the pre-secure-unlock key manager used,
 * and must stay that way: an existing install's `db_passphrase` has to be readable in place so
 * [SecureUnlockManager.setupPin] can migrate it instead of generating a new passphrase over the
 * top of an encrypted database it would then be unable to open.
 */
@Singleton
class KeystoreEncryptedPrefsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : EncryptedPrefsStore {

    // Lazy because building a MasterKey touches the Keystore, and this @Singleton is reachable
    // from Application.onCreate; an eager initializer would do that work on the main thread during
    // startup. (SecureUnlockManager's `prefs` is lazy for the same reason, one layer up.)
    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    override fun open(): SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun discard() {
        context.deleteSharedPreferences(PREFS_NAME)
    }

    private companion object {
        /**
         * On-disk name of the encrypted prefs file. Existing installs already have a file with
         * this exact name holding their only copy of the database passphrase, so it must never
         * change — renaming it would look exactly like a fresh install to every current user.
         */
        const val PREFS_NAME = "secret_shared_prefs"
    }
}
