package my.cheysoff.feature_settings

import my.cheysoff.core_domain.sync.SyncPassState
import my.cheysoff.core_domain.sync.SyncPassSummary
import my.cheysoff.feature_settings.model.SyncStatus
import my.cheysoff.feature_settings.model.syncCheckAvailable
import my.cheysoff.feature_settings.model.syncRetryAvailable
import my.cheysoff.feature_settings.model.syncStatus
import my.cheysoff.feature_settings.model.syncStatusLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The configured/unconfigured state machine behind the Sync section, and the words it produces.
 *
 * Both halves are tested, because both can lie. A wrong state sends the user to fix the wrong
 * thing; wrong copy tells them their notes are somewhere they are not.
 */
class SyncRowTest {

    private val url = "https://notes.example.com"

    private fun status(
        paired: Boolean? = true,
        storedUrl: String? = url,
        storedUrlUsable: Boolean = true,
        checking: Boolean = false,
        lastCheckFailed: Boolean = false,
        sync: SyncPassState = SyncPassState.Idle,
    ) = syncStatus(paired, storedUrl, storedUrlUsable, checking, lastCheckFailed, sync)

    // -- the state machine ---------------------------------------------------------------------

    @Test
    fun `nothing is claimed until the pairing fact is known`() {
        assertEquals(SyncStatus.UNKNOWN, status(paired = null))
    }

    @Test
    fun `an unpaired device is not paired even with a server set`() {
        // Order matters: a stored address on an unpaired device is not a usable configuration, and
        // reporting anything else would send the user looking at their server.
        assertEquals(SyncStatus.NOT_PAIRED, status(paired = false))
    }

    @Test
    fun `paired with no stored address is NO_SERVER`() {
        assertEquals(SyncStatus.NO_SERVER, status(storedUrl = null))
    }

    @Test
    fun `an empty stored address is never READY`() {
        // The regression this exists for: treating "" or null as configured would put the app in
        // its best-looking state with nowhere to send anything.
        listOf(null, "").forEach { stored ->
            val status = status(storedUrl = stored, storedUrlUsable = false)
            assertTrue("stored=<$stored> produced $status", status != SyncStatus.READY)
            assertFalse("stored=<$stored> offered a check", syncCheckAvailable(status))
        }
    }

    @Test
    fun `a stored address that no longer validates is its own state`() {
        assertEquals(
            SyncStatus.SERVER_UNUSABLE,
            status(storedUrl = "http://192.168.1.10", storedUrlUsable = false),
        )
    }

    @Test
    fun `paired with a usable address and nothing attempted is READY`() {
        assertEquals(SyncStatus.READY, status())
    }

    @Test
    fun `a failed check is reported over READY`() {
        assertEquals(SyncStatus.UNREACHABLE, status(lastCheckFailed = true))
    }

    @Test
    fun `a check in flight outranks its own previous failure`() {
        assertEquals(SyncStatus.CHECKING, status(checking = true, lastCheckFailed = true))
    }

    @Test
    fun `a check cannot appear to be running on an unpaired device`() {
        assertEquals(SyncStatus.NOT_PAIRED, status(paired = false, storedUrl = null, checking = true))
    }

    // -- the engine's own states -----------------------------------------------------------------

    @Test
    fun `a running pass is reported as running`() {
        assertEquals(SyncStatus.SYNCING, status(sync = SyncPassState.Running))
    }

    @Test
    fun `a completed pass outranks READY, because something actually happened`() {
        assertEquals(SyncStatus.SYNC_RAN, status(sync = SyncPassState.Completed(SyncPassSummary())))
    }

    /**
     * The reverse, which is the one that would mislead: a pass that completed is a fact about a
     * moment that has passed, and a check the user just ran and watched fail is about right now.
     * Letting the pass win would leave the section reading "the last sync sent 3" over a server
     * that is not answering.
     */
    @Test
    fun `a failed check outranks an older completed pass`() {
        assertEquals(
            SyncStatus.UNREACHABLE,
            status(lastCheckFailed = true, sync = SyncPassState.Completed(SyncPassSummary(pushed = 3))),
        )
    }

    /**
     * A halt outranks everything the engine can otherwise report, and outranks a check in flight.
     * Every other line would read as "things are fine" while nothing is syncing at all and nothing
     * will until a person intervenes.
     */
    @Test
    fun `a halt outranks every other engine state and the check`() {
        assertEquals(
            SyncStatus.HALTED,
            status(checking = true, sync = SyncPassState.Halted("The server's history is older.")),
        )
    }

    /**
     * The prerequisites still come first. A halt recorded before the user cleared their server
     * address must not hide the fact that there is no address — that is the thing they can fix.
     */
    @Test
    fun `a missing prerequisite outranks a halt`() {
        assertEquals(
            SyncStatus.NO_SERVER,
            status(storedUrl = null, sync = SyncPassState.Halted("stopped")),
        )
    }

    @Test
    fun `a deferred pass is its own state, not a failed check`() {
        assertEquals(
            SyncStatus.SYNC_INTERRUPTED,
            status(sync = SyncPassState.Deferred("Couldn't reach the server.")),
        )
    }

    @Test
    fun `a pass that could not start is distinct from one that stopped early`() {
        assertEquals(
            SyncStatus.CANNOT_SYNC,
            status(sync = SyncPassState.Unavailable("Not authorised on the account.")),
        )
    }

    // -- what may be done ----------------------------------------------------------------------

    @Test
    fun `the check action exists only where there is something to check`() {
        listOf(SyncStatus.READY, SyncStatus.UNREACHABLE, SyncStatus.SYNC_RAN, SyncStatus.HALTED)
            .forEach { assertTrue("$it refused a check", syncCheckAvailable(it)) }

        listOf(
            SyncStatus.UNKNOWN,
            SyncStatus.NOT_PAIRED,
            SyncStatus.NO_SERVER,
            SyncStatus.SERVER_UNUSABLE,
            SyncStatus.CHECKING,
            SyncStatus.SYNCING,
        ).forEach { assertFalse("$it offered a check", syncCheckAvailable(it)) }
    }

    // -- the copy ------------------------------------------------------------------------------

    @Test
    fun `every state has a non-blank line`() {
        SyncStatus.entries.forEach { assertTrue("$it", syncStatusLine(it).isNotBlank()) }
    }

    /**
     * The rule this screen lives under, restated for a build in which sync is real.
     *
     * It used to be "no line may claim anything was synced", because nothing was. Now something is,
     * and the rule that replaces it is narrower and harder: **a line may report an event, never a
     * state of the world.** "Sent 3, applied 2" is a fact about a pass that finished and can be
     * checked against what the engine returned. "Synced", "up to date" and "backed up" are claims
     * about where the user's notes *are*, and no pass can support one: it says nothing about the
     * notes written since it ran, about the other device that has been offline for a week, or about
     * whether the server still holds what it acknowledged.
     *
     * The forbidden list is unchanged from the version of this test that predates the engine, and
     * that is the point — the words were the wrong words then because nothing had synced, and they
     * are the wrong words now because they say more than a sync pass can prove.
     */
    @Test
    fun `no line claims a state of the world rather than an event`() {
        val everyLine = SyncStatus.entries.flatMap { status ->
            listOf(
                syncStatusLine(status),
                syncStatusLine(status, SyncPassState.Completed(SyncPassSummary(pushed = 3, applied = 2))),
                syncStatusLine(status, SyncPassState.Completed(SyncPassSummary())),
                syncStatusLine(status, SyncPassState.Halted("stopped")),
                syncStatusLine(status, SyncPassState.Deferred("no network")),
            ).map { status to it }
        }

        everyLine.forEach { (status, line) ->
            listOf("synced", "up to date", "backed up", "uploaded to").forEach { claim ->
                assertFalse("$status says \"$claim\": $line", line.lowercase().contains(claim))
            }
        }
    }

    /**
     * The completed line reports the pass's counts and nothing else. It is the only line in this
     * file that describes an outcome, so it is the only one that could overstate.
     */
    @Test
    fun `a completed pass reports exactly what it moved`() {
        val line = syncStatusLine(
            SyncStatus.SYNC_RAN,
            SyncPassState.Completed(SyncPassSummary(pushed = 3, applied = 2, received = 2)),
        )
        assertTrue(line, line.contains("sent 3"))
        assertTrue(line, line.contains("applied 2"))
    }

    /**
     * A pass that moved nothing says so, and does **not** say the account is up to date. Finding
     * nothing to do proves this device and the server agreed at that moment; it proves nothing
     * about a second device that has not synced yet.
     */
    @Test
    fun `a pass that moved nothing says nothing moved`() {
        val line = syncStatusLine(SyncStatus.SYNC_RAN, SyncPassState.Completed(SyncPassSummary()))
        assertTrue(line, line.contains("nothing to send or receive"))
    }

    /** A conflict copy is a note the user now has and did not make. It has to be visible. */
    @Test
    fun `a conflict copy is reported`() {
        val line = syncStatusLine(
            SyncStatus.SYNC_RAN,
            SyncPassState.Completed(SyncPassSummary(applied = 1, conflictCopies = 1)),
        )
        assertTrue(line, line.contains("conflicting copy"))
    }

    /** Records that would not open are the early warning for a halt, so they are never hidden. */
    @Test
    fun `unreadable records are reported`() {
        val line = syncStatusLine(
            SyncStatus.SYNC_RAN,
            SyncPassState.Completed(SyncPassSummary(received = 2, unreadable = 2)),
        )
        assertTrue(line, line.contains("couldn't read 2"))
    }

    /**
     * A device a version behind must say so. The records are not lost and nothing is broken, but
     * "some notes here are missing parts this app cannot show" is a fact the person needs in order
     * to know the answer is to update -- and it is invisible on the screen that would otherwise
     * report a completely successful sync.
     */
    @Test
    fun `a pass that ignored records says so, and points at the reason`() {
        val line = syncStatusLine(
            SyncStatus.SYNC_RAN,
            SyncPassState.Completed(SyncPassSummary(received = 4, applied = 3, ignored = 1)),
        )

        assertTrue("the count must appear: $line", line.contains("1"))
        assertTrue(
            "and it must name the cause rather than sounding like damage: $line",
            line.contains("newer version", ignoreCase = true),
        )
        assertFalse("it is not a failure: $line", line.contains("failed", ignoreCase = true))
    }

    /**
     * A halt shows the engine's own sentence, because each halt reason names a different thing a
     * person has to decide and none of them clears by itself.
     */
    @Test
    fun `a halt shows the engine's reason rather than a generic line`() {
        val message = "The server's history is older than this device's."
        assertTrue(syncStatusLine(SyncStatus.HALTED, SyncPassState.Halted(message)).contains(message))
    }

    @Test
    fun `the ready line says what is wired and when a sync happens, and promises nothing else`() {
        val line = syncStatusLine(SyncStatus.READY)
        assertTrue(line, line.contains("Ready"))
        assertTrue("must say when a sync happens: $line", line.contains("unlock"))
    }

    @Test
    fun `a failed check is described as a failed check, not as a failed sync`() {
        val line = syncStatusLine(SyncStatus.UNREACHABLE).lowercase()
        assertTrue(line, line.contains("reach"))
        assertFalse("no sync was attempted: $line", line.contains("sync failed"))
    }

    /**
     * "Try again" is offered in exactly one state, and it is the one where nothing else works.
     *
     * A halt makes every other control inert: the engine refuses at the top of each pass, so a
     * pull-to-refresh, the timer and another unlock all provably do nothing. This is the only
     * thing that can change the situation — and offering it anywhere else would be a button with
     * no halt to clear.
     */
    @Test
    fun `retry is offered only while the engine is halted`() {
        assertTrue("a halt is the one state it belongs in", syncRetryAvailable(SyncStatus.HALTED))

        SyncStatus.entries
            .filter { it != SyncStatus.HALTED }
            .forEach { assertFalse("$it offered a retry", syncRetryAvailable(it)) }
    }
}
