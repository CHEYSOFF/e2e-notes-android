package my.cheysoff.notes.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The device id the **server** assigned this device on one account.
 *
 * ## Two different device ids, and conflating them breaks sync silently
 *
 * `SecureUnlockManager.deviceId()` is a locally generated random string that never leaves the
 * device; it is the salt the HLC node pseudonym is derived from. The value stored here is
 * server-assigned, comes back from `POST /v1/account` or `POST /v1/devices`, and is meaningless to
 * anything but that server. The phase-3 plan's decision D4 says to keep both and not to conflate
 * them, and the failure if they are conflated is not a crash: every authenticated request is
 * rejected as an unknown device, forever.
 *
 * ## Keyed by account
 *
 * An install that re-pairs onto a different account is a different device to that account's server
 * and holds a different id. Storing one unkeyed value would have the new account inherit the old
 * one's, which authenticates as nothing.
 *
 * ## Not a secret
 *
 * The id is a public handle; what proves this device is the ECDSA key in the AndroidKeyStore, which
 * is non-exportable and is not here. So ordinary preferences rather than `secret_shared_prefs`,
 * beside the server address it is useless without.
 */
@Singleton
class SyncEnrolmentStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** The server device id for [accountId], or null if this device has not enrolled on it. */
    suspend fun deviceId(accountId: String): String? =
        context.syncSettingsDataStore.data.first()[key(accountId)]?.takeIf { it.isNotBlank() }

    /** Records the id the server assigned. Called once per account, after a successful claim. */
    suspend fun setDeviceId(accountId: String, deviceId: String) {
        context.syncSettingsDataStore.edit { it[key(accountId)] = deviceId }
    }

    /**
     * The account handle is already an opaque base64url string derived from the ARK, so it can go
     * into a preference key as it stands — but it is prefixed rather than used bare so that this
     * file cannot collide with `sync_server_url` or with whatever is added next.
     */
    private fun key(accountId: String) = stringPreferencesKey("sync_device_id/$accountId")
}
