package my.cheysoff.feature_notes.model.calendar

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Date arithmetic behind the Calendar tab.
 *
 * Everything here is pure: no Android types, no clock reads, no time zone reads. The zone and the
 * "today" date are always parameters, which is what makes this file testable on the JVM and what
 * keeps a test from passing only in the time zone the machine happens to be in.
 *
 * The calendar buckets notes by **`updatedAt`**, not `createdAt`. The issue left the choice open,
 * and both are defensible, but `updatedAt` is the one the rest of the app already speaks:
 *
 *  - Every note card prints `relativeTime(note.updatedAt)` ("2h ago"). Bucketing by `createdAt`
 *    would let a note sit on a day in June whose own card reads "2h ago" — the calendar and the
 *    card would contradict each other on screen.
 *  - `NotesSortOrder.DEFAULT` orders the list by `updatedAt`, so "recent" already means "recently
 *    updated" throughout the app.
 *  - `NotePreviewUi` carries `updatedAt` and not `createdAt`, so this needs no new plumbing.
 *
 * The cost is that editing an old note moves it to today's cell: this is a view of when you last
 * worked on something, not of when you first wrote it.
 */

/**
 * The day [epochMillis] falls on in [zone], or null when the timestamp is unset.
 *
 * Rows written before the timestamp columns existed carry 0L, and the migrations backfill them
 * with 0 rather than inventing a date (see `MIGRATION_5_6`). Treating 0 as a real instant would
 * silently file those notes under 1 Jan 1970, so an unset timestamp is reported as "no day" and
 * the caller decides what to do with it. The `<= 0` guard matches `relativeTime`, which renders
 * an empty string for exactly the same range rather than "20455d ago".
 */
fun dayOf(epochMillis: Long, zone: ZoneId): LocalDate? =
    if (epochMillis <= 0L) null
    else Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

/**
 * Groups [items] into day buckets, preserving each bucket's input order.
 *
 * Generic over the item so the grouping can be tested without constructing UI models; the caller
 * supplies the timestamp. Items whose timestamp is unset are absent from the result entirely —
 * see [undatedCount], which is how the screen reports them instead of dropping them silently.
 */
fun <T> groupByDay(
    items: List<T>,
    zone: ZoneId,
    timestamp: (T) -> Long,
): Map<LocalDate, List<T>> {
    // LinkedHashMap + append keeps both the bucket order and the within-bucket order equal to the
    // input order, so a list already sorted by "most recent first" stays that way inside each day.
    val buckets = LinkedHashMap<LocalDate, MutableList<T>>()
    for (item in items) {
        val day = dayOf(timestamp(item), zone) ?: continue
        buckets.getOrPut(day) { mutableListOf() }.add(item)
    }
    return buckets
}

/** How many of [items] carry no usable timestamp and so appear on no day at all. */
fun <T> undatedCount(items: List<T>, timestamp: (T) -> Long): Int =
    items.count { timestamp(it) <= 0L }

/**
 * The dates to draw for [month], as whole weeks starting on [firstDayOfWeek].
 *
 * The result always covers the entire month and always has a length that is a multiple of 7, so
 * the caller can chunk it into rows without a remainder. Leading and trailing dates belong to the
 * neighbouring months and are returned as real dates rather than nulls: the grid greys them out,
 * but tapping one is a legitimate way to move to that day, and a null would make that impossible.
 *
 * The week count is derived, not fixed at 6. A 28-day February that starts on [firstDayOfWeek]
 * occupies exactly 4 rows, and most months take 5; hard-coding 6 would leave a blank row under
 * them.
 */
fun monthGrid(month: YearMonth, firstDayOfWeek: DayOfWeek): List<LocalDate> {
    val first = month.atDay(1)
    // How far the 1st sits past the start of its week, 0..6.
    val lead = Math.floorMod(first.dayOfWeek.value - firstDayOfWeek.value, 7)
    val start = first.minusDays(lead.toLong())
    val cells = ceilToWeek(lead + month.lengthOfMonth())
    return (0 until cells).map { start.plusDays(it.toLong()) }
}

/** Rounds a cell count up to a whole number of weeks. */
private fun ceilToWeek(cells: Int): Int = ((cells + 6) / 7) * 7

/**
 * The seven weekday labels for a grid starting on [firstDayOfWeek], in column order.
 *
 * Returned as [DayOfWeek] rather than as strings so the formatting (and its locale) stays in the
 * UI layer where a `Locale` is available.
 */
fun weekdayOrder(firstDayOfWeek: DayOfWeek): List<DayOfWeek> =
    (0 until 7).map { firstDayOfWeek.plus(it.toLong()) }
