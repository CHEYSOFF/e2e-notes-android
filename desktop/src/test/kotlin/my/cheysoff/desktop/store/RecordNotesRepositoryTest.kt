package my.cheysoff.desktop.store

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import my.cheysoff.core_crypto.sync.AccountRootKey
import my.cheysoff.core_domain.model.Folder
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NotesSortOrder
import my.cheysoff.core_domain.model.TrashPolicy
import my.cheysoff.core_domain.sync.FieldClocks
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class RecordNotesRepositoryTest {

    private val keys = AccountRootKey.derive(AccountRootKey.generateArk())
    private val codec = RecordCodec(keys)
    private lateinit var store: RecordStore
    private var now = 1_000_000L

    private lateinit var repository: RecordNotesRepository

    @Before
    fun setUp() {
        store = RecordStore.inMemory("repo-${UUID.randomUUID()}")
        repository = reload()
    }

    @After
    fun tearDown() {
        store.close()
    }

    /** Rebuilds the repository from what is actually on disk — the round trip, not a cache. */
    private fun reload() = RecordNotesRepository.load(store, codec, node = "abcd1234") { now }

    private fun note(id: String, title: String = "t", content: String = "c") =
        Note(id = id, title = title, content = content)

    // -------------------------------------------------------------------------------------------
    // The round trip
    // -------------------------------------------------------------------------------------------

    @Test
    fun `a saved note comes back`() = runTest {
        repository.saveNote(note("a", title = "Groceries"))
        val notes = repository.getNotes(NotesSortOrder.RECENTLY_EDITED).first()
        assertEquals(listOf("Groceries"), notes.map { it.title })
    }

    @Test
    fun `notes survive a reload from the sealed store`() = runTest {
        repository.saveNote(note("a", title = "Groceries", content = "milk"))
        repository.saveNote(note("b", title = "Ideas"))

        val reloaded = reload()
        val notes = reloaded.getNotes(NotesSortOrder.TITLE_ASC).first()
        assertEquals(listOf("Groceries", "Ideas"), notes.map { it.title })
        assertEquals("milk", notes.first().content)
        assertEquals(0, reloaded.diagnostics.total)
    }

    /**
     * The property the whole record-shaped store exists for: what is on disk is what would be on
     * the wire. A failure here means the desktop is keeping plaintext.
     */
    @Test
    fun `nothing readable reaches the stored row`() = runTest {
        repository.saveNote(note("a", title = "a very distinctive title", content = "and its body"))

        val row = store.readAll().single()
        val asText = String(row.envelope, Charsets.ISO_8859_1)
        assertFalse(asText.contains("a very distinctive title"))
        assertFalse(asText.contains("and its body"))
        assertFalse(row.blindedId.contains("a"))
    }

    @Test
    fun `a note is one row, updated in place rather than appended`() = runTest {
        repository.saveNote(note("a", title = "first"))
        repository.saveNote(note("a", title = "second"))

        assertEquals(1, store.readAll().size)
        assertEquals("second", reload().getNotes(NotesSortOrder.RECENTLY_EDITED).first().single().title)
    }

    /** Records this build cannot read are counted and left completely alone. */
    @Test
    fun `an unreadable row is reported and not destroyed`() = runTest {
        repository.saveNote(note("a"))
        store.put("a-label-from-nowhere", ByteArray(64) { 0x11 })

        val reloaded = reload()
        assertEquals(1, reloaded.diagnostics.unreadable)
        assertEquals(1, reloaded.getNotes(NotesSortOrder.RECENTLY_EDITED).first().size)
        assertEquals(2, store.readAll().size)
    }

    // -------------------------------------------------------------------------------------------
    // Column ownership — the same rules as NoteDao.upsertNote
    // -------------------------------------------------------------------------------------------

    @Test
    fun `a new note takes its timestamps from the clock`() = runTest {
        now = 4242
        repository.saveNote(note("a"))
        val saved = repository.getNoteById("a").first()!!
        assertEquals(4242L, saved.createdAt)
        assertEquals(4242L, saved.updatedAt)
    }

    @Test
    fun `an update moves updatedAt and leaves createdAt alone`() = runTest {
        now = 100
        repository.saveNote(note("a", title = "first"))
        now = 500
        repository.saveNote(note("a", title = "second"))

        val saved = repository.getNoteById("a").first()!!
        assertEquals(100L, saved.createdAt)
        assertEquals(500L, saved.updatedAt)
    }

    /**
     * `saveNote` does not own `isFavorite` — the editor never sets it, so a Note built from what
     * the editor holds arrives with the default and would wipe the stored value.
     */
    @Test
    fun `saveNote does not clear a favourite set elsewhere`() = runTest {
        repository.saveNote(note("a"))
        repository.setNoteFavorite("a", true)
        repository.saveNote(note("a", title = "edited"))

        val saved = repository.getNoteById("a").first()!!
        assertTrue(saved.isFavorite)
        assertEquals("edited", saved.title)
    }

    @Test
    fun `saveNote ignores an isFavorite passed in by a caller`() = runTest {
        repository.saveNote(note("a").copy(isFavorite = true))
        assertFalse(repository.getNoteById("a").first()!!.isFavorite)
    }

    @Test
    fun `saveNote does not resurrect a trashed note`() = runTest {
        repository.saveNote(note("a"))
        repository.deleteNote("a")
        repository.saveNote(note("a", title = "edited"))

        assertNull(repository.getNoteById("a").first())
        assertEquals(1, repository.getDeletedNotes().first().size)
    }

    /**
     * `saveNote` claims a clock for the fields it writes and no others. Claiming one for
     * `isFavorite` would make the next merge discard the other device's favourite.
     */
    @Test
    fun `saveNote does not claim a clock for fields it did not write`() = runTest {
        now = 100
        repository.saveNote(note("a"))
        repository.setNoteFavorite("a", true)
        now = 200
        repository.saveNote(note("a", title = "edited"))

        val payload = openOnly("a")
        // The favourite was set before this save, so its clock must still be the older one and
        // therefore written down explicitly rather than left implicit at the (newer) row clock.
        assertTrue(payload.clocks.containsKey(FieldClocks.FAVORITE))
        assertTrue(payload.clocks.getValue(FieldClocks.FAVORITE) < payload.rowClock)
    }

    // -------------------------------------------------------------------------------------------
    // Trash
    // -------------------------------------------------------------------------------------------

    @Test
    fun `a deleted note leaves the list and appears in trash`() = runTest {
        repository.saveNote(note("a"))
        repository.deleteNote("a")

        assertTrue(repository.getNotes(NotesSortOrder.RECENTLY_EDITED).first().isEmpty())
        assertEquals(listOf("a"), repository.getDeletedNotes().first().map { it.id })
        assertNull(repository.getNoteById("a").first())
    }

    /**
     * The idempotence guard. A second delete must not re-stamp `deletedAt`, which would silently
     * restart the 30-day retention on a note the user trashed a month ago.
     */
    @Test
    fun `deleting an already-trashed note does not restart its retention`() = runTest {
        now = 1000
        repository.saveNote(note("a"))
        repository.deleteNote("a")
        now = 9_000_000
        repository.deleteNote("a")

        assertEquals(1000L, repository.getDeletedNotes().first().single().deletedAt)
    }

    @Test
    fun `restoring brings a note back and clears its stamp`() = runTest {
        repository.saveNote(note("a"))
        repository.deleteNote("a")
        repository.restoreNote("a")

        val restored = repository.getNoteById("a").first()
        assertNotNull(restored)
        assertNull(restored!!.deletedAt)
        assertTrue(repository.getDeletedNotes().first().isEmpty())
    }

    @Test
    fun `purging destroys the row`() = runTest {
        repository.saveNote(note("a"))
        repository.saveNote(note("b"))
        repository.purgeNote("a")

        assertEquals(1, store.readAll().size)
        assertEquals(listOf("b"), reload().getNotes(NotesSortOrder.RECENTLY_EDITED).first().map { it.id })
    }

    @Test
    fun `expired trash is purged and unexpired trash is kept`() = runTest {
        now = 1_000_000
        repository.saveNote(note("old"))
        repository.saveNote(note("recent"))
        repository.deleteNote("old")
        now += TrashPolicy.RETENTION_MILLIS
        repository.deleteNote("recent")

        val purged = repository.purgeExpiredTrash(now)
        assertEquals(1, purged)
        assertEquals(listOf("recent"), repository.getDeletedNotes().first().map { it.id })
        assertEquals(1, store.readAll().size)
    }

    @Test
    fun `purging expired trash leaves live notes alone`() = runTest {
        repository.saveNote(note("live"))
        assertEquals(0, repository.purgeExpiredTrash(now + TrashPolicy.RETENTION_MILLIS * 10))
        assertEquals(1, repository.getNotes(NotesSortOrder.RECENTLY_EDITED).first().size)
    }

    // -------------------------------------------------------------------------------------------
    // Sorting — transcribed from NoteDao's ORDER BY clauses
    // -------------------------------------------------------------------------------------------

    @Test
    fun `recently edited puts the newest updatedAt first`() = runTest {
        now = 100
        repository.saveNote(note("a"))
        now = 300
        repository.saveNote(note("b"))
        now = 200
        repository.saveNote(note("c"))

        assertEquals(
            listOf("b", "c", "a"),
            repository.getNotes(NotesSortOrder.RECENTLY_EDITED).first().map { it.id },
        )
    }

    @Test
    fun `newest created puts the newest createdAt first, whatever the edits did`() = runTest {
        now = 100
        repository.saveNote(note("a"))
        now = 200
        repository.saveNote(note("b"))
        now = 300
        repository.saveNote(note("a", title = "edited"))

        assertEquals(
            listOf("b", "a"),
            repository.getNotes(NotesSortOrder.NEWEST_CREATED).first().map { it.id },
        )
    }

    /** `ORDER BY (title = '') ASC, title COLLATE NOCASE ASC` — untitled last, case-insensitive. */
    @Test
    fun `title order is case-insensitive and puts untitled notes last`() = runTest {
        repository.saveNote(note("a", title = "banana"))
        repository.saveNote(note("b", title = "Apple"))
        repository.saveNote(note("c", title = ""))

        assertEquals(
            listOf("Apple", "banana", ""),
            repository.getNotes(NotesSortOrder.TITLE_ASC).first().map { it.title },
        )
    }

    @Test
    fun `notes with identical timestamps are ordered by id, not arbitrarily`() = runTest {
        repository.saveNote(note("b"))
        repository.saveNote(note("a"))
        repository.saveNote(note("c"))

        assertEquals(
            listOf("a", "b", "c"),
            repository.getNotes(NotesSortOrder.RECENTLY_EDITED).first().map { it.id },
        )
    }

    @Test
    fun `trash is ordered newest-deleted first`() = runTest {
        repository.saveNote(note("a"))
        repository.saveNote(note("b"))
        now = 100
        repository.deleteNote("a")
        now = 200
        repository.deleteNote("b")

        assertEquals(listOf("b", "a"), repository.getDeletedNotes().first().map { it.id })
    }

    // -------------------------------------------------------------------------------------------
    // Metadata
    // -------------------------------------------------------------------------------------------

    @Test
    fun `pin, favourite and folder are set and survive a reload`() = runTest {
        repository.saveNote(note("a"))
        repository.saveFolder(Folder(id = "f", name = "Work"))
        repository.setNotePinned("a", true)
        repository.setNoteFavorite("a", true)
        repository.setNoteFolder("a", "f")

        val saved = reload().getNoteById("a").first()!!
        assertTrue(saved.isPinned)
        assertTrue(saved.isFavorite)
        assertEquals("f", saved.folderId)
    }

    @Test
    fun `setting metadata on a note that does not exist is a no-op`() = runTest {
        repository.setNotePinned("nothing", true)
        repository.setNoteFavorite("nothing", true)
        repository.setNoteFolder("nothing", "f")
        assertTrue(store.readAll().isEmpty())
    }

    // -------------------------------------------------------------------------------------------
    // Folders
    // -------------------------------------------------------------------------------------------

    @Test
    fun `folders are listed by name, case-insensitively`() = runTest {
        repository.saveFolder(Folder(id = "1", name = "work"))
        repository.saveFolder(Folder(id = "2", name = "Admin"))

        assertEquals(listOf("Admin", "work"), repository.getFolders().first().map { it.name })
    }

    @Test
    fun `deleting a folder unfiles its notes in the same step`() = runTest {
        repository.saveFolder(Folder(id = "f", name = "Work"))
        repository.saveNote(note("a"))
        repository.saveNote(note("b"))
        repository.setNoteFolder("a", "f")
        repository.setNoteFolder("b", "f")

        repository.deleteFolder("f")

        assertTrue(repository.getFolders().first().isEmpty())
        assertEquals(listOf("f"), repository.getDeletedFolders().first().map { it.id })
        assertTrue(repository.getNotes(NotesSortOrder.RECENTLY_EDITED).first().all { it.folderId == null })
    }

    /**
     * One user action, one clock. Every unfiled note lands at the same point in the account's
     * history as the folder's tombstone, so a merge on the other device sees one event rather than
     * N+1 unrelated ones.
     */
    @Test
    fun `a folder delete stamps the folder and its notes with the same clock`() = runTest {
        repository.saveFolder(Folder(id = "f", name = "Work"))
        repository.saveNote(note("a"))
        repository.setNoteFolder("a", "f")
        repository.deleteFolder("f")

        assertEquals(openOnly("f").rowClock, openOnly("a").rowClock)
    }

    @Test
    fun `a restored folder comes back empty`() = runTest {
        repository.saveFolder(Folder(id = "f", name = "Work"))
        repository.saveNote(note("a"))
        repository.setNoteFolder("a", "f")
        repository.deleteFolder("f")
        repository.restoreFolder("f")

        assertEquals(listOf("Work"), repository.getFolders().first().map { it.name })
        assertNull(repository.getNoteById("a").first()!!.folderId)
    }

    @Test
    fun `purging a folder destroys its row`() = runTest {
        repository.saveFolder(Folder(id = "f", name = "Work"))
        repository.purgeFolder("f")
        assertTrue(store.readAll().isEmpty())
    }

    @Test
    fun `folder colour survives a reload`() = runTest {
        repository.saveFolder(Folder(id = "f", name = "Work", colorArgb = 0xFF102030L))
        assertEquals(0xFF102030L, reload().getFolders().first().single().colorArgb)
    }

    @Test
    fun `a folder with no colour keeps null rather than gaining a zero`() = runTest {
        repository.saveFolder(Folder(id = "f", name = "Work", colorArgb = null))
        assertNull(reload().getFolders().first().single().colorArgb)
    }

    /** Reads the stored record for a domain id back out, so a test can assert on its clocks. */
    private fun openOnly(id: String): RecordPayload {
        val opened = store.readAll().mapNotNull { row ->
            (codec.open(row.blindedId, row.envelope) as? OpenResult.Ok)?.payload
        }
        return opened.single { it.uuid == id }
    }
}
