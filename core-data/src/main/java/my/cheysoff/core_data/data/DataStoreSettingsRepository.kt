package my.cheysoff.core_data.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import my.cheysoff.core_domain.model.HeaderSettings
import my.cheysoff.core_domain.model.NotesSortOrder
import my.cheysoff.core_domain.sketch.SketchColors
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
        val NOTES_SORT_ORDER = stringPreferencesKey("notes_sort_order")
        val RECENT_SKETCH_COLORS = stringPreferencesKey("recent_sketch_colors")
    }

    // distinctUntilChanged because DataStore's `data` re-emits the whole Preferences snapshot
    // after every successful edit(), including edits to keys this flow does not read. Without it,
    // changing the sort order would re-emit an identical HeaderSettings and make every collector
    // recompose for nothing.
    override val headerSettings: Flow<HeaderSettings> =
        context.settingsDataStore.data.map { prefs ->
            HeaderSettings(
                showGreetings = prefs[Keys.GREETINGS] ?: true,
                showDailyPhrases = prefs[Keys.DAILY_PHRASES] ?: true,
                showStats = prefs[Keys.STATS] ?: true,
            )
        }.distinctUntilChanged()

    override suspend fun setShowGreetings(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.GREETINGS] = enabled }
    }

    override suspend fun setShowDailyPhrases(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.DAILY_PHRASES] = enabled }
    }

    override suspend fun setShowStats(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.STATS] = enabled }
    }

    // Stored as the order's stable string key, so an unrecognised value (e.g. a preference left
    // behind by a newer build) degrades to the default instead of crashing. See NotesSortOrder.
    //
    // distinctUntilChanged for the same reason as headerSettings - DataStore re-emits on every
    // unrelated write - but it matters more here: NotesListViewModel feeds this into flatMapLatest,
    // so a duplicate emission cancels and re-subscribes the Room flow, re-running the full SELECT
    // and the HTML parse behind note.toUi() for every note. Toggling a header switch, or re-picking
    // the order that is already active, would otherwise pay that cost.
    override val notesSortOrder: Flow<NotesSortOrder> =
        context.settingsDataStore.data.map { prefs ->
            NotesSortOrder.fromKey(prefs[Keys.NOTES_SORT_ORDER])
        }.distinctUntilChanged()

    override suspend fun setNotesSortOrder(order: NotesSortOrder) {
        context.settingsDataStore.edit { it[Keys.NOTES_SORT_ORDER] = order.key }
    }

    /**
     * Stored as comma-separated decimal, because DataStore's typed sets are unordered and this list
     * is nothing but an order. An entry that will not parse is dropped rather than failing the
     * whole read: the worst case is one fewer swatch, where throwing would take the canvas' toolbar
     * down with it.
     */
    override val recentSketchColors: Flow<List<Long>> =
        context.settingsDataStore.data.map { prefs ->
            prefs[Keys.RECENT_SKETCH_COLORS]
                ?.split(',')
                ?.mapNotNull { it.trim().toLongOrNull() }
                ?.take(SketchColors.MAX_RECENTS)
                .orEmpty()
        }.distinctUntilChanged()

    override suspend fun addRecentSketchColor(argb: Long) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs[Keys.RECENT_SKETCH_COLORS]
                ?.split(',')
                ?.mapNotNull { it.trim().toLongOrNull() }
                .orEmpty()
            prefs[Keys.RECENT_SKETCH_COLORS] =
                SketchColors.withRecent(current, argb).joinToString(",")
        }
    }
}
