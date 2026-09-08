package my.cheysoff.feature_notes.model.calendar

import my.cheysoff.core_domain.model.EventData
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Turning what the composer collected -- a day, an optional time of day, a length -- into the
 * [EventData] the repository stores.
 *
 * Pure: the zone and the clock are parameters, never read here. That is what makes the two rules
 * below testable without a device and without the test passing only in the time zone the machine
 * happens to sit in.
 *
 * ## The two rules, which are the whole file
 *
 * **All-day events belong to no zone.** `timeZone` is empty and the local dates are authoritative;
 * the instants are UTC midnight of those dates purely so the row still sorts. A birthday created in
 * Moscow reads as the same date in London because nothing ever converts it. Deriving the instant
 * from the author's zone instead is the classic calendar bug: the event lands a day earlier for
 * anyone far enough west.
 *
 * **Ends are exclusive.** An event from 09:00 lasting an hour ends at 10:00; an all-day event on
 * the 5th ends on the 6th. Inclusive ends need a plus-or-minus-one-day fixup at every mapping
 * boundary, and the boundary that forgets makes events grow or shrink by a day per round trip.
 */
object EventDrafts {

    /** `2026-09-08T09:00:00`. Seconds are always written so the shape never varies. */
    private val LOCAL_DATE_TIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    /**
     * The event the composer describes.
     *
     * @param startMinutes minutes past midnight, or **null for an all-day event**.
     * @param existing the row being edited, when there is one. Its `createdAt` is carried forward:
     *   creation time is the one column nothing may move, and re-stamping it on every edit would
     *   quietly rewrite history each time a title changed.
     */
    fun build(
        id: String,
        title: String,
        day: LocalDate,
        startMinutes: Int?,
        durationMinutes: Int,
        zone: ZoneId,
        nowMillis: Long,
        existing: EventData? = null,
    ): EventData {
        val allDay = startMinutes == null
        val startAt: Long
        val endAt: Long
        val startLocal: String
        val endLocal: String

        if (allDay) {
            // UTC, not [zone]. See the class KDoc -- this is the rule the whole type exists for.
            val endDay = day.plusDays(1)
            startAt = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            endAt = endDay.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            startLocal = day.toString()
            endLocal = endDay.toString()
        } else {
            val start = day.atTime(LocalTime.MIDNIGHT.plusMinutes(startMinutes.toLong()))
            val end = start.plusMinutes(durationMinutes.coerceAtLeast(1).toLong())
            startAt = start.atZone(zone).toInstant().toEpochMilli()
            endAt = end.atZone(zone).toInstant().toEpochMilli()
            startLocal = start.format(LOCAL_DATE_TIME)
            endLocal = end.format(LOCAL_DATE_TIME)
        }

        return EventData(
            id = id,
            title = title.trim(),
            startAt = startAt,
            endAt = endAt,
            startLocal = startLocal,
            endLocal = endLocal,
            timeZone = if (allDay) "" else zone.id,
            isAllDay = allDay,
            // Carried forward rather than blanked: this composer does not collect them, and an
            // edit made here must not throw away a value some other surface put there.
            location = existing?.location.orEmpty(),
            description = existing?.description.orEmpty(),
            descriptionFormat = existing?.descriptionFormat ?: EventData.DESCRIPTION_PLAIN,
            reminders = existing?.reminders.orEmpty(),
            recurrence = existing?.recurrence.orEmpty(),
            linkedNoteId = existing?.linkedNoteId,
            shareToGoogle = existing?.shareToGoogle ?: false,
            colorArgb = existing?.colorArgb,
            meta = existing?.meta.orEmpty(),
            createdAt = existing?.createdAt ?: nowMillis,
            updatedAt = nowMillis,
        )
    }

    /** The day [event] belongs on, read the way the class KDoc says it must be. */
    fun dayOf(event: EventData, zone: ZoneId): LocalDate =
        if (event.isAllDay) {
            // Parsed, never converted. Converting an all-day date through a zone is what moves a
            // birthday to the day before.
            runCatching { LocalDate.parse(event.startLocal) }.getOrElse {
                java.time.Instant.ofEpochMilli(event.startAt).atZone(ZoneOffset.UTC).toLocalDate()
            }
        } else {
            java.time.Instant.ofEpochMilli(event.startAt).atZone(zone).toLocalDate()
        }
}
