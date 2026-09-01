package my.cheysoff.core_data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import my.cheysoff.core_data.data.RoomNotesRepository
import my.cheysoff.core_data.data.local.NoteDatabase
import my.cheysoff.core_data.data.sync.RoomSyncStore
import my.cheysoff.core_data.data.sync.SyncClock
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NotesSortOrder
import my.cheysoff.core_domain.sync.RecordType
import my.cheysoff.core_domain.sync.SyncRecord
import my.cheysoff.core_sync_engine.ChangePage
import my.cheysoff.core_sync_engine.ClockObserver
import my.cheysoff.core_sync_engine.IncomingRecord
import my.cheysoff.core_sync_engine.PushAck
import my.cheysoff.core_sync_engine.PushRequest
import my.cheysoff.core_sync_engine.PushResponse
import my.cheysoff.core_sync_engine.SyncEngine
import my.cheysoff.core_sync_engine.SyncOutcome
import my.cheysoff.core_sync_engine.SyncTransport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Two devices, two Room databases, one server: a note written on one arrives on the other.
 *
 * ## Why this test rather than the convergence harness
 *
 * `ConvergenceTest` in `:core-sync-engine` already drives the real `SyncEngine` over thousands of
 * seeded schedules, and it proves everything about the *loop*. It proves nothing about this module,
 * because its store is a `LinkedHashMap`: no SQL, no `ON CONFLICT`, no `CASE`, no transaction, no
 * `fieldHlc` string that has to be parsed back into the map it was serialised from. Every one of
 * those is a place a record can lose a field between two devices, and every one of them is only
 * exercised by running the engine over the store this module ships.
 *
 * So this is the same idea one layer down and far smaller: the **real** `SyncEngine`, the **real**
 * `RoomSyncStore` over a **real** Room database, and — the part that matters most — the **real**
 * `RoomNotesRepository` making the local edits, so the rows being pushed are the rows the editor
 * actually writes rather than rows a test fixture composed.
 *
 * The server here is a map. It has to be: `:core-sync-net`'s contract against the real server is
 * `SyncServerContractTest`, and mixing the two would mean a failure could be either a merge bug or
 * an HTTP bug. What crosses this seam is a plain `SyncRecord`, exactly as
 * `core-sync-engine/harness/FakeServer` does it and for the same stated reason.
 */
@RunWith(RobolectricTestRunner::class)
class TwoDeviceSyncTest {

    private val server = RecordServer()
    private lateinit var phone: Device
    private lateinit var tablet: Device

    /**
     * One simulated device: its database, the repository the user's edits go through, and the
     * engine that syncs it.
     */
    private inner class Device(name: String) {
        val database: NoteDatabase = Room.inMemoryDatabaseBuilder(
            getApplicationContext<Context>(), NoteDatabase::class.java,
        ).allowMainThreadQueries().build()

        /**
         * A distinct node per device, which is what makes the tie-break in `Hlc.compareTo`
         * meaningful. Two devices sharing a node would make two different writes compare equal,
         * which is the one thing the whole design cannot tolerate — and it is exactly what a
         * fixture that forgot to vary this would silently arrange.
         */
        val clock = SyncClock(node = { name })

        val repository = RoomNotesRepository(database.noteDao, database.folderDao, database, clock)

        val store = RoomSyncStore(
            database, database.noteDao, database.folderDao, database.syncStateDao,
            accountId = "acct",
        )

        val engine = SyncEngine(
            store = store,
            transport = DirectTransport(server),
            clock = ClockObserver { clock.observe(it) },
        )

        suspend fun notes(): List<Note> = repository.getNotes(NotesSortOrder.RECENTLY_EDITED).first()

        suspend fun note(id: String): Note? = notes().firstOrNull { it.id == id }

        fun close() = database.close()
    }

    private fun newDevices() {
        phone = Device("phone")
        tablet = Device("tablt")
    }

    init {
        ApplicationProvider.getApplicationContext<Context>()
    }

    @After
    fun tearDown() {
        if (::phone.isInitialized) phone.close()
        if (::tablet.isInitialized) tablet.close()
    }

    /** Passes on both devices until neither moves, so an assertion is about a settled account. */
    private suspend fun settle(vararg devices: Device) {
        repeat(6) {
            var moved = false
            devices.forEach { device ->
                val outcome = device.engine.runPass()
                assertTrue("a pass ended badly: $outcome", outcome is SyncOutcome.Completed)
                if ((outcome as SyncOutcome.Completed).stats.moved) moved = true
            }
            if (!moved) return
        }
    }

    // -- the headline ----------------------------------------------------------------------------

    /**
     * The thing that has never happened in this app: a note written on one device appears on
     * another. Every column the user can see is compared, because a merge that carried the title
     * and dropped the body would pass a test that only looked for the id.
     */
    @Test
    fun `a note written on the phone arrives on the tablet`() = runTest {
        newDevices()
        phone.repository.saveNote(
            Note(id = "n1", title = "Groceries", content = "milk, eggs", isPinned = true, folderId = null),
        )

        settle(phone, tablet)

        val arrived = assertNotNull(tablet.note("n1")).let { tablet.note("n1")!! }
        assertEquals("Groceries", arrived.title)
        assertEquals("milk, eggs", arrived.content)
        assertEquals(true, arrived.isPinned)
        assertEquals(phone.note("n1")!!.updatedAt, arrived.updatedAt)
    }

    /**
     * The gesture the whole field-level design exists for: a metadata toggle on one device and a
     * body edit on the other, at the same time. Record-level last-writer-wins throws one of them
     * away; both must survive here.
     */
    @Test
    fun `a pin on one device and an edit on the other both survive`() = runTest {
        newDevices()
        phone.repository.saveNote(Note(id = "n1", title = "Trip", content = "book flights", folderId = null))
        settle(phone, tablet)

        phone.repository.setNotePinned("n1", true)
        tablet.repository.saveNote(tablet.note("n1")!!.copy(content = "book flights and hotel"))

        settle(phone, tablet)

        listOf(phone, tablet).forEach { device ->
            val note = device.note("n1")!!
            assertEquals("${device.hashCode()} lost the pin", true, note.isPinned)
            assertEquals("book flights and hotel", note.content)
        }
    }

    /**
     * A delete is a record like any other — the protocol has no delete endpoint — so it has to
     * travel as a tombstone and remove the note on the other device. A delete that did not travel
     * is the failure that leaves a note the user deleted reappearing every time they open the app.
     */
    @Test
    fun `a delete on one device removes the note on the other`() = runTest {
        newDevices()
        phone.repository.saveNote(Note(id = "n1", title = "Old", content = "x", folderId = null))
        settle(phone, tablet)
        assertNotNull(tablet.note("n1"))

        phone.repository.deleteNote("n1")
        settle(phone, tablet)

        assertNull("the tombstone did not travel", tablet.note("n1"))
        assertTrue("the note should be in Trash, not gone", tablet.repository.getDeletedNotes().first().any { it.id == "n1" })
    }

    /**
     * Both devices edit the body while neither can see the other. Nothing the user typed may be
     * discarded: the higher clock keeps the original record and the loser becomes a second note,
     * on **both** devices, with the same id — the copy's uuid is derived from the losing body, so
     * the two devices name the same copy rather than each minting one.
     */
    @Test
    fun `two concurrent body edits keep both bodies, once`() = runTest {
        newDevices()
        phone.repository.saveNote(Note(id = "n1", title = "Notes", content = "original", folderId = null))
        settle(phone, tablet)

        phone.repository.saveNote(phone.note("n1")!!.copy(content = "phone version"))
        tablet.repository.saveNote(tablet.note("n1")!!.copy(content = "tablet version"))

        settle(phone, tablet)

        listOf(phone, tablet).forEach { device ->
            val bodies = device.notes().map { it.content }.toSet()
            assertTrue("lost a body: $bodies", bodies.containsAll(setOf("phone version", "tablet version")))
            assertEquals("gained a duplicate: ${device.notes().map { it.id }}", 2, device.notes().size)
        }
        assertEquals(
            "the two devices named different copies",
            phone.notes().map { it.id }.toSet(),
            tablet.notes().map { it.id }.toSet(),
        )
    }

    /**
     * The pre-v8 behaviour, kept honest: with a recorded content baseline, pinning on one device
     * while the other edits the body is **not** a contested body and costs no duplicate. Without
     * the baseline the merge cannot tell the two apart and writes one. This is decision D7, and
     * `contentSyncedHlc` is what closes it — so this test is the reason that column exists.
     */
    @Test
    fun `a pin does not cost a duplicate note`() = runTest {
        newDevices()
        phone.repository.saveNote(Note(id = "n1", title = "Trip", content = "book flights", folderId = null))
        settle(phone, tablet)

        phone.repository.setNotePinned("n1", true)
        tablet.repository.saveNote(tablet.note("n1")!!.copy(content = "book flights and hotel"))
        settle(phone, tablet)

        assertEquals("a pin produced a conflict copy", 1, phone.notes().size)
        assertEquals(1, tablet.notes().size)
    }

    /**
     * A pass that has nothing to say must write nothing. It is the steady state — a phone syncing
     * every sixty seconds spends almost all of its life here — and a store that marked a row dirty
     * on re-delivery would turn it into an infinite push loop between two devices.
     */
    @Test
    fun `a settled account stays settled`() = runTest {
        newDevices()
        phone.repository.saveNote(Note(id = "n1", title = "Settled", content = "x", folderId = null))
        settle(phone, tablet)

        val outcome = tablet.engine.runPass() as SyncOutcome.Completed
        assertEquals(0, outcome.stats.applied)
        assertEquals(0, outcome.stats.pushed)
        assertEquals(0, outcome.stats.conflicts)
    }

    /**
     * A folder is a record too, and it has its own dirty flag, its own clock and its own row in the
     * same push stream. Notes referencing it must not arrive before it exists — not because the
     * schema enforces it (there is no foreign key) but because a note in a folder the device has
     * never heard of shows up unfiled.
     */
    @Test
    fun `a folder and the note filed in it both travel`() = runTest {
        newDevices()
        phone.repository.saveFolder(my.cheysoff.core_domain.model.Folder(id = "f1", name = "Work", colorArgb = null))
        phone.repository.saveNote(Note(id = "n1", title = "Report", content = "x", folderId = "f1"))

        settle(phone, tablet)

        assertEquals(listOf("Work"), tablet.repository.getFolders().first().map { it.name })
        assertEquals("f1", tablet.note("n1")!!.folderId)
    }

    // -- the server ------------------------------------------------------------------------------

    /**
     * The account as the server holds it: one head version per record, each with the monotonic
     * `seq` the client's cursor is made of, and a compare-and-set write.
     *
     * Deliberately not a `SyncApi`. What is being tested is the store, and a record crossing this
     * seam as a plain `SyncRecord` means a failure here is a merge or persistence failure and can
     * never be a decryption or an HTTP one. `:core-sync-net` is checked against the real server in
     * its own module.
     */
    private class RecordServer {
        private val head = LinkedHashMap<String, Pair<Long, SyncRecord>>()
        private var nextSeq = 0L

        fun changes(since: Long, limit: Int): Pair<List<Pair<Long, SyncRecord>>, Boolean> {
            val all = head.values.filter { it.first > since }.sortedBy { it.first }
            return all.take(limit) to (all.size > limit)
        }

        /** Accepts only when [baseSeq] is still the record's head; `0` asserts "must not exist". */
        fun push(key: String, baseSeq: Long, record: SyncRecord): Pair<Long, SyncRecord>? {
            val current = head[key]
            if ((current?.first ?: 0L) != baseSeq) return current
            nextSeq += 1
            head[key] = nextSeq to record
            return null
        }
    }

    private class DirectTransport(private val server: RecordServer) : SyncTransport {

        override suspend fun changesSince(since: Long, limit: Int): ChangePage {
            val (records, hasMore) = server.changes(since, limit)
            return ChangePage(
                records = records.map { (seq, record) -> IncomingRecord.Opened(seq, record) },
                hasMore = hasMore,
            )
        }

        override suspend fun push(items: List<PushRequest>): PushResponse = PushResponse(
            items.map { item ->
                val blocking = server.push(keyOf(item.type, item.uuid), item.baseSeq, item.record)
                if (blocking == null) {
                    PushAck.Accepted(item.type, item.uuid, seqOf(item))
                } else {
                    PushAck.Conflicted(item.type, item.uuid, blocking.second, blocking.first)
                }
            }
        )

        /**
         * The seq the server just assigned. Read back rather than returned from `push` because the
         * engine matches acks by identity and this keeps the server's own bookkeeping in one place.
         */
        private fun seqOf(item: PushRequest): Long =
            server.changes(-1, Int.MAX_VALUE).first.first { it.second.uuid == item.uuid && it.second.type == item.type }.first

        private fun keyOf(type: RecordType, uuid: String) = "${type.wireKey}:$uuid"
    }
}
