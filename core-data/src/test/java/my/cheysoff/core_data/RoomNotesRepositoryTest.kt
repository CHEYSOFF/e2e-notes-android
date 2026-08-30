package my.cheysoff.core_data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import my.cheysoff.core_data.data.RoomNotesRepository
import my.cheysoff.core_data.data.local.NoteDatabase
import my.cheysoff.core_data.data.local.NoteEntity
import my.cheysoff.core_domain.model.Folder
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NoteContentFormat
import my.cheysoff.core_domain.model.NotesSortOrder
import my.cheysoff.core_domain.model.TrashPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [RoomNotesRepository] against a real Room database.
 *
 * ## Why a JVM test and not an instrumented one
 *
 * Robolectric ships its own native SQLite, so Room runs here exactly as it does on a device — real
 * schema, real SQL, real `ON CONFLICT` and real transactions. Nothing about the repository is
 * stubbed; the only thing missing versus `androidTest` is SQLCipher, and SQLCipher is an
 * open-helper swap that changes nothing about what these statements do (the same reasoning
 * Migration4to5Test and Migration5to6Test already rely on).
 *
 * The deciding factor is that `jacocoMergedReport` merges only `testDebugUnitTest` execution data.
 * An instrumented copy of these tests would exercise the same code and still report this class at
 * 0% coverage.
 *
 * ## What is under test
 *
 * The repository is thin, so most of these assertions are really about the DAO statements it
 * chooses and the order it runs them in. That is deliberate: the bugs this file exists to catch —
 * a read path that forgets `WHERE isDeleted = 0`, a delete that hard-deletes, a folder delete that
 * leaves notes pointing at a row that is gone — all live exactly there.
 */
@RunWith(RobolectricTestRunner::class)
class RoomNotesRepositoryTest {

    private lateinit var database: NoteDatabase
    private lateinit var repository: RoomNotesRepository

    /** A fixed "long ago" stamp, so a later write is unambiguously newer than it. */
    private val longAgo = 1_000L

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, NoteDatabase::class.java)
            // The repository's suspend functions already hop off the caller's thread; this is only
            // so the fixture helpers below can seed rows without ceremony.
            .allowMainThreadQueries()
            .build()
        repository = RoomNotesRepository(database.noteDao, database.folderDao, database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    // -------------------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------------------

    /**
     * Seeds a row through the DAO's plain @Insert rather than through [RoomNotesRepository.saveNote],
     * so the test controls createdAt/updatedAt exactly. saveNote stamps `System.currentTimeMillis()`
     * internally and is not injectable, which makes "is this timestamp newer than that one?"
     * unanswerable when both writes land in the same millisecond.
     */
    private suspend fun seedNote(
        id: String,
        title: String = "t-$id",
        content: String = "c-$id",
        folderId: String? = null,
        createdAt: Long = longAgo,
        updatedAt: Long = longAgo,
        isPinned: Boolean = false,
        isFavorite: Boolean = false,
        isDeleted: Boolean = false,
        deletedAt: Long? = null,
    ) = database.noteDao.insertNote(
        NoteEntity(
            id = id,
            title = title,
            content = content,
            contentFormat = NoteContentFormat.PLAIN.storageValue,
            checklist = "",
            isPinned = isPinned,
            isFavorite = isFavorite,
            folderId = folderId,
            createdAt = createdAt,
            updatedAt = updatedAt,
            isDeleted = isDeleted,
            deletedAt = deletedAt,
        ),
    )

    private suspend fun seedFolder(id: String, name: String = "f-$id") =
        repository.saveFolder(Folder(id = id, name = name))

    /** Reads a row straight from SQLite, tombstoned or not — no repository read path filters here. */
    private suspend fun rawNote(id: String): NoteEntity? =
        database.noteDao.getDeletedNotes().first().firstOrNull { it.id == id }
            ?: database.noteDao.getNotesByUpdatedAt().first().firstOrNull { it.id == id }

    private suspend fun visibleIds(order: NotesSortOrder = NotesSortOrder.RECENTLY_EDITED) =
        repository.getNotes(order).first().map { it.id }

    // -------------------------------------------------------------------------------------
    // saveNote
    // -------------------------------------------------------------------------------------

    @Test
    fun `saveNote inserts a new note and stamps both timestamps`() = runTest {
        val before = System.currentTimeMillis()
        repository.saveNote(Note(id = "n1", title = "Title", content = "Body"))

        val stored = repository.getNoteById("n1").first()!!
        assertEquals("Title", stored.title)
        assertEquals("Body", stored.content)
        assertTrue("createdAt was stamped", stored.createdAt >= before)
        assertEquals("a brand new note was created and updated at the same instant", stored.createdAt, stored.updatedAt)
    }

    @Test
    fun `saveNote on an existing note refreshes updatedAt but keeps createdAt`() = runTest {
        seedNote("n1", createdAt = longAgo, updatedAt = longAgo)

        repository.saveNote(Note(id = "n1", title = "Edited", content = "Body"))

        val stored = repository.getNoteById("n1").first()!!
        assertEquals("Edited", stored.title)
        assertEquals("the note was not re-created", longAgo, stored.createdAt)
        assertTrue("but it was re-stamped as edited", stored.updatedAt > longAgo)
    }

    @Test
    fun `saveNote does not clobber fields the editor does not own`() = runTest {
        // isFavorite and the tombstone belong to other gestures. An autosave firing while a note is
        // favorited — or in Trash — must leave both alone.
        seedNote("n1", isFavorite = true)

        repository.saveNote(Note(id = "n1", title = "Edited", content = "Body"))

        assertTrue("favorite survived the save", rawNote("n1")!!.isFavorite)
    }

    @Test
    fun `saving a trashed note does not resurrect it`() = runTest {
        seedNote("n1", isDeleted = true, deletedAt = longAgo)

        repository.saveNote(Note(id = "n1", title = "Edited", content = "Body"))

        assertTrue("still in Trash", rawNote("n1")!!.isDeleted)
        assertEquals("and its retention window did not restart", longAgo, rawNote("n1")!!.deletedAt)
        assertTrue("and it is still not in the notes list", visibleIds().isEmpty())
    }

    // -------------------------------------------------------------------------------------
    // Soft delete / restore / purge
    // -------------------------------------------------------------------------------------

    @Test
    fun `deleteNote is soft - the row survives, flagged and stamped`() = runTest {
        seedNote("n1")
        val before = System.currentTimeMillis()

        repository.deleteNote("n1")

        val row = rawNote("n1")
        assertNotNull("the row must NOT be deleted from the table", row)
        assertTrue(row!!.isDeleted)
        assertTrue("and it must carry a stamp, or it can never expire", row.deletedAt!! >= before)
        assertEquals("its content is kept so Restore is lossless", "c-n1", row.content)
    }

    @Test
    fun `deleting an already-trashed note does not restart its retention window`() = runTest {
        seedNote("n1", isDeleted = true, deletedAt = longAgo)

        repository.deleteNote("n1")

        // A re-stamp here would silently give the note another 30 days, so the automatic purge
        // would keep sliding away from it.
        assertEquals(longAgo, rawNote("n1")!!.deletedAt)
    }

    @Test
    fun `restoreNote brings the note back and clears the stamp`() = runTest {
        seedNote("n1")
        repository.deleteNote("n1")
        assertTrue(visibleIds().isEmpty())

        repository.restoreNote("n1")

        assertEquals(listOf("n1"), visibleIds())
        assertFalse(rawNote("n1")!!.isDeleted)
        assertNull("clearing the stamp is what lets the NEXT delete start a fresh window", rawNote("n1")!!.deletedAt)
        assertTrue(repository.getDeletedNotes().first().isEmpty())
    }

    @Test
    fun `purgeNote really removes the row`() = runTest {
        seedNote("n1")
        repository.deleteNote("n1")

        repository.purgeNote("n1")

        assertNull("this one is irreversible", rawNote("n1"))
        assertTrue(repository.getDeletedNotes().first().isEmpty())
        assertTrue(visibleIds().isEmpty())
    }

    @Test
    fun `getDeletedNotes returns only trashed notes, newest deleted first`() = runTest {
        seedNote("alive")
        seedNote("old", isDeleted = true, deletedAt = 100L)
        seedNote("new", isDeleted = true, deletedAt = 300L)
        seedNote("unstamped", isDeleted = true, deletedAt = null)

        val trashed = repository.getDeletedNotes().first()

        assertEquals(
            "newest deleted first, and a row with no stamp sorts last rather than to the top",
            listOf("new", "old", "unstamped"),
            trashed.map { it.id },
        )
        assertTrue("every one of them is flagged", trashed.all { it.isDeleted })
    }

    // -------------------------------------------------------------------------------------
    // Every read path excludes isDeleted = 1
    // -------------------------------------------------------------------------------------

    @Test
    fun `all three note orders exclude trashed notes`() = runTest {
        // The single most repeated bug shape in this schema: delete is soft, so a read that forgets
        // the filter shows the note the user just trashed as if nothing had happened.
        seedNote("alive", title = "Alive", createdAt = 200L, updatedAt = 200L)
        seedNote("trashed", title = "Trashed", createdAt = 900L, updatedAt = 900L, isDeleted = true, deletedAt = 900L)

        for (order in NotesSortOrder.entries) {
            assertEquals(
                "order $order must not show the trashed note (it sorts FIRST in every one of them)",
                listOf("alive"),
                visibleIds(order),
            )
        }
    }

    @Test
    fun `getNoteById emits null for a trashed note and for an unknown id`() = runTest {
        // This is what keeps a note in Trash out of the editor: the screen simply never loads.
        seedNote("n1", isDeleted = true, deletedAt = longAgo)

        assertNull(repository.getNoteById("n1").first())
        assertNull(repository.getNoteById("does-not-exist").first())
    }

    @Test
    fun `getFolders excludes trashed folders`() = runTest {
        seedFolder("f1", "Work")
        seedFolder("f2", "Personal")
        repository.deleteFolder("f2")

        assertEquals(listOf("f1"), repository.getFolders().first().map { it.id })
    }

    // -------------------------------------------------------------------------------------
    // Sort orders
    // -------------------------------------------------------------------------------------

    @Test
    fun `RECENTLY_EDITED sorts by updatedAt descending`() = runTest {
        seedNote("old", updatedAt = 100L)
        seedNote("newest", updatedAt = 300L)
        seedNote("middle", updatedAt = 200L)

        assertEquals(
            listOf("newest", "middle", "old"),
            visibleIds(NotesSortOrder.RECENTLY_EDITED),
        )
    }

    @Test
    fun `NEWEST_CREATED sorts by createdAt descending, independently of edits`() = runTest {
        // Deliberately opposed to the updatedAt order, so a repository that routed this to the
        // wrong @Query would fail rather than accidentally agree.
        seedNote("first", createdAt = 100L, updatedAt = 900L)
        seedNote("second", createdAt = 200L, updatedAt = 800L)
        seedNote("third", createdAt = 300L, updatedAt = 700L)

        assertEquals(listOf("third", "second", "first"), visibleIds(NotesSortOrder.NEWEST_CREATED))
        assertEquals(listOf("first", "second", "third"), visibleIds(NotesSortOrder.RECENTLY_EDITED))
    }

    @Test
    fun `TITLE_ASC is case-insensitive and sinks untitled notes to the bottom`() = runTest {
        seedNote("b", title = "banana")
        seedNote("a", title = "Apple")
        seedNote("untitled", title = "")
        seedNote("c", title = "Cherry")

        assertEquals(
            "untitled last, and 'Apple' before 'banana' rather than all capitals first",
            listOf("a", "b", "c", "untitled"),
            visibleIds(NotesSortOrder.TITLE_ASC),
        )
    }

    // -------------------------------------------------------------------------------------
    // Per-note metadata
    // -------------------------------------------------------------------------------------

    @Test
    fun `setNoteFolder files and unfiles a note without re-stamping it`() = runTest {
        seedNote("n1", updatedAt = longAgo)
        seedFolder("f1")

        repository.setNoteFolder("n1", "f1")
        assertEquals("f1", repository.getNoteById("n1").first()!!.folderId)

        repository.setNoteFolder("n1", null)
        assertNull(repository.getNoteById("n1").first()!!.folderId)

        // PR #32: these are user gestures on one note, and bumping updatedAt would jump the note to
        // the top of a newest-first list the user did not ask to reorder.
        assertEquals(longAgo, rawNote("n1")!!.updatedAt)
    }

    @Test
    fun `setNoteFavorite and setNotePinned toggle without re-stamping`() = runTest {
        seedNote("n1", updatedAt = longAgo)

        repository.setNoteFavorite("n1", true)
        repository.setNotePinned("n1", true)
        assertTrue(rawNote("n1")!!.isFavorite)
        assertTrue(repository.getNoteById("n1").first()!!.isPinned)

        repository.setNoteFavorite("n1", false)
        repository.setNotePinned("n1", false)
        assertFalse(rawNote("n1")!!.isFavorite)
        assertFalse(repository.getNoteById("n1").first()!!.isPinned)

        assertEquals(longAgo, rawNote("n1")!!.updatedAt)
    }

    // -------------------------------------------------------------------------------------
    // Folders
    // -------------------------------------------------------------------------------------

    @Test
    fun `saveFolder inserts, then updates name and color while keeping createdAt`() = runTest {
        repository.saveFolder(Folder(id = "f1", name = "Work", colorArgb = 1L))
        val created = repository.getFolders().first().single().createdAt

        repository.saveFolder(Folder(id = "f1", name = "Werk", colorArgb = 2L))

        val stored = repository.getFolders().first().single()
        assertEquals("Werk", stored.name)
        assertEquals(2L, stored.colorArgb)
        // The old @Insert(REPLACE) was a DELETE + INSERT, so a rename used to wipe this.
        assertEquals("a rename is not a re-creation", created, stored.createdAt)
    }

    @Test
    fun `renaming a trashed folder does not pull it out of Trash`() = runTest {
        seedFolder("f1", "Work")
        repository.deleteFolder("f1")

        repository.saveFolder(Folder(id = "f1", name = "Renamed"))

        assertTrue(repository.getFolders().first().isEmpty())
        assertEquals(listOf("f1"), repository.getDeletedFolders().first().map { it.id })
    }

    @Test
    fun `deleteFolder unfiles its notes and DOES bump their updatedAt`() = runTest {
        seedFolder("f1")
        seedNote("filed", folderId = "f1", updatedAt = longAgo)
        seedNote("elsewhere", folderId = null, updatedAt = longAgo)
        val before = System.currentTimeMillis()

        repository.deleteFolder("f1")

        val filed = rawNote("filed")!!
        assertNull("the note moved to All", filed.folderId)
        // Unlike the single-note gestures above, this is a mass edit the user did not aim at any
        // note. Leaving it traceless would make the change invisible to anything that reasons about
        // when a note last changed.
        assertTrue("and it was re-stamped", filed.updatedAt >= before)

        assertEquals("untouched notes stay untouched", longAgo, rawNote("elsewhere")!!.updatedAt)
    }

    @Test
    fun `deleteFolder also unfiles notes that are themselves in Trash`() = runTest {
        // Deliberately not filtered by isDeleted: a trashed note still carries this folderId, and
        // leaving it pointing at a row that is about to be purged would dangle the moment either
        // one is restored.
        seedFolder("f1")
        seedNote("trashed", folderId = "f1", isDeleted = true, deletedAt = longAgo)

        repository.deleteFolder("f1")

        assertNull(rawNote("trashed")!!.folderId)
    }

    @Test
    fun `deleteFolder is soft and stamps the folder`() = runTest {
        seedFolder("f1", "Work")
        val before = System.currentTimeMillis()

        repository.deleteFolder("f1")

        val trashed = repository.getDeletedFolders().first().single()
        assertEquals("f1", trashed.id)
        assertEquals("Work", trashed.name)
        assertTrue(trashed.isDeleted)
        assertTrue(trashed.deletedAt!! >= before)
    }

    @Test
    fun `restoreFolder brings the folder back, empty`() = runTest {
        seedFolder("f1", "Work")
        seedNote("n1", folderId = "f1")
        repository.deleteFolder("f1")

        repository.restoreFolder("f1")

        val restored = repository.getFolders().first().single()
        assertEquals("f1", restored.id)
        assertFalse(restored.isDeleted)
        assertNull(restored.deletedAt)
        // Deleting the folder unfiled its notes and nothing recorded which ones they were, so it
        // comes back empty. That is the documented behaviour, not an oversight.
        assertNull(rawNote("n1")!!.folderId)
    }

    @Test
    fun `purgeFolder really removes the row`() = runTest {
        seedFolder("f1")
        repository.deleteFolder("f1")

        repository.purgeFolder("f1")

        assertTrue(repository.getDeletedFolders().first().isEmpty())
        assertTrue(repository.getFolders().first().isEmpty())
    }

    @Test
    fun `getDeletedFolders returns only trashed folders, newest deleted first`() = runTest {
        seedFolder("alive")
        seedFolder("old")
        seedFolder("new")
        // Stamped through the DAO rather than through repository.deleteFolder, which stamps
        // System.currentTimeMillis(): two deletes landing in the same millisecond would tie, and
        // the expected order would then be decided by the query's `id ASC` tie-breaker instead of
        // by deletedAt — which is the thing under test.
        database.folderDao.softDeleteFolder("old", 100L)
        database.folderDao.softDeleteFolder("new", 300L)

        assertEquals(
            listOf("new", "old"),
            repository.getDeletedFolders().first().map { it.id },
        )
        assertEquals(listOf("alive"), repository.getFolders().first().map { it.id })
    }

    // -------------------------------------------------------------------------------------
    // purgeExpiredTrash
    // -------------------------------------------------------------------------------------

    @Test
    fun `purgeExpiredTrash destroys only rows past the retention window`() = runTest {
        val now = 100L * TrashPolicy.RETENTION_MILLIS
        val expired = now - TrashPolicy.RETENTION_MILLIS - 1
        val fresh = now - TrashPolicy.RETENTION_MILLIS + 1

        seedNote("note-expired", isDeleted = true, deletedAt = expired)
        seedNote("note-fresh", isDeleted = true, deletedAt = fresh)
        seedNote("note-alive")
        seedFolder("folder-expired")
        seedFolder("folder-fresh")
        seedFolder("folder-alive")
        database.folderDao.softDeleteFolder("folder-expired", expired)
        database.folderDao.softDeleteFolder("folder-fresh", fresh)

        val purged = repository.purgeExpiredTrash(now)

        assertEquals("one note and one folder", 2, purged)
        assertEquals(listOf("note-fresh"), repository.getDeletedNotes().first().map { it.id })
        assertEquals(listOf("folder-fresh"), repository.getDeletedFolders().first().map { it.id })
        assertEquals("living rows are never touched", listOf("note-alive"), visibleIds())
        assertEquals(listOf("folder-alive"), repository.getFolders().first().map { it.id })
    }

    @Test
    fun `purgeExpiredTrash keeps rows whose stamp is missing or unset`() = runTest {
        // A tombstone with no usable stamp has no measurable age. Guessing at one would destroy
        // notes, so both the query and TrashPolicy resolve the ambiguity as "keep".
        seedNote("null-stamp", isDeleted = true, deletedAt = null)
        seedNote("zero-stamp", isDeleted = true, deletedAt = 0L)

        val purged = repository.purgeExpiredTrash(100L * TrashPolicy.RETENTION_MILLIS)

        assertEquals(0, purged)
        assertEquals(2, repository.getDeletedNotes().first().size)
    }

    @Test
    fun `purgeExpiredTrash reports zero when there is nothing to purge`() = runTest {
        seedNote("alive")
        assertEquals(0, repository.purgeExpiredTrash(System.currentTimeMillis()))
        assertEquals(listOf("alive"), visibleIds())
    }
}
