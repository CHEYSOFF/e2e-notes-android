package my.cheysoff.core_data.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import my.cheysoff.core_data.data.local.EventDao
import my.cheysoff.core_data.data.local.EventEntity
import my.cheysoff.core_data.data.local.NoteDatabase
import my.cheysoff.core_data.data.local.RowClock
import my.cheysoff.core_data.data.local.toDomain
import my.cheysoff.core_data.data.sync.SyncClock
import my.cheysoff.core_data.data.sync.SyncStamp
import my.cheysoff.core_domain.model.EventData
import my.cheysoff.core_domain.repository.EventsRepository
import my.cheysoff.core_domain.sync.FieldClocks
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [RoomSketchesRepository]'s pattern applied to `events`: one clock per write, the row's previous
 * clocks read inside the transaction, then a write stamped with the new row clock and a `fieldHlc`
 * saying which fields actually changed.
 *
 * [clock] is the same `SyncClock` singleton every other repository mints from. Sharing it is
 * required rather than incidental -- two generators would each keep their own counter and could
 * issue one clock twice for two different writes, which is the one thing an HLC cannot tolerate.
 */
@Singleton
class RoomEventsRepository @Inject constructor(
    private val eventDao: EventDao,
    private val database: NoteDatabase,
    private val clock: SyncClock,
) : EventsRepository {

    private val seedMutex = Mutex()

    @Volatile
    private var seeded = false

    /** See `RoomNotesRepository.stamp` -- identical reasoning, this table's own high-water mark. */
    private suspend fun stamp(): SyncStamp {
        if (!seeded) {
            seedMutex.withLock {
                if (!seeded) {
                    eventDao.highestRowClock()?.let { clock.observe(it.rowHlc()) }
                    seeded = true
                }
            }
        }
        return clock.next()
    }

    override fun getEvents(): Flow<List<EventData>> =
        eventDao.events().map { list -> list.map { it.toDomain() } }

    override fun getEventsBetween(fromMillis: Long, toMillis: Long): Flow<List<EventData>> =
        eventDao.eventsBetween(fromMillis, toMillis).map { list -> list.map { it.toDomain() } }

    override suspend fun saveEvent(event: EventData) {
        // Refused before anything is stamped. This build has no way to express a recurrence or a
        // reminder, so writing the event back would blank whichever one a later build set -- and
        // because the write carries a newer clock, the merge would take the blank on every device
        // and the rule would be gone for good. Thrown rather than silently skipped: a save that
        // reports success while discarding half the record is the worse failure.
        if (event.isFromNewerBuild) {
            throw UnsupportedOperationException(
                "This event was created by a newer version of the app and cannot be edited here.",
            )
        }
        val stamp = stamp()
        // A transaction for the reason `saveSketch` gives: the field-clock computation is
        // read-modify-write, and the read must not see a different row than the write updates.
        database.withTransaction {
            val prior = eventDao.eventRow(event.id)
            eventDao.upsertEvent(
                EventEntity(
                    uuid = event.id,
                    title = event.title,
                    startAt = event.startAt,
                    endAt = event.endAt,
                    startLocal = event.startLocal,
                    endLocal = event.endLocal,
                    timeZone = event.timeZone,
                    isAllDay = event.isAllDay,
                    location = event.location,
                    description = event.description,
                    descriptionFormat = event.descriptionFormat,
                    reminders = event.reminders,
                    recurrence = event.recurrence,
                    linkedNoteId = event.linkedNoteId,
                    shareToGoogle = event.shareToGoogle,
                    colorArgb = event.colorArgb,
                    createdAt = event.createdAt,
                    updatedAt = event.updatedAt,
                    isDeleted = event.isDeleted,
                    deletedAt = event.deletedAt,
                    hlcMs = stamp.hlc.ms,
                    hlcCounter = stamp.hlc.counter,
                    hlcNode = stamp.hlc.node,
                    fieldHlc = fieldHlc(prior?.clocks(), touchedFields(prior, event), stamp),
                    dirty = true,
                    lastSyncedSeq = prior?.lastSyncedSeq ?: 0L,
                    // Never this build's own value. `meta` is opaque here, so an existing row's is
                    // carried forward and a new row's is empty -- writing an empty string over an
                    // existing one would erase whatever a newer build had put there.
                    meta = prior?.meta.orEmpty(),
                )
            )
        }
    }

    override suspend fun deleteEvent(id: String) {
        val stamp = stamp()
        database.withTransaction {
            val prior = eventDao.rowClock(id)
            eventDao.softDeleteEvent(
                uuid = id,
                timestamp = stamp.wallMs,
                hlcMs = stamp.hlc.ms,
                hlcCounter = stamp.hlc.counter,
                hlcNode = stamp.hlc.node,
                fieldHlc = fieldHlc(prior, TOMBSTONE_FIELDS, stamp),
            )
        }
    }

    private fun fieldHlc(prior: RowClock?, touched: Set<String>, stamp: SyncStamp): String =
        FieldClocks.stamp(
            previousSerialized = prior?.fieldHlc ?: "",
            previousRowClock = prior?.rowHlc(),
            allFields = FieldClocks.EVENT_FIELDS,
            touched = touched,
            newClock = stamp.hlc,
        )

    /**
     * Which clocked fields this save actually changed.
     *
     * "Wrote" and "changed" are different questions -- see `RoomNotesRepository.savedNoteFields`.
     * Claiming a clock for a value this write only copied back unchanged is what lets a concurrent
     * edit to a different field be discarded on the next merge.
     *
     * The whole schedule counts as one field: any of its six parts moving stamps `SCHEDULE`, which
     * is the point of grouping them.
     */
    private fun touchedFields(prior: EventEntity?, event: EventData): Set<String> {
        if (prior == null) return FieldClocks.EVENT_FIELDS

        val touched = mutableSetOf(FieldClocks.UPDATED_AT)
        if (prior.title != event.title) touched += FieldClocks.TITLE
        if (
            prior.startAt != event.startAt || prior.endAt != event.endAt ||
            prior.startLocal != event.startLocal || prior.endLocal != event.endLocal ||
            prior.timeZone != event.timeZone || prior.isAllDay != event.isAllDay
        ) {
            touched += FieldClocks.SCHEDULE
        }
        if (prior.location != event.location) touched += FieldClocks.LOCATION
        if (prior.description != event.description ||
            prior.descriptionFormat != event.descriptionFormat
        ) {
            touched += FieldClocks.DESCRIPTION
        }
        if (prior.reminders != event.reminders) touched += FieldClocks.REMINDERS
        if (prior.recurrence != event.recurrence) touched += FieldClocks.RECURRENCE
        if (prior.linkedNoteId != event.linkedNoteId) touched += FieldClocks.LINKED_NOTE
        if (prior.shareToGoogle != event.shareToGoogle) touched += FieldClocks.SHARE_TO_GOOGLE
        if (prior.colorArgb != event.colorArgb) touched += FieldClocks.COLOR
        if (prior.isDeleted != event.isDeleted || prior.deletedAt != event.deletedAt) {
            touched += FieldClocks.DELETED
        }
        return touched
    }

    private companion object {
        /** What [deleteEvent] writes: the tombstone alone. Trashing an event is not editing it. */
        val TOMBSTONE_FIELDS = setOf(FieldClocks.DELETED)
    }
}
