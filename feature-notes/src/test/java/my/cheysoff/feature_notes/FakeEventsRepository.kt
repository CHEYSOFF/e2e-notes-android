package my.cheysoff.feature_notes

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import my.cheysoff.core_domain.model.EventData
import my.cheysoff.core_domain.repository.EventsRepository

/**
 * An in-memory [EventsRepository], mirroring [FakeSketchesRepository].
 *
 * It keeps the one refusal the real implementation makes -- an event carrying a recurrence or
 * reminders cannot be saved by this build -- because that rule protects data on every device and a
 * fake that quietly accepted such a save would let a ViewModel test prove the opposite of the
 * truth.
 */
class FakeEventsRepository : EventsRepository {

    private val state = MutableStateFlow<List<EventData>>(emptyList())

    /** Every save that got through, in order, for assertions. */
    val saved = mutableListOf<EventData>()

    /** Every id passed to [deleteEvent]. */
    val deleted = mutableListOf<String>()

    /** Seeds the repository without going through [saveEvent]. */
    fun emit(events: List<EventData>) {
        state.value = events
    }

    override fun getEvents(): Flow<List<EventData>> = state

    override fun getEventsBetween(fromMillis: Long, toMillis: Long): Flow<List<EventData>> =
        state.map { list -> list.filter { it.startAt >= fromMillis && it.startAt < toMillis } }

    override suspend fun saveEvent(event: EventData) {
        if (event.isFromNewerBuild) {
            throw UnsupportedOperationException(
                "This event was created by a newer version of the app and cannot be edited here.",
            )
        }
        saved += event
        state.value = state.value.filterNot { it.id == event.id } + event
    }

    override suspend fun deleteEvent(id: String) {
        deleted += id
        state.value = state.value.filterNot { it.id == id }
    }
}
