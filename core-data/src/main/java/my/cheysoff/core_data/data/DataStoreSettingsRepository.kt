package my.cheysoff.core_data.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import my.cheysoff.core_domain.model.HeaderSettings
import my.cheysoff.core_domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore by preferencesDataStore(name = "manana_settings")

@Singleton
class DataStoreSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : SettingsRepository {

    private object Keys {
        val GREETINGS = booleanPreferencesKey("header_greetings")
        val DAILY_PHRASES = booleanPreferencesKey("header_daily_phrases")
        val STATS = booleanPreferencesKey("header_stats")
    }

    override val headerSettings: Flow<HeaderSettings> =
        context.settingsDataStore.data.map { prefs ->
            HeaderSettings(
                showGreetings = prefs[Keys.GREETINGS] ?: true,
                showDailyPhrases = prefs[Keys.DAILY_PHRASES] ?: true,
                showStats = prefs[Keys.STATS] ?: false,
            )
        }

    override suspend fun setShowGreetings(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.GREETINGS] = enabled }
    }

    override suspend fun setShowDailyPhrases(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.DAILY_PHRASES] = enabled }
    }

    override suspend fun setShowStats(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.STATS] = enabled }
    }
}
