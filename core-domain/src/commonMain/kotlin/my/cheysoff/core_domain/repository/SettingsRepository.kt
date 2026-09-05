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

    /**
     * The last few colours mixed in the sketch canvas, most recent first, as opaque ARGB.
     *
     * Device-local and deliberately **not** synced. It is a state of the tool rather than anything
     * about a note: the drawing already carries its own colours in `Stroke.colorArgb`, and a
     * phone's recent-colour strip is no more the desktop's business than which nib was last used.
     * Keeping it out of the record set also keeps it out of a payload column set that can never
     * change once shipped.
     */
    val recentSketchColors: Flow<List<Long>>

    /** Records [argb] as most-recently-used. See `SketchColors.withRecent` for the ordering rule. */
    suspend fun addRecentSketchColor(argb: Long)
}
