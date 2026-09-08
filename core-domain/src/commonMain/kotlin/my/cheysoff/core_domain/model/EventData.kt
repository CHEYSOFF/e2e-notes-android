package my.cheysoff.core_domain.model

/**
 * One calendar event, as a record.
 *
 * The first record type in this app that is **not** a child of a note. [linkedNoteId] is an
 * optional back-reference, not ownership: an event written beside a note outlives that note being
 * trashed, which is why it does not reuse the `noteId` field name the sketch and attachment types
 * share -- that name marks a child the tombstone cascade collects.
 *
 * ## Time
 *
 * [startAt]/[endAt] are epoch milliseconds and [endAt] is **exclusive**: an event from 09:00 to
 * 10:00 ends at 10:00, and an all-day event on the 5th ends on the 6th. Inclusive ends need a plus
 * or minus one day at every mapping boundary, and the boundary that forgets makes events grow or
 * shrink by a day on each round trip.
 *
 * [startLocal]/[endLocal] are not redundant with the instants, and which pair is authoritative
 * depends on [isAllDay]:
 *
 *  - **All-day**: the local strings are authoritative and no renderer may convert them. A birthday
 *    on the 5th is `"2026-09-05"` and reads as the 5th in every zone. The instants are defined as
 *    UTC midnight of those dates so that sorting still works.
 *  - **Timed**: [startAt] plus [timeZone] are authoritative and the local strings are derived.
 *
 * Keeping both survives a time-zone database update. Stored only as an instant, a 09:00 standup
 * silently becomes 08:00 when a country changes its rules -- Mexico dropped DST in 2022, Kazakhstan
 * collapsed to one zone in 2024. Stored only as local text, there is no orderable key, and both
 * `:core-domain` and the widget need one.
 *
 * ## Fields this build writes but never reads
 *
 * [recurrence] and [reminders] are always empty here. They exist because `PayloadFields`'
 * `EVENT_COLUMNS` is frozen the moment the first event record reaches a server, and a column added
 * afterwards halts the account of every device still on the older build. A record arriving from a
 * later build with either set is shown but refused for editing -- see `EventsRepository.saveEvent`.
 */
data class EventData(
    val id: String,
    val title: String,
    /** Epoch millis. For an all-day event, UTC midnight of [startLocal]. */
    val startAt: Long,
    /** Epoch millis, **exclusive**. */
    val endAt: Long,
    /** `YYYY-MM-DDTHH:mm:ss`, or `YYYY-MM-DD` when [isAllDay]. */
    val startLocal: String,
    /** Same shape as [startLocal], and exclusive like [endAt]. */
    val endLocal: String,
    /** IANA zone id, or empty for an all-day event, which belongs to no zone. */
    val timeZone: String,
    val isAllDay: Boolean,
    val location: String = "",
    val description: String = "",
    /** How [description] is encoded. Always `plain` in this build; see the class KDoc. */
    val descriptionFormat: String = DESCRIPTION_PLAIN,
    /** Reserved, always empty here. */
    val reminders: String = "",
    /** Reserved, always empty here. An RFC 5545 rule when a later build writes one. */
    val recurrence: String = "",
    /** The note this event was written beside, if any. Not ownership -- see the class KDoc. */
    val linkedNoteId: String? = null,
    /** Whether this event may be mirrored out of the end-to-end boundary. Off unless chosen. */
    val shareToGoogle: Boolean = false,
    val colorArgb: Long? = null,
    /** Carried verbatim, never parsed. See `PayloadFields.META`. */
    val meta: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
) {
    /** True when this event came from a build that understands more than this one does. */
    val isFromNewerBuild: Boolean get() = recurrence.isNotEmpty() || reminders.isNotEmpty()

    companion object {
        const val DESCRIPTION_PLAIN = "plain"
    }
}
