package manana.sync.server

import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Opening a database written before the rendezvous had two slots.
 *
 * `CREATE TABLE IF NOT EXISTS` is silent about a table that exists with the wrong columns, so
 * without the drop-and-rebuild in `SyncStore.open` an upgraded server would accept a deposit and
 * fail on the insert -- at runtime, on the one endpoint that has no session to blame it on.
 *
 * The drop is safe here and would not be anywhere else in this schema: `pairings` is a dead drop
 * with a two-minute lease, and every row in it belongs to a handshake that is over by the time a
 * process restarts. This test asserts both halves of that: the old rows go, and every other table
 * is untouched.
 */
class PairingSchemaMigrationTest {

    @Test
    fun aOneSlotPairingsTableIsRebuiltAndTheRestOfTheSchemaSurvives() {
        val file = Files.createTempFile("manana-migration", ".db")
        Files.delete(file)
        val path = file.toAbsolutePath().toString()
        val clock = MutableClock()

        // A database in the shape the previous release wrote, with one live pairing parked in it
        // and one account row beside it.
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "CREATE TABLE pairings (" +
                        "sid TEXT NOT NULL PRIMARY KEY, sealed TEXT NOT NULL, " +
                        "expires_at INTEGER NOT NULL)"
                )
                statement.execute(
                    "INSERT INTO pairings(sid, sealed, expires_at) VALUES ('old-sid', 'blob', ${Long.MAX_VALUE})"
                )
                statement.execute(
                    "CREATE TABLE accounts (account_id TEXT NOT NULL PRIMARY KEY, " +
                        "created_at INTEGER NOT NULL, last_seq INTEGER NOT NULL)"
                )
                statement.execute("INSERT INTO accounts VALUES ('acct', 1, 7)")
            }
        }

        SyncStore.open(path, clock, historyDepth = 10).use { store ->
            // The stale blob is gone rather than migrated: nobody is still waiting for it.
            assertEquals(0L, store.pairingRowCount())

            // And the new shape works, both slots, under one sid.
            assertEquals(true, store.putPairing("sid-a", "reply", "one", Long.MAX_VALUE))
            assertEquals(true, store.putPairing("sid-a", "bundle", "two", Long.MAX_VALUE))
            assertEquals(1L, store.livePairingCount())
            assertEquals("one", store.takePairing("sid-a", "reply"))
            assertNull(store.takePairing("sid-a", "reply"))
            assertNotNull(store.takePairing("sid-a", "bundle"))

            // Nothing else was dropped: the account row is where it was.
            assertEquals(7L, store.lastSeq("acct"))
        }

        Files.deleteIfExists(file)
    }

    /** Opening the current shape twice must not throw away a live pairing on the second open. */
    @Test
    fun reopeningACurrentDatabaseLeavesItsPairingsAlone() {
        val file = Files.createTempFile("manana-migration", ".db")
        Files.delete(file)
        val path = file.toAbsolutePath().toString()
        val clock = MutableClock()

        SyncStore.open(path, clock, historyDepth = 10).use {
            it.putPairing("sid-b", "reply", "kept", Long.MAX_VALUE)
        }
        SyncStore.open(path, clock, historyDepth = 10).use {
            assertEquals("kept", it.takePairing("sid-b", "reply"))
        }

        Files.deleteIfExists(file)
    }
}
