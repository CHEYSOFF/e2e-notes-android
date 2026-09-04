package my.cheysoff.core_data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import my.cheysoff.core_data.data.RoomSketchesRepository
import my.cheysoff.core_data.data.local.NoteDatabase
import my.cheysoff.core_data.data.local.SketchDao
import my.cheysoff.core_data.data.local.SketchEntity
import my.cheysoff.core_data.data.sync.SyncClock
import my.cheysoff.core_domain.model.SketchData
import org.junit.After
import org.junit.Assert.assertEquals
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
            hlcMs = 0L,
            hlcCounter = 0,
            hlcNode = "",
            fieldHlc = "",
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
        // Three sketches, two sharing a sortOrder. The tie must break by uuid so two devices
        // holding the same rows agree on an order rather than each picking SQLite's unspecified one.
        seedDirect(uuid = "c", noteId = "n1", sortOrder = 5)
        seedDirect(uuid = "a", noteId = "n1", sortOrder = 1)
        seedDirect(uuid = "b", noteId = "n1", sortOrder = 1)

        val ids = sketchDao.getSketchesByNoteId("n1").first().map { it.uuid }
        assertEquals(listOf("a", "b", "c"), ids)
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
