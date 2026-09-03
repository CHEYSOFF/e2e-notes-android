package my.cheysoff.notes.sync

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import my.cheysoff.core_domain.sync.PeriodicSyncPolicy
import my.cheysoff.core_domain.sync.SyncController
import my.cheysoff.core_domain.sync.SyncPassState
import my.cheysoff.core_domain.sync.SyncPassSummary
import my.cheysoff.core_domain.sync.SyncTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The timer that makes a note written on another device turn up here on its own.
 *
 * All of it is timing, so all of it runs on virtual time: the real interval is a minute, and a test
 * that waited one would be a test nobody runs. `runTest` skips the delays outright, which means
 * these assertions are about *ordering and counts*, not about elapsed wall time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PeriodicSyncLoopTest {

    private val interval = PeriodicSyncPolicy.INTERVAL_MS

    @Test
    fun `it waits a full interval before the first pass`() = runTest {
        val unlocked = MutableStateFlow(true)
        val controller = RecordingController()
        val loop = launch { periodicSyncLoop(unlocked, controller, interval) }

        advanceTimeBy(interval - 1)
        assertEquals(
            "an unlock has just triggered a pass of its own; firing here would double every unlock",
            0,
            controller.triggers.size,
        )

        advanceTimeBy(2)
        assertEquals(listOf(SyncTrigger.PERIODIC), controller.triggers)

        loop.cancelAndJoin()
    }

    @Test
    fun `it keeps going`() = runTest {
        val unlocked = MutableStateFlow(true)
        val controller = RecordingController()
        val loop = launch { periodicSyncLoop(unlocked, controller, interval) }

        advanceTimeBy(interval * 3 + 1)
        assertEquals(3, controller.triggers.size)
        assertTrue(controller.triggers.all { it == SyncTrigger.PERIODIC })

        loop.cancelAndJoin()
    }

    @Test
    fun `locking stops it, and unlocking starts it again`() = runTest {
        val unlocked = MutableStateFlow(true)
        val controller = RecordingController()
        val loop = launch { periodicSyncLoop(unlocked, controller, interval) }

        advanceTimeBy(interval + 1)
        assertEquals(1, controller.triggers.size)

        // A timer that outlived the lock would run against zeroed keys and a closed database once a
        // minute, forever. This is the assertion that says it does not.
        unlocked.value = false
        advanceTimeBy(interval * 5)
        assertEquals("no pass may run while locked", 1, controller.triggers.size)

        unlocked.value = true
        advanceTimeBy(interval + 1)
        assertEquals(2, controller.triggers.size)

        loop.cancelAndJoin()
    }

    @Test
    fun `the interval is measured from the end of a pass, not the start of one`() = runTest {
        val unlocked = MutableStateFlow(true)
        // A pass slower than the interval itself -- a bad connection, a large first pull.
        val controller = RecordingController(passDurationMs = interval * 2)
        val loop = launch { periodicSyncLoop(unlocked, controller, interval) }

        // First tick at 1x fires a pass that does not finish until 3x. Were the loop launching and
        // sleeping, ticks at 2x and 3x would have queued up behind it.
        advanceTimeBy(interval * 3 + 1)
        assertEquals(1, controller.triggers.size)

        // The next one comes an interval after that pass ended, not on the original cadence.
        advanceTimeBy(interval)
        assertEquals(2, controller.triggers.size)

        loop.cancelAndJoin()
    }

    @Test
    fun `a tick while a pass is already running is skipped`() = runTest {
        val unlocked = MutableStateFlow(true)
        val controller = RecordingController()
        // Something else -- an unlock, a pull-to-refresh -- is mid-pass when the timer comes round.
        controller.passState.value = SyncPassState.Running
        val loop = launch { periodicSyncLoop(unlocked, controller, interval) }

        advanceTimeBy(interval + 1)
        assertEquals("the running pass is doing this tick's work", 0, controller.triggers.size)

        controller.passState.value = SyncPassState.Idle
        advanceTimeBy(interval)
        assertEquals(1, controller.triggers.size)

        loop.cancelAndJoin()
    }

    @Test
    fun `a halted engine is left alone`() = runTest {
        val unlocked = MutableStateFlow(true)
        val controller = RecordingController()
        controller.passState.value = SyncPassState.Halted("The server was rolled back.")
        val loop = launch { periodicSyncLoop(unlocked, controller, interval) }

        advanceTimeBy(interval * 10)
        assertEquals(
            "a halt clears only when a person deals with it",
            0,
            controller.triggers.size,
        )

        loop.cancelAndJoin()
    }

    private class RecordingController(
        private val passDurationMs: Long = 0,
    ) : SyncController {

        val triggers = mutableListOf<SyncTrigger>()
        val passState = MutableStateFlow<SyncPassState>(SyncPassState.Idle)
        override val state: StateFlow<SyncPassState> = passState.asStateFlow()

        override fun requestSync(trigger: SyncTrigger) {
            throw AssertionError("the loop must suspend on a pass, not fire and forget")
        }

        override suspend fun clearHaltAndSync(): SyncPassState {
            // A halt is cleared only when a person asks. A timer that cleared one would turn a
            // deliberate stop into a halt-resume loop against the very server it refused to trust.
            throw AssertionError("the timer must never clear a halt")
        }

        override suspend fun syncNow(trigger: SyncTrigger): SyncPassState {
            triggers += trigger
            if (passDurationMs > 0) {
                passState.value = SyncPassState.Running
                kotlinx.coroutines.delay(passDurationMs)
            }
            passState.value = SyncPassState.Completed(SyncPassSummary())
            return passState.value
        }
    }
}
