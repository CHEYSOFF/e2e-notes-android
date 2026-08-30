package my.cheysoff.core_data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import my.cheysoff.core_data.data.local.NOTE_DATABASE_VERSION
import my.cheysoff.core_data.data.local.NoteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises MIGRATION_5_6 (Trash tombstones + folder timestamps) against a v5 database built by
 * hand, using the DDL committed at core-data/schemas/…/5.json verbatim.
 *
 * v5 IS exported, so Room's MigrationTestHelper would be the better tool here, and it is now
 * reachable: `androidx.room:room-testing:2.8.4` resolves from google() and `:core-data:
 * assembleDebugAndroidTest` succeeds with it on the classpath. (An earlier version of this comment
 * claimed the artifact was unresolvable. That was wrong — only Maven Central is blocked in this
 * environment; Google's Maven, which serves every androidx artifact, is not.) Rewriting this file
 * on MigrationTestHelper and deleting the hand-written DDL below is tracked separately; until then
 * it follows the pattern Migration4to5Test established.
 *
 * Like Migration4to5Test this runs on a PLAIN (unencrypted) SQLite file: SQLCipher is an open-helper
 * swap and changes nothing about what the migration SQL does.
 *
 * What is actually at risk in this migration is not the ALTER statements — they are trivial — but
 * the DEFAULTS. Every read query gained `WHERE isDeleted = 0` in the same change, so a wrong
 * default on `notes.isDeleted` hides the user's entire library, and a `0` rather than NULL default
 * on `deletedAt` would make every pre-existing row look deleted-in-1970 to a purge pass. Those are
 * what the assertions below check.
 */
@RunWith(AndroidJUnit4::class)
class Migration5to6Test {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration_5_6_test.db"

    /** v5's exported `notes` DDL, copied from schemas/…/5.json. */
    private val v5Notes = "CREATE TABLE IF NOT EXISTS `notes` (`id` TEXT NOT NULL, " +
        "`title` TEXT NOT NULL, `content` TEXT NOT NULL, `contentFormat` TEXT NOT NULL, " +
        "`checklist` TEXT NOT NULL, `isPinned` INTEGER NOT NULL, `isFavorite` INTEGER NOT NULL, " +
        "`folderId` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
        "PRIMARY KEY(`id`))"

    /** v5's exported `folders` DDL — no timestamps and no tombstone, which is the gap v6 closes. */
    private val v5Folders = "CREATE TABLE IF NOT EXISTS `folders` (`id` TEXT NOT NULL, " +
        "`name` TEXT NOT NULL, `colorArgb` INTEGER, PRIMARY KEY(`id`))"

    /** Note id to the (title, content, folderId) it holds in v5. */
    private val noteFixtures = listOf(
        Triple("plain", "Groceries", null),
        Triple("html", "<p>hello</p>", "f1"),
        Triple("filed", "in a folder", "f1"),
        Triple("empty", "", null),
    )

    private val folderFixtures = listOf("f1" to "Work", "f2" to "Personal")

    @Before
    fun setUp() {
        ctx.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        ctx.deleteDatabase(dbName)
    }

    private fun seedV5() {
        val path = ctx.getDatabasePath(dbName)
        path.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(path, null)
        db.execSQL(v5Notes)
        db.execSQL(v5Folders)
        folderFixtures.forEach { (id, name) ->
            db.execSQL("INSERT INTO folders (id, name, colorArgb) VALUES (?, ?, NULL)", arrayOf<Any>(id, name))
        }
        // Two statements rather than one with a nullable bind: execSQL's bindArgs is a Java
        // Object[], and an unfiled note is clearer (and better typed) as a literal NULL.
        val insertPrefix = "INSERT INTO notes (id, title, content, contentFormat, checklist, " +
            "isPinned, isFavorite, folderId, createdAt, updatedAt) VALUES (?, ?, ?, 'plain', '', 0, 0, "
        val insertUnfiled = insertPrefix + "NULL, 1000, 2000)"
        val insertFiled = insertPrefix + "?, 1000, 2000)"
        noteFixtures.forEach { (id, content, folderId) ->
            if (folderId == null) {
                db.execSQL(insertUnfiled, arrayOf<Any>(id, "title-$id", content))
            } else {
                db.execSQL(insertFiled, arrayOf<Any>(id, "title-$id", content, folderId))
            }
        }
        db.execSQL("PRAGMA user_version = 5")
        db.close()
    }

    private fun openMigrated(): NoteDatabase =
        Room.databaseBuilder(ctx, NoteDatabase::class.java, dbName)
            .addMigrations(
                NoteDatabase.MIGRATION_1_2,
                NoteDatabase.MIGRATION_2_3,
                NoteDatabase.MIGRATION_3_4,
                NoteDatabase.MIGRATION_4_5,
                NoteDatabase.MIGRATION_5_6,
            )
            .build()

    /**
     * The migration runs, Room accepts the resulting schema as v6 (it validates the real table
     * shape against the entity on open — an ALTER that disagreed with NoteEntity/FolderEntity would
     * throw here), and nothing is added or dropped.
     */
    @Test
    fun migrationRunsAndPreservesEveryRow() {
        seedV5()
        val db = openMigrated()
        try {
            // Room opens lazily, so touching the helper is what actually runs the migration.
            assertEquals(NOTE_DATABASE_VERSION, db.openHelper.readableDatabase.version)

            db.openHelper.readableDatabase.query("SELECT count(*) FROM notes").use { c ->
                c.moveToFirst()
                assertEquals(noteFixtures.size, c.getInt(0))
            }
            db.openHelper.readableDatabase.query("SELECT count(*) FROM folders").use { c ->
                c.moveToFirst()
                assertEquals(folderFixtures.size, c.getInt(0))
            }
        } finally {
            db.close()
        }
    }

    /**
     * The defaults that decide whether the library is still visible after the update: every
     * migrated note must be isDeleted = 0 with a NULL deletedAt, and its own columns untouched.
     */
    @Test
    fun everyExistingNoteSurvivesUndeleted() {
        seedV5()
        val db = openMigrated()
        try {
            noteFixtures.forEach { (id, content, folderId) ->
                db.openHelper.readableDatabase.query(
                    "SELECT content, folderId, createdAt, updatedAt, isDeleted, deletedAt " +
                        "FROM notes WHERE id = ?",
                    arrayOf<Any>(id),
                ).use { c ->
                    assertEquals("row $id missing after migration", 1, c.count)
                    c.moveToFirst()
                    assertEquals("content of $id was rewritten", content, c.getString(0))
                    assertEquals("folderId of $id was rewritten", folderId, c.getString(1))
                    assertEquals("createdAt of $id was rewritten", 1000L, c.getLong(2))
                    assertEquals("updatedAt of $id was rewritten", 2000L, c.getLong(3))
                    assertEquals("$id must migrate as NOT deleted", 0, c.getInt(4))
                    assertTrue("$id must migrate with no deletedAt stamp", c.isNull(5))
                }
            }
        } finally {
            db.close()
        }
    }

    /**
     * Folders gain four columns at once. createdAt/updatedAt land at 0 — the "unset" sentinel the
     * notes table has used since v1 -> v2 — rather than at the migration's own clock, because the
     * folder's real creation time is not recorded anywhere and inventing one would be a lie.
     */
    @Test
    fun everyExistingFolderGetsZeroTimestampsAndNoTombstone() {
        seedV5()
        val db = openMigrated()
        try {
            folderFixtures.forEach { (id, name) ->
                db.openHelper.readableDatabase.query(
                    "SELECT name, colorArgb, createdAt, updatedAt, isDeleted, deletedAt " +
                        "FROM folders WHERE id = ?",
                    arrayOf<Any>(id),
                ).use { c ->
                    assertEquals("folder $id missing after migration", 1, c.count)
                    c.moveToFirst()
                    assertEquals("name of $id was rewritten", name, c.getString(0))
                    assertTrue("colorArgb of $id was rewritten", c.isNull(1))
                    assertEquals("createdAt default", 0L, c.getLong(2))
                    assertEquals("updatedAt default", 0L, c.getLong(3))
                    assertEquals("$id must migrate as NOT deleted", 0, c.getInt(4))
                    assertTrue("$id must migrate with no deletedAt stamp", c.isNull(5))
                }
            }
        } finally {
            db.close()
        }
    }

    /**
     * End-to-end through the DAOs on a migrated database: a note the user deletes disappears from
     * all three ordered reads, shows up in Trash, and comes back intact on restore.
     *
     * This is the assertion that would have caught a read query left without `WHERE isDeleted = 0`,
     * which is the easiest thing to get wrong in this change and the least visible.
     */
    @Test
    fun softDeleteHidesTheNoteFromEveryOrderedReadAndRestoreBringsItBack() = runBlocking {
        seedV5()
        val db = openMigrated()
        try {
            val dao = db.noteDao
            dao.softDeleteNote("filed", 12_345L)

            assertTrue("updatedAt order still returns the deleted note",
                dao.getNotesByUpdatedAt().first().none { it.id == "filed" })
            assertTrue("createdAt order still returns the deleted note",
                dao.getNotesByCreatedAt().first().none { it.id == "filed" })
            assertTrue("title order still returns the deleted note",
                dao.getNotesByTitle().first().none { it.id == "filed" })
            assertNull("a deleted note must not be loadable by id",
                dao.getNoteById("filed").first())

            val trashed = dao.getDeletedNotes().first()
            assertEquals(listOf("filed"), trashed.map { it.id })
            assertEquals("in a folder", trashed.single().content)
            assertEquals(12_345L, trashed.single().deletedAt)

            dao.restoreNote("filed")
            assertTrue("restore did not bring the note back",
                dao.getNotesByUpdatedAt().first().any { it.id == "filed" })
            assertTrue("restore left the note in Trash", dao.getDeletedNotes().first().isEmpty())
        } finally {
            db.close()
        }
    }

    /** Deleting a folder unfiles its notes AND bumps their updatedAt, then flags the folder. */
    @Test
    fun deletingAFolderUnfilesItsNotesAndLeavesATrace() = runBlocking {
        seedV5()
        val db = openMigrated()
        try {
            db.noteDao.clearFolder("f1", 99_000L)
            db.folderDao.softDeleteFolder("f1", 99_000L)

            val notes = db.noteDao.getNotesByUpdatedAt().first()
            val affected = notes.filter { it.id == "html" || it.id == "filed" }
            assertEquals(2, affected.size)
            affected.forEach {
                assertNull("${it.id} was not unfiled", it.folderId)
                assertEquals("${it.id} was unfiled without a trace", 99_000L, it.updatedAt)
            }
            // A note that was never in f1 must be untouched.
            assertEquals(2000L, notes.single { it.id == "plain" }.updatedAt)

            assertEquals(listOf("f1"), db.folderDao.getDeletedFolders().first().map { it.id })
            assertEquals(listOf("f2"), db.folderDao.getFolders().first().map { it.id })
        } finally {
            db.close()
        }
    }
}
