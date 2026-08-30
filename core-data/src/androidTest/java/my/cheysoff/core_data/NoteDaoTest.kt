package my.cheysoff.core_data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import my.cheysoff.core_data.data.local.NoteDatabase
import my.cheysoff.core_data.data.local.NoteEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The `notes` write paths, exercised as SQL against a real (in-memory, unencrypted) v6 database.
 *
 * Everything asserted here is a rule that exists ONLY as SQL — a `CASE WHEN`, an omitted column in
 * an `ON CONFLICT` branch, an `AND isDeleted = 0` on an UPDATE. None of it is reachable from a JVM
 * unit test, and none of it is covered by [Migration5to6Test], which exercises the post-migration
 * shape of the schema rather than the ordinary write paths that run on every keystroke.
 *
 * Each test names the consequence of the rule breaking, because in this app all of them are
 * silent: the app keeps working and the data quietly changes underneath it.
 *
 * SQLCipher is deliberately absent, exactly as in [Migration4to5Test]: it is an open-helper swap
 * and changes nothing about what these statements do, while requiring a Keystore-wrapped
 * passphrase inside a test.
 *
 * Run (`connectedAndroidTest` cannot resolve its own dependencies in this environment):
 *
 *     ./gradlew :core-data:assembleDebugAndroidTest
 *     adb install -r -t core-data/build/outputs/apk/androidTest/debug/core-data-debug-androidTest.apk
 *     adb shell am instrument -w \
 *       -e class my.cheysoff.core_data.NoteDaoTest \
 *       my.cheysoff.core_data.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class NoteDaoTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: NoteDatabase

    private val dao get() = db.noteDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ctx, NoteDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun note(
        id: String,
        title: String = "title-$id",
        content: String = "body-$id",
        isPinned: Boolean = false,
        isFavorite: Boolean = false,
        folderId: String? = null,
        createdAt: Long = 1_000L,
        updatedAt: Long = 2_000L,
        isDeleted: Boolean = false,
        deletedAt: Long? = null,
    ) = NoteEntity(
        id = id,
        title = title,
        content = content,
        isPinned = isPinned,
        isFavorite = isFavorite,
        folderId = folderId,
        createdAt = createdAt,
        updatedAt = updatedAt,
        isDeleted = isDeleted,
        deletedAt = deletedAt,
    )

    /** The row as it is on disk, tombstone included — i.e. NOT through the filtered reads. */
    private suspend fun row(id: String): NoteEntity? =
        dao.getNotesByUpdatedAt().first().find { it.id == id }
            ?: dao.getDeletedNotes().first().find { it.id == id }

    /** An upsert with the shape the editor's autosave uses: content fields and isPinned only. */
    private suspend fun upsert(
        id: String,
        title: String = "edited",
        content: String = "edited body",
        isPinned: Boolean = false,
        folderId: String? = null,
        timestamp: Long = 9_000L,
    ) = dao.upsertNote(
        id = id,
        title = title,
        content = content,
        contentFormat = "html",
        checklist = "",
        isPinned = isPinned,
        folderId = folderId,
        timestamp = timestamp,
    )

    // ── upsertNote: the fields the save path does NOT own ──────────────────────────────────────

    /**
     * The editor's save builds its [my.cheysoff.core_domain.model.Note] without isFavorite (see
     * SingleNoteViewModel.saveNote), so if the conflict branch wrote `isFavorite = excluded.
     * isFavorite` every autosave would silently un-favorite the note being typed into.
     */
    @Test
    fun anUpsertLeavesIsFavoriteAlone() = runBlocking {
        dao.insertNote(note("n1", isFavorite = true))

        upsert("n1")

        assertTrue("autosave cleared isFavorite", row("n1")!!.isFavorite)
    }

    /**
     * A save racing a delete must not resurrect the note. The tombstone columns are absent from
     * the conflict branch for exactly this reason; only restoreNote clears them.
     */
    @Test
    fun anUpsertCannotPullANoteBackOutOfTrash() = runBlocking {
        dao.insertNote(note("n1", isDeleted = true, deletedAt = 5_000L))

        upsert("n1")

        val stored = row("n1")!!
        assertTrue("upsert resurrected a trashed note", stored.isDeleted)
        assertEquals("upsert restarted the retention window", 5_000L, stored.deletedAt)
        // It also stays out of the notes list and out of the editor.
        assertTrue(dao.getNotesByUpdatedAt().first().none { it.id == "n1" })
        assertNull(dao.getNoteById("n1").first())
        // …but the edit itself did land, so nothing is lost if the user restores it.
        assertEquals("edited", dao.getDeletedNotes().first().single().title)
    }

    /** createdAt is set once and then kept: the list's "newest created" order depends on it. */
    @Test
    fun anUpsertKeepsTheOriginalCreatedAtAndRefreshesUpdatedAt() = runBlocking {
        dao.insertNote(note("n1", createdAt = 1_000L, updatedAt = 2_000L))

        upsert("n1", timestamp = 9_000L)

        val stored = row("n1")!!
        assertEquals("createdAt was rewritten", 1_000L, stored.createdAt)
        assertEquals(9_000L, stored.updatedAt)
    }

    /**
     * The one case where createdAt DOES move: a legacy row migrated in before v2 stores 0, the
     * "unset" sentinel, and the first save backfills it. isDiscardableOnOpen's comment turns on
     * this being true, so it is worth pinning rather than assuming.
     */
    @Test
    fun anUpsertBackfillsALegacyZeroCreatedAt() = runBlocking {
        dao.insertNote(note("legacy", createdAt = 0L, updatedAt = 0L))

        upsert("legacy", timestamp = 9_000L)

        assertEquals(9_000L, row("legacy")!!.createdAt)
    }

    /** A brand-new id is inserted alive, unfavorited, and stamped on both timestamps. */
    @Test
    fun anUpsertOfAnUnknownIdInsertsAFreshLiveRow() = runBlocking {
        upsert("new", timestamp = 9_000L)

        val stored = row("new")!!
        assertEquals(9_000L, stored.createdAt)
        assertEquals(9_000L, stored.updatedAt)
        assertFalse(stored.isFavorite)
        assertFalse(stored.isDeleted)
        assertNull(stored.deletedAt)
    }

    // ── the tombstone ─────────────────────────────────────────────────────────────────────────

    /**
     * Deleting an already-trashed note must not re-stamp deletedAt: that would silently restart
     * its 30-day retention, so a note the user deleted a month ago would never expire.
     */
    @Test
    fun aSecondDeleteDoesNotRestartTheRetentionWindow() = runBlocking {
        dao.insertNote(note("n1"))

        dao.softDeleteNote("n1", 5_000L)
        dao.softDeleteNote("n1", 90_000L)

        assertEquals(5_000L, dao.getDeletedNotes().first().single().deletedAt)
    }

    /** Restore clears the stamp, so the NEXT delete starts a fresh window rather than an aged one. */
    @Test
    fun restoreClearsTheStampSoTheNextDeleteStartsOver() = runBlocking {
        dao.insertNote(note("n1"))
        dao.softDeleteNote("n1", 5_000L)

        dao.restoreNote("n1")
        val restored = row("n1")!!
        assertFalse(restored.isDeleted)
        assertNull("a restored note kept its deletedAt", restored.deletedAt)

        dao.softDeleteNote("n1", 90_000L)
        assertEquals(90_000L, dao.getDeletedNotes().first().single().deletedAt)
    }

    // ── purgeNotesDeletedBefore: the only statement in the app that destroys a note ────────────

    /**
     * The purge is irreversible, so what it must NOT take is the interesting half. Every guard in
     * the WHERE clause is represented by a row here, and each survivor names the guard that saved
     * it.
     */
    @Test
    fun thePurgeTakesOnlyExpiredTombstonesAndNothingElse() = runBlocking {
        val threshold = 10_000L
        dao.insertNote(note("alive"))                                            // isDeleted = 0
        dao.insertNote(note("expired", isDeleted = true, deletedAt = threshold))  // == threshold
        dao.insertNote(note("older", isDeleted = true, deletedAt = 1L))
        dao.insertNote(note("fresh", isDeleted = true, deletedAt = threshold + 1))
        dao.insertNote(note("unstamped", isDeleted = true, deletedAt = null))
        dao.insertNote(note("zero", isDeleted = true, deletedAt = 0L))

        val purged = dao.purgeNotesDeletedBefore(threshold)

        assertEquals(2, purged)
        assertEquals(listOf("alive"), dao.getNotesByUpdatedAt().first().map { it.id })
        assertEquals(
            // A tombstone with no usable stamp has no measurable age, so it is kept rather than
            // guessed at — the same rule TrashPolicy.isExpired states for the UI.
            listOf("fresh", "unstamped", "zero").sorted(),
            dao.getDeletedNotes().first().map { it.id }.sorted(),
        )
    }

    @Test
    fun aPurgeWithNothingExpiredReportsZeroAndDeletesNothing() = runBlocking {
        dao.insertNote(note("alive"))
        dao.insertNote(note("fresh", isDeleted = true, deletedAt = 20_000L))

        assertEquals(0, dao.purgeNotesDeletedBefore(10_000L))
        assertEquals(2, dao.getNotesByUpdatedAt().first().size + dao.getDeletedNotes().first().size)
    }

    // ── the metadata writes, which must leave no trace ────────────────────────────────────────

    /**
     * Pin, favorite and folder are single-note gestures that must NOT reorder a newest-first list
     * (PR #32). They are targeted UPDATEs precisely so updatedAt stays put; a stray
     * `updatedAt = :timestamp` in any of them would make favoriting a note jump it to the top.
     */
    @Test
    fun theThreeMetadataUpdatesDoNotBumpUpdatedAt() = runBlocking {
        dao.insertNote(note("n1", updatedAt = 2_000L))

        dao.setNotePinned("n1", true)
        dao.setNoteFavorite("n1", true)
        dao.setNoteFolder("n1", "f1")

        val stored = row("n1")!!
        assertEquals("a metadata write reordered the list", 2_000L, stored.updatedAt)
        assertTrue(stored.isPinned)
        assertTrue(stored.isFavorite)
        assertEquals("f1", stored.folderId)
    }

    /**
     * The exception, and deliberately so: unfiling on folder delete is a mass edit the user did
     * not aim at any note, and leaving it traceless would make it invisible to anything reasoning
     * about when a note last changed. It also reaches notes already in Trash, so restoring one
     * cannot leave it pointing at a folder row that is gone.
     */
    @Test
    fun clearFolderStampsUpdatedAtAndReachesTrashedNotesToo() = runBlocking {
        dao.insertNote(note("live", folderId = "f1", updatedAt = 2_000L))
        dao.insertNote(note("trashed", folderId = "f1", updatedAt = 2_000L, isDeleted = true, deletedAt = 5_000L))
        dao.insertNote(note("elsewhere", folderId = "f2", updatedAt = 2_000L))

        dao.clearFolder("f1", 99_000L)

        assertNull(row("live")!!.folderId)
        assertEquals(99_000L, row("live")!!.updatedAt)
        assertNull("a trashed note kept a dangling folderId", row("trashed")!!.folderId)
        assertEquals(99_000L, row("trashed")!!.updatedAt)
        assertEquals("an unrelated folder was unfiled", "f2", row("elsewhere")!!.folderId)
        assertEquals(2_000L, row("elsewhere")!!.updatedAt)
    }

    // ── ordering: every read must be a TOTAL order ────────────────────────────────────────────

    /**
     * Legacy rows carry updatedAt = createdAt = 0 until their first post-migration save, so they
     * tie on both timestamp keys. Without the `id ASC` tiebreak SQLite may return tied rows in any
     * order, and the list would visibly reshuffle between emissions of unchanged data.
     */
    @Test
    fun rowsTiedOnEveryTimestampAreStillOrderedById() = runBlocking {
        listOf("c", "a", "b").forEach { dao.insertNote(note(it, createdAt = 0L, updatedAt = 0L)) }

        assertEquals(listOf("a", "b", "c"), dao.getNotesByUpdatedAt().first().map { it.id })
        assertEquals(listOf("a", "b", "c"), dao.getNotesByCreatedAt().first().map { it.id })
    }

    /** Two untitled notes tie on the title key for the same reason, and are broken the same way. */
    @Test
    fun untitledNotesSinkToTheBottomInTitleOrderAndAreOrderedByIdThere() = runBlocking {
        dao.insertNote(note("z-untitled", title = ""))
        dao.insertNote(note("a-untitled", title = ""))
        dao.insertNote(note("banana", title = "banana"))
        dao.insertNote(note("Apple", title = "Apple"))

        assertEquals(
            // ASCII case-insensitive (NOCASE), then the two blanks last, in id order.
            listOf("Apple", "banana", "a-untitled", "z-untitled"),
            dao.getNotesByTitle().first().map { it.id },
        )
    }

    /** Trash is newest-deleted first, and an unstamped tombstone sorts last rather than first. */
    @Test
    fun trashIsNewestFirstWithUnstampedRowsLast() = runBlocking {
        dao.insertNote(note("old", isDeleted = true, deletedAt = 1_000L))
        dao.insertNote(note("new", isDeleted = true, deletedAt = 9_000L))
        dao.insertNote(note("unstamped", isDeleted = true, deletedAt = null))

        assertEquals(listOf("new", "old", "unstamped"), dao.getDeletedNotes().first().map { it.id })
    }
}
