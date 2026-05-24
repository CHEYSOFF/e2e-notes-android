package my.cheysoff.core_crypto

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EncryptionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private var _wasPassphraseReset = false
    val wasPassphraseReset: Boolean get() = _wasPassphraseReset

    private var cachedPassphrase: ByteArray? = null

    // When the app is backgrounded we clear the cache and must NOT let an in-flight
    // pre-warm (or any concurrent call) repopulate it. Reset on foreground.
    @Volatile
    private var isBackgrounded = false

    private val sharedPreferences: SharedPreferences by lazy {
        try {
            createSharedPreferences()
        } catch (e: Exception) {
            // Only treat genuine key loss (e.g. Keystore wiped after a restore) as a reset.
            // Transient/non-crypto failures must NOT clear prefs or flag a DB wipe — rethrow.
            if (!isKeyLoss(e)) throw e
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit(commit = true) {
                clear()
            }
            _wasPassphraseReset = true
            createSharedPreferences()
        }
    }

    private fun createSharedPreferences(): SharedPreferences {
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Gets or generates a random passphrase for the database.
     * This passphrase is stored securely in EncryptedSharedPreferences.
     */
    @Synchronized
    fun getDatabasePassphrase(): ByteArray {
        cachedPassphrase?.let { return it.copyOf() }

        val key = PASSPHRASE_KEY
        val existingPassphrase = try {
            sharedPreferences.getString(key, null)
        } catch (e: Exception) {
            // Only a genuine decryption/key-loss failure (e.g. after restore) should trigger
            // regeneration + DB reset. Transient/non-crypto errors must NOT wipe the database.
            if (!isKeyLoss(e)) throw e
            _wasPassphraseReset = true
            null
        }

        val result = if (existingPassphrase != null) {
            android.util.Base64.decode(existingPassphrase, android.util.Base64.DEFAULT)
        } else {
            // If we are generating a new passphrase but a database file already exists,
            // it means the old passphrase was lost (e.g. after restore or clearing prefs).
            if (context.getDatabasePath(DATABASE_NAME).exists()) {
                _wasPassphraseReset = true
            }

            val newPassphrase = ByteArray(32)
            SecureRandom().nextBytes(newPassphrase)
            val encoded =
                android.util.Base64.encodeToString(newPassphrase, android.util.Base64.DEFAULT)
            sharedPreferences.edit(commit = true) { putString(key, encoded) }
            newPassphrase
        }

        // Don't repopulate the cache if we've been backgrounded (race with clearCache).
        if (!isBackgrounded) {
            cachedPassphrase = result
        }
        return result.copyOf()
    }

    /**
     * Pre-warms the passphrase by generating it if it doesn't exist.
     * This should be called from a background thread to avoid blocking the main thread.
     */
    fun preWarmPassphrase() {
        getDatabasePassphrase()
    }

    /**
     * Clears the cached passphrase from memory by zeroing out the byte array.
     */
    @Synchronized
    fun clearCache() {
        isBackgrounded = true
        cachedPassphrase?.fill(0)
        cachedPassphrase = null
    }

    /**
     * Re-enables passphrase caching when the app returns to the foreground.
     */
    @Synchronized
    fun onForeground() {
        isBackgrounded = false
    }

    /**
     * True only for exceptions indicating the encryption key itself is gone or unusable
     * (Keystore wiped, backup/restore, decryption failure) — NOT transient I/O or other
     * runtime errors, which must not trigger a passphrase reset / database wipe.
     * Walks the cause chain because EncryptedSharedPreferences often wraps crypto errors.
     */
    private fun isKeyLoss(error: Throwable): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is GeneralSecurityException || cause is SecurityException) return true
            cause = cause.cause
        }
        return false
    }

    companion object {
        private const val PREFS_NAME = "secret_shared_prefs"
        private const val PASSPHRASE_KEY = "db_passphrase"
        const val DATABASE_NAME = "notes.db"
    }
}
