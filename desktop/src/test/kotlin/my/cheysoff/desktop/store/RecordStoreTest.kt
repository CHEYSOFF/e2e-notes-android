package my.cheysoff.desktop.store

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

class RecordStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var store: RecordStore

    @Before
    fun setUp() {
        store = RecordStore.inMemory("records-${UUID.randomUUID()}")
    }

    @After
    fun tearDown() {
        store.close()
    }

    private fun envelope(vararg bytes: Int) = ByteArray(bytes.size) { bytes[it].toByte() }

    @Test
    fun `a fresh store is empty`() {
        assertTrue(store.readAll().isEmpty())
    }

    @Test
    fun `a stored record comes back byte for byte`() {
        val blob = envelope(1, 2, 3, 250, 0, 255)
        store.put("abc", blob)

        val row = store.readAll().single()
        assertEquals("abc", row.blindedId)
        assertArrayEquals(blob, row.envelope)
    }

    /**
     * A new record has never been pushed, so the server has no version of it. NULL rather than 0:
     * JDBC returns 0 for a SQL NULL and only `wasNull()` separates them, which is the kind of thing
     * that is right by accident until someone reads the column.
     */
    @Test
    fun `a new record is dirty with no synced sequence`() {
        store.put("abc", envelope(1))
        val row = store.readAll().single()
        assertTrue(row.dirty)
        assertNull(row.lastSyncedSeq)
    }

    @Test
    fun `putting the same id again replaces the envelope rather than inserting a second row`() {
        store.put("abc", envelope(1))
        store.put("abc", envelope(2, 2))

        val row = store.readAll().single()
        assertArrayEquals(envelope(2, 2), row.envelope)
    }

    @Test
    fun `marking a record synced clears dirty and records the sequence`() {
        store.put("abc", envelope(1))
        store.markSynced("abc", 42)

        val row = store.readAll().single()
        assertFalse(row.dirty)
        assertEquals(42L, row.lastSyncedSeq)
    }

    /**
     * `last_synced_seq` is the baseline the next push is built on. Resetting it on an update would
     * tell the server that an already-uploaded record must not exist — the same rule
     * `NoteDao.upsertNote` states for its own conflict branch.
     */
    @Test
    fun `an update marks the record dirty again but keeps its synced sequence`() {
        store.put("abc", envelope(1))
        store.markSynced("abc", 42)
        store.put("abc", envelope(9))

        val row = store.readAll().single()
        assertTrue(row.dirty)
        assertEquals(42L, row.lastSyncedSeq)
    }

    @Test
    fun `removing a record destroys the row`() {
        store.put("abc", envelope(1))
        store.put("def", envelope(2))
        store.remove("abc")

        assertEquals(listOf("def"), store.readAll().map { it.blindedId })
    }

    @Test
    fun `removing a record that is not there is a no-op`() {
        store.put("abc", envelope(1))
        store.remove("nothing")
        assertEquals(1, store.readAll().size)
    }

    @Test
    fun `records survive closing and reopening the file`() {
        val path = folder.root.toPath().resolve("records.db")
        RecordStore.open(path).use { first ->
            first.put("abc", envelope(7, 7, 7))
            first.markSynced("abc", 3)
        }
        RecordStore.open(path).use { second ->
            val row = second.readAll().single()
            assertArrayEquals(envelope(7, 7, 7), row.envelope)
            assertEquals(3L, row.lastSyncedSeq)
            assertFalse(row.dirty)
        }
    }

    /** Opening an existing store must not wipe it — `CREATE TABLE IF NOT EXISTS`, not `CREATE`. */
    @Test
    fun `reopening does not recreate the table`() {
        val path = folder.root.toPath().resolve("records.db")
        RecordStore.open(path).use { it.put("abc", envelope(1)) }
        RecordStore.open(path).use { assertEquals(1, it.readAll().size) }
        RecordStore.open(path).use { assertEquals(1, it.readAll().size) }
    }
}
