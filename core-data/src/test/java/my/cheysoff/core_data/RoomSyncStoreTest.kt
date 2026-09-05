package my.cheysoff.core_data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import my.cheysoff.core_data.data.local.FolderEntity
import my.cheysoff.core_data.data.local.NoteDatabase
import my.cheysoff.core_data.data.local.NoteEntity
import my.cheysoff.core_data.data.local.SketchEntity
import my.cheysoff.core_data.data.sync.RoomSyncStore
import my.cheysoff.core_domain.sync.FieldClocks
import my.cheysoff.core_domain.sync.FieldValue
import my.cheysoff.core_domain.sync.Hlc
import my.cheysoff.core_domain.sync.RecordType
import my.cheysoff.core_domain.sync.SyncRecord
import my.cheysoff.core_sync_engine.ClockObserver
import my.cheysoff.core_sync_engine.HaltReason
import my.cheysoff.core_sync_engine.MergedWrite
import my.cheysoff.core_sync_engine.SyncEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [RoomSyncStore] against a real Room database.
 *
 * Robolectric ships its own native SQLite, so every statement here is executed rather than
 * inspected — real `ON CONFLICT`, real `CASE`, real transactions. The only thing missing versus an
 * instrumented run is SQLCipher, which is an open-helper swap and changes nothing about what these
 * statements do; `SyncStoreConvergenceTest` in `androidTest` runs the same store over the real
 * encrypted database for the part that genuinely differs.
 *
 * ## What this file is really about
 *
 * Two rules of `docs/design/e2e-sync-phase3-plan.md` §3.2, which the sync engine cannot enforce
 * from outside and which are the reason this class exists rather than a DAO:
 *
 *  1. `dirty` is cleared **only** if the row has not moved since the pushed version was sealed;
 *  2. `lastSyncedSeq` is written **either way**.
 *
 * and the thing neither of them is on its own: that the two are **one statement**. Two statements
 * are correct only while nothing runs between them, which is exactly what no caller can promise.
 * `theAcknowledgementIsOneStatement…` is the check, and it is a real check rather than a reading of
 * the SQL: it counts row-update events with a trigger.
 */
@RunWith(RobolectricTestRunner::class)
class RoomSyncStoreTest {

    private lateinit var database: NoteDatabase
    private lateinit var store: RoomSyncStore

    private val account = "acct-1"
    private val node = "testnode"

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, NoteDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomSyncStore(
            database = database,
            noteDao = database.noteDao,
            folderDao = database.folderDao,
            sketchDao = database.sketchDao,
            attachmentDao = database.attachmentDao,
            syncStateDao = database.syncStateDao,
            accountId = account,
            clockObserver = ClockObserver {},
            wallClock = { 1_000L },
        )
    }

    @After
    fun tearDown() = database.close()

    // -- fixtures --------------------------------------------------------------------------------

    private fun clock(ms: Long, counter: Int = 0, n: String = node) = Hlc(ms, counter, n)

    /** A row exactly as it is after the editor has saved it: dirty, never pushed. */
    private fun localNote(
        id: String = "n1",
        title: String = "Groceries",
        content: String = "milk",
        rowClock: Hlc = clock(1_000),
        dirty: Boolean = true,
        lastSyncedSeq: Long = 0L,
        isFavorite: Boolean = false,
        isDeleted: Boolean = false,
        createdAt: Long = 50L,
        updatedAt: Long = 100L,
        contentSyncedHlc: String = "",
    ) = NoteEntity(
        id = id,
        title = title,
        content = content,
        contentFormat = "plain",
        checklist = "",
        isPinned = false,
        isFavorite = isFavorite,
        folderId = null,
        createdAt = createdAt,
        updatedAt = updatedAt,
        isDeleted = isDeleted,
        deletedAt = if (isDeleted) 900L else null,
        hlcMs = rowClock.ms,
        hlcCounter = rowClock.counter,
        hlcNode = rowClock.node,
        fieldHlc = "",
        dirty = dirty,
        lastSyncedSeq = lastSyncedSeq,
        contentSyncedHlc = contentSyncedHlc,
    )

    private fun noteRecord(
        id: String = "n1",
        title: String = "Groceries",
        content: String = "milk",
        rowClock: Hlc = clock(1_000),
        isFavorite: String = "0",
        isDeleted: String = "0",
        updatedAt: String = "100",
    ) = SyncRecord(
        type = RecordType.NOTE,
        uuid = id,
        rowClock = rowClock,
        fieldClocks = emptyMap(),
        fields = mapOf(
            FieldClocks.TITLE to FieldValue.of(title),
            FieldClocks.CONTENT to FieldValue.of(content, "plain"),
            FieldClocks.CHECKLIST to FieldValue.of(""),
            FieldClocks.PINNED to FieldValue.of("0"),
            FieldClocks.FAVORITE to FieldValue.of(isFavorite),
            FieldClocks.FOLDER to FieldValue.of(null),
            FieldClocks.UPDATED_AT to FieldValue.of(updatedAt),
            FieldClocks.DELETED to FieldValue.of(isDeleted, if (isDeleted == "1") "900" else null),
        ),
    )

    /** A sketch row exactly as it is after a local save: dirty, never pushed. */
    private fun localSketch(
        id: String = "s1",
        noteId: String = "n1",
        anchor: Int = 0,
        order: Int = 0,
        strokes: String = "1|10x10|ff000000,4:0,0",
        rowClock: Hlc = clock(1_000),
        dirty: Boolean = true,
        lastSyncedSeq: Long = 0L,
        isDeleted: Boolean = false,
        createdAt: Long = 50L,
        updatedAt: Long = 100L,
    ) = SketchEntity(
        uuid = id,
        noteId = noteId,
        anchor = anchor,
        sortOrder = order,
        strokes = strokes,
        createdAt = createdAt,
        updatedAt = updatedAt,
        isDeleted = isDeleted,
        deletedAt = if (isDeleted) 900L else null,
        hlcMs = rowClock.ms,
        hlcCounter = rowClock.counter,
        hlcNode = rowClock.node,
        fieldHlc = "",
        dirty = dirty,
        lastSyncedSeq = lastSyncedSeq,
    )

    private fun sketchRecord(
        id: String = "s1",
        noteId: String = "n1",
        anchor: Int = 0,
        order: Int = 0,
        strokes: String = "1|10x10|ff000000,4:0,0",
        rowClock: Hlc = clock(1_000),
        updatedAt: String = "100",
        isDeleted: String = "0",
    ) = SyncRecord(
        type = RecordType.SKETCH,
        uuid = id,
        rowClock = rowClock,
        fieldClocks = emptyMap(),
        fields = mapOf(
            FieldClocks.NOTE_ID to FieldValue.of(noteId),
            FieldClocks.ANCHOR to FieldValue.of(anchor.toString()),
            FieldClocks.ORDER to FieldValue.of(order.toString()),
            FieldClocks.STROKES to FieldValue.of(strokes),
            FieldClocks.UPDATED_AT to FieldValue.of(updatedAt),
            FieldClocks.DELETED to FieldValue.of(isDeleted, if (isDeleted == "1") "900" else null),
        ),
    )

    private suspend fun storedNote(id: String = "n1") = database.noteDao.noteRow(id)!!

    private suspend fun storedSketch(id: String = "s1") = database.sketchDao.sketchRow(id)!!

    // -- §3.2 rule 1: dirty is conditional -------------------------------------------------------

    @Test
    fun `an accepted push clears dirty when the row has not moved`() = runTest {
        database.noteDao.applyRemoteNote(localNote(rowClock = clock(1_000)))

        store.acknowledgePush(RecordType.NOTE, "n1", clock(1_000), seq = 7L, contentBaseline = null)

        assertFalse("the row was unchanged, so the push published it", storedNote().dirty)
        assertEquals(7L, storedNote().lastSyncedSeq)
    }

    /**
     * The bug this rule exists for: the user types while the push is in flight. The row's clock has
     * moved on from the one that was sealed, so the version the server accepted is not the version
     * this device now holds — and clearing `dirty` would declare the newer one published and never
     * send it. No error, no retry, no way for anyone to notice.
     */
    @Test
    fun `an edit made while the push was in flight keeps the row dirty`() = runTest {
        database.noteDao.applyRemoteNote(localNote(rowClock = clock(1_000)))
        // The push was sealed at 1000; the editor saved again at 2000 before the ack arrived.
        database.noteDao.applyRemoteNote(localNote(rowClock = clock(2_000), content = "milk, eggs"))

        store.acknowledgePush(RecordType.NOTE, "n1", clock(1_000), seq = 7L, contentBaseline = null)

        assertTrue("the newer edit must still be pushed", storedNote().dirty)
        assertEquals("milk, eggs", storedNote().content)
    }

    /**
     * The guard is the whole row clock, not a prefix of it. Two nodes can reach the same
     * `(ms, counter)` — the counter is per-generator — so comparing only those two would read
     * another device's write as "unchanged" and clear `dirty` over it.
     */
    @Test
    fun `a row rewritten by another node in the same millisecond is not treated as unchanged`() = runTest {
        database.noteDao.applyRemoteNote(localNote(rowClock = clock(1_000, 0, "othernode")))

        store.acknowledgePush(RecordType.NOTE, "n1", clock(1_000, 0, node), seq = 7L, contentBaseline = null)

        assertTrue(storedNote().dirty)
    }

    // -- §3.2 rule 2: lastSyncedSeq is unconditional ---------------------------------------------

    /**
     * The server did accept the version that was sent, whatever has happened locally since. A row
     * left at its old `lastSyncedSeq` sends a stale `baseSeq` on the next push and takes a
     * guaranteed `409` for nothing — every pass, forever.
     */
    @Test
    fun `lastSyncedSeq is written even when the row moved and dirty stayed set`() = runTest {
        database.noteDao.applyRemoteNote(localNote(rowClock = clock(1_000), lastSyncedSeq = 3L))
        database.noteDao.applyRemoteNote(localNote(rowClock = clock(2_000), lastSyncedSeq = 3L))

        store.acknowledgePush(RecordType.NOTE, "n1", clock(1_000), seq = 7L, contentBaseline = null)

        assertEquals(7L, storedNote().lastSyncedSeq)
        assertTrue(storedNote().dirty)
    }

    // -- and the part neither rule states: they are one statement --------------------------------

    /**
     * The acknowledgement is **one** row-update event, in both branches.
     *
     * Written as an event count rather than as a reading of the SQL, because the failure mode is
     * an implementation that satisfies both rules above and splits them into two statements: an
     * `UPDATE … WHERE the clock matches` followed by an `UPDATE … SET lastSyncedSeq`. Every
     * assertion in this file except this one passes for that implementation, and it is wrong —
     * between the two statements the row can move, and then `dirty` has been cleared for a version
     * `lastSyncedSeq` does not describe. The engine cannot enforce this from outside; nothing can,
     * except the statement being one statement.
     *
     * An `AFTER UPDATE` trigger fires once per row per statement, whether or not the values
     * actually changed, so the count is exactly the number of update statements that matched.
     */
    @Test
    fun `the acknowledgement is one statement when the clock matches`() = runTest {
        database.noteDao.applyRemoteNote(localNote(rowClock = clock(1_000)))
        countUpdates {
            store.acknowledgePush(RecordType.NOTE, "n1", clock(1_000), seq = 7L, contentBaseline = null)
        }.let { assertEquals("two statements would have counted 2", 1, it) }
    }

    @Test
    fun `the acknowledgement is one statement when the clock does not match`() = runTest {
        database.noteDao.applyRemoteNote(localNote(rowClock = clock(2_000)))
        countUpdates {
            store.acknowledgePush(RecordType.NOTE, "n1", clock(1_000), seq = 7L, contentBaseline = null)
        }.let { assertEquals(1, it) }
    }

    @Test
    fun `a folder acknowledgement is one statement too`() = runTest {
        database.folderDao.applyRemoteFolder(
            FolderEntity(
                id = "f1", name = "Work", colorArgb = null, createdAt = 1, updatedAt = 2,
                hlcMs = 1_000, hlcCounter = 0, hlcNode = node, dirty = true,
            )
        )
        countUpdates(table = "folders") {
            store.acknowledgePush(RecordType.FOLDER, "f1", clock(1_000), seq = 7L, contentBaseline = null)
        }.let { assertEquals(1, it) }
    }

    // -- SKETCH mirrors FOLDER: same two rules, no body to conflict-copy -------------------------

    /**
     * The acceptance criterion this file exists to defend for SKETCH: `acknowledgePush` clears
     * `dirty` only if the row's clock still matches the version that was sealed and sent. Clearing
     * it unconditionally would drop an edit made while the push was in flight -- exactly the bug
     * §3.2 rule 1 exists to prevent, and nothing distinguishes a sketch from a note or folder here.
     */
    @Test
    fun `an acknowledged sketch push clears dirty when the row has not moved`() = runTest {
        database.sketchDao.upsertSketch(localSketch(rowClock = clock(1_000)))

        store.acknowledgePush(RecordType.SKETCH, "s1", clock(1_000), seq = 7L, contentBaseline = null)

        assertFalse("the row was unchanged, so the push published it", storedSketch().dirty)
        assertEquals(7L, storedSketch().lastSyncedSeq)
    }

    @Test
    fun `an edit made while the sketch push was in flight keeps the row dirty`() = runTest {
        database.sketchDao.upsertSketch(localSketch(rowClock = clock(1_000)))
        // The push was sealed at 1000; a redraw landed at 2000 before the ack arrived.
        database.sketchDao.upsertSketch(localSketch(rowClock = clock(2_000), strokes = "1|10x10|ffffffff,2:0,0"))

        store.acknowledgePush(RecordType.SKETCH, "s1", clock(1_000), seq = 7L, contentBaseline = null)

        assertTrue("the newer edit must still be pushed", storedSketch().dirty)
        assertEquals("1|10x10|ffffffff,2:0,0", storedSketch().strokes)
    }

    @Test
    fun `a sketch acknowledgement is one statement too`() = runTest {
        database.sketchDao.upsertSketch(localSketch(rowClock = clock(1_000)))
        countUpdates(table = "sketches") {
            store.acknowledgePush(RecordType.SKETCH, "s1", clock(1_000), seq = 7L, contentBaseline = null)
        }.let { assertEquals(1, it) }
    }

    /**
     * Counts `UPDATE` events on [table] while [body] runs.
     *
     * The audit table and trigger are created on the writable connection Room uses for writes, so
     * they see exactly the statements the DAO issues. They are dropped again so one test cannot
     * count another's writes.
     */
    private inline fun countUpdates(table: String = "notes", body: () -> Unit): Int {
        val db = database.openHelper.writableDatabase
        db.execSQL("CREATE TABLE IF NOT EXISTS update_audit (n INTEGER)")
        db.execSQL("DELETE FROM update_audit")
        db.execSQL(
            "CREATE TRIGGER update_audit_trg AFTER UPDATE ON $table " +
                "BEGIN INSERT INTO update_audit VALUES (1); END"
        )
        try {
            body()
        } finally {
            db.execSQL("DROP TRIGGER update_audit_trg")
        }
        return db.query("SELECT COUNT(*) FROM update_audit").use { it.moveToFirst(); it.getInt(0) }
    }

    // -- recordSeen ------------------------------------------------------------------------------

    /**
     * `MergeResult.NoChange` is reached by three ordinary production events, and in all three the
     * row's data must not be touched while its `lastSyncedSeq` must be. A `recordSeen` that also
     * cleared `dirty` would declare an unpushed local edit published.
     */
    @Test
    fun `recordSeen moves the seq and leaves the row and its dirty flag alone`() = runTest {
        database.noteDao.applyRemoteNote(localNote(content = "milk", dirty = true))

        store.recordSeen(RecordType.NOTE, "n1", seq = 9L, contentBaseline = clock(500))

        assertEquals(9L, storedNote().lastSyncedSeq)
        assertTrue("a NoChange must not publish a local edit", storedNote().dirty)
        assertEquals("milk", storedNote().content)
        assertEquals("500-0-$node", storedNote().contentSyncedHlc)
    }

    @Test
    fun `recordSketchSeen moves the seq and leaves the row and its dirty flag alone`() = runTest {
        database.sketchDao.upsertSketch(localSketch(strokes = "1|10x10|ff000000,4:0,0", dirty = true))

        store.recordSeen(RecordType.SKETCH, "s1", seq = 9L, contentBaseline = null)

        assertEquals(9L, storedSketch().lastSyncedSeq)
        assertTrue("a NoChange must not publish a local edit", storedSketch().dirty)
        assertEquals("1|10x10|ff000000,4:0,0", storedSketch().strokes)
    }

    // -- applyMerged -----------------------------------------------------------------------------

    /**
     * The reason a merged record may not go through `upsertNote`: that method's conflict branch
     * deliberately refuses to write `isFavorite`, `isDeleted` and `deletedAt`, which is right for
     * the editor and fatal here — a remote delete would be dropped and the note would come back
     * from the dead on every device.
     */
    @Test
    fun `a remote delete and a remote favourite are written, not refused`() = runTest {
        database.noteDao.applyRemoteNote(localNote(isFavorite = false, isDeleted = false))

        store.applyMerged(
            MergedWrite(
                record = noteRecord(rowClock = clock(3_000), isFavorite = "1", isDeleted = "1"),
                dirty = false,
                seq = 4L,
                contentBaseline = clock(3_000),
                conflictCopy = null,
            )
        )

        assertTrue("a remote favourite must land", storedNote().isFavorite)
        assertTrue("a remote delete must land", storedNote().isDeleted)
        assertEquals(900L, storedNote().deletedAt)
        assertEquals(4L, storedNote().lastSyncedSeq)
        assertFalse(storedNote().dirty)
    }

    @Test
    fun `a conflict copy is written alongside the winner, dirty and never pushed`() = runTest {
        database.noteDao.applyRemoteNote(localNote())

        store.applyMerged(
            MergedWrite(
                record = noteRecord(content = "winner", rowClock = clock(3_000)),
                dirty = false,
                seq = 4L,
                contentBaseline = clock(3_000),
                conflictCopy = noteRecord(id = "copy-1", content = "loser", rowClock = clock(2_000)),
            )
        )

        val copy = storedNote("copy-1")
        assertEquals("loser", copy.content)
        assertTrue("the copy has never been on the server", copy.dirty)
        assertEquals(0L, copy.lastSyncedSeq)
        assertEquals("winner", storedNote().content)
    }

    /**
     * A row this device already has keeps its own `createdAt`. It is the one column with no history
     * to fall back on — no write path can move it — so a merge that reset it would destroy the
     * value permanently and on every device.
     */
    @Test
    fun `a merge never moves an existing createdAt`() = runTest {
        database.noteDao.applyRemoteNote(localNote(createdAt = 50L, updatedAt = 100L))

        store.applyMerged(
            MergedWrite(
                record = noteRecord(rowClock = clock(3_000), updatedAt = "7000"),
                dirty = false, seq = 4L, contentBaseline = null, conflictCopy = null,
            )
        )

        assertEquals(50L, storedNote().createdAt)
    }

    /**
     * A record arriving for the first time has no `createdAt` — the merge does not model it — so
     * the store supplies the record's own `updatedAt`, the convention `ConflictCopies` already
     * chose. Dating it to the moment of the merge instead would put the note in the user's Recent
     * list stamped with a moment they were not editing.
     */
    @Test
    fun `a first sighting takes its createdAt from the record's updatedAt`() = runTest {
        store.applyMerged(
            MergedWrite(
                record = noteRecord(id = "new", rowClock = clock(3_000), updatedAt = "7000"),
                dirty = false, seq = 4L, contentBaseline = null, conflictCopy = null,
            )
        )

        assertEquals(7_000L, storedNote("new").createdAt)
    }

    /**
     * The acceptance criterion this file exists to defend for SKETCH's `applyMerged` branch: a
     * merged remote sketch is written to the `sketches` table -- not silently dropped -- and is
     * readable back with every field intact. Mirrors `FOLDER`, not `NOTE`: no conflict copy is
     * ever produced, because only a note has a body worth preserving that way.
     */
    @Test
    fun `a merged remote sketch is written and readable`() = runTest {
        store.applyMerged(
            MergedWrite(
                record = sketchRecord(
                    id = "new-sketch",
                    noteId = "n1",
                    anchor = 2,
                    order = 1,
                    strokes = "1|10x10|ff112233,6:0,0;10,10",
                    rowClock = clock(3_000),
                    updatedAt = "7000",
                ),
                dirty = false,
                seq = 4L,
                contentBaseline = null,
                conflictCopy = null,
            )
        )

        val written = storedSketch("new-sketch")
        assertEquals("n1", written.noteId)
        assertEquals(2, written.anchor)
        assertEquals(1, written.sortOrder)
        assertEquals("1|10x10|ff112233,6:0,0;10,10", written.strokes)
        assertEquals(7_000L, written.updatedAt)
        assertEquals(7_000L, written.createdAt)
        assertEquals(4L, written.lastSyncedSeq)
        assertFalse("the remote's own version is not dirty", written.dirty)
        assertEquals(clock(3_000), written.rowHlc())
    }

    /**
     * The full-row path, exactly like `applyRemoteNote`/`applyRemoteFolder`: a merge that decides
     * the remote wins overwrites every column of the existing local row, including clearing
     * `dirty` -- there is no conflict-copy branch for a sketch to fall back into instead.
     */
    @Test
    fun `a merged remote sketch overwrites a locally dirty row outright`() = runTest {
        database.sketchDao.upsertSketch(localSketch(strokes = "local-strokes", dirty = true))

        store.applyMerged(
            MergedWrite(
                record = sketchRecord(strokes = "remote-strokes", rowClock = clock(3_000)),
                dirty = false,
                seq = 4L,
                contentBaseline = null,
                conflictCopy = null,
            )
        )

        assertEquals("remote-strokes", storedSketch().strokes)
        assertFalse("the remote's version replaced the local one outright", storedSketch().dirty)
    }

    // -- load ------------------------------------------------------------------------------------

    /**
     * Every other single-row read on the DAO hides tombstones, because a trashed note must not
     * reach the editor. This one must not hide them: a tombstone is the only delete the protocol
     * has, and a store that could not see one would report "never heard of it" for a note deleted
     * ten seconds ago and then accept the server's living copy back over the delete.
     */
    @Test
    fun `load sees a tombstoned row`() = runTest {
        database.noteDao.applyRemoteNote(localNote(isDeleted = true))

        val stored = store.load(RecordType.NOTE, "n1")!!
        assertEquals(FieldValue.of("1", "900"), stored.record.valueOf(FieldClocks.DELETED))
    }

    @Test
    fun `load sees a tombstoned sketch row`() = runTest {
        database.sketchDao.upsertSketch(localSketch(isDeleted = true))

        val stored = store.load(RecordType.SKETCH, "s1")!!
        assertEquals(FieldValue.of("1", "900"), stored.record.valueOf(FieldClocks.DELETED))
    }

    @Test
    fun `load reports an absent record as absent rather than as an empty one`() = runTest {
        assertNull(store.load(RecordType.NOTE, "nope"))
        assertNull(store.load(RecordType.FOLDER, "nope"))
        assertNull(store.load(RecordType.SKETCH, "nope"))
    }

    /**
     * `''` means "no agreement is recorded", and the merge reads that as "fall back to the
     * conservative rule". Parsing it into `Hlc.ZERO` would instead claim an agreement at the
     * beginning of time — that every body this device holds was already published — and the merge
     * would then discard an unpushed body with no conflict copy.
     */
    @Test
    fun `an unset content baseline reads as absent, not as the zero clock`() = runTest {
        database.noteDao.applyRemoteNote(localNote(contentSyncedHlc = ""))
        assertNull(store.load(RecordType.NOTE, "n1")!!.contentBaseline)

        database.noteDao.applyRemoteNote(localNote(contentSyncedHlc = "500-0-$node"))
        assertEquals(clock(500), store.load(RecordType.NOTE, "n1")!!.contentBaseline)
    }

    // -- dirtyRecords ----------------------------------------------------------------------------

    /**
     * The order is part of the contract: a device offline for a week pushes the week in the order
     * it happened, and a deterministic order is what makes a failing convergence seed replay
     * identically. Notes, folders and sketches are one stream, so a sketch minted between two
     * notes has to sort between them rather than after all of both tables.
     */
    @Test
    fun `dirty rows of all three tables come back as one stream, oldest clock first`() = runTest {
        database.noteDao.applyRemoteNote(localNote(id = "late", rowClock = clock(3_000)))
        database.noteDao.applyRemoteNote(localNote(id = "early", rowClock = clock(1_000)))
        database.folderDao.applyRemoteFolder(
            FolderEntity(
                id = "mid", name = "Work", colorArgb = null, createdAt = 1, updatedAt = 2,
                hlcMs = 2_000, hlcCounter = 0, hlcNode = node, dirty = true,
            )
        )
        database.sketchDao.upsertSketch(localSketch(id = "midsketch", rowClock = clock(2_000, 1)))

        assertEquals(
            listOf("early", "mid", "midsketch", "late"),
            store.dirtyRecords().map { it.record.uuid },
        )
    }

    /**
     * The acceptance criterion this file exists to defend for SKETCH's `dirtyRecords` branch: a
     * dirty sketch is offered for pushing exactly like a dirty note or folder.
     */
    @Test
    fun `a dirty sketch appears in dirtyRecords`() = runTest {
        database.sketchDao.upsertSketch(localSketch(id = "s1", dirty = true))

        assertEquals(listOf("s1"), store.dirtyRecords().map { it.record.uuid })
    }

    @Test
    fun `a clean row is not offered for pushing`() = runTest {
        database.noteDao.applyRemoteNote(localNote(id = "clean", dirty = false, lastSyncedSeq = 3L))
        database.noteDao.applyRemoteNote(localNote(id = "dirty", dirty = true))
        database.sketchDao.upsertSketch(localSketch(id = "clean-sketch", dirty = false, lastSyncedSeq = 3L))

        assertEquals(listOf("dirty"), store.dirtyRecords().map { it.record.uuid })
    }

    /**
     * A tombstone is an ordinary dirty row. The protocol has no delete endpoint, so if it is not in
     * this list the delete never leaves the device.
     */
    @Test
    fun `a tombstone is pushed like any other row`() = runTest {
        database.noteDao.applyRemoteNote(localNote(isDeleted = true))
        assertEquals(listOf("n1"), store.dirtyRecords().map { it.record.uuid })
    }

    // -- the cursor ------------------------------------------------------------------------------

    @Test
    fun `the cursor starts at zero and remembers what it was told`() = runTest {
        assertEquals(0L, store.cursor())
        store.saveCursor(12L)
        assertEquals(12L, store.cursor())
    }

    /**
     * §8's F7. A cursor that went backwards would re-deliver the whole account, and against rows
     * that are already clean that is indistinguishable from "this account is empty" — the next pass
     * would read a full library as a mass delete. The guard is in the SQL so that no future caller
     * can be the one that gets it wrong.
     */
    @Test
    fun `the cursor never moves backwards`() = runTest {
        store.saveCursor(12L)
        store.saveCursor(5L)
        assertEquals(12L, store.cursor())
    }

    @Test
    fun `a cursor belongs to its account and is not inherited by another`() = runTest {
        store.saveCursor(12L)
        val other = RoomSyncStore(
            database, database.noteDao, database.folderDao, database.sketchDao,
            database.attachmentDao, database.syncStateDao,
            accountId = "acct-2",
            clockObserver = ClockObserver {},
        )
        assertEquals(0L, other.cursor())
    }

    // -- the halt --------------------------------------------------------------------------------

    @Test
    fun `a halt survives a new store over the same database`() = runTest {
        assertNull(store.halt())
        store.recordHalt(HaltReason.SERVER_ROLLED_BACK)

        val restarted = RoomSyncStore(
            database, database.noteDao, database.folderDao, database.sketchDao,
            database.attachmentDao, database.syncStateDao, account,
            clockObserver = ClockObserver {},
        )
        assertEquals(HaltReason.SERVER_ROLLED_BACK, restarted.halt())
    }

    /**
     * The first reason is the one that explains the rest — a rolled-back server produces a cascade
     * of record-level rejections, and reporting the last of them would report the symptom.
     */
    @Test
    fun `the first halt reason is the one kept`() = runTest {
        store.recordHalt(HaltReason.SERVER_ROLLED_BACK)
        store.recordHalt(HaltReason.RECORDS_UNREADABLE)
        assertEquals(HaltReason.SERVER_ROLLED_BACK, store.halt())
    }

    /**
     * A halt written by a build that knows a reason this one does not is still a halt. Reading it
     * as "healthy" would be an older build quietly resuming a sync a newer one had stopped.
     */
    @Test
    fun `a halt reason this build does not know still halts`() = runTest {
        database.syncStateDao.recordHalt(account, "SOMETHING_FROM_THE_FUTURE")
        assertEquals(HaltReason.SERVER_ROLLED_BACK, store.halt())
    }

    @Test
    fun `recording a halt does not disturb the cursor`() = runTest {
        store.saveCursor(12L)
        store.recordHalt(HaltReason.DEVICE_REVOKED)
        assertEquals(12L, store.cursor())
    }

    /**
     * The other direction, which is the dangerous one. Both writes are `INSERT … ON CONFLICT` on
     * the same row, and the cursor's insert branch names `haltReason = ''`. If its conflict branch
     * ever assigned that column too, a halted engine would un-halt itself the next time anything
     * moved a cursor — which is the engine resuming against exactly the server it refused to trust.
     */
    @Test
    fun `advancing the cursor does not clear a halt`() = runTest {
        store.recordHalt(HaltReason.SERVER_ROLLED_BACK)
        store.saveCursor(12L)
        assertEquals(HaltReason.SERVER_ROLLED_BACK, store.halt())
    }

    @Test
    fun `clearing a halt makes the store healthy again`() = runTest {
        store.recordHalt(HaltReason.UNSUPPORTED_PAYLOAD_VERSION)
        assertEquals(HaltReason.UNSUPPORTED_PAYLOAD_VERSION, store.halt())

        store.clearHalt()

        assertNull("a cleared halt reads as healthy, which is what lets a pass run", store.halt())
    }

    /**
     * Clearing is the only write here that must not create the row.
     *
     * A device with no `sync_state` row has never pulled and therefore cannot be halted, so an
     * upsert would fabricate a row — and with it a cursor of 0 — for an account this device knows
     * nothing about. A cursor of 0 is not inert: it is what `takeSnapshotOnce` reads as "before the
     * first pull on this account", so inventing one would arm a pre-sync snapshot for an account
     * that has none.
     */
    @Test
    fun `clearing a halt that was never recorded creates nothing`() = runTest {
        assertNull("precondition: nothing recorded", store.halt())

        store.clearHalt()

        assertNull(store.halt())
        assertNull(
            "clearing must not have invented a sync_state row",
            database.syncStateDao.get(account),
        )
    }

    /** A halt cleared and then re-detected is recorded again, rather than being sticky either way. */
    @Test
    fun `a halt can be recorded again after being cleared`() = runTest {
        store.recordHalt(HaltReason.SERVER_ROLLED_BACK)
        store.clearHalt()

        store.recordHalt(HaltReason.DEVICE_REVOKED)

        assertEquals(
            "the first-reason guard is scoped to a live halt, not to all of history",
            HaltReason.DEVICE_REVOKED,
            store.halt(),
        )
    }

    @Test
    fun `clearing a halt does not disturb the cursor`() = runTest {
        store.saveCursor(12L)
        store.recordHalt(HaltReason.SERVER_ROLLED_BACK)

        store.clearHalt()

        assertEquals(
            "the cursor is where the account is, and clearing a halt says nothing about that",
            12L,
            store.cursor(),
        )
    }

    // -- the data version --------------------------------------------------------------------------

    @Test
    fun `a data version round-trips`() = runTest {
        assertNull("a device that has never pulled has no version", database.syncStateDao.dataVersion(account))

        // saveDataVersion is an UPDATE, never an upsert (see its doc), so the round trip is only
        // meaningful for a device that has a row -- i.e. one that has completed a pull, which is
        // exactly when the engine calls it in production.
        store.saveCursor(5L)
        database.syncStateDao.saveDataVersion(account, 2)

        assertEquals(2, database.syncStateDao.dataVersion(account))
    }

    /**
     * Saving the version must not invent a cursor. A row conjured here would claim this device had
     * pulled up to 0 on an account it has never contacted, and `takeSnapshotOnce` reads a cursor of
     * 0 as "before the first pull".
     */
    @Test
    fun `saving a data version does not disturb the cursor`() = runTest {
        store.saveCursor(12L)

        database.syncStateDao.saveDataVersion(account, 2)

        assertEquals(12L, store.cursor())
    }

    /**
     * `advanceCursor`'s INSERT never names `dataVersion`, so a device that has pulled at least
     * once but never had `saveDataVersion` called sits at the column's own `NOT NULL DEFAULT 0` --
     * a real, genuinely stored `0`, not `null`.
     *
     * This must read back as `0`, **not** as [SyncEngine.DATA_VERSION]: an earlier version of
     * [RoomSyncStore.dataVersion] masked `0` to the current generation on the theory that it was
     * indistinguishable from "no row", and that mask is exactly why `SyncEngine`'s generation
     * write never fired for a device in this state — the engine read it as already current and so
     * never wrote anything to correct it. `0` can never be a value [SyncEngine.saveDataVersion]
     * itself wrote, since [SyncEngine.DATA_VERSION] starts at 1 and only increases, so `0` is a
     * safe, unambiguous "behind" that the next completed pull corrects.
     */
    @Test
    fun `a device that has pulled but never recorded a version reports zero, not the current generation`() = runTest {
        store.saveCursor(12L)

        assertEquals(0, store.dataVersion())
    }

    /**
     * `saveDataVersion` is an UPDATE, never an upsert -- see [clearHalt] for why a missing row
     * must stay missing. This checks the row itself, not just what `dataVersion` reads back,
     * because a `dataVersion` of null is also what an inserted-then-unset row would report; the
     * only way to be sure nothing was fabricated is to look for the row.
     */
    @Test
    fun `saving a data version against a missing row writes nothing and creates no row`() = runTest {
        database.syncStateDao.saveDataVersion(account, 2)

        assertNull(
            "an UPDATE against a missing row must not fabricate one",
            database.syncStateDao.get(account),
        )
    }
}
