package my.cheysoff.core_domain.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule both apps read before running a pass nobody asked for.
 *
 * Small enough to look obvious, which is the reason to pin it: it is the one piece of the periodic
 * trigger that exists in a single copy, and the failure it prevents — two apps disagreeing about
 * when to sync — is invisible on either device alone.
 */
class PeriodicSyncPolicyTest {

    @Test
    fun `an idle app syncs`() {
        assertTrue(PeriodicSyncPolicy.shouldRun(passRunning = false, halted = false))
    }

    @Test
    fun `a tick during a pass is dropped, not queued`() {
        // The running pass is already doing this tick's work. A second one would push the same
        // dirty rows and take a 409 on every one of them.
        assertFalse(PeriodicSyncPolicy.shouldRun(passRunning = true, halted = false))
    }

    @Test
    fun `a halted engine is not poked once a minute`() {
        // A halt clears only when a person deals with it, so ticking at it forever rewrites the
        // same state and tells nobody anything.
        assertFalse(PeriodicSyncPolicy.shouldRun(passRunning = false, halted = true))
        assertFalse(PeriodicSyncPolicy.shouldRun(passRunning = true, halted = true))
    }

    @Test
    fun `the interval is a minute, and long enough to be worth waiting for`() {
        // Pinned because both apps read it and neither would notice the other changing it. The
        // lower bound is the one that matters: a short interval spends a request, a wakeup and a
        // round trip per device to be told nothing changed.
        assertEquals(60_000L, PeriodicSyncPolicy.INTERVAL_MS)
        assertTrue(PeriodicSyncPolicy.INTERVAL_MS >= 30_000L)
    }
}
