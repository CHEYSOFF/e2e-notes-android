package my.cheysoff.feature_notes

import my.cheysoff.feature_notes.model.calendar.dayOf
import my.cheysoff.feature_notes.model.calendar.groupByDay
import my.cheysoff.feature_notes.model.calendar.monthGrid
import my.cheysoff.feature_notes.model.calendar.undatedCount
import my.cheysoff.feature_notes.model.calendar.weekdayOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

/**
 * Tests for the Calendar tab's date arithmetic.
 *
 * Every case passes an explicit zone. The grouping is zone-sensitive by nature — the same instant
 * is two different dates either side of midnight — so a test that leaned on the machine's default
 * zone would assert something different on a CI box than on a laptop.
 */
class CalendarGroupingTest {

    private val utc = ZoneId.of("UTC")
    private val moscow = ZoneId.of("Europe/Moscow")   // UTC+3, no DST
    private val chatham = ZoneId.of("Pacific/Chatham") // UTC+12:45, a non-hour offset

    /** Epoch millis for a wall-clock time in a zone, so the fixtures read as dates not numbers. */
    private fun millis(zone: ZoneId, y: Int, mo: Int, d: Int, h: Int = 12, mi: Int = 0): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    // --- dayOf -------------------------------------------------------------

    @Test
    fun `dayOf maps an instant to its date in the given zone`() {
        val ts = millis(utc, 2026, 8, 30, h = 12)
        assertEquals(LocalDate.of(2026, 8, 30), dayOf(ts, utc))
    }

    @Test
    fun `dayOf resolves the same instant to different days in different zones`() {
        // 23:30 in Moscow on the 30th is 20:30 UTC on the 30th, but 22:30 UTC on the 30th is
        // already 01:30 on the 31st in Moscow. Pin the second case: one instant, two dates.
        val ts = millis(utc, 2026, 8, 30, h = 22, mi = 30)
        assertEquals(LocalDate.of(2026, 8, 30), dayOf(ts, utc))
        assertEquals(LocalDate.of(2026, 8, 31), dayOf(ts, moscow))
    }

    @Test
    fun `dayOf handles a zone whose offset is not a whole hour`() {
        val ts = millis(chatham, 2026, 8, 30, h = 0, mi = 30)
        assertEquals(LocalDate.of(2026, 8, 30), dayOf(ts, chatham))
    }

    @Test
    fun `dayOf reports no day for an unset timestamp`() {
        // Rows predating the timestamp columns carry 0. They must not land on 1 Jan 1970.
        assertNull(dayOf(0L, utc))
        assertNull(dayOf(-1L, utc))
    }

    @Test
    fun `dayOf treats the first millisecond after the epoch as a real day`() {
        // The guard is `<= 0`, so 1L is data, not a sentinel.
        assertEquals(LocalDate.of(1970, 1, 1), dayOf(1L, utc))
    }

    // --- groupByDay --------------------------------------------------------

    private data class Item(val name: String, val ts: Long)

    @Test
    fun `groupByDay buckets items by their date`() {
        val a = Item("a", millis(utc, 2026, 8, 30, h = 1))
        val b = Item("b", millis(utc, 2026, 8, 30, h = 23))
        val c = Item("c", millis(utc, 2026, 8, 31))
        val grouped = groupByDay(listOf(a, b, c), utc) { it.ts }

        assertEquals(setOf(LocalDate.of(2026, 8, 30), LocalDate.of(2026, 8, 31)), grouped.keys)
        assertEquals(listOf(a, b), grouped.getValue(LocalDate.of(2026, 8, 30)))
        assertEquals(listOf(c), grouped.getValue(LocalDate.of(2026, 8, 31)))
    }

    @Test
    fun `groupByDay preserves input order inside a bucket`() {
        // The list arrives sorted newest-first; each day's notes must stay in that order.
        val newest = Item("newest", millis(utc, 2026, 8, 30, h = 20))
        val middle = Item("middle", millis(utc, 2026, 8, 30, h = 12))
        val oldest = Item("oldest", millis(utc, 2026, 8, 30, h = 3))
        val grouped = groupByDay(listOf(newest, middle, oldest), utc) { it.ts }

        assertEquals(
            listOf(newest, middle, oldest),
            grouped.getValue(LocalDate.of(2026, 8, 30)),
        )
    }

    @Test
    fun `groupByDay omits undated items rather than filing them under the epoch`() {
        val dated = Item("dated", millis(utc, 2026, 8, 30))
        val undated = Item("undated", 0L)
        val grouped = groupByDay(listOf(dated, undated), utc) { it.ts }

        assertEquals(setOf(LocalDate.of(2026, 8, 30)), grouped.keys)
        assertTrue(
            "an unset timestamp must not create a 1970 bucket",
            LocalDate.of(1970, 1, 1) !in grouped.keys,
        )
    }

    @Test
    fun `groupByDay returns nothing for an empty list`() {
        assertEquals(
            emptyMap<LocalDate, List<Item>>(),
            groupByDay(emptyList<Item>(), utc) { it.ts },
        )
    }

    // --- undatedCount ------------------------------------------------------

    @Test
    fun `undatedCount counts exactly the items groupByDay drops`() {
        val items = listOf(
            Item("a", millis(utc, 2026, 8, 30)),
            Item("b", 0L),
            Item("c", -5L),
            Item("d", millis(utc, 2026, 8, 31)),
        )
        val grouped = groupByDay(items, utc) { it.ts }

        assertEquals(2, undatedCount(items) { it.ts })
        // The two must agree: every item is either in a bucket or counted as undated.
        assertEquals(items.size, grouped.values.sumOf { it.size } + undatedCount(items) { it.ts })
    }

    // --- monthGrid ---------------------------------------------------------

    @Test
    fun `monthGrid covers every day of the month for every week start`() {
        // Sweeping all seven starts is what makes this bite. With any single start the lead
        // offset can come out right by luck; it is the starts that fall *after* the 1st's own
        // weekday that force the offset to wrap, and dropping that wrap silently truncates the
        // first row instead of failing loudly.
        val august = (1..31).map { LocalDate.of(2026, 8, it) }
        for (first in DayOfWeek.entries) {
            val grid = monthGrid(YearMonth.of(2026, 8), first)
            for (day in august) {
                assertTrue("$day missing from a grid starting on $first", day in grid)
            }
        }
    }

    @Test
    fun `monthGrid covers every day of every month across two years`() {
        // The same property, swept over month lengths and start weekdays together, so a
        // regression cannot hide in a month shape that one hand-picked fixture misses.
        for (first in DayOfWeek.entries) {
            var month = YearMonth.of(2025, 1)
            repeat(24) {
                val grid = monthGrid(month, first)
                for (d in 1..month.lengthOfMonth()) {
                    val day = month.atDay(d)
                    assertTrue("$day missing from a grid starting on $first", day in grid)
                }
                month = month.plusMonths(1)
            }
        }
    }

    @Test
    fun `monthGrid starts on the requested first day of week`() {
        for (first in DayOfWeek.entries) {
            val grid = monthGrid(YearMonth.of(2026, 8), first)
            assertEquals("grid starting on $first", first, grid.first().dayOfWeek)
        }
    }

    @Test
    fun `monthGrid returns whole weeks`() {
        // Sweep two years of months against both common week starts: the length must always
        // divide by 7, or the UI would be chunking a partial final row.
        for (first in listOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY)) {
            var month = YearMonth.of(2025, 1)
            repeat(24) {
                val grid = monthGrid(month, first)
                assertEquals("$month starting $first", 0, grid.size % 7)
                month = month.plusMonths(1)
            }
        }
    }

    @Test
    fun `monthGrid is contiguous with no gaps or repeats`() {
        val grid = monthGrid(YearMonth.of(2026, 8), DayOfWeek.MONDAY)
        grid.zipWithNext { a, b ->
            assertEquals("cells must be consecutive days", a.plusDays(1), b)
        }
        assertEquals("no date may appear twice", grid.size, grid.toSet().size)
    }

    @Test
    fun `monthGrid pads with the neighbouring months rather than with blanks`() {
        // 1 Aug 2026 is a Saturday, so a Monday-start grid leads with five days of July.
        val grid = monthGrid(YearMonth.of(2026, 8), DayOfWeek.MONDAY)
        assertEquals(LocalDate.of(2026, 7, 27), grid.first())
        assertEquals(LocalDate.of(2026, 9, 6), grid.last())
    }

    @Test
    fun `monthGrid uses exactly four rows for a February that fills them`() {
        // Feb 2027 has 28 days and starts on a Monday: 4 rows exactly, no trailing blank row.
        val grid = monthGrid(YearMonth.of(2027, 2), DayOfWeek.MONDAY)
        assertEquals(DayOfWeek.MONDAY, LocalDate.of(2027, 2, 1).dayOfWeek)
        assertEquals(28, grid.size)
        assertEquals(LocalDate.of(2027, 2, 1), grid.first())
        assertEquals(LocalDate.of(2027, 2, 28), grid.last())
    }

    @Test
    fun `monthGrid uses six rows when a 31 day month starts late in the week`() {
        // 1 Aug 2026 is a Saturday: 5 lead cells + 31 days = 36, which rounds to 42.
        assertEquals(42, monthGrid(YearMonth.of(2026, 8), DayOfWeek.MONDAY).size)
    }

    @Test
    fun `monthGrid includes the leap day`() {
        val grid = monthGrid(YearMonth.of(2028, 2), DayOfWeek.MONDAY)
        assertTrue(LocalDate.of(2028, 2, 29) in grid)
    }

    // --- weekdayOrder ------------------------------------------------------

    @Test
    fun `weekdayOrder lists seven days starting from the given one`() {
        assertEquals(
            listOf(
                DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY,
            ),
            weekdayOrder(DayOfWeek.MONDAY),
        )
        assertEquals(DayOfWeek.SUNDAY, weekdayOrder(DayOfWeek.SUNDAY).first())
        assertEquals(DayOfWeek.SATURDAY, weekdayOrder(DayOfWeek.SUNDAY).last())
    }

    @Test
    fun `weekdayOrder column order matches the grid it labels`() {
        // The header row and the first week of the grid must agree column for column, otherwise
        // every date would be drawn under the wrong weekday name.
        for (first in DayOfWeek.entries) {
            val grid = monthGrid(YearMonth.of(2026, 8), first)
            assertEquals(
                "columns for a grid starting on $first",
                weekdayOrder(first),
                grid.take(7).map { it.dayOfWeek },
            )
        }
    }
}
