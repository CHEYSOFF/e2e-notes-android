package my.cheysoff.core_domain.repository

import kotlinx.coroutines.flow.Flow
import my.cheysoff.core_domain.model.EventData

/**
 * The seam a UI uses to put a calendar event somewhere real, mirroring [SketchesRepository].
 *
 * Its own interface rather than more methods on `NotesRepository`, for the reason the attachment
 * work settled: an event is not a property of a note, and a repository that owns both invites a
 * caller to assume the two share a lifetime.
 */
interface EventsRepository {

    /** Every visible event, soonest first. Tombstoned rows never appear. */
    fun getEvents(): Flow<List<EventData>>

    /** Visible events that start inside `[fromMillis, toMillis)`, soonest first. */
    fun getEventsBetween(fromMillis: Long, toMillis: Long): Flow<List<EventData>>

    /**
     * Creates or updates an event. One method for both, matching `saveSketch`: the caller does not
     * have to know whether [event]'s id exists, and the implementation decides per field what
     * actually changed so a concurrent edit to another field on another device is not clobbered.
     *
     * **Throws [UnsupportedOperationException] for an event carrying a recurrence or reminders.**
     * This build cannot express either, so saving one would write back an empty rule and erase a
     * repeat that a later build set -- permanently, on every device, because the merge would see a
     * newer clock and take it. The guard is here rather than in the UI because the UI is one write
     * path and there will be several; see `EventData.isFromNewerBuild`.
     */
    suspend fun saveEvent(event: EventData)

    /** Soft-deletes one event: its own tombstone, its own fresh clock, `dirty` set. */
    suspend fun deleteEvent(id: String)
}
