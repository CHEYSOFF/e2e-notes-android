package my.cheysoff.feature_notes

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NoteContentFormat
import my.cheysoff.feature_notes.model.list.NotesListIntent
import my.cheysoff.feature_notes.ui.list.NotesListViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

/**
 * The Calendar tab's half of [NotesListViewModel]: day bucketing, the month/day selection, and
 * the undated-note count.
 *
 * The waiting strategy and the "every note is PLAIN" rule are the same as in
 * [NotesListViewModelTest], and for the same reasons — the calendar pipeline also ends in
 * `.flowOn(Dispatchers.Default)`, so its upstream half runs on the real pool and is not reachable
 * by the virtual clock.
 *
 * Timestamps here are built from [LocalDate]s in the SYSTEM DEFAULT zone, because that is the zone
 * the production pipeline reads. Hard-coding UTC millis would make these tests pass or fail
 * depending on where the machine running them happens to be. (The zone-conversion rules themselves
 * are pinned against explicit zones in [CalendarGroupingTest]; this file is about the wiring.)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesListCalendarTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val notesRepo = FakeNotesRepository()
    private val settingsRepo = FakeSettingsRepository()
    private val syncController = FakeSyncController()

    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** Midday on [date] in the zone the ViewModel will read, as epoch millis. */
    private fun at(date: LocalDate): Long =
        date.atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()

    private fun note(id: String, updatedAt: Long, title: String = id) = Note(
        id = id,
        title = title,
        content = "",
        // PLAIN keeps HtmlCompat (android.text.Html, stubbed in a JVM test) out of the pipeline.
        contentFormat = NoteContentFormat.PLAIN,
        updatedAt = updatedAt,
    )

    private fun viewModel() = NotesListViewModel(notesRepo, settingsRepo, syncController)

    private fun currentNotes(vararg notes: Note) {
        notesRepo.notesFor(settingsRepo.notesSortOrder.value).value = notes.toList()
    }

    private fun TestScope.awaitState(
        reason: String,
        timeoutMillis: Long = 5_000,
        predicate: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (true) {
            advanceUntilIdle()
            if (predicate()) return
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for: $reason")
            Thread.sleep(2)
        }
    }

    // --- opening the tab ---------------------------------------------------

    @Test
    fun `the calendar opens on today with today selected`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = viewModel()
            awaitState("the first calendar emission") { vm.state.value.calendarMonth != null }

            val today = LocalDate.now(zone)
            assertEquals(today, vm.state.value.calendarSelectedDay)
            assertEquals(YearMonth.from(today), vm.state.value.calendarMonth)
        }

    // --- bucketing ---------------------------------------------------------

    @Test
    fun `day counts group the notes by the day they were last updated`() =
        runTest(mainDispatcherRule.dispatcher) {
            val today = LocalDate.now(zone)
            val yesterday = today.minusDays(1)
            currentNotes(
                note("a", at(today)),
                note("b", at(today)),
                note("c", at(yesterday)),
            )
            val vm = viewModel()
            awaitState("counts") { vm.state.value.calendarCounts.isNotEmpty() }

            assertEquals(2, vm.state.value.calendarCounts[today])
            assertEquals(1, vm.state.value.calendarCounts[yesterday])
            assertEquals(
                "a day with no notes must be absent, not zero",
                null,
                vm.state.value.calendarCounts[today.minusDays(2)],
            )
        }

    @Test
    fun `the selected day lists exactly that day's notes`() =
        runTest(mainDispatcherRule.dispatcher) {
            val today = LocalDate.now(zone)
            val yesterday = today.minusDays(1)
            currentNotes(
                note("today-1", at(today)),
                note("yesterday-1", at(yesterday)),
                note("today-2", at(today)),
            )
            val vm = viewModel()
            awaitState("today's notes") { vm.state.value.calendarDayNotes.isNotEmpty() }

            assertEquals(
                listOf("today-1", "today-2"),
                vm.state.value.calendarDayNotes.map { it.id },
            )

            vm.onIntent(NotesListIntent.CalendarDaySelected(yesterday))
            awaitState("yesterday's notes") {
                vm.state.value.calendarSelectedDay == yesterday &&
                    vm.state.value.calendarDayNotes.map { it.id } == listOf("yesterday-1")
            }
        }

    @Test
    fun `a day with no notes selects cleanly with an empty list`() =
        runTest(mainDispatcherRule.dispatcher) {
            val today = LocalDate.now(zone)
            currentNotes(note("a", at(today)))
            val vm = viewModel()
            awaitState("today's notes") { vm.state.value.calendarDayNotes.isNotEmpty() }

            val empty = today.minusDays(3)
            vm.onIntent(NotesListIntent.CalendarDaySelected(empty))
            awaitState("the empty day") { vm.state.value.calendarSelectedDay == empty }
            assertTrue(vm.state.value.calendarDayNotes.isEmpty())
            // The other days' counts must survive selecting an empty one.
            assertEquals(1, vm.state.value.calendarCounts[today])
        }

    // --- moving the grid ---------------------------------------------------

    @Test
    fun `stepping months carries the selected day into the month on screen`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = viewModel()
            awaitState("the first calendar emission") { vm.state.value.calendarMonth != null }
            val startMonth = vm.state.value.calendarMonth!!
            val startDay = vm.state.value.calendarSelectedDay!!

            vm.onIntent(NotesListIntent.CalendarPreviousMonth)
            awaitState("previous month") {
                vm.state.value.calendarMonth == startMonth.minusMonths(1)
            }
            // The selection must stay inside the month being shown, or the day-list under the
            // grid would describe a day that is not on the grid.
            val moved = vm.state.value.calendarSelectedDay!!
            assertEquals(YearMonth.from(moved), vm.state.value.calendarMonth)
            assertEquals(
                "the day-of-month carries across",
                startDay.dayOfMonth,
                moved.dayOfMonth,
            )
        }

    @Test
    fun `stepping onto a shorter month clamps the day instead of throwing`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = viewModel()
            awaitState("the first calendar emission") { vm.state.value.calendarMonth != null }

            // Land on 31 January, then step forward into February. Selecting the day directly
            // also moves the grid to January, so this is one intent, not two.
            val jan31 = LocalDate.of(2027, 1, 31)
            vm.onIntent(NotesListIntent.CalendarDaySelected(jan31))
            awaitState("31 January selected") { vm.state.value.calendarSelectedDay == jan31 }

            vm.onIntent(NotesListIntent.CalendarNextMonth)
            awaitState("February") {
                vm.state.value.calendarMonth == YearMonth.of(2027, 2)
            }
            // 2027 is not a leap year, so February ends on the 28th.
            assertEquals(LocalDate.of(2027, 2, 28), vm.state.value.calendarSelectedDay)
        }

    @Test
    fun `stepping across a year boundary keeps going`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = viewModel()
            awaitState("the first calendar emission") { vm.state.value.calendarMonth != null }
            val startMonth = vm.state.value.calendarMonth!!

            // Thirteen, not twelve, so the result is guaranteed to be both a different year and a
            // different month than the start — twelve would land on the same month and could pass
            // against an implementation that ignored the year entirely.
            repeat(13) { vm.onIntent(NotesListIntent.CalendarPreviousMonth) }
            awaitState("thirteen months back") {
                vm.state.value.calendarMonth == startMonth.minusMonths(13)
            }
            val landed = vm.state.value.calendarMonth!!
            assertTrue("the year must have changed", landed.year < startMonth.year)
            assertTrue("the month must have changed", landed.month != startMonth.month)
        }

    @Test
    fun `selecting a day outside the shown month brings the grid to it`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = viewModel()
            awaitState("the first calendar emission") { vm.state.value.calendarMonth != null }
            val startMonth = vm.state.value.calendarMonth!!

            // The grid draws leading/trailing cells from the neighbouring months, so this is a
            // tap the user can really make.
            val elsewhere = startMonth.minusMonths(1).atDay(15)
            vm.onIntent(NotesListIntent.CalendarDaySelected(elsewhere))
            awaitState("the grid follows the selection") {
                vm.state.value.calendarSelectedDay == elsewhere
            }
            assertEquals(
                "selecting a day in another month must bring the grid with it",
                YearMonth.from(elsewhere),
                vm.state.value.calendarMonth,
            )
        }

    // --- undated notes -----------------------------------------------------

    @Test
    fun `notes with no timestamp are counted rather than filed under 1970`() =
        runTest(mainDispatcherRule.dispatcher) {
            val today = LocalDate.now(zone)
            currentNotes(
                note("dated", at(today)),
                note("legacy-1", updatedAt = 0L),
                note("legacy-2", updatedAt = 0L),
            )
            val vm = viewModel()
            awaitState("the undated count") { vm.state.value.calendarUndatedCount > 0 }

            assertEquals(2, vm.state.value.calendarUndatedCount)
            assertEquals(
                "an unset timestamp must not create a 1970 bucket",
                null,
                vm.state.value.calendarCounts[LocalDate.of(1970, 1, 1)],
            )
            assertEquals(setOf(today), vm.state.value.calendarCounts.keys)
        }

    // --- staying live ------------------------------------------------------

    @Test
    fun `the grid re-buckets when the notes change underneath it`() =
        runTest(mainDispatcherRule.dispatcher) {
            val today = LocalDate.now(zone)
            val yesterday = today.minusDays(1)
            currentNotes(note("a", at(yesterday)))
            val vm = viewModel()
            awaitState("the first counts") { vm.state.value.calendarCounts.isNotEmpty() }
            assertEquals(1, vm.state.value.calendarCounts[yesterday])

            // The note is edited: the same row comes back with today's timestamp.
            currentNotes(note("a", at(today)))
            awaitState("the note moves to today") {
                vm.state.value.calendarCounts[today] == 1
            }
            assertEquals(
                "the old day must lose its count, not keep a stale one",
                null,
                vm.state.value.calendarCounts[yesterday],
            )
        }

    @Test
    fun `the calendar spans every folder rather than honouring the folder chips`() =
        runTest(mainDispatcherRule.dispatcher) {
            val today = LocalDate.now(zone)
            currentNotes(
                note("filed", at(today)).copy(folderId = "work"),
                note("loose", at(today)),
            )
            val vm = viewModel()
            awaitState("counts") { vm.state.value.calendarCounts.isNotEmpty() }

            // Select a folder the way the chips would, then check the calendar is unaffected:
            // the chips are not drawn in this tab, so a filter here would be invisible and
            // un-clearable.
            vm.onIntent(NotesListIntent.FolderClicked("work"))
            advanceUntilIdle()

            assertEquals(2, vm.state.value.calendarCounts[today])
            assertEquals(
                listOf("filed", "loose"),
                vm.state.value.calendarDayNotes.map { it.id },
            )
        }
}
