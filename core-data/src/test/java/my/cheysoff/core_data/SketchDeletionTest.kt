package my.cheysoff.core_data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import my.cheysoff.core_data.data.RoomNotesRepository
import my.cheysoff.core_data.data.RoomSketchesRepository
import my.cheysoff.core_data.data.local.NoteDatabase
import my.cheysoff.core_data.data.sync.RoomSyncStore
import my.cheysoff.core_data.data.sync.SyncClock
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.SketchData
import my.cheysoff.core_domain.model.TrashPolicy
import my.cheysoff.core_domain.sync.FieldClocks
import my.cheysoff.core_domain.sync.FieldValue
import my.cheysoff.core_domain.sync.Hlc
import my.cheysoff.core_domain.sync.RecordType
import my.cheysoff.core_domain.sync.SyncRecord
import my.cheysoff.core_sync_engine.MergedWrite
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Task 7: a note's deletion tombstones its sketches as separate records with their own
 * tombstones — never through `ON DELETE CASCADE` — and the same rule is honoured the other way,
 * when a sketch *arrives* pointing at a note this device already has an opinion about.
 *
 * See `SketchEntity`'s KDoc for why the table has no foreign key at all, and
 * `RoomNotesRepository.deleteNote` / `RoomSyncStore.reconcileAgainstNote` for the two halves of
 * the rule this file exercises: one enacted by the device that deletes, the other by a device
 * that later receives a sketch record.
 *
 * Uses the real repository seams throughout (`RoomNotesRepository`, `RoomSketchesRepository`),
 * not the DAO directly — the same discipline `SketchDaoTest` documents and for the same reason:
 * a test that seeds through the DAO while claiming to exercise "the real path" hides exactly the
 * kind of dead seam that survived four reviews earlier in this project.
 */
@RunWith(RobolectricTestRunner::class)
class SketchDeletionTest {

    private lateinit var database: NoteDatabase
    private lateinit var notesRepository: RoomNotesRepository
    private lateinit var sketchesRepository: RoomSketchesRepository
    private lateinit var syncStore: RoomSyncStore

    private val node = "testnode"

    /** Shared between both repositories, exactly as Hilt shares the singleton in production. */
    private val clock = SyncClock(node = { node })

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, NoteDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        notesRepository = RoomNotesRepository(database.noteDao, database.folderDao, database.sketchDao, database, clock)
        sketchesRepository = RoomSketchesRepository(database.sketchDao, database, clock)
        syncStore = RoomSyncStore(
            database = database,
            noteDao = database.noteDao,
            folderDao = database.folderDao,
            sketchDao = database.sketchDao,
            syncStateDao = database.syncStateDao,
            accountId = "acct-1",
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    // -- fixtures ----------------------------------------------------------------------------

    private fun sketch(
        id: String,
        noteId: String,
        strokes: String = "1|10x10|ff000000,4:0,0",
    ) = SketchData(
        id = id,
        noteId = noteId,
        anchor = 0,
        order = 0,
        strokes = strokes,
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    private fun hlc(ms: Long, counter: Int = 0, n: String = node) = Hlc(ms, counter, n)

    /** A wire-shaped `SyncRecord` for a sketch, the same shape `RoomSyncStoreTest.sketchRecord` builds. */
    private fun sketchRecord(
        id: String,
        noteId: String,
        rowClock: Hlc,
        strokes: String = "1|10x10|ff000000,4:0,0",
        isDeleted: String = "0",
    ) = SyncRecord(
        type = RecordType.SKETCH,
        uuid = id,
        rowClock = rowClock,
        fieldClocks = emptyMap(),
        fields = mapOf(
            FieldClocks.NOTE_ID to FieldValue.of(noteId),
            FieldClocks.ANCHOR to FieldValue.of("0"),
            FieldClocks.ORDER to FieldValue.of("0"),
            FieldClocks.STROKES to FieldValue.of(strokes),
            FieldClocks.UPDATED_AT to FieldValue.of("1000"),
            FieldClocks.DELETED to FieldValue.of(isDeleted, if (isDeleted == "1") "900" else null),
        ),
    )

    // -- 1. deleting a note cascades to its sketches, by reconciliation not cascade ----------

    /**
     * Falsifiability: verified by temporarily deleting the `sketchDao.activeSketchesForNote(id)
     * .forEach { ... }` block from `RoomNotesRepository.deleteNote` and re-running this test — it
     * failed on the very first assertion (`s1After.isDeleted` was false), then the block was
     * restored byte-identically and the suite re-run green. See the task report for the exact
     * failure text.
     */
    @Test
    fun `deleting a note tombstones its sketches in the same transaction`() = runTest {
        notesRepository.saveNote(Note(id = "n1", title = "Title", content = "Body"))
        sketchesRepository.saveSketch(sketch("s1", "n1"))
        sketchesRepository.saveSketch(sketch("s2", "n1"))
        val s1Before = database.sketchDao.sketchRow("s1")!!
        val s2Before = database.sketchDao.sketchRow("s2")!!
        val before = System.currentTimeMillis()

        notesRepository.deleteNote("n1")

        val s1After = database.sketchDao.sketchRow("s1")!!
        val s2After = database.sketchDao.sketchRow("s2")!!

        assertTrue("s1 must be tombstoned", s1After.isDeleted)
        assertTrue("s2 must be tombstoned", s2After.isDeleted)
        assertTrue("s1's tombstone must carry a real stamp", s1After.deletedAt!! >= before)
        assertTrue("s2's tombstone must carry a real stamp", s2After.deletedAt!! >= before)
        assertTrue("s1 must be dirty so its tombstone is pushed", s1After.dirty)
        assertTrue("s2 must be dirty so its tombstone is pushed", s2After.dirty)

        assertTrue("s1 must have moved forward in the account's history", s1After.rowHlc() > s1Before.rowHlc())
        assertTrue("s2 must have moved forward in the account's history", s2After.rowHlc() > s2Before.rowHlc())

        // "Its own clock bump" -- each sketch's tombstone is a distinct write, not one clock
        // shared across the whole cascade the way deleteFolder shares one across many notes.
        assertNotEquals(
            "each sketch's tombstone must be its own clock, not a shared stamp",
            s1After.rowHlc(),
            s2After.rowHlc(),
        )

        assertTrue(
            "a tombstoned sketch must not render",
            sketchesRepository.getSketchesForNote("n1").first().isEmpty(),
        )

        val note = database.noteDao.noteRow("n1")!!
        assertTrue("the note itself is deleted too", note.isDeleted)

        // The fix-round-1 rule: every sketch tombstoned BY THIS DELETION shares the note's own
        // wall-clock deletedAt -- it is one deletion event -- which is exactly what restoreNote
        // uses to tell "tombstoned by this delete" apart from "already deleted beforehand".
        assertEquals(
            "a sketch tombstoned by this note's deletion must carry the note's own deletedAt",
            note.deletedAt,
            s1After.deletedAt,
        )
        assertEquals(
            "a sketch tombstoned by this note's deletion must carry the note's own deletedAt",
            note.deletedAt,
            s2After.deletedAt,
        )
    }

    @Test
    fun `a note with no sketches deletes exactly as before`() = runTest {
        notesRepository.saveNote(Note(id = "n1", title = "Title", content = "Body"))

        notesRepository.deleteNote("n1")

        assertTrue(database.noteDao.noteRow("n1")!!.isDeleted)
    }

    @Test
    fun `deleting a note does not touch another note's sketches`() = runTest {
        notesRepository.saveNote(Note(id = "n1", title = "One", content = "Body"))
        notesRepository.saveNote(Note(id = "n2", title = "Two", content = "Body"))
        sketchesRepository.saveSketch(sketch("s1", "n1"))
        sketchesRepository.saveSketch(sketch("s2", "n2"))

        notesRepository.deleteNote("n1")

        assertTrue(database.sketchDao.sketchRow("s1")!!.isDeleted)
        assertFalse("n2's sketch must be untouched", database.sketchDao.sketchRow("s2")!!.isDeleted)
    }

    // -- 1b. restoreNote un-tombstones exactly the sketches ITS OWN deletion tombstoned ------

    /**
     * Falsifiability: verified by running this against the pre-fix-round `restoreNote` (which
     * calls only `noteDao.restoreNote`) — failed with both sketches still `isDeleted`. See the
     * report for the exact assertion.
     */
    @Test
    fun `restoring a note un-tombstones the sketches its own deletion tombstoned`() = runTest {
        notesRepository.saveNote(Note(id = "n1", title = "Title", content = "Body"))
        sketchesRepository.saveSketch(sketch("s1", "n1"))
        sketchesRepository.saveSketch(sketch("s2", "n1"))
        notesRepository.deleteNote("n1")

        notesRepository.restoreNote("n1")

        assertFalse("s1 must come back live", database.sketchDao.sketchRow("s1")!!.isDeleted)
        assertFalse("s2 must come back live", database.sketchDao.sketchRow("s2")!!.isDeleted)
        assertNull(database.sketchDao.sketchRow("s1")!!.deletedAt)
        assertNull(database.sketchDao.sketchRow("s2")!!.deletedAt)
        assertTrue(
            "both drawings render again",
            sketchesRepository.getSketchesForNote("n1").first().map { it.id }.containsAll(listOf("s1", "s2")),
        )
    }

    /**
     * The test that matters: nothing distinguishes "tombstoned by this note's deletion" from
     * "the user deleted this sketch individually, earlier" except the exact `deletedAt` match the
     * fix relies on. Getting this wrong resurrects a drawing the user deliberately deleted.
     *
     * Falsifiability: verified against an unconditional "un-tombstone every sketch under this
     * note" implementation — failed with the individually-deleted sketch coming back live. See
     * the report.
     */
    @Test
    fun `restoring a note does not resurrect a sketch that was deleted individually beforehand`() = runTest {
        notesRepository.saveNote(Note(id = "n1", title = "Title", content = "Body"))
        sketchesRepository.saveSketch(sketch("individually-deleted", "n1"))
        sketchesRepository.saveSketch(sketch("cascaded", "n1"))

        // Seeded directly at a fixed, deliberately old instant -- rather than through
        // `sketchesRepository.deleteSketch`, which stamps the real wall clock and could otherwise
        // land in the same millisecond as `deleteNote` below, making the two tombstones coincide
        // by sheer timing luck and this test's own precondition assertion flaky.
        val individualDeletedAt = 500L
        database.sketchDao.softDeleteSketch(
            uuid = "individually-deleted",
            timestamp = individualDeletedAt,
            hlcMs = individualDeletedAt,
            hlcCounter = 0,
            hlcNode = node,
            fieldHlc = "",
        )

        notesRepository.deleteNote("n1")
        val cascadedDeletedAt = database.sketchDao.sketchRow("cascaded")!!.deletedAt
        // Precondition: the two tombstones must NOT coincide, or this test would prove nothing.
        assertNotEquals(individualDeletedAt, cascadedDeletedAt)

        notesRepository.restoreNote("n1")

        assertTrue(
            "the individually-deleted sketch must stay deleted",
            database.sketchDao.sketchRow("individually-deleted")!!.isDeleted,
        )
        assertEquals(
            "its tombstone's own timestamp must be untouched by the note's restore",
            individualDeletedAt,
            database.sketchDao.sketchRow("individually-deleted")!!.deletedAt,
        )
        assertFalse(
            "the sketch tombstoned by the note's own deletion must come back",
            database.sketchDao.sketchRow("cascaded")!!.isDeleted,
        )
        assertEquals(listOf("cascaded"), sketchesRepository.getSketchesForNote("n1").first().map { it.id })
    }

    /**
     * The concurrent-merge shape F3 exists for: the note's tombstone and each sketch's tombstone
     * are independently clocked records, so a note deleted concurrently on two devices can merge
     * to a note `deletedAt` that differs from a sketch tombstoned by the very same event on the
     * *other* device. An exact match then leaves the sketch stranded in Trash with no per-sketch
     * restore UI to recover it. This seeds that shape directly — the note and the sketch tombstoned
     * at two different instants, exactly as two independently-merged records would land — rather
     * than through `deleteNote`, which always stamps both at one shared instant and so can never
     * produce it on a single device.
     */
    @Test
    fun `restoring a note un-tombstones a sketch whose merged deletedAt is later than the note's`() = runTest {
        notesRepository.saveNote(Note(id = "n1", title = "Title", content = "Body"))
        sketchesRepository.saveSketch(sketch("s1", "n1"))

        val noteDeletedAt = 1_000L
        val sketchDeletedAt = 1_500L // later: as if the sketch's own DELETED field won the merge
        database.noteDao.softDeleteNote(
            id = "n1",
            timestamp = noteDeletedAt,
            hlcMs = noteDeletedAt,
            hlcCounter = 0,
            hlcNode = node,
            fieldHlc = "",
        )
        database.sketchDao.softDeleteSketch(
            uuid = "s1",
            timestamp = sketchDeletedAt,
            hlcMs = sketchDeletedAt,
            hlcCounter = 0,
            hlcNode = node,
            fieldHlc = "",
        )

        notesRepository.restoreNote("n1")

        assertFalse(
            "a sketch tombstoned at or after the note's own deletedAt must come back with it",
            database.sketchDao.sketchRow("s1")!!.isDeleted,
        )
        assertNull(database.sketchDao.sketchRow("s1")!!.deletedAt)
    }

    /**
     * Falsifiability: verified by making the restore skip the per-sketch clock bump (writing
     * `isDeleted = 0` without touching `hlcMs`/`hlcCounter`/`hlcNode`/`fieldHlc`/`dirty`) — failed
     * because the sketch's row clock had not moved and `dirty` was still whatever `deleteNote`
     * left it at (already true from the tombstone, which is why this test checks the clock
     * strictly advanced, not just that `dirty` reads true). See the report.
     */
    @Test
    fun `restored sketches are dirty with a clock strictly newer than their tombstone`() = runTest {
        notesRepository.saveNote(Note(id = "n1", title = "Title", content = "Body"))
        sketchesRepository.saveSketch(sketch("s1", "n1"))
        notesRepository.deleteNote("n1")
        val tombstoned = database.sketchDao.sketchRow("s1")!!

        notesRepository.restoreNote("n1")

        val restored = database.sketchDao.sketchRow("s1")!!
        assertTrue("the restore must be pushed so the other device un-deletes it too", restored.dirty)
        assertTrue(
            "the restore must be its own clock bump, not a re-write at the tombstone's clock",
            restored.rowHlc() > tombstoned.rowHlc(),
        )
    }

    // -- 2. a sketch whose note is already deleted is treated as deleted on arrival ----------

    /**
     * Falsifiability: verified by temporarily making `reconcileAgainstNote` return [entity]
     * unchanged (the naive "just write what arrived" implementation) — this test failed with
     * `stored.isDeleted` false. Restored, and the suite re-run green.
     */
    @Test
    fun `a sketch whose note is already deleted is treated as deleted on arrival`() = runTest {
        notesRepository.saveNote(Note(id = "n1", title = "Title", content = "Body"))
        notesRepository.deleteNote("n1")

        // A remote push -- e.g. from a build with no sketch support, which never cascaded a
        // tombstone of its own, or a device flushing an old dirty sketch after the fact -- delivers
        // a LIVE sketch for the now-dead note.
        syncStore.applyMerged(
            MergedWrite(
                record = sketchRecord(id = "orphan", noteId = "n1", rowClock = hlc(5_000), strokes = "still-live"),
                dirty = false,
                seq = 10L,
                contentBaseline = null,
                conflictCopy = null,
            )
        )

        val stored = database.sketchDao.sketchRow("orphan")!!
        assertTrue("a sketch whose note is dead must not be stored live", stored.isDeleted)
        assertNotNull("it needs a tombstone stamp to ever be purgeable", stored.deletedAt)
        assertTrue("the correction must be pushed back so other devices converge", stored.dirty)
        assertTrue(
            "must not render",
            database.sketchDao.getSketchesByNoteId("n1").first().isEmpty(),
        )
    }

    /** A sketch that already arrives tombstoned needs no correction and is simply trusted. */
    @Test
    fun `a sketch that arrives already tombstoned is stored as-is`() = runTest {
        notesRepository.saveNote(Note(id = "n1", title = "Title", content = "Body"))
        notesRepository.deleteNote("n1")

        syncStore.applyMerged(
            MergedWrite(
                record = sketchRecord(id = "s1", noteId = "n1", rowClock = hlc(5_000), isDeleted = "1"),
                dirty = false,
                seq = 10L,
                contentBaseline = null,
                conflictCopy = null,
            )
        )

        val stored = database.sketchDao.sketchRow("s1")!!
        assertTrue(stored.isDeleted)
        assertFalse("nothing needed correcting, so nothing needed re-pushing", stored.dirty)
    }

    /** The mirror-image control: a live note must leave an arriving live sketch alone. */
    @Test
    fun `a sketch whose note is alive is stored live, unchanged`() = runTest {
        notesRepository.saveNote(Note(id = "n1", title = "Title", content = "Body"))

        syncStore.applyMerged(
            MergedWrite(
                record = sketchRecord(id = "s1", noteId = "n1", rowClock = hlc(5_000)),
                dirty = false,
                seq = 10L,
                contentBaseline = null,
                conflictCopy = null,
            )
        )

        val stored = database.sketchDao.sketchRow("s1")!!
        assertFalse(stored.isDeleted)
        assertFalse("the remote's own version is not dirty", stored.dirty)
    }

    // -- 3. a sketch whose note this device does not have yet is kept, not discarded --------

    /**
     * The case the brief calls out as most likely to be got wrong: "unknown note" and "deleted
     * note" look similar (in both cases `noteId` names something not currently live) but must not
     * be treated the same. Records arrive in `seq` order, not dependency order -- the note may
     * land later in the same pull, or a later one -- so an unknown note is not evidence the
     * sketch should die.
     *
     * Falsifiability: verified by changing the note lookup's null case from `return entity`
     * (keep) to the tombstoning branch (treat unknown as deleted) -- this test failed with
     * `stored.isDeleted` true and `stored.strokes` unreadable via the by-note query. Restored,
     * and the suite re-run green.
     */
    @Test
    fun `a sketch whose note this device does not have yet is kept, not discarded`() = runTest {
        // Deliberately no note "n1" seeded anywhere in this device's database.
        syncStore.applyMerged(
            MergedWrite(
                record = sketchRecord(id = "s1", noteId = "n1", rowClock = hlc(5_000), strokes = "drawing-data"),
                dirty = false,
                seq = 3L,
                contentBaseline = null,
                conflictCopy = null,
            )
        )

        val stored = database.sketchDao.sketchRow("s1")
        assertNotNull("the sketch must not be discarded", stored)
        assertFalse("an unknown note must not be treated as a deleted one", stored!!.isDeleted)
        assertNull("no tombstone stamp was manufactured for it", stored.deletedAt)
        assertEquals("drawing-data", stored.strokes)
    }

    /**
     * Once the note DOES land -- possibly in a later pull, exactly as the brief describes -- the
     * earlier "kept" sketch is unaffected by that arrival: this test pins the current, narrower
     * scope of Task 7 (reconciliation triggers on the SKETCH record's own arrival, not on the
     * NOTE's), so a future change to broaden it is a deliberate decision, not an accidental one.
     */
    @Test
    fun `a previously-kept sketch is unaffected when its note arrives afterwards, alive`() = runTest {
        syncStore.applyMerged(
            MergedWrite(
                record = sketchRecord(id = "s1", noteId = "n1", rowClock = hlc(3_000)),
                dirty = false,
                seq = 3L,
                contentBaseline = null,
                conflictCopy = null,
            )
        )

        notesRepository.saveNote(Note(id = "n1", title = "Title", content = "Body"))

        assertFalse(database.sketchDao.sketchRow("s1")!!.isDeleted)
    }

    // -- deleteSketch: the repository seam Task 5 deferred here ------------------------------

    /**
     * Falsifiability: verified by temporarily making `deleteSketch` a no-op stub — this test
     * failed with `stored.isDeleted` false. Restored, and the suite re-run green.
     */
    @Test
    fun `deleteSketch soft-deletes and stamps its own timestamps and clock`() = runTest {
        sketchesRepository.saveSketch(sketch("s1", "n1"))
        val before = System.currentTimeMillis()

        sketchesRepository.deleteSketch("s1")

        val stored = database.sketchDao.sketchRow("s1")!!
        assertTrue(stored.isDeleted)
        assertTrue("deletedAt must be stamped by the repository, not left to the caller", stored.deletedAt!! >= before)
        assertTrue("a delete must mark the row dirty so it is pushed", stored.dirty)
        assertTrue(
            "a deleted sketch must not render",
            database.sketchDao.getSketchesByNoteId("n1").first().isEmpty(),
        )
    }

    @Test
    fun `deleteSketch is idempotent - a second delete does not re-stamp deletedAt`() = runTest {
        sketchesRepository.saveSketch(sketch("s1", "n1"))
        sketchesRepository.deleteSketch("s1")
        val firstDeletedAt = database.sketchDao.sketchRow("s1")!!.deletedAt

        sketchesRepository.deleteSketch("s1")

        assertEquals(
            "a second delete of an already-trashed sketch must not restart its retention window",
            firstDeletedAt,
            database.sketchDao.sketchRow("s1")!!.deletedAt,
        )
    }

    // -- purging expired trash must take sketches with it ------------------------------------

    /**
     * `RoomNotesRepository.purgeExpiredTrash` cannot control the wall-clock instant `deleteNote`
     * stamps, so the sketch's tombstone is stamped directly via the DAO at an exact, already-
     * expired instant -- exactly the trick `RoomNotesRepositoryTest`'s own purge tests use for
     * notes and folders (`stampFolderDeletedAt`).
     */
    @Test
    fun `purgeExpiredTrash purges a tombstoned sketch past retention, alongside its note`() = runTest {
        val now = 100L * TrashPolicy.RETENTION_MILLIS
        val expired = now - TrashPolicy.RETENTION_MILLIS - 1

        notesRepository.saveNote(Note(id = "n1", title = "Title", content = "Body"))
        sketchesRepository.saveSketch(sketch("s1", "n1"))
        database.sketchDao.softDeleteSketch(
            uuid = "s1",
            timestamp = expired,
            hlcMs = expired,
            hlcCounter = 0,
            hlcNode = node,
            fieldHlc = "",
        )

        val purged = notesRepository.purgeExpiredTrash(now)

        assertEquals("exactly the one expired sketch tombstone", 1, purged)
        assertNull("the sketch row must be gone", database.sketchDao.sketchRow("s1"))
    }

    @Test
    fun `purgeExpiredTrash keeps a sketch tombstone still inside the retention window`() = runTest {
        val now = 100L * TrashPolicy.RETENTION_MILLIS
        val fresh = now - TrashPolicy.RETENTION_MILLIS + 1

        sketchesRepository.saveSketch(sketch("s1", "n1"))
        database.sketchDao.softDeleteSketch(
            uuid = "s1",
            timestamp = fresh,
            hlcMs = fresh,
            hlcCounter = 0,
            hlcNode = node,
            fieldHlc = "",
        )

        val purged = notesRepository.purgeExpiredTrash(now)

        assertEquals(0, purged)
        assertNotNull("a fresh tombstone must survive the purge", database.sketchDao.sketchRow("s1"))
    }
}
