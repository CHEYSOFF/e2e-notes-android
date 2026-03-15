package my.cheysoff.core_crypto

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.content.edit

@Singleton
class EncryptionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences = EncryptedSharedPreferences.create(
        context,
        "secret_shared_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /**
     * Gets or generates a random passphrase for the database.
     * This passphrase is stored securely in EncryptedSharedPreferences.
     */
    fun getDatabasePassphrase(): ByteArray {
        val key = "db_passphrase"
        val existingPassphrase = sharedPreferences.getString(key, null)
        
        return if (existingPassphrase != null) {
            android.util.Base64.decode(existingPassphrase, android.util.Base64.DEFAULT)
        } else {
            val newPassphrase = ByteArray(32)
            SecureRandom().nextBytes(newPassphrase)
            val encoded = android.util.Base64.encodeToString(newPassphrase, android.util.Base64.DEFAULT)
            sharedPreferences.edit(commit = true) { putString(key, encoded) }
            newPassphrase
        }
    }
}
