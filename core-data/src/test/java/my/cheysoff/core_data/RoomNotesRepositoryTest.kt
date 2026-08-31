package my.cheysoff.core_data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import my.cheysoff.core_data.data.RoomNotesRepository
import my.cheysoff.core_data.data.local.NoteDatabase
import my.cheysoff.core_data.data.local.NoteEntity
import my.cheysoff.core_data.data.sync.SyncClock
import my.cheysoff.core_domain.model.Folder
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NoteContentFormat
import my.cheysoff.core_domain.model.NotesSortOrder
import my.cheysoff.core_domain.model.TrashPolicy
import my.cheysoff.core_domain.sync.FieldClocks
import my.cheysoff.core_domain.sync.Hlc
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

    /**
     * The node every clock minted in this file carries. A fixed string rather than a real
     * pseudonym: `HlcNode`'s derivation needs an ARK and a Keystore, and nothing here is testing
     * the derivation — only that whatever the node is, it reaches the row.
     */
    private val node = "testnode"

    /**
     * The real wall clock, deliberately. Several tests below assert that a write stamped
     * `updatedAt` at "about now", and a frozen clock would make those vacuous. The HLC's
     * monotonicity does not depend on the wall clock advancing — that is the whole point of the
     * counter — so the sync assertions further down still hold when two writes land in the same
     * millisecond.
     */
    private val clock = SyncClock(node = { node })

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, NoteDatabase::class.java)
            // The repository's suspend functions already hop off the caller's thread; this is only
            // so the fixture helpers below can seed rows without ceremony.
            .allowMainThreadQueries()
            .build()
        repository = RoomNotesRepository(database.noteDao, database.folderDao, database, clock)
    }

    @After
    fun tearDown() {
        database.close()
    }

    // -------------------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------------------

    /**
     * Seeds a row through the DAO's full-row write rather than through
     * [RoomNotesRepository.saveNote], so the test controls createdAt/updatedAt exactly. saveNote
     * stamps the wall clock internally, which makes "is this timestamp newer than that one?"
     * unanswerable when both writes land in the same millisecond.
     *
     * [NoteDao.applyRemoteNote] is the write that exists for exactly this shape — every column at
     * once, nothing inferred — and it replaced the `@Insert(REPLACE)` this helper used to call.
     *
     * The sync columns default to a **clean, already-pushed** row (`dirty = false`, a non-zero
     * `lastSyncedSeq`, a real row clock) because that is the interesting starting state: it is
     * what every assertion about "this write marked the row dirty" and "this write bumped the
     * clock" needs in order to be able to fail.
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
        hlc: Hlc = seededHlc,
        fieldHlc: String = "",
        dirty: Boolean = false,
        lastSyncedSeq: Long = 42L,
    ) = database.noteDao.applyRemoteNote(
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
            hlcMs = hlc.ms,
            hlcCounter = hlc.counter,
            hlcNode = hlc.node,
            fieldHlc = fieldHlc,
            dirty = dirty,
            lastSyncedSeq = lastSyncedSeq,
        ),
    )

    /**
     * The row clock a seeded note starts at: an instant in 1970, so every clock the repository
     * mints from the real wall clock is unambiguously greater than it.
     */
    private val seededHlc = Hlc(ms = 5_000L, counter = 0, node = "seed")

    private suspend fun seedFolder(id: String, name: String = "f-$id") =
        repository.saveFolder(Folder(id = id, name = name))

    /**
     * Trashes a folder at an exact [deletedAt], straight through the DAO.
     *
     * `repository.deleteFolder` stamps the real wall clock, so two deletes in one test can land in
     * the same millisecond and tie — at which point the query's `id ASC` tie-breaker decides the
     * order rather than `deletedAt`, which is the thing under test. The clock arguments are
     * irrelevant to those assertions but the DAO requires them, so they are the same shape a real
     * write would pass.
     */
    private suspend fun stampFolderDeletedAt(id: String, deletedAt: Long) =
        database.folderDao.softDeleteFolder(
            id = id,
            timestamp = deletedAt,
            hlcMs = deletedAt,
            hlcCounter = 0,
            hlcNode = node,
            fieldHlc = "",
        )

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
        stampFolderDeletedAt("old", 100L)
        stampFolderDeletedAt("new", 300L)

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
        stampFolderDeletedAt("folder-expired", expired)
        stampFolderDeletedAt("folder-fresh", fresh)

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

    // -------------------------------------------------------------------------------------
    // Sync bookkeeping (v7): the row clock, the field clocks and `dirty`
    //
    // Each of the fifteen write paths in the two DAOs either changes a row — in which case it
    // owes the account a new clock and a `dirty = 1`, or the change is invisible to every other
    // device and is lost on the next merge — or it is a hard DELETE, which owes nothing because
    // there is no row left to stamp. The tests below name the path in the test name, so a
    // regression says which one.
    // -------------------------------------------------------------------------------------

    /** The sync columns of one row, read straight out of SQLite so nothing can filter them. */
    private data class SyncColumns(
        val hlc: Hlc,
        val fieldHlc: String,
        val dirty: Boolean,
        val lastSyncedSeq: Long,
        val updatedAt: Long,
    )

    private fun syncColumns(table: String, id: String): SyncColumns =
        database.openHelper.readableDatabase.query(
            "SELECT hlcMs, hlcCounter, hlcNode, fieldHlc, dirty, lastSyncedSeq, updatedAt " +
                "FROM $table WHERE id = ?",
            arrayOf<Any>(id),
        ).use { c ->
            assertTrue("no row '$id' in $table", c.moveToFirst())
            SyncColumns(
                hlc = Hlc(ms = c.getLong(0), counter = c.getInt(1), node = c.getString(2)),
                fieldHlc = c.getString(3),
                dirty = c.getInt(4) != 0,
                lastSyncedSeq = c.getLong(5),
                updatedAt = c.getLong(6),
            )
        }

    private fun noteSync(id: String) = syncColumns("notes", id)
    private fun folderSync(id: String) = syncColumns("folders", id)

    /** Asserts a write moved the row forward in the account's history and left it unpushed. */
    private fun assertStamped(what: String, before: Hlc, after: SyncColumns) {
        assertTrue(
            "$what did not advance the row clock ($before -> ${after.hlc})",
            after.hlc > before,
        )
        assertEquals("$what did not stamp this device's node", node, after.hlc.node)
        assertTrue("$what left the row clean, so it would never be pushed", after.dirty)
    }

    @Test
    fun `saveNote stamps the row clock and marks the note dirty`() = runTest {
        seedNote("n1")

        repository.saveNote(Note(id = "n1", title = "Edited", content = "Body"))

        assertStamped("saveNote", seededHlc, noteSync("n1"))
    }

    @Test
    fun `saveNote on a brand new note starts it dirty and unpushed`() = runTest {
        repository.saveNote(Note(id = "n1", title = "New", content = "Body"))

        val sync = noteSync("n1")
        assertTrue("a note the server has never seen must be dirty", sync.dirty)
        assertEquals("and must claim no server version at all", 0L, sync.lastSyncedSeq)
        assertTrue("and must carry a real clock, not the zero one", sync.hlc > Hlc.ZERO)
        // Every field of a note that did not exist a moment ago was written by this one statement,
        // which is exactly what an empty fieldHlc means.
        assertEquals("a new note has no field older than itself", "", sync.fieldHlc)
    }

    @Test
    fun `saveNote on an existing note keeps its lastSyncedSeq as the push baseline`() = runTest {
        seedNote("n1", lastSyncedSeq = 42L)

        repository.saveNote(Note(id = "n1", title = "Edited", content = "Body"))

        // Resetting this to 0 would tell the server "this record must not exist" and turn the next
        // push of an already-uploaded note into a guaranteed conflict.
        assertEquals(42L, noteSync("n1").lastSyncedSeq)
    }

    @Test
    fun `deleteNote stamps the row clock and marks the note dirty`() = runTest {
        seedNote("n1")

        repository.deleteNote("n1")

        assertStamped("deleteNote", seededHlc, noteSync("n1"))
    }

    @Test
    fun `restoreNote stamps the row clock and marks the note dirty`() = runTest {
        seedNote("n1", isDeleted = true, deletedAt = longAgo)

        repository.restoreNote("n1")

        assertStamped("restoreNote", seededHlc, noteSync("n1"))
    }

    @Test
    fun `setNoteFolder stamps the row clock and marks the note dirty`() = runTest {
        seedNote("n1")
        seedFolder("f1")

        repository.setNoteFolder("n1", "f1")

        assertStamped("setNoteFolder", seededHlc, noteSync("n1"))
    }

    @Test
    fun `setNoteFavorite stamps the row clock and marks the note dirty`() = runTest {
        seedNote("n1")

        repository.setNoteFavorite("n1", true)

        assertStamped("setNoteFavorite", seededHlc, noteSync("n1"))
    }

    @Test
    fun `setNotePinned stamps the row clock and marks the note dirty`() = runTest {
        seedNote("n1")

        repository.setNotePinned("n1", true)

        assertStamped("setNotePinned", seededHlc, noteSync("n1"))
    }

    /**
     * The whole PR #32 question, settled: the three metadata gestures leave `updatedAt` exactly
     * where it was AND still leave a clock behind.
     *
     * Both halves matter and they pull in opposite directions. Bump `updatedAt` and a pin jumps
     * the note to the top of a newest-first list nobody asked to reorder. Skip the clock and the
     * pin never reaches the other device at all. They are separate columns precisely so that both
     * can be true.
     */
    @Test
    fun `the three metadata gestures bump the clock while leaving updatedAt alone`() = runTest {
        seedNote("pin", updatedAt = longAgo)
        seedNote("fav", updatedAt = longAgo)
        seedNote("folder", updatedAt = longAgo)
        seedFolder("f1")

        repository.setNotePinned("pin", true)
        repository.setNoteFavorite("fav", true)
        repository.setNoteFolder("folder", "f1")

        for (id in listOf("pin", "fav", "folder")) {
            val sync = noteSync(id)
            assertEquals("$id was re-stamped as edited", longAgo, sync.updatedAt)
            assertTrue("$id changed without leaving a clock", sync.hlc > seededHlc)
            assertTrue("$id changed without being marked dirty", sync.dirty)
        }
    }

    @Test
    fun `saveFolder stamps the row clock and marks the folder dirty`() = runTest {
        repository.saveFolder(Folder(id = "f1", name = "Work"))
        val afterInsert = folderSync("f1")
        assertTrue("a new folder must be dirty", afterInsert.dirty)

        repository.saveFolder(Folder(id = "f1", name = "Werk"))

        assertStamped("saveFolder", afterInsert.hlc, folderSync("f1"))
    }

    @Test
    fun `restoreFolder stamps the row clock and marks the folder dirty`() = runTest {
        seedFolder("f1")
        repository.deleteFolder("f1")
        val trashed = folderSync("f1")

        repository.restoreFolder("f1")

        assertStamped("restoreFolder", trashed.hlc, folderSync("f1"))
    }

    /**
     * Deleting a folder is ONE user action, so it gets one clock — shared by the folder's
     * tombstone and by every note it unfiles.
     *
     * Advancing the counter per note would spread a single gesture across N+1 points in the
     * account's history and imply an ordering between the notes that does not exist. It would also
     * make the folder's tombstone strictly newer or older than the unfiling it caused, which the
     * merging device would then be free to apply in halves.
     */
    @Test
    fun `deleteFolder gives the folder and every note it unfiles ONE shared clock`() = runTest {
        seedFolder("f1")
        seedNote("a", folderId = "f1")
        seedNote("b", folderId = "f1")
        seedNote("trashed", folderId = "f1", isDeleted = true, deletedAt = longAgo)
        seedNote("elsewhere", folderId = null)

        repository.deleteFolder("f1")

        val folder = folderSync("f1")
        val clocks = listOf("a", "b", "trashed").map { noteSync(it).hlc }
        clocks.forEach { assertTrue("a note was unfiled without a clock", it > seededHlc) }
        assertEquals(
            "the folder delete was spread across more than one point in history",
            setOf(folder.hlc),
            clocks.toSet(),
        )
        listOf("a", "b", "trashed").forEach {
            assertTrue("$it was unfiled but left clean", noteSync(it).dirty)
        }
        assertEquals(
            "a note outside the folder was stamped anyway",
            seededHlc,
            noteSync("elsewhere").hlc,
        )
    }

    /**
     * A write that matches no row must not mint a version of the record either.
     *
     * `softDeleteNote` carries `AND isDeleted = 0` so a repeat delete cannot restart the retention
     * window. The clock is inside the same statement, so it inherits the guard — which is the
     * behaviour we want: nothing changed, so there is nothing for another device to hear about,
     * and stamping anyway would push a byte-identical record on every duplicate tap.
     */
    @Test
    fun `deleting an already-trashed note does not mint a new clock`() = runTest {
        seedNote("n1")
        repository.deleteNote("n1")
        val afterFirst = noteSync("n1")

        repository.deleteNote("n1")

        assertEquals("a no-op delete re-stamped the row", afterFirst.hlc, noteSync("n1").hlc)
    }

    /**
     * Field clocks: a write records the fields it did NOT touch at the clocks they already had.
     *
     * The empty string means "every field is at the row clock", so a write that left `fieldHlc`
     * empty would claim the whole note is as new as this one gesture — and the next merge would
     * use that claim to discard the other device's newer title or body. What the column has to
     * carry after a favourite toggle is everything *except* `isFavorite`.
     */
    @Test
    fun `a metadata write records the clocks of the fields it did not touch`() = runTest {
        seedNote("n1")
        // First a full save, so the note's fields have a real clock rather than the seed's.
        repository.saveNote(Note(id = "n1", title = "Body v1", content = "Body"))
        val afterSave = noteSync("n1").hlc

        repository.setNoteFavorite("n1", true)

        val clocks = FieldClocks.parse(noteSync("n1").fieldHlc)
        assertEquals(
            "the fields this write did not touch lost their own clocks",
            afterSave,
            clocks[FieldClocks.TITLE],
        )
        assertEquals(afterSave, clocks[FieldClocks.CONTENT])
        assertNull(
            "isFavorite was written by this gesture, so it is at the row clock and implicit",
            clocks[FieldClocks.FAVORITE],
        )
    }

    /**
     * `upsertNote` does not write `isFavorite` or the tombstone, so a save must not claim a clock
     * for them either.
     *
     * Claiming one is the quiet version of the resurrection bug the `ON CONFLICT` branch already
     * guards against: the columns would keep their old values while their clocks said "written
     * just now", and the next merge would use that to overwrite the other device's genuine delete
     * with this device's stale "not deleted".
     */
    @Test
    fun `saveNote does not claim a clock for the fields it refuses to write`() = runTest {
        seedNote("n1", isFavorite = true)

        repository.saveNote(Note(id = "n1", title = "Edited", content = "Body"))

        val sync = noteSync("n1")
        val clocks = FieldClocks.parse(sync.fieldHlc)
        assertEquals(
            "isFavorite was claimed by a save that never wrote it",
            seededHlc,
            clocks[FieldClocks.FAVORITE],
        )
        assertEquals(
            "the tombstone was claimed by a save that never wrote it",
            seededHlc,
            clocks[FieldClocks.DELETED],
        )
        assertNull("but the title WAS written, so it is at the row clock", clocks[FieldClocks.TITLE])
    }

    @Test
    fun `every write mints a clock strictly greater than the one before it`() = runTest {
        seedNote("n1")
        seedFolder("f1")

        val clocks = mutableListOf<Hlc>()
        repository.saveNote(Note(id = "n1", title = "a", content = "a"))
        clocks += noteSync("n1").hlc
        repository.setNotePinned("n1", true)
        clocks += noteSync("n1").hlc
        repository.setNoteFavorite("n1", true)
        clocks += noteSync("n1").hlc
        repository.setNoteFolder("n1", "f1")
        clocks += noteSync("n1").hlc
        repository.deleteNote("n1")
        clocks += noteSync("n1").hlc
        repository.restoreNote("n1")
        clocks += noteSync("n1").hlc

        // Five writes can easily land in the same millisecond on a fast machine, which is the
        // whole reason the counter exists — so this is a real assertion, not a formality.
        clocks.zipWithNext { earlier, later ->
            assertTrue("the clock did not advance: $earlier then $later", later > earlier)
        }
    }

    /**
     * A new process seeds its clock from the database before its first write.
     *
     * Without that, a device whose wall clock has been wound back since the last session starts
     * minting clocks *below* the ones already on disk, and a row whose clock goes backwards loses
     * to its own older version the next time it is merged — the edit is discarded with no error
     * anywhere. `HlcGenerator` cannot defend against this alone: a fresh generator has been shown
     * nothing, and the only durable record of how far the clock has got is the rows themselves.
     */
    @Test
    fun `a restarted repository does not mint clocks below what is already stored`() = runTest {
        seedNote("n1", hlc = Hlc(ms = 9_000_000L, counter = 4, node = "otherdevice"))

        // A brand-new clock, as after a process restart — and a wall clock that has been wound
        // back to 1970 relative to what is stored.
        val rewound = SyncClock(node = { node }, wallClock = { 1_000L })
        val restarted = RoomNotesRepository(database.noteDao, database.folderDao, database, rewound)

        restarted.saveNote(Note(id = "n1", title = "Edited", content = "Body"))

        val after = noteSync("n1").hlc
        assertTrue(
            "the row clock went BACKWARDS across a restart: $after",
            after > Hlc(ms = 9_000_000L, counter = 4, node = "otherdevice"),
        )
        assertEquals("the physical part was kept rather than reset to the rewound clock", 9_000_000L, after.ms)
    }
}
