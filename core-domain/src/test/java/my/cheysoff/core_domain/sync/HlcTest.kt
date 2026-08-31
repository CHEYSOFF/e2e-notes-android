package my.cheysoff.core_domain.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Hlc]: the ordering, and the round trip through the wire form.
 *
 * Both are things a merge on another device depends on being identical to this one's. A wrong
 * comparison makes two replicas each decide the other's write lost, and a `toString`/`parse` pair
 * that disagree produce records that decrypt on the device that wrote them and nowhere else,
 * because the clock string is an input to the envelope's associated data.
 */
class HlcTest {

    private fun hlc(ms: Long, counter: Int = 0, node: String = "n") = Hlc(ms, counter, node)

    // ── Ordering ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the physical component decides first`() {
        assertTrue(hlc(2) > hlc(1))
        // …even when the earlier one has a much higher counter, which is what makes the clock
        // track real time rather than write volume.
        assertTrue(hlc(2, counter = 0) > hlc(1, counter = 9_999))
    }

    @Test
    fun `the counter decides inside one millisecond`() {
        assertTrue(hlc(1, counter = 1) > hlc(1, counter = 0))
    }

    @Test
    fun `the node breaks a full tie, deterministically`() {
        // Two devices writing in the same millisecond with the same counter is the one case where
        // an ordering has to be invented. It only has to be the SAME invention on both devices.
        assertTrue(hlc(1, 0, "b") > hlc(1, 0, "a"))
        assertTrue(hlc(1, 0, "a") < hlc(1, 0, "b"))
        assertEquals(0, hlc(1, 0, "a").compareTo(hlc(1, 0, "a")))
    }

    @Test
    fun `an empty node sorts below any real one`() {
        // A device with no account key publishes an empty node (see HlcNode). It must still order
        // against a device that has one rather than throwing or comparing equal.
        assertTrue(hlc(1, 0, "") < hlc(1, 0, "0"))
    }

    @Test
    fun `the zero clock is below every stamped one`() {
        // Every row migrated into v7 carries this, and it must lose to a genuine remote edit —
        // the row is still dirty, so its content is merged rather than dropped.
        assertTrue(Hlc.ZERO < hlc(1, 0, ""))
        assertEquals(Hlc(0L, 0, ""), Hlc.ZERO)
    }

    @Test
    fun `sorting a shuffled list agrees with the pairwise comparison`() {
        val ascending = listOf(
            Hlc(1L, 0, "a"),
            Hlc(1L, 0, "b"),
            Hlc(1L, 1, "a"),
            Hlc(2L, 0, "a"),
        )
        assertEquals(ascending, ascending.reversed().sorted())
    }

    // ── Wire form ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `toString is the canonical ms-counter-node form`() {
        assertEquals("1756612345678-3-a1b2c3d4", Hlc(1_756_612_345_678L, 3, "a1b2c3d4").toString())
    }

    @Test
    fun `parse is the exact inverse of toString`() {
        val samples = listOf(
            Hlc(0L, 0, ""),
            Hlc(1L, 0, "a"),
            Hlc(1_756_612_345_678L, 2_147_483_647, "a1b2c3d4e5f6a7b8"),
        )
        samples.forEach { assertEquals(it, Hlc.parse(it.toString())) }
    }

    @Test
    fun `parse rejects everything that is not a clock`() {
        listOf(
            "",                    // empty
            "123",                 // no separators at all
            "123-4",               // only one separator
            "-4-node",             // no physical component
            "abc-4-node",          // physical component is not a number
            "123-abc-node",        // counter is not a number
            "-1--1-node",          // negative components
            "99999999999999999999999-0-node", // overflows Long
            "7-1-a-b",             // a node the constructor itself would refuse
        ).forEach { assertNull("'$it' parsed as a clock", Hlc.parse(it)) }
    }

    // ── Invariants ────────────────────────────────────────────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `a negative physical component is rejected`() {
        Hlc(-1L, 0, "n")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative counter is rejected`() {
        Hlc(1L, -1, "n")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a node containing the separator is rejected at construction`() {
        // Constructing one is a bug; parsing one back is not, which is why only this direction
        // throws.
        Hlc(1L, 0, "a-b")
    }
}
