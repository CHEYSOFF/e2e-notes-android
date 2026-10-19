package my.cheysoff.desktop.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import my.cheysoff.core_crypto.sync.AccountRootKey
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NoteContentFormat
import my.cheysoff.core_domain.sync.SyncRecord
import my.cheysoff.core_sync_codec.RecordCodec
import my.cheysoff.core_sync_engine.ChangePage
import my.cheysoff.core_sync_engine.IncomingRecord
import my.cheysoff.core_sync_engine.PushAck
import my.cheysoff.core_sync_engine.PushRequest
import my.cheysoff.core_sync_engine.PushResponse
import my.cheysoff.core_sync_engine.SyncEngine
import my.cheysoff.core_sync_engine.SyncOutcome
import my.cheysoff.core_sync_engine.SyncTransport
import my.cheysoff.desktop.store.RecordNotesRepository
import my.cheysoff.desktop.store.RecordStore
import my.cheysoff.desktop.ui.state.NotesWorkspaceModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Two replicas over the real desktop stack and one fake server: the laptop driven through
 * [NotesWorkspaceModel] as the UI would, the other standing in for the phone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LaptopToPhoneSyncTest {

    private class Server : SyncTransport {
        private val rows = LinkedHashMap<String, Pair<Long, SyncRecord>>()
        private var seq = 0L
        override suspend fun changesSince(since: Long, limit: Int): ChangePage {
            val page = rows.values.filter { it.first > since }.sortedBy { it.first }.take(limit)
            return ChangePage(page.map { IncomingRecord.Opened(seq = it.first, record = it.second, createdAt = null) }, page.size == limit)
        }
        override suspend fun push(items: List<PushRequest>): PushResponse = PushResponse(items.map { item ->
            val key = "${item.type.wireKey}:${item.uuid}"
            val current = rows[key]
            if ((current?.first ?: 0L) != item.baseSeq) {
                PushAck.Conflicted(item.type, item.uuid, current!!.second, current.first)
            } else {
                seq += 1
                rows[key] = seq to item.record
                PushAck.Accepted(item.type, item.uuid, seq)
            }
        })
    }

    private var wall = 1_000_000L
    private val codec = RecordCodec(AccountRootKey.derive(AccountRootKey.generateArk()))
    private val server = Server()
    private val laptopStore = RecordStore.inMemory("laptop-" + UUID.randomUUID())
    private val phoneStore = RecordStore.inMemory("phone-" + UUID.randomUUID())
    private val laptop = RecordNotesRepository.load(laptopStore, codec, "laptop") { wall }
    private val phone = RecordNotesRepository.load(phoneStore, codec, "phone") { wall }
    private val laptopEngine = SyncEngine(RecordSyncStore(laptopStore, codec, "acct"), server, laptop.clockObserver)
    private val phoneEngine = SyncEngine(RecordSyncStore(phoneStore, codec, "acct"), server, phone.clockObserver)
    private val scopes = mutableListOf<CoroutineScope>()

    @After fun tearDown() { scopes.forEach { it.cancel() } }

    private suspend fun syncLaptop() { wall += 1000; assertTrue(laptopEngine.runPass() is SyncOutcome.Completed); laptop.refreshFromStore() }
    private suspend fun syncPhone() { wall += 1000; assertTrue(phoneEngine.runPass() is SyncOutcome.Completed); phone.refreshFromStore() }
    private suspend fun phoneNote(id: String) = phone.getNoteById(id).first()!!

    private fun TestScope.model(): NotesWorkspaceModel {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        scopes += scope
        return NotesWorkspaceModel(repository = laptop, scope = scope, now = { wall }, newId = { UUID.randomUUID().toString() })
    }

    private fun TestScope.settle() { advanceTimeBy(1_000); advanceUntilIdle(); wall += 1000 }

    @Test
    fun `a new note and the body typed into it both reach the phone`() = runTest {
        val model = model(); settle()
        val id = model.newNote(); settle()
        syncLaptop(); syncPhone()                       // the empty note arrives first
        model.setContent("<p>typed on the laptop</p>"); settle()
        syncLaptop(); syncPhone()
        assertEquals("<p>typed on the laptop</p>", phoneNote(id).content)
        assertEquals(NoteContentFormat.HTML, phoneNote(id).contentFormat)
    }

    @Test
    fun `checklist ticks on the laptop reach the phone`() = runTest {
        val id = "list"
        phone.saveNote(Note(id = id, title = "Shop", content = "", checklist = "0milk\n0eggs\n0bread"))
        syncPhone(); syncLaptop()
        val model = model(); settle()
        model.selectNote(id); settle()
        val items = model.state.value.editor!!.checklist
        model.toggleChecklistItem(items[0].id)
        model.toggleChecklistItem(items[1].id); settle()
        syncLaptop(); syncPhone()
        assertEquals("1milk\n1eggs\n0bread", phoneNote(id).checklist)
    }

    @Test
    fun `ticking a box in an open note keeps the body the phone edited meanwhile`() = runTest {
        val id = "shared"
        phone.saveNote(Note(id = id, title = "Shop", content = "old", checklist = "0milk"))
        syncPhone(); syncLaptop()
        val model = model(); settle()
        model.selectNote(id); settle()                  // laptop has the note open

        phone.saveNote(phoneNote(id).copy(content = "edited on the phone"))
        syncPhone(); syncLaptop(); settle()
        assertEquals("edited on the phone", model.state.value.editor!!.content)

        model.toggleChecklistItem(model.state.value.editor!!.checklist[0].id); settle()
        syncLaptop(); syncPhone()
        assertEquals("1milk", phoneNote(id).checklist)
        assertEquals("edited on the phone", phoneNote(id).content)
    }
}
