package my.cheysoff.feature_notes

import my.cheysoff.feature_notes.model.calendar.EventDrafts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * The arithmetic behind creating an event, and the one rule it exists to protect: an all-day event
 * belongs to no time zone and must read as the same date everywhere.
 *
 * Every zone is a parameter, so nothing here passes only on a machine set to one particular one --
 * the same discipline `CalendarGroupingTest` already applies.
 */
class EventDraftsTest {

    private val moscow = ZoneId.of("Europe/Moscow")     // UTC+3
    private val losAngeles = ZoneId.of("America/Los_Angeles") // UTC-7/-8
    private val day = LocalDate.of(2026, 9, 5)
    private val now = 1_757_000_000_000L

    // --- all-day -------------------------------------------------------------------------------

    /**
     * The bug this whole representation exists to prevent: a birthday created in Moscow must not
     * become the 4th when read in Los Angeles.
     */
    @Test
    fun `an all day event reads as the same date in every zone`() {
        val event = EventDrafts.build(
            id = "e1", title = "Birthday", day = day,
            startMinutes = null, durationMinutes = 60, zone = moscow, nowMillis = now,
        )

        assertEquals(day, EventDrafts.dayOf(event, moscow))
        assertEquals(day, EventDrafts.dayOf(event, losAngeles))
        assertEquals(day, EventDrafts.dayOf(event, ZoneId.of("Pacific/Chatham")))
    }

    @Test
    fun `an all day event stores dates, no zone, and an exclusive end`() {
        val event = EventDrafts.build(
            id = "e1", title = "Birthday", day = day,
            startMinutes = null, durationMinutes = 60, zone = moscow, nowMillis = now,
        )

        assertTrue(event.isAllDay)
        assertEquals("2026-09-05", event.startLocal)
        assertEquals("the end is exclusive: the 5th ends on the 6th", "2026-09-06", event.endLocal)
        assertEquals("an all-day event belongs to no zone", "", event.timeZone)
    }

    /**
     * The instants are UTC midnight regardless of who wrote the event, which is what keeps them
     * sortable without ever being the authority on the date.
     */
    @Test
    fun `an all day event anchors its instants at UTC midnight whoever creates it`() {
        val fromMoscow = EventDrafts.build(
            id = "a", title = "x", day = day,
            startMinutes = null, durationMinutes = 60, zone = moscow, nowMillis = now,
        )
        val fromLa = EventDrafts.build(
            id = "b", title = "x", day = day,
            startMinutes = null, durationMinutes = 60, zone = losAngeles, nowMillis = now,
        )

        assertEquals(fromMoscow.startAt, fromLa.startAt)
        assertEquals(fromMoscow.endAt, fromLa.endAt)
        assertEquals("exactly one day long", 86_400_000L, fromMoscow.endAt - fromMoscow.startAt)
    }

    // --- timed ---------------------------------------------------------------------------------

    @Test
    fun `a timed event keeps its zone and its wall clock time`() {
        val event = EventDrafts.build(
            id = "e2", title = "Dentist", day = day,
            startMinutes = 9 * 60 + 30, durationMinutes = 45, zone = moscow, nowMillis = now,
        )

        assertFalse(event.isAllDay)
        assertEquals("2026-09-05T09:30:00", event.startLocal)
        assertEquals("2026-09-05T10:15:00", event.endLocal)
        assertEquals("Europe/Moscow", event.timeZone)
        assertEquals(45L * 60_000L, event.endAt - event.startAt)
    }

    /** The same wall-clock time in two zones is two different instants. */
    @Test
    fun `a timed event resolves its instant through its own zone`() {
        val inMoscow = EventDrafts.build(
            id = "a", title = "x", day = day,
            startMinutes = 9 * 60, durationMinutes = 60, zone = moscow, nowMillis = now,
        )
        val inLa = EventDrafts.build(
            id = "b", title = "x", day = day,
            startMinutes = 9 * 60, durationMinutes = 60, zone = losAngeles, nowMillis = now,
        )

        assertTrue("09:00 in Moscow happens before 09:00 in LA", inMoscow.startAt < inLa.startAt)
        assertEquals("both are still 09:00 where they were written", inMoscow.startLocal, inLa.startLocal)
    }

    @Test
    fun `a timed event is read back on its own day in its own zone`() {
        val event = EventDrafts.build(
            id = "e", title = "x", day = day,
            startMinutes = 9 * 60, durationMinutes = 60, zone = moscow, nowMillis = now,
        )

        assertEquals(day, EventDrafts.dayOf(event, moscow))
    }

    @Test
    fun `a zero length is refused rather than stored`() {
        val event = EventDrafts.build(
            id = "e", title = "x", day = day,
            startMinutes = 9 * 60, durationMinutes = 0, zone = moscow, nowMillis = now,
        )

        assertTrue("an event must occupy some time", event.endAt > event.startAt)
    }

    // --- editing -------------------------------------------------------------------------------

    /**
     * `createdAt` is the one column nothing may move. Re-stamping it on every edit would rewrite
     * the record's history each time its title changed.
     */
    @Test
    fun `editing keeps the original creation time and the fields this composer does not collect`() {
        val original = EventDrafts.build(
            id = "e", title = "x", day = day,
            startMinutes = 9 * 60, durationMinutes = 60, zone = moscow, nowMillis = 1_000L,
        ).copy(location = "Clinic", description = "bring the form", meta = "{}", colorArgb = 42L)

        val edited = EventDrafts.build(
            id = "e", title = "y", day = day,
            startMinutes = 10 * 60, durationMinutes = 60, zone = moscow, nowMillis = 9_000L,
            existing = original,
        )

        assertEquals(1_000L, edited.createdAt)
        assertEquals(9_000L, edited.updatedAt)
        assertEquals("Clinic", edited.location)
        assertEquals("bring the form", edited.description)
        assertEquals("an opaque value must survive an edit that never saw it", "{}", edited.meta)
        assertEquals(42L, edited.colorArgb)
    }

    @Test
    fun `a new event takes now as its creation time`() {
        val event = EventDrafts.build(
            id = "e", title = "x", day = day,
            startMinutes = null, durationMinutes = 60, zone = moscow, nowMillis = now,
        )

        assertEquals(now, event.createdAt)
        assertEquals(now, event.updatedAt)
    }

    @Test
    fun `a title is trimmed`() {
        val event = EventDrafts.build(
            id = "e", title = "  Dentist  ", day = day,
            startMinutes = null, durationMinutes = 60, zone = moscow, nowMillis = now,
        )

        assertEquals("Dentist", event.title)
    }

    /** Reserved columns stay empty; this build has no way to fill them. See `EventData`. */
    @Test
    fun `a new event carries no recurrence and no reminders`() {
        val event = EventDrafts.build(
            id = "e", title = "x", day = day,
            startMinutes = null, durationMinutes = 60, zone = moscow, nowMillis = now,
        )

        assertEquals("", event.recurrence)
        assertEquals("", event.reminders)
        assertFalse("it must not be shared outward unless chosen", event.shareToGoogle)
        assertFalse(event.isFromNewerBuild)
    }
}
