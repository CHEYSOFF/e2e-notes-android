package my.cheysoff.core_domain.repository

import kotlinx.coroutines.flow.Flow
import my.cheysoff.core_domain.model.HeaderSettings
import my.cheysoff.core_domain.model.NotesSortOrder

interface SettingsRepository {
    val headerSettings: Flow<HeaderSettings>
    suspend fun setShowGreetings(enabled: Boolean)
    suspend fun setShowDailyPhrases(enabled: Boolean)
    suspend fun setShowStats(enabled: Boolean)

    val notesSortOrder: Flow<NotesSortOrder>
    suspend fun setNotesSortOrder(order: NotesSortOrder)
}
