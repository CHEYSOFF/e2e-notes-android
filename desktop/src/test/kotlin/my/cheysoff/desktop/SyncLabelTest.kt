package my.cheysoff.desktop

import my.cheysoff.desktop.app.DesktopSyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The words the title bar puts on syncing.
 *
 * Copy gets its own test here for the same reason the Android Sync row does: this app has exactly
 * one way of knowing the server holds what this device holds, and that is a pass that completed. A
 * label promising more than that is a lie the user acts on -- they stop worrying about a backup
 * that is not happening.
 */
class SyncLabelTest {

    @Test
    fun `a device that cannot sync gets no label at all`() {
        // Not "Not syncing", not a disabled button: nothing. The reason a device cannot sync --
        // never paired, or a stored address that stopped validating -- is not something a greyed
        // out control can explain, and offering one invites pressing it.
        assertNull(syncLabelOf(DesktopSyncState.Unavailable))
    }

    @Test
    fun `a completed pass says what it did and not what is true now`() {
        assertEquals("Synced, nothing new", syncLabelOf(DesktopSyncState.Done(applied = 0)))
        assertEquals("Synced, 3 new", syncLabelOf(DesktopSyncState.Done(applied = 3)))
    }

    @Test
    fun `no label claims the notes are backed up or up to date`() {
        val labels = listOf(
            DesktopSyncState.Idle,
            DesktopSyncState.Syncing,
            DesktopSyncState.Done(0),
            DesktopSyncState.Done(7),
            DesktopSyncState.Deferred,
            DesktopSyncState.Halted("SERVER_ROLLED_BACK"),
            DesktopSyncState.Failed("timeout"),
        ).mapNotNull(::syncLabelOf)

        for (forbidden in listOf("up to date", "backed up", "safe", "all your notes")) {
            assertTrue(
                "a label claimed \"$forbidden\": $labels",
                labels.none { it.contains(forbidden, ignoreCase = true) },
            )
        }
    }

    @Test
    fun `a halt names its reason rather than reporting a generic failure`() {
        // "The server rolled back" and "this device was revoked" call for completely different
        // responses from the person reading it, and a halt does not clear itself by retrying.
        assertTrue(syncLabelOf(DesktopSyncState.Halted("DEVICE_REVOKED"))!!.contains("DEVICE_REVOKED"))
    }

    @Test
    fun `a failed pass is distinguishable from a halted one`() {
        // One is worth retrying and the other is not; a shared message would teach the user to
        // retry a halt forever.
        assertTrue(syncLabelOf(DesktopSyncState.Failed("timeout")) != syncLabelOf(DesktopSyncState.Halted("x")))
    }
}
