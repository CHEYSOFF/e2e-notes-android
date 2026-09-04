package my.cheysoff.core_data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import my.cheysoff.core_data.data.RoomSketchesRepository
import my.cheysoff.core_data.data.local.NoteDatabase
import my.cheysoff.core_data.data.local.SketchDao
import my.cheysoff.core_data.data.local.SketchEntity
import my.cheysoff.core_data.data.sync.SyncClock
import my.cheysoff.core_domain.model.SketchData
import my.cheysoff.core_domain.sync.FieldClocks
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The `sketches` table, its DAO, and [RoomSketchesRepository] — the seam Task 8's two-device test
 * and Plan 3's UI both reach through, exercised here rather than through the DAO directly. Seeding
 * fixtures through `SketchDao.upsertSketch` (as [seedDirect] does) is fine when a test needs exact
 * control over columns the repository would otherwise compute; asserting that a create is
 * *readable back* only counts if it goes through [RoomSketchesRepository.saveSketch], which is
 * what [aSketchInsertedThroughTheRepositoryIsReadableBackWithEveryColumnIntact] does.
 *
 * Robolectric ships its own native SQLite, so this runs real Room, real SQL and real migrations —
 * see [RoomNotesRepositoryTest] for why that is a JVM test and not an instrumented one.
 */
@RunWith(RobolectricTestRunner::class)
class SketchDaoTest {

    private lateinit var database: NoteDatabase
    private lateinit var sketchDao: SketchDao
    private lateinit var repository: RoomSketchesRepository

    private val node = "testnode"
    private val clock = SyncClock(node = { node })

    /** File-backed rather than in-memory, so [aSketchSurvivesTheStoreBeingReopenedOverTheSameDatabase] can reopen it. */
    private val dbName = "sketch_dao_test.db"

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun openDatabase(): NoteDatabase =
        Room.databaseBuilder(context, NoteDatabase::class.java, dbName)
            .allowMainThreadQueries()
            .build()

    @Before
    fun setUp() {
        context.deleteDatabase(dbName)
        database = openDatabase()
        sketchDao = database.sketchDao
        repository = RoomSketchesRepository(sketchDao, database, clock)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(dbName)
    }

    private fun sketch(
        id: String = "s1",
        noteId: String = "n1",
        anchor: Int = 0,
        order: Int = 0,
        strokes: String = "stroke-data",
        createdAt: Long = 1_000L,
        updatedAt: Long = 1_000L,
        isDeleted: Boolean = false,
        deletedAt: Long? = null,
    ) = SketchData(
        id = id,
        noteId = noteId,
        anchor = anchor,
        order = order,
        strokes = strokes,
        createdAt = createdAt,
        updatedAt = updatedAt,
        isDeleted = isDeleted,
        deletedAt = deletedAt,
    )

    /** Seeds a row with exact column control, bypassing the repository's clock stamping. */
    private suspend fun seedDirect(
        uuid: String,
        noteId: String = "n1",
        anchor: Int = 0,
        sortOrder: Int = 0,
        strokes: String = "raw",
        isDeleted: Boolean = false,
        deletedAt: Long? = null,
        hlcMs: Long = 0L,
        hlcCounter: Int = 0,
        hlcNode: String = "",
        fieldHlc: String = "",
    ) = sketchDao.upsertSketch(
        SketchEntity(
            uuid = uuid,
            noteId = noteId,
            anchor = anchor,
            sortOrder = sortOrder,
            strokes = strokes,
            createdAt = 500L,
            updatedAt = 500L,
            isDeleted = isDeleted,
            deletedAt = deletedAt,
            hlcMs = hlcMs,
            hlcCounter = hlcCounter,
            hlcNode = hlcNode,
            fieldHlc = fieldHlc,
            dirty = false,
            lastSyncedSeq = 0L,
        )
    )

    @Test
    fun aSketchInsertedThroughTheRepositoryIsReadableBackWithEveryColumnIntact() = runTest {
        val original = sketch(
            id = "s1",
            noteId = "n1",
            anchor = 3,
            order = 7,
            strokes = "M0,0 L10,10",
            createdAt = 111L,
            updatedAt = 222L,
        )

        repository.saveSketch(original)

        val back = repository.getSketchesForNote("n1").first().single()
        assertEquals(original.id, back.id)
        assertEquals(original.noteId, back.noteId)
        assertEquals(original.anchor, back.anchor)
        assertEquals(original.order, back.order)
        assertEquals(original.strokes, back.strokes)
        assertEquals(original.createdAt, back.createdAt)
        assertEquals(original.updatedAt, back.updatedAt)
        assertEquals(original.isDeleted, back.isDeleted)
        assertEquals(original.deletedAt, back.deletedAt)
    }

    /**
     * `RoomSketchesRepository.saveSketch` always supplies `dirty = true` itself, and
     * `SketchEntity`'s Kotlin default only matters at object-construction time — Room's generated
     * `@Upsert` lists every column explicitly, so neither of those paths can observe what the
     * column's own `DEFAULT` clause says. Only a raw `INSERT` that omits `dirty` from its column
     * list forces SQLite to fall through to the DDL default, which is the one thing
     * `MIGRATION_9_10` actually needs verified: flip that default to `0` and every write path
     * above would keep passing while a real upgrade silently declared the whole library already
     * uploaded. See the "flip and watch it fail" note in the fix-round log in the task report.
     */
    @Test
    fun dirtyFallsThroughToTheColumnDefaultWhenAnInsertOmitsIt() {
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO sketches " +
                "(uuid, noteId, anchor, sortOrder, strokes, createdAt, updatedAt, hlcMs, hlcCounter, hlcNode) " +
                "VALUES ('s1', 'n1', 0, 0, 'x', 1, 1, 0, 0, '')"
        )

        val row = runBlocking { sketchDao.sketchRow("s1") }
        assertTrue(
            "a row inserted with no `dirty` in its column list must fall through to the DDL's own DEFAULT of 1",
            row!!.dirty,
        )
    }

    @Test
    fun dirtyDefaultsToTrueOnAFreshInsertWithoutAnyoneSettingIt() = runTest {
        repository.saveSketch(sketch(id = "s1"))

        val row = sketchDao.sketchRow("s1")
        assertTrue("a freshly inserted sketch must be dirty", row!!.dirty)
    }

    @Test
    fun aSoftDeletedSketchDisappearsFromTheByNoteQueryButIsStillInTheTable() = runTest {
        seedDirect(uuid = "visible", noteId = "n1")
        seedDirect(uuid = "trashed", noteId = "n1", isDeleted = true, deletedAt = 900L)

        val visible = sketchDao.getSketchesByNoteId("n1").first()
        assertEquals(listOf("visible"), visible.map { it.uuid })

        val trashedRow = sketchDao.sketchRow("trashed")
        assertTrue("the tombstoned row must still be in the table", trashedRow != null)
        assertTrue(trashedRow!!.isDeleted)
    }

    @Test
    fun sketchesForNoteComeBackOrderedAndTiesBreakByUuid() = runTest {
        // Three sketches, two sharing a sortOrder. Insert order is DELIBERATELY b, then a, then c
        // -- the reverse of the asserted result for the tied pair -- so SQLite's natural insertion
        // (rowid) order would produce "b, a, c" if `getSketchesByNoteId` ever lost its `uuid ASC`
        // tie-break. Only an explicit `ORDER BY sortOrder ASC, uuid ASC` can produce "a, b, c" here.
        seedDirect(uuid = "c", noteId = "n1", sortOrder = 5)
        seedDirect(uuid = "b", noteId = "n1", sortOrder = 1)
        seedDirect(uuid = "a", noteId = "n1", sortOrder = 1)

        val ids = sketchDao.getSketchesByNoteId("n1").first().map { it.uuid }
        assertEquals(listOf("a", "b", "c"), ids)
    }

    /**
     * Persistence fidelity for the four sync columns Task 4 flagged as the real gap this task had
     * to close: `SketchRecords.toPayload` minted `Hlc.ZERO` for want of anywhere real to read a
     * clock from. Seeded directly (not through the repository, which mints its own clock) so this
     * is purely "does the store give back what was written", independent of any stamping logic.
     */
    @Test
    fun clockColumnsRoundTripThroughTheStoreUnchanged() = runTest {
        seedDirect(
            uuid = "s1",
            hlcMs = 424_242L,
            hlcCounter = 7,
            hlcNode = "nodeZ",
            fieldHlc = "anchor=100-2-nodeZ;strokes=90-0-nodeZ",
        )

        val row = sketchDao.sketchRow("s1")!!
        assertEquals(424_242L, row.hlcMs)
        assertEquals(7, row.hlcCounter)
        assertEquals("nodeZ", row.hlcNode)
        assertEquals("anchor=100-2-nodeZ;strokes=90-0-nodeZ", row.fieldHlc)
    }

    /**
     * The property the whole per-field-clock design rests on: `anchor` and `strokes` clock
     * independently, so editing one cannot make the other look newer than it really is. A single
     * row clock would let a text reflow that only moves `anchor` silently outrun a concurrent
     * drawing edit to `strokes` on another device, or the reverse.
     *
     * Goes through [RoomSketchesRepository.saveSketch] on every write -- this is exactly the code
     * path `Merge.kt`'s field-level rule will read from in Task 6, not a hand-assembled fixture.
     */
    @Test
    fun editingAnchorAndEditingStrokesSeparatelyStampDifferentFieldClocks() = runTest {
        val created = sketch(id = "s1", noteId = "n1", anchor = 1, order = 0, strokes = "v1")
        repository.saveSketch(created)
        val afterCreate = sketchDao.sketchRow("s1")!!
        // A brand-new row has every field implicit at the row clock -- nothing pinned yet.
        assertEquals("", afterCreate.fieldHlc)

        // Edit ONLY anchor. `strokes` did not move, so its clock must now be written down
        // explicitly (it is older than this write's row clock); `anchor` becomes implicit instead.
        repository.saveSketch(created.copy(anchor = 2))
        val afterAnchorEdit = sketchDao.sketchRow("s1")!!
        val clocksAfterAnchorEdit = FieldClocks.parse(afterAnchorEdit.fieldHlc)
        assertTrue(
            "strokes must be pinned to its own clock once anchor moves without it",
            clocksAfterAnchorEdit.containsKey(FieldClocks.STROKES),
        )
        assertFalse(
            "a just-edited anchor must be implicit at the row clock, not pinned",
            clocksAfterAnchorEdit.containsKey(FieldClocks.ANCHOR),
        )

        // Edit ONLY strokes. Now `anchor` must be the one pinned to the clock it was stamped at
        // during the PREVIOUS write, proving the two fields are tracked independently rather than
        // sharing one clock this write would otherwise bump on top of both.
        repository.saveSketch(created.copy(anchor = 2, strokes = "v2"))
        val afterStrokesEdit = sketchDao.sketchRow("s1")!!
        val clocksAfterStrokesEdit = FieldClocks.parse(afterStrokesEdit.fieldHlc)
        assertTrue(
            "anchor must now be pinned to the clock it was stamped at when it was last actually edited",
            clocksAfterStrokesEdit.containsKey(FieldClocks.ANCHOR),
        )
        assertFalse(
            "a just-edited strokes must be implicit at the row clock, not pinned",
            clocksAfterStrokesEdit.containsKey(FieldClocks.STROKES),
        )
        assertEquals(
            "anchor's pinned clock must be the moment IT was actually written, not the moment strokes was",
            afterAnchorEdit.rowHlc(),
            clocksAfterStrokesEdit.getValue(FieldClocks.ANCHOR),
        )
        assertTrue(
            "anchor's pinned clock and this write's own (strokes') row clock must differ -- that is the whole point",
            clocksAfterStrokesEdit.getValue(FieldClocks.ANCHOR) != afterStrokesEdit.rowHlc(),
        )
    }

    @Test
    fun aSketchSurvivesTheStoreBeingReopenedOverTheSameDatabase() = runTest {
        repository.saveSketch(sketch(id = "s1", noteId = "n1", strokes = "persisted"))
        database.close()

        val reopened = openDatabase()
        try {
            val row = reopened.sketchDao.sketchRow("s1")
            assertTrue(row != null)
            assertEquals("persisted", row!!.strokes)
        } finally {
            reopened.close()
        }
    }
}
