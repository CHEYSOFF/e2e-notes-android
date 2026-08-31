package my.cheysoff.core_data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import my.cheysoff.core_data.data.local.NoteDatabase
import my.cheysoff.core_data.data.local.NoteEntity
import my.cheysoff.core_domain.sync.FieldClocks
import my.cheysoff.core_domain.sync.Hlc
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The `notes` write paths, exercised as SQL against a real (in-memory, unencrypted) v7 database.
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
        hlc: Hlc = seedHlc,
        fieldHlc: String = "",
        dirty: Boolean = false,
        lastSyncedSeq: Long = 42L,
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
        hlcMs = hlc.ms,
        hlcCounter = hlc.counter,
        hlcNode = hlc.node,
        fieldHlc = fieldHlc,
        dirty = dirty,
        lastSyncedSeq = lastSyncedSeq,
    )

    /**
     * The clock a seeded row starts at: older than [writeHlc], and CLEAN (`dirty = false`,
     * `lastSyncedSeq = 42`), so that "this statement stamped the row and dirtied it" is something
     * a test can actually fail.
     */
    private val seedHlc = Hlc(ms = 1_000L, counter = 0, node = "seed")

    /** The row as it is on disk, tombstone included — i.e. NOT through the filtered reads. */
    private suspend fun row(id: String): NoteEntity? =
        dao.getNotesByUpdatedAt().first().find { it.id == id }
            ?: dao.getDeletedNotes().first().find { it.id == id }

    /**
     * The clock a write in this file stamps, unless it says otherwise.
     *
     * A fixed value rather than a generated one: these tests are about what the SQL writes, not
     * about where the number came from, and a constant makes "did this statement stamp the row"
     * an equality assertion instead of an inequality.
     */
    private val writeHlc = Hlc(ms = 50_000L, counter = 0, node = "testnode")

    /** An upsert with the shape the editor's autosave uses: content fields and isPinned only. */
    private suspend fun upsert(
        id: String,
        title: String = "edited",
        content: String = "edited body",
        isPinned: Boolean = false,
        folderId: String? = null,
        timestamp: Long = 9_000L,
        hlc: Hlc = writeHlc,
        fieldHlc: String = "",
    ) = dao.upsertNote(
        id = id,
        title = title,
        content = content,
        contentFormat = "html",
        checklist = "",
        isPinned = isPinned,
        folderId = folderId,
        timestamp = timestamp,
        hlcMs = hlc.ms,
        hlcCounter = hlc.counter,
        hlcNode = hlc.node,
        fieldHlc = fieldHlc,
    )

    // The sync columns are noise in every test that predates v7, so each of these wrappers passes
    // the same fixed clock and lets the test read as it did before.

    private suspend fun softDelete(id: String, timestamp: Long, hlc: Hlc = writeHlc) =
        dao.softDeleteNote(id, timestamp, hlc.ms, hlc.counter, hlc.node, "")

    private suspend fun restore(id: String, hlc: Hlc = writeHlc) =
        dao.restoreNote(id, hlc.ms, hlc.counter, hlc.node, "")

    private suspend fun setPinned(id: String, isPinned: Boolean, hlc: Hlc = writeHlc) =
        dao.setNotePinned(id, isPinned, hlc.ms, hlc.counter, hlc.node, "")

    private suspend fun setFavorite(id: String, isFavorite: Boolean, hlc: Hlc = writeHlc) =
        dao.setNoteFavorite(id, isFavorite, hlc.ms, hlc.counter, hlc.node, "")

    private suspend fun setFolder(id: String, folderId: String?, hlc: Hlc = writeHlc) =
        dao.setNoteFolder(id, folderId, hlc.ms, hlc.counter, hlc.node, "")

    /**
     * Unfiles a whole folder the way `RoomNotesRepository.deleteFolder` does: one read of every
     * affected row, then one update each, all sharing a single clock.
     */
    private suspend fun clearFolder(
        folderId: String,
        timestamp: Long,
        hlc: Hlc = writeHlc,
    ) = dao.rowClocksInFolder(folderId).forEach {
        dao.clearFolderForNote(it.id, timestamp, hlc.ms, hlc.counter, hlc.node, "")
    }

    // ── upsertNote: the fields the save path does NOT own ──────────────────────────────────────

    /**
     * The editor's save builds its [my.cheysoff.core_domain.model.Note] without isFavorite (see
     * SingleNoteViewModel.saveNote), so if the conflict branch wrote `isFavorite = excluded.
     * isFavorite` every autosave would silently un-favorite the note being typed into.
     */
    @Test
    fun anUpsertLeavesIsFavoriteAlone() = runBlocking {
        dao.applyRemoteNote(note("n1", isFavorite = true))

        upsert("n1")

        assertTrue("autosave cleared isFavorite", row("n1")!!.isFavorite)
    }

    /**
     * A save racing a delete must not resurrect the note. The tombstone columns are absent from
     * the conflict branch for exactly this reason; only restoreNote clears them.
     */
    @Test
    fun anUpsertCannotPullANoteBackOutOfTrash() = runBlocking {
        dao.applyRemoteNote(note("n1", isDeleted = true, deletedAt = 5_000L))

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
        dao.applyRemoteNote(note("n1", createdAt = 1_000L, updatedAt = 2_000L))

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
        dao.applyRemoteNote(note("legacy", createdAt = 0L, updatedAt = 0L))

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
        dao.applyRemoteNote(note("n1"))

        softDelete("n1", 5_000L)
        softDelete("n1", 90_000L)

        assertEquals(5_000L, dao.getDeletedNotes().first().single().deletedAt)
    }

    /** Restore clears the stamp, so the NEXT delete starts a fresh window rather than an aged one. */
    @Test
    fun restoreClearsTheStampSoTheNextDeleteStartsOver() = runBlocking {
        dao.applyRemoteNote(note("n1"))
        softDelete("n1", 5_000L)

        restore("n1")
        val restored = row("n1")!!
        assertFalse(restored.isDeleted)
        assertNull("a restored note kept its deletedAt", restored.deletedAt)

        softDelete("n1", 90_000L)
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
        dao.applyRemoteNote(note("alive"))                                            // isDeleted = 0
        dao.applyRemoteNote(note("expired", isDeleted = true, deletedAt = threshold))  // == threshold
        dao.applyRemoteNote(note("older", isDeleted = true, deletedAt = 1L))
        dao.applyRemoteNote(note("fresh", isDeleted = true, deletedAt = threshold + 1))
        dao.applyRemoteNote(note("unstamped", isDeleted = true, deletedAt = null))
        dao.applyRemoteNote(note("zero", isDeleted = true, deletedAt = 0L))

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
        dao.applyRemoteNote(note("alive"))
        dao.applyRemoteNote(note("fresh", isDeleted = true, deletedAt = 20_000L))

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
        dao.applyRemoteNote(note("n1", updatedAt = 2_000L))

        setPinned("n1", true)
        setFavorite("n1", true)
        setFolder("n1", "f1")

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
        dao.applyRemoteNote(note("live", folderId = "f1", updatedAt = 2_000L))
        dao.applyRemoteNote(note("trashed", folderId = "f1", updatedAt = 2_000L, isDeleted = true, deletedAt = 5_000L))
        dao.applyRemoteNote(note("elsewhere", folderId = "f2", updatedAt = 2_000L))

        clearFolder("f1", 99_000L)

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
        listOf("c", "a", "b").forEach { dao.applyRemoteNote(note(it, createdAt = 0L, updatedAt = 0L)) }

        assertEquals(listOf("a", "b", "c"), dao.getNotesByUpdatedAt().first().map { it.id })
        assertEquals(listOf("a", "b", "c"), dao.getNotesByCreatedAt().first().map { it.id })
    }

    /** Two untitled notes tie on the title key for the same reason, and are broken the same way. */
    @Test
    fun untitledNotesSinkToTheBottomInTitleOrderAndAreOrderedByIdThere() = runBlocking {
        dao.applyRemoteNote(note("z-untitled", title = ""))
        dao.applyRemoteNote(note("a-untitled", title = ""))
        dao.applyRemoteNote(note("banana", title = "banana"))
        dao.applyRemoteNote(note("Apple", title = "Apple"))

        assertEquals(
            // ASCII case-insensitive (NOCASE), then the two blanks last, in id order.
            listOf("Apple", "banana", "a-untitled", "z-untitled"),
            dao.getNotesByTitle().first().map { it.id },
        )
    }

    /** Trash is newest-deleted first, and an unstamped tombstone sorts last rather than first. */
    @Test
    fun trashIsNewestFirstWithUnstampedRowsLast() = runBlocking {
        dao.applyRemoteNote(note("old", isDeleted = true, deletedAt = 1_000L))
        dao.applyRemoteNote(note("new", isDeleted = true, deletedAt = 9_000L))
        dao.applyRemoteNote(note("unstamped", isDeleted = true, deletedAt = null))

        assertEquals(listOf("new", "old", "unstamped"), dao.getDeletedNotes().first().map { it.id })
    }

    // ── applyRemoteNote: the sync write path, and why it is not upsertNote ─────────────────────

    /**
     * The reason there are two write paths at all.
     *
     * `upsertNote`'s conflict branch refuses to write `isFavorite`, `isDeleted` and `deletedAt`,
     * which is exactly right for an autosave and fatal for sync — those three are precisely what a
     * remote favourite or a remote delete consists of. Routed through the editor's statement, a
     * delete made on the phone would be silently dropped on the tablet and the note would come
     * back from the dead on every device, forever.
     */
    @Test
    fun applyRemoteNoteWritesTheFieldsUpsertRefusesTo() = runBlocking {
        dao.applyRemoteNote(note("n1", isFavorite = true, isDeleted = false, deletedAt = null))

        dao.applyRemoteNote(
            note("n1", isFavorite = false, isDeleted = true, deletedAt = 7_000L, title = "remote"),
        )

        val stored = row("n1")!!
        assertFalse("a remote un-favorite was dropped", stored.isFavorite)
        assertTrue("a remote delete was dropped", stored.isDeleted)
        assertEquals(7_000L, stored.deletedAt)
        assertEquals("remote", stored.title)
    }

    /** The same three writes through the editor's statement, to show it genuinely cannot do it. */
    @Test
    fun upsertNoteCannotWriteThoseFields() = runBlocking {
        dao.applyRemoteNote(note("n1", isFavorite = true))

        upsert("n1", title = "local")

        val stored = row("n1")!!
        assertTrue("upsertNote wrote isFavorite; applyRemoteNote would then be redundant", stored.isFavorite)
        assertFalse(stored.isDeleted)
    }

    /**
     * `@Upsert`, not `@Insert(REPLACE)`. REPLACE is a DELETE followed by an INSERT, so it would
     * have destroyed `createdAt` and every sync column of the row it was "updating" — which is
     * precisely why the dead `insertNote` was deleted in v7 rather than reused for this.
     */
    @Test
    fun applyRemoteNoteUpdatesInPlaceRatherThanReplacingTheRow() = runBlocking {
        dao.applyRemoteNote(note("n1", createdAt = 1_234L, lastSyncedSeq = 99L))

        dao.applyRemoteNote(
            note("n1", title = "remote", createdAt = 1_234L, lastSyncedSeq = 100L, dirty = false),
        )

        val stored = row("n1")!!
        assertEquals(1_234L, stored.createdAt)
        assertEquals(100L, stored.lastSyncedSeq)
        assertFalse(stored.dirty)
    }

    /**
     * A merge that produced something the server has not seen says so, and the row is pushed on
     * the next pass; a remote record that won outright is clean and carries the seq it arrived at.
     * Both are the caller's decision, which is why they are ordinary columns here.
     */
    @Test
    fun applyRemoteNoteLetsTheCallerDecideWhetherTheResultIsDirty() = runBlocking {
        dao.applyRemoteNote(note("clean", dirty = false, lastSyncedSeq = 5L))
        dao.applyRemoteNote(note("merged", dirty = true, lastSyncedSeq = 5L))

        assertFalse(row("clean")!!.dirty)
        assertTrue(row("merged")!!.dirty)
    }

    // ── every write bumps the clock and dirties the row ────────────────────────────────────────

    /**
     * The rule, path by path. A write that changes a row without stamping it is a change no other
     * device ever hears about: the pull sees a record no newer than its own copy and the local
     * edit is overwritten the next time anything else touches the note.
     */
    @Test
    fun everyWritePathStampsTheClockAndMarksTheRowDirty() = runBlocking {
        suspend fun freshRow(id: String) = dao.applyRemoteNote(note(id))

        freshRow("upsert")
        upsert("upsert")
        freshRow("delete")
        softDelete("delete", 5_000L)
        freshRow("restore")
        restore("restore")
        freshRow("pin")
        setPinned("pin", true)
        freshRow("fav")
        setFavorite("fav", true)
        freshRow("folder")
        setFolder("folder", "f1")
        dao.applyRemoteNote(note("cleared", folderId = "f9"))
        clearFolder("f9", 9_000L)

        for (id in listOf("upsert", "delete", "restore", "pin", "fav", "folder", "cleared")) {
            val stored = row(id)!!
            assertEquals("'$id' was written without a clock", writeHlc, stored.rowHlc())
            assertTrue("'$id' was written but left clean, so it would never be pushed", stored.dirty)
        }
    }

    /**
     * …and the three metadata gestures do it without touching `updatedAt`, which is the whole of
     * PR #32 and the reason the two are separate columns.
     */
    @Test
    fun theThreeMetadataUpdatesStampTheClockWithoutBumpingUpdatedAt() = runBlocking {
        dao.applyRemoteNote(note("n1", updatedAt = 2_000L))

        setPinned("n1", true)

        val stored = row("n1")!!
        assertEquals("a metadata write reordered the list", 2_000L, stored.updatedAt)
        assertEquals("a metadata write left no clock", writeHlc, stored.rowHlc())
        assertTrue(stored.dirty)
    }

    /**
     * An UPDATE that matches no row must not mint a clock either. `softDeleteNote`'s
     * `AND isDeleted = 0` guard covers the whole SET clause, which is the behaviour we want: a
     * second tap on Delete changed nothing, so there is nothing for the other device to hear about.
     */
    @Test
    fun aWriteThatMatchesNoRowDoesNotStampAClock() = runBlocking {
        dao.applyRemoteNote(note("n1"))
        val first = Hlc(ms = 10_000L, counter = 0, node = "first")
        softDelete("n1", 5_000L, first)

        softDelete("n1", 90_000L, Hlc(ms = 60_000L, counter = 0, node = "second"))

        assertEquals("a no-op delete re-stamped the row", first, row("n1")!!.rowHlc())
    }

    @Test
    fun anUpsertOfAnUnknownIdStartsItDirtyAndUnpushed() = runBlocking {
        upsert("new")

        val stored = row("new")!!
        assertTrue("a note the server has never seen must be dirty", stored.dirty)
        assertEquals("and must claim no server version", 0L, stored.lastSyncedSeq)
        assertEquals(writeHlc, stored.rowHlc())
    }

    /**
     * An edit to an already-pushed note keeps `lastSyncedSeq`.
     *
     * It is the baseline the next push is built on: reset to 0 it means "the server must not have
     * this record", so every save of a synced note would take a guaranteed conflict.
     */
    @Test
    fun anUpsertKeepsTheExistingLastSyncedSeq() = runBlocking {
        dao.applyRemoteNote(note("n1", lastSyncedSeq = 77L))

        upsert("n1")

        assertEquals(77L, row("n1")!!.lastSyncedSeq)
    }

    /** The field clocks are written verbatim; computing them is the repository's job, not SQL's. */
    @Test
    fun anUpsertStoresTheFieldClocksItIsGiven() = runBlocking {
        val clocks = FieldClocks.serialize(mapOf(FieldClocks.FAVORITE to seedHlc))
        dao.applyRemoteNote(note("n1"))

        upsert("n1", fieldHlc = clocks)

        assertEquals(clocks, row("n1")!!.fieldHlc)
    }

    // ── the clock reads the repository seeds itself from ──────────────────────────────────────

    @Test
    fun highestRowClockIsTheMaximumAcrossEveryRowIncludingTombstones() = runBlocking {
        dao.applyRemoteNote(note("a", hlc = Hlc(100L, 0, "n")))
        dao.applyRemoteNote(note("b", hlc = Hlc(300L, 2, "n"), isDeleted = true, deletedAt = 1L))
        dao.applyRemoteNote(note("c", hlc = Hlc(300L, 1, "n")))

        // A delete is a write like any other and its clock still has to be beaten, so excluding
        // trashed rows here would let the next local write reuse a clock the server already has.
        assertEquals(Hlc(300L, 2, "n"), dao.highestRowClock()!!.rowHlc())
    }

    @Test
    fun highestRowClockIsNullOnAnEmptyTable() = runBlocking {
        assertNull(dao.highestRowClock())
    }

    @Test
    fun rowClockIsNullForARowThatDoesNotExist() = runBlocking {
        assertNull(dao.rowClock("nope"))
    }

    @Test
    fun rowClocksInFolderReachesTrashedNotesAndNothingOutsideTheFolder() = runBlocking {
        dao.applyRemoteNote(note("live", folderId = "f1"))
        dao.applyRemoteNote(note("trashed", folderId = "f1", isDeleted = true, deletedAt = 5_000L))
        dao.applyRemoteNote(note("elsewhere", folderId = "f2"))
        dao.applyRemoteNote(note("unfiled", folderId = null))

        assertEquals(
            listOf("live", "trashed"),
            dao.rowClocksInFolder("f1").map { it.id }.sorted(),
        )
    }
}
