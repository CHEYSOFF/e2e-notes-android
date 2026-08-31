package my.cheysoff.feature_settings

import my.cheysoff.feature_settings.model.SyncStatus
import my.cheysoff.feature_settings.model.syncCheckAvailable
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

    // -- the state machine ---------------------------------------------------------------------

    @Test
    fun `nothing is claimed until the pairing fact is known`() {
        assertEquals(
            SyncStatus.UNKNOWN,
            syncStatus(
                paired = null,
                storedUrl = url,
                storedUrlUsable = true,
                checking = false,
                lastCheckFailed = false,
            ),
        )
    }

    @Test
    fun `an unpaired device is not paired even with a server set`() {
        // Order matters: a stored address on an unpaired device is not a usable configuration, and
        // reporting anything else would send the user looking at their server.
        assertEquals(
            SyncStatus.NOT_PAIRED,
            syncStatus(
                paired = false,
                storedUrl = url,
                storedUrlUsable = true,
                checking = false,
                lastCheckFailed = false,
            ),
        )
    }

    @Test
    fun `paired with no stored address is NO_SERVER`() {
        assertEquals(
            SyncStatus.NO_SERVER,
            syncStatus(
                paired = true,
                storedUrl = null,
                storedUrlUsable = true,
                checking = false,
                lastCheckFailed = false,
            ),
        )
    }

    @Test
    fun `an empty stored address is never READY`() {
        // The regression this exists for: treating "" or null as configured would put the app in
        // its best-looking state with nowhere to send anything.
        listOf(null, "").forEach { stored ->
            val status = syncStatus(
                paired = true,
                storedUrl = stored,
                storedUrlUsable = false,
                checking = false,
                lastCheckFailed = false,
            )
            assertTrue("stored=<$stored> produced $status", status != SyncStatus.READY)
            assertFalse("stored=<$stored> offered a check", syncCheckAvailable(status))
        }
    }

    @Test
    fun `a stored address that no longer validates is its own state`() {
        assertEquals(
            SyncStatus.SERVER_UNUSABLE,
            syncStatus(
                paired = true,
                storedUrl = "http://192.168.1.10",
                storedUrlUsable = false,
                checking = false,
                lastCheckFailed = false,
            ),
        )
    }

    @Test
    fun `paired with a usable address is READY`() {
        assertEquals(
            SyncStatus.READY,
            syncStatus(
                paired = true,
                storedUrl = url,
                storedUrlUsable = true,
                checking = false,
                lastCheckFailed = false,
            ),
        )
    }

    @Test
    fun `a failed check is reported over READY`() {
        assertEquals(
            SyncStatus.UNREACHABLE,
            syncStatus(
                paired = true,
                storedUrl = url,
                storedUrlUsable = true,
                checking = false,
                lastCheckFailed = true,
            ),
        )
    }

    @Test
    fun `a check in flight outranks its own previous failure`() {
        assertEquals(
            SyncStatus.CHECKING,
            syncStatus(
                paired = true,
                storedUrl = url,
                storedUrlUsable = true,
                checking = true,
                lastCheckFailed = true,
            ),
        )
    }

    @Test
    fun `a check cannot appear to be running on an unpaired device`() {
        assertEquals(
            SyncStatus.NOT_PAIRED,
            syncStatus(
                paired = false,
                storedUrl = null,
                storedUrlUsable = true,
                checking = true,
                lastCheckFailed = false,
            ),
        )
    }

    // -- what may be done ----------------------------------------------------------------------

    @Test
    fun `the check action exists only where there is something to check`() {
        assertTrue(syncCheckAvailable(SyncStatus.READY))
        assertTrue(syncCheckAvailable(SyncStatus.UNREACHABLE))

        listOf(
            SyncStatus.UNKNOWN,
            SyncStatus.NOT_PAIRED,
            SyncStatus.NO_SERVER,
            SyncStatus.SERVER_UNUSABLE,
            SyncStatus.CHECKING,
        ).forEach { assertFalse("$it offered a check", syncCheckAvailable(it)) }
    }

    // -- the copy ------------------------------------------------------------------------------

    @Test
    fun `every state has a non-blank line`() {
        SyncStatus.entries.forEach { assertTrue("$it", syncStatusLine(it).isNotBlank()) }
    }

    @Test
    fun `no line claims anything was synced`() {
        // The one lie this screen must never tell. There is no sync engine in this build, so any
        // state that reads as "your notes are on the server" would be false for every user.
        SyncStatus.entries.forEach { status ->
            val line = syncStatusLine(status).lowercase()
            listOf("synced", "up to date", "backed up", "uploaded to").forEach { claim ->
                assertFalse("$status says \"$claim\": $line", line.contains(claim))
            }
        }
    }

    @Test
    fun `the best case says both that it is wired and that nothing moves`() {
        val line = syncStatusLine(SyncStatus.READY)
        assertTrue(line, line.contains("Ready"))
        assertTrue("must say nothing is uploaded: $line", line.contains("nothing is uploaded"))
    }

    @Test
    fun `a failed check is described as a failed check, not as a failed sync`() {
        val line = syncStatusLine(SyncStatus.UNREACHABLE).lowercase()
        assertTrue(line, line.contains("reach"))
        assertFalse("no sync was attempted: $line", line.contains("sync failed"))
    }
}
