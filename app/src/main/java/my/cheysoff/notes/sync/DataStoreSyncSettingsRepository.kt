package my.cheysoff.notes.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import my.cheysoff.core_domain.repository.SyncSettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The backing file. See [DataStoreSyncSettingsRepository] for why it is not `manana_settings`.
 */
private val Context.syncSettingsDataStore by preferencesDataStore(name = "manana_sync")

/**
 * The sync server address, in its own preferences file.
 *
 * ## Why it lives in `:app` and in a separate file
 *
 * `:app` is where the sync transport's other two adapters already are — [KeystoreDeviceSigner] and
 * [ArkDeviceLabelSealer] — for the reason both of them spell out: it is the module that can see
 * every side of this seam at once. This setting is read by exactly that code
 * ([DefaultSyncTransportProvider]) and by nothing else, so it sits beside it.
 *
 * A separate DataStore file rather than `manana_settings` is not a preference, it is a
 * requirement: `preferencesDataStore` refuses to have two active instances over one file in a
 * process, and the notes settings' delegate is already open on that name.
 *
 * ## The storage shape
 *
 * One `stringPreferencesKey`, the same shape `notes_sort_order` uses, and for the same reason: a
 * stable string key means an unrecognised or malformed value degrades rather than crashes. Here
 * "degrades" means [DefaultSyncTransportProvider] re-validates whatever comes out and reports
 * [SyncNotConfigured.UNUSABLE_SERVER_URL] for anything that does not pass — the read never throws.
 *
 * An absent key and a blank string both mean "not set". Blank is possible only via a file written
 * by something other than this class, since [setServerUrl] stores null for it.
 */
@Singleton
class DataStoreSyncSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : SyncSettingsRepository {

    private object Keys {
        val SERVER_URL = stringPreferencesKey("sync_server_url")
    }

    // distinctUntilChanged for the same reason DataStoreSettingsRepository uses it: DataStore
    // re-emits the whole snapshot after every edit, including edits to keys this flow does not
    // read. There is only one key in this file today, so it costs nothing and stops a future
    // second key from re-triggering every collector of this one.
    override val serverUrl: Flow<String?> =
        context.syncSettingsDataStore.data
            .map { prefs -> prefs[Keys.SERVER_URL]?.takeIf { it.isNotBlank() } }
            .distinctUntilChanged()

    override suspend fun setServerUrl(url: String?) {
        val value = url?.trim()?.takeIf { it.isNotEmpty() }
        context.syncSettingsDataStore.edit { prefs ->
            // Removed rather than stored as "" so that "never set" and "cleared" are the same
            // state on disk. Two representations of one state is two branches everything that
            // reads this has to get right.
            if (value == null) prefs.remove(Keys.SERVER_URL) else prefs[Keys.SERVER_URL] = value
        }
    }
}
