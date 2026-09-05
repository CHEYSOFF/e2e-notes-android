package my.cheysoff.feature_notes

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import my.cheysoff.core_domain.model.HeaderSettings
import my.cheysoff.core_domain.model.NotesSortOrder
import my.cheysoff.core_domain.sketch.SketchColors
import my.cheysoff.core_domain.repository.SettingsRepository

/**
 * Hand-written [SettingsRepository] backed by two [MutableStateFlow]s.
 *
 * The real one is DataStore-backed and distinct-until-changed; a StateFlow has both properties by
 * construction, which is what the notes list relies on (it re-picks its header line on every
 * `headerSettings` emission and would reshuffle on screen if unrelated writes re-emitted).
 *
 * The setters write straight through to the flow, matching production: nothing in the app echoes a
 * settings change optimistically, so the value always comes back the way it was stored.
 */
internal class FakeSettingsRepository(
    initialHeader: HeaderSettings = HeaderSettings(),
    initialSortOrder: NotesSortOrder = NotesSortOrder.DEFAULT,
) : SettingsRepository {

    override val headerSettings = MutableStateFlow(initialHeader)
    override val notesSortOrder = MutableStateFlow(initialSortOrder)

    val calls = mutableListOf<String>()

    override suspend fun setShowGreetings(enabled: Boolean) {
        calls += "setShowGreetings($enabled)"
        headerSettings.value = headerSettings.value.copy(showGreetings = enabled)
    }

    override suspend fun setShowDailyPhrases(enabled: Boolean) {
        calls += "setShowDailyPhrases($enabled)"
        headerSettings.value = headerSettings.value.copy(showDailyPhrases = enabled)
    }

    override suspend fun setShowStats(enabled: Boolean) {
        calls += "setShowStats($enabled)"
        headerSettings.value = headerSettings.value.copy(showStats = enabled)
    }

    override suspend fun setNotesSortOrder(order: NotesSortOrder) {
        calls += "setNotesSortOrder(${order.key})"
        notesSortOrder.value = order
    }

    val recentSketchColorsState = MutableStateFlow<List<Long>>(emptyList())
    override val recentSketchColors: Flow<List<Long>> get() = recentSketchColorsState

    override suspend fun addRecentSketchColor(argb: Long) {
        calls += "addRecentSketchColor($argb)"
        recentSketchColorsState.value = SketchColors.withRecent(recentSketchColorsState.value, argb)
    }
}
