package my.cheysoff.desktop.app

import my.cheysoff.desktop.store.LoadDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [unlockDiagnosticsMessage], the one user-facing string this branch shipped with no coverage.
 *
 * `RecordNotesRepositoryTest` already proves [LoadDiagnostics] counts damage and a newer record
 * type separately; nothing proved the sentence built from both counts joins them correctly rather
 * than dropping or merging one. The Android twin (`SyncRowTest`'s `lastPassLine`) covers its two
 * halves individually the same way -- this is the combined case neither side had.
 */
class AppControllerTest {

    @Test
    fun `no diagnostics means no message`() {
        assertNull(unlockDiagnosticsMessage(LoadDiagnostics(0, 0, 0, 0, 0)))
    }

    @Test
    fun `damage alone is said as one sentence`() {
        assertEquals(
            "1 record(s) on disk could not be read and are being left untouched.",
            unlockDiagnosticsMessage(LoadDiagnostics(unreadable = 1, mislabelled = 0, unsupportedVersion = 0, malformed = 0)),
        )
    }

    @Test
    fun `a newer record type alone is said as its own sentence, not as damage`() {
        assertEquals(
            "3 record(s) were written by a newer version of the app and are waiting for an update.",
            unlockDiagnosticsMessage(LoadDiagnostics(0, 0, 0, 0, newerType = 3)),
        )
    }

    /**
     * The untested case: both counts present at once. Damage is `unreadable + mislabelled +
     * unsupportedVersion + malformed` ([LoadDiagnostics.total]), which is 2 here, spread across
     * two different causes so the sentence is proved to sum them rather than report just one.
     */
    @Test
    fun `damage and a newer record type are both said, as two full sentences joined by a space`() {
        val message = unlockDiagnosticsMessage(
            LoadDiagnostics(unreadable = 1, mislabelled = 0, unsupportedVersion = 1, malformed = 0, newerType = 3),
        )

        assertEquals(
            "2 record(s) on disk could not be read and are being left untouched. " +
                "3 record(s) were written by a newer version of the app and are waiting for an update.",
            message,
        )
    }
}
