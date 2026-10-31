package my.cheysoff.core_data.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import my.cheysoff.core_domain.model.EventData
import my.cheysoff.core_domain.sync.Hlc

/**
 * One calendar event's row, added in v12.
 *
 * Structurally a sibling of [SketchEntity] and [AttachmentEntity] in its sync columns, and unlike
 * either in its relationship to a note: there is **no cascade of any kind** here, in SQL or in
 * tombstones. `linkedNoteId` is a back-reference to the note an event was written beside, and an
 * event outlives that note being trashed. `RoomSyncStore`'s `EVENT` branch calls neither note
 * reconciler, and the column is deliberately not named `noteId` so that a later refactor which
 * cascades "every type with a noteId" cannot quietly start deleting events.
 *
 * Indexed on `startAt` because every query this table has is a window over time -- a month of the
 * calendar grid, or the next fortnight for the widget -- and on `linkedNoteId` so opening a note
 * can find the events written beside it without scanning.
 *
 * ## The reserved columns
 *
 * `recurrence` and `reminders` are always `''` in this build. They exist from the first shipped
 * row because `PayloadFields.EVENT_COLUMNS` is frozen the moment one reaches a server: a column
 * added to a record type afterwards fails `RecordPayloadCodec.decodeOrThrow`'s exact key-set check
 * on every device still running the older build, freezing its cursor and halting its account after
 * five such records. `descriptionFormat` is here for the same reason and one of its own -- Google
 * Calendar descriptions are HTML, so the moment anything is imported there has to be somewhere to
 * record which parser a body wants.
 *
 * `meta` is the general escape hatch, unclocked and carried verbatim. See `AttachmentEntity.meta`.
 *
 * ## The sync columns
 *
 * The same six `attachments` carries. `dirty` defaults to `1` in all three places that have to
 * agree -- this Kotlin default, `@ColumnInfo` below, and `MIGRATION_11_12`'s DDL -- because `0`
 * would assert the server already has the row, and the first pull would read its absence as
 * "deleted elsewhere".
 */
@Entity(
    tableName = "events",
    indices = [Index("startAt"), Index("linkedNoteId")],
)
data class EventEntity(
    @PrimaryKey val uuid: String,
    val title: String,
    val startAt: Long,
    /** Exclusive. See [EventData.endAt]. */
    val endAt: Long,
    val startLocal: String,
    val endLocal: String,
    val timeZone: String,
    val isAllDay: Boolean,
    val location: String,
    val description: String,
    val descriptionFormat: String,
    /** Reserved; always `''` in this build. See the class KDoc. */
    val reminders: String,
    /** Reserved; always `''` in this build. See the class KDoc. */
    val recurrence: String,
    val linkedNoteId: String?,
    val shareToGoogle: Boolean,
    val colorArgb: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0")
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,

    /** Row clock, physical component. See `NoteEntity.hlcMs`. */
    val hlcMs: Long = 0L,
    /** Row clock, logical component. */
    val hlcCounter: Int = 0,
    /** Row clock, node component. */
    val hlcNode: String = "",
    /** Per-field clocks, serialised — see `FieldClocks.EVENT_FIELDS`. */
    @ColumnInfo(defaultValue = "''")
    val fieldHlc: String = "",
    /**
     * True when this row holds changes the server has not acknowledged. **The default is 1 in three
     * places and changing any of them to 0 is data loss** — see the class KDoc.
     */
    @ColumnInfo(defaultValue = "1")
    val dirty: Boolean = true,
    @ColumnInfo(defaultValue = "0")
    val lastSyncedSeq: Long = 0L,
    /** Opaque, unclocked, carried verbatim. See `AttachmentEntity.meta`. */
    @ColumnInfo(defaultValue = "''")
    val meta: String = "",
) {
    /** The row clock as one value. Same member every other synced entity carries. */
    fun rowHlc(): Hlc = Hlc(ms = hlcMs, counter = hlcCounter, node = hlcNode)

    /** This row's clocks without its columns, for the field-clock read-modify-write. */
    fun clocks(): RowClock = RowClock(hlcMs, hlcCounter, hlcNode, fieldHlc)
}

/** The row as the rest of the app sees it. */
fun EventEntity.toDomain(): EventData = EventData(
    id = uuid,
    title = title,
    startAt = startAt,
    endAt = endAt,
    startLocal = startLocal,
    endLocal = endLocal,
    timeZone = timeZone,
    isAllDay = isAllDay,
    location = location,
    description = description,
    descriptionFormat = descriptionFormat,
    reminders = reminders,
    recurrence = recurrence,
    linkedNoteId = linkedNoteId,
    shareToGoogle = shareToGoogle,
    colorArgb = colorArgb,
    meta = meta,
    createdAt = createdAt,
    updatedAt = updatedAt,
    isDeleted = isDeleted,
    deletedAt = deletedAt,
)
