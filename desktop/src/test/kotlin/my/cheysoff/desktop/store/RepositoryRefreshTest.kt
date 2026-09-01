package my.cheysoff.desktop.store

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import my.cheysoff.core_crypto.sync.AccountRootKey
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NotesSortOrder
import my.cheysoff.core_sync_codec.RecordCodec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

/**
 * What happens to the screen when something else writes to the store.
 *
 * The sync engine writes through `RecordSyncStore` directly into the same [RecordStore] this
 * repository reads, and it has no way to announce that. The snapshot here is built once, at load,
 * so a pass that pulled twenty-six notes left them on disk with an empty list on screen — under a
 * message correctly saying twenty-six had arrived. The notes only appeared after quitting and
 * unlocking again, which reads as "sync is broken" when in fact sync had worked perfectly.
 *
 * Android has no equivalent problem: Room emits on write and its sync store writes through the same
 * database. A plain SQLite file offers no such notification, so this is a desktop-only seam and
 * needs a desktop-only test.
 *
 * Two repositories over one store stand in for "the engine wrote behind my back", because that is
 * exactly the shape of it: a writer this instance does not know about.
 */
class RepositoryRefreshTest {

    @get:Rule val folder = TemporaryFolder()

    private lateinit var store: RecordStore
    private lateinit var codec: RecordCodec

    @Before
    fun setUp() {
        store = RecordStore.open(folder.newFolder("vault").toPath().resolve("records.db"))
        codec = RecordCodec(AccountRootKey.derive(AccountRootKey.generateArk()))
    }

    @After
    fun tearDown() {
        store.close()
    }

    @Test
    fun `a note written by another instance appears only after a refresh`() = runBlocking {
        val onScreen = RecordNotesRepository.load(store, codec, node = "screen")
        val engine = RecordNotesRepository.load(store, codec, node = "engine")

        assertTrue(
            "the store starts empty",
            onScreen.getNotes(NotesSortOrder.RECENTLY_EDITED).first().isEmpty(),
        )

        val id = UUID.randomUUID().toString()
        engine.saveNote(Note(id = id, title = "Arrived by sync", content = "from the other device"))

        // The row is on disk, and this instance is none the wiser. Asserted rather than assumed:
        // it is the whole reason refreshFromStore has to exist, and if it ever stops being true
        // the refresh call can go.
        assertTrue(
            "the snapshot must be stale until told otherwise",
            onScreen.getNotes(NotesSortOrder.RECENTLY_EDITED).first().isEmpty(),
        )

        onScreen.refreshFromStore()

        val visible = onScreen.getNotes(NotesSortOrder.RECENTLY_EDITED).first()
        assertEquals(1, visible.size)
        assertEquals("Arrived by sync", visible.single().title)
        assertEquals("from the other device", visible.single().content)
    }

    @Test
    fun `a refresh does not lose what this instance wrote itself`() = runBlocking {
        val repository = RecordNotesRepository.load(store, codec, node = "screen")
        val id = UUID.randomUUID().toString()
        repository.saveNote(Note(id = id, title = "Typed here", content = "local"))

        repository.refreshFromStore()

        val visible = repository.getNotes(NotesSortOrder.RECENTLY_EDITED).first()
        assertEquals("a refresh re-reads the store, which already holds this note", 1, visible.size)
        assertEquals("Typed here", visible.single().title)
    }
}
