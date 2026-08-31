package my.cheysoff.core_domain.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [HlcGenerator]'s single guarantee: every clock it returns is strictly greater than every clock
 * it has already returned or been shown.
 *
 * The tests are written against a **hostile wall clock**, because the real one is: it is
 * user-settable in Settings, it is stepped by NTP, and it restarts from nothing on a device with a
 * dead battery. `LockoutPolicy` and `TrashPolicy` already carry that assumption; this file holds
 * the clock generator to the same standard, and unlike those two the cost of failure here is not
 * an early unlock or a late purge but a silently discarded edit.
 */
class HlcGeneratorTest {

    private val node = "testnode"

    private fun generator() = HlcGenerator { node }

    @Test
    fun `a forward-moving clock is taken at face value`() {
        val g = generator()
        assertEquals(Hlc(100L, 0, node), g.next(100L))
        assertEquals(Hlc(200L, 0, node), g.next(200L))
        assertEquals(Hlc(300L, 0, node), g.next(300L))
    }

    @Test
    fun `two writes in the same millisecond are told apart by the counter`() {
        val g = generator()
        assertEquals(Hlc(100L, 0, node), g.next(100L))
        assertEquals(Hlc(100L, 1, node), g.next(100L))
        assertEquals(Hlc(100L, 2, node), g.next(100L))
    }

    @Test
    fun `the counter resets once the wall clock moves on`() {
        val g = generator()
        g.next(100L)
        g.next(100L)
        assertEquals(Hlc(101L, 0, node), g.next(101L))
    }

    /**
     * The headline case: the user winds the device clock back a day mid-session.
     *
     * The physical component holds and the counter advances instead, so the clock still increases.
     * A generator that simply used the wall clock would emit a clock a day in the past, and the
     * edit it stamped would lose to the note's own previous version on the next merge — deleted,
     * with no error, on every device.
     */
    @Test
    fun `a wall clock that jumps backwards never produces a smaller clock`() {
        val g = generator()
        val start = g.next(1_000_000L)

        val dayEarlier = g.next(1_000_000L - 86_400_000L)

        assertTrue("the clock went backwards: $start then $dayEarlier", dayEarlier > start)
        assertEquals("the physical part must hold, not follow the rewound clock", 1_000_000L, dayEarlier.ms)
        assertEquals(1, dayEarlier.counter)
    }

    @Test
    fun `a clock rewound to before the epoch still produces an increasing clock`() {
        // Settings will accept a date in 1969, and Hlc refuses a negative physical component, so
        // this is the case where "floor at the last value" has to be doing real work.
        val g = generator()
        val start = g.next(1_000L)
        val negative = g.next(-5_000L)
        assertTrue(negative > start)
        assertEquals(1_000L, negative.ms)
    }

    @Test
    fun `a long run of hostile clock readings is strictly increasing throughout`() {
        val g = generator()
        // Forward jumps, backward jumps, repeats, and a return to the far past.
        val readings = listOf(
            10L, 11L, 11L, 11L, 5L, 4L, 3L, 12L, 12L, 0L, 1L, 100L, 99L, 100L, 100L, 50L,
        )
        val issued = readings.map { g.next(it) }
        issued.zipWithNext { earlier, later ->
            assertTrue("not increasing: $earlier then $later", later > earlier)
        }
    }

    @Test
    fun `observe makes the next clock beat what was seen`() {
        val g = generator()
        g.observe(Hlc(5_000L, 7, "otherdevice"))

        val next = g.next(1_000L)

        assertTrue(next > Hlc(5_000L, 7, "otherdevice"))
        assertEquals(5_000L, next.ms)
        assertEquals(8, next.counter)
    }

    @Test
    fun `observing something older changes nothing`() {
        val g = generator()
        val first = g.next(9_000L)
        g.observe(Hlc(3L, 0, "otherdevice"))
        val second = g.next(9_000L)
        assertEquals(Hlc(9_000L, 1, node), second)
        assertTrue(second > first)
    }

    @Test
    fun `observing the same millisecond with a higher counter advances only the counter`() {
        val g = generator()
        g.next(500L)                                  // (500, 0)
        g.observe(Hlc(500L, 4, "otherdevice"))
        assertEquals(Hlc(500L, 5, node), g.next(500L))
    }

    /**
     * The counter is an Int, and an Int overflows to a negative number — which would both violate
     * [Hlc]'s own invariant and, far worse, make the clock go backwards. Reaching this needs 2^31
     * writes inside one millisecond, so it is unreachable in production and cheap to make correct.
     */
    @Test
    fun `an exhausted counter carries into the next millisecond instead of overflowing`() {
        val g = generator()
        g.observe(Hlc(700L, Int.MAX_VALUE, node))

        val next = g.next(700L)

        assertEquals(701L, next.ms)
        assertEquals(0, next.counter)
        assertTrue(next > Hlc(700L, Int.MAX_VALUE, node))
    }

    @Test
    fun `a node that changes mid-session does not disturb the ordering`() {
        // The node arrives at unlock and changes when the device joins an account, so this happens
        // for real. (ms, counter) is strictly increasing on its own, so the node — which sorts
        // last and only breaks exact ties — can never invert two of this generator's own clocks.
        var currentNode = "zzzz"
        val g = HlcGenerator { currentNode }
        val first = g.next(1_000L)
        currentNode = "aaaa"
        val second = g.next(1_000L)
        assertTrue("a node change inverted two clocks: $first then $second", second > first)
    }

    /**
     * Room's write coroutines are not confined to one thread, so two writes really can call this
     * concurrently. Without the lock, two of them can read the same `lastCounter` and both return
     * it — one clock, two different note versions, and no way for a merge to tell them apart.
     */
    @Test
    fun `concurrent callers never receive the same clock twice`() {
        val g = generator()
        val threads = 8
        val perThread = 500
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val issued = CopyOnWriteArrayList<Hlc>()
        repeat(threads) {
            pool.submit {
                start.await()
                // One frozen wall-clock reading for everybody, so every clock has to be separated
                // by the counter rather than by the passage of time.
                repeat(perThread) { issued += g.next(1_000L) }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue("the pool did not finish", pool.awaitTermination(30, TimeUnit.SECONDS))

        assertEquals(threads * perThread, issued.size)
        assertEquals("a clock was issued twice", issued.size, issued.toSet().size)
    }
}
