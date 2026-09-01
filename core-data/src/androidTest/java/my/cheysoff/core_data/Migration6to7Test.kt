package my.cheysoff.core_data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import my.cheysoff.core_data.data.local.NOTE_DATABASE_VERSION
import my.cheysoff.core_data.data.local.NoteDatabase
import my.cheysoff.core_data.data.local.SyncStateEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises MIGRATION_6_7 — the sync bookkeeping columns and the `sync_state` table — against a v6
 * database built by hand from the DDL committed at `core-data/schemas/…/6.json`.
 *
 * Modelled on [Migration5to6Test], including running on a PLAIN (unencrypted) SQLite file:
 * SQLCipher is an open-helper swap and changes nothing about what the migration SQL does.
 *
 * ## What is actually at risk here
 *
 * Not the ALTER statements. Six `ADD COLUMN`s and one `CREATE TABLE` either run or they do not,
 * and Room's own schema validation on open catches a column of the wrong name or type before any
 * assertion in this file does. **The risk is entirely in the DEFAULT clauses**, because those
 * decide what the user's existing library *says about itself* the first time a sync engine looks
 * at it, and every one of them is silent when wrong.
 *
 * The one that matters most:
 *
 * ⚠️ **`dirty` must migrate to 1.** Every row on disk when this runs has never been pushed —
 * there was no sync engine when it was written. A `DEFAULT 0` would declare the user's entire
 * library already uploaded, and the first pull would then compare a full local library against an
 * account the server has never heard of. A record the client believes it pushed and the server
 * does not have is a record deleted elsewhere: the library would be tombstoned note by note,
 * propagated to every paired device, with no undo and no error message. It is one character in
 * the DDL, so [everyMigratedRowIsDirty] asserts it directly rather than through anything that
 * could be true for another reason.
 *
 * The zero clock is the second: a migrated row must compare BELOW every real clock, so that it
 * loses to a genuine remote edit it knows nothing about — and, because it is still dirty, its
 * content is pushed and merged rather than dropped.
 */
@RunWith(AndroidJUnit4::class)
class Migration6to7Test {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration_6_7_test.db"

    /** v6's exported `notes` DDL, copied from schemas/…/6.json. */
    private val v6Notes = "CREATE TABLE IF NOT EXISTS `notes` (`id` TEXT NOT NULL, " +
        "`title` TEXT NOT NULL, `content` TEXT NOT NULL, `contentFormat` TEXT NOT NULL, " +
        "`checklist` TEXT NOT NULL, `isPinned` INTEGER NOT NULL, `isFavorite` INTEGER NOT NULL, " +
        "`folderId` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
        "`isDeleted` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`id`))"

    /** v6's exported `folders` DDL — timestamps and tombstone present, sync columns absent. */
    private val v6Folders = "CREATE TABLE IF NOT EXISTS `folders` (`id` TEXT NOT NULL, " +
        "`name` TEXT NOT NULL, `colorArgb` INTEGER, `createdAt` INTEGER NOT NULL, " +
        "`updatedAt` INTEGER NOT NULL, `isDeleted` INTEGER NOT NULL, `deletedAt` INTEGER, " +
        "PRIMARY KEY(`id`))"

    /** Note id to the (content, folderId) it holds in v6, plus whether it is in Trash. */
    private val noteFixtures = listOf(
        NoteFixture("plain", "Groceries", null, isDeleted = false),
        NoteFixture("html", "<p>hello</p>", "f1", isDeleted = false),
        NoteFixture("filed", "in a folder", "f1", isDeleted = false),
        // A row in Trash must migrate exactly like a live one. A tombstone the server has never
        // seen is as unsynced as any other row, and a `dirty = 0` on it would mean the delete
        // never propagates — the note comes back on the other device.
        NoteFixture("trashed", "already deleted", null, isDeleted = true),
        NoteFixture("empty", "", null, isDeleted = false),
    )

    private data class NoteFixture(
        val id: String,
        val content: String,
        val folderId: String?,
        val isDeleted: Boolean,
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

    private fun seedV6() {
        val path = ctx.getDatabasePath(dbName)
        path.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(path, null)
        db.execSQL(v6Notes)
        db.execSQL(v6Folders)
        folderFixtures.forEach { (id, name) ->
            db.execSQL(
                "INSERT INTO folders (id, name, colorArgb, createdAt, updatedAt, isDeleted, deletedAt) " +
                    "VALUES (?, ?, NULL, 1000, 2000, 0, NULL)",
                arrayOf<Any>(id, name),
            )
        }
        // Two statements rather than one with a nullable bind, for the reason Migration5to6Test
        // gives: execSQL's bindArgs is a Java Object[], and an unfiled note is better typed as a
        // literal NULL.
        val prefix = "INSERT INTO notes (id, title, content, contentFormat, checklist, isPinned, " +
            "isFavorite, folderId, createdAt, updatedAt, isDeleted, deletedAt) VALUES (?, ?, ?, 'plain', '', 0, 0, "
        noteFixtures.forEach { f ->
            val tombstone = if (f.isDeleted) "1, 7000" else "0, NULL"
            val sql = if (f.folderId == null) {
                prefix + "NULL, 1000, 2000, $tombstone)"
            } else {
                prefix + "?, 1000, 2000, $tombstone)"
            }
            val args = if (f.folderId == null) {
                arrayOf<Any>(f.id, "title-${f.id}", f.content)
            } else {
                arrayOf<Any>(f.id, "title-${f.id}", f.content, f.folderId)
            }
            db.execSQL(sql, args)
        }
        db.execSQL("PRAGMA user_version = 6")
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
                NoteDatabase.MIGRATION_6_7,
                NoteDatabase.MIGRATION_7_8,
            )
            .build()

    /**
     * The migration runs, Room accepts the result as v7, and every row survives.
     *
     * Room validates the real table shape against the entities when it opens — including, for a
     * column whose entity declares one, its DEFAULT. So a migration that added `dirty` with the
     * wrong default, or a column with the wrong type, throws here before any assertion below is
     * reached. That check is a genuine second line of defence and the reason every sync column
     * carries an explicit `@ColumnInfo(defaultValue = …)`.
     */
    @Test
    fun migrationRunsAndPreservesEveryRow() {
        seedV6()
        val db = openMigrated()
        try {
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
     * ⚠️ The expensive one. Every migrated note and folder must arrive `dirty = 1`.
     *
     * See this class's KDoc for what a 0 here costs. Asserted on notes AND folders, live rows AND
     * tombstones, because the column is added to both tables by one loop and a partial mistake is
     * as destructive as a total one.
     */
    @Test
    fun everyMigratedRowIsDirty() {
        seedV6()
        val db = openMigrated()
        try {
            for (table in listOf("notes", "folders")) {
                db.openHelper.readableDatabase
                    .query("SELECT id, dirty FROM $table")
                    .use { c ->
                        assertTrue("$table came back empty", c.count > 0)
                        while (c.moveToNext()) {
                            assertEquals(
                                "${c.getString(0)} in $table migrated as ALREADY PUSHED — the " +
                                    "first pull would then read the whole library as deleted",
                                1,
                                c.getInt(1),
                            )
                        }
                    }
            }
        } finally {
            db.close()
        }
    }

    /**
     * The same claim stated against the DDL itself, so it holds for a row inserted by a statement
     * that omits the column as well as for the rows that were already there.
     */
    @Test
    fun theDirtyColumnDefaultsToOneInTheMigratedSchema() {
        seedV6()
        val db = openMigrated()
        try {
            for (table in listOf("notes", "folders")) {
                // `PRAGMA table_info` rather than the `pragma_table_info()` table-valued function:
                // the function form needs SQLite 3.16, and this module's minSdk is 24, whose
                // bundled SQLite is older than that.
                db.openHelper.readableDatabase.query("PRAGMA table_info(`$table`)").use { c ->
                    val nameColumn = c.getColumnIndexOrThrow("name")
                    val defaultColumn = c.getColumnIndexOrThrow("dflt_value")
                    var found = false
                    while (c.moveToNext()) {
                        if (c.getString(nameColumn) != "dirty") continue
                        found = true
                        assertEquals(
                            "$table.dirty must DEFAULT to 1, not 0",
                            "1",
                            c.getString(defaultColumn),
                        )
                    }
                    assertTrue("$table has no dirty column", found)
                }
            }
        } finally {
            db.close()
        }
    }

    /**
     * Every other sync column's default, and the note's own columns left untouched.
     *
     * The zero clock is the point: it compares below every real clock, so a migrated row loses a
     * merge against a genuine remote edit it never saw — while staying dirty, so its own content
     * is still pushed rather than dropped. Stamping every row with the migration's own clock
     * instead would invent a history that never happened and make thousands of rows tie.
     */
    @Test
    fun everyExistingNoteGetsTheZeroClockAndKeepsItsOwnColumns() {
        seedV6()
        val db = openMigrated()
        try {
            noteFixtures.forEach { f ->
                db.openHelper.readableDatabase.query(
                    "SELECT content, folderId, createdAt, updatedAt, isDeleted, deletedAt, " +
                        "hlcMs, hlcCounter, hlcNode, fieldHlc, dirty, lastSyncedSeq " +
                        "FROM notes WHERE id = ?",
                    arrayOf<Any>(f.id),
                ).use { c ->
                    assertEquals("row ${f.id} missing after migration", 1, c.count)
                    c.moveToFirst()
                    assertEquals("content of ${f.id} was rewritten", f.content, c.getString(0))
                    assertEquals("folderId of ${f.id} was rewritten", f.folderId, c.getString(1))
                    assertEquals("createdAt of ${f.id} was rewritten", 1000L, c.getLong(2))
                    assertEquals("updatedAt of ${f.id} was rewritten", 2000L, c.getLong(3))
                    assertEquals("the v6 tombstone of ${f.id} was disturbed", if (f.isDeleted) 1 else 0, c.getInt(4))

                    assertEquals("${f.id} must migrate at the zero clock", 0L, c.getLong(6))
                    assertEquals(0, c.getInt(7))
                    assertEquals("", c.getString(8))
                    // "" means "every field is at the row clock", which is exactly true of a row
                    // whose fields were all written together before any of this existed.
                    assertEquals("", c.getString(9))
                    assertEquals(1, c.getInt(10))
                    // 0 means "the server has no version of this record", which is also what the
                    // server itself reads a baseSeq of 0 as.
                    assertEquals("${f.id} must claim no server version", 0L, c.getLong(11))
                }
            }
        } finally {
            db.close()
        }
    }

    /** Folders get the identical six columns with the identical defaults. */
    @Test
    fun everyExistingFolderGetsTheZeroClockAndKeepsItsOwnColumns() {
        seedV6()
        val db = openMigrated()
        try {
            folderFixtures.forEach { (id, name) ->
                db.openHelper.readableDatabase.query(
                    "SELECT name, createdAt, updatedAt, isDeleted, " +
                        "hlcMs, hlcCounter, hlcNode, fieldHlc, dirty, lastSyncedSeq " +
                        "FROM folders WHERE id = ?",
                    arrayOf<Any>(id),
                ).use { c ->
                    assertEquals("folder $id missing after migration", 1, c.count)
                    c.moveToFirst()
                    assertEquals("name of $id was rewritten", name, c.getString(0))
                    assertEquals(1000L, c.getLong(1))
                    assertEquals(2000L, c.getLong(2))
                    assertEquals(0, c.getInt(3))
                    assertEquals("$id must migrate at the zero clock", 0L, c.getLong(4))
                    assertEquals(0, c.getInt(5))
                    assertEquals("", c.getString(6))
                    assertEquals("", c.getString(7))
                    assertEquals("$id migrated as ALREADY PUSHED", 1, c.getInt(8))
                    assertEquals(0L, c.getLong(9))
                }
            }
        } finally {
            db.close()
        }
    }

    /**
     * `sync_state` exists, is empty, and round-trips through its DAO.
     *
     * Empty is the correct starting state and not merely an incidental one: a row here would mean
     * "this device has already read the account's history up to cursor N", which on a device that
     * has never synced would skip everything before N on the first pull.
     */
    @Test
    fun syncStateIsCreatedEmptyAndUsable() = runBlocking {
        seedV6()
        val db = openMigrated()
        try {
            db.openHelper.readableDatabase.query("SELECT count(*) FROM sync_state").use { c ->
                c.moveToFirst()
                assertEquals("a migrated device must not claim to have pulled anything", 0, c.getInt(0))
            }

            assertNull(db.syncStateDao.get("account-1"))
            db.syncStateDao.upsert(SyncStateEntity(accountId = "account-1", cursor = 12L, lastPullAt = 99L))
            assertEquals(12L, db.syncStateDao.get("account-1")!!.cursor)

            // Upsert, not INSERT OR REPLACE: the row is updated in place.
            db.syncStateDao.upsert(SyncStateEntity(accountId = "account-1", cursor = 30L, lastPullAt = 100L))
            assertEquals(30L, db.syncStateDao.get("account-1")!!.cursor)
            assertEquals(100L, db.syncStateDao.get("account-1")!!.lastPullAt)

            // Two accounts do not share a cursor. Inheriting one from a previous account would
            // leave the device convinced it had already read a history it has never seen.
            db.syncStateDao.upsert(SyncStateEntity(accountId = "account-2", cursor = 1L))
            assertEquals(30L, db.syncStateDao.get("account-1")!!.cursor)

            db.syncStateDao.forget("account-1")
            assertNull(db.syncStateDao.get("account-1"))
            assertEquals(1L, db.syncStateDao.get("account-2")!!.cursor)
        } finally {
            db.close()
        }
    }

    /**
     * End-to-end on a migrated database: a migrated row's first local write stamps it with a real
     * clock, and everything the v6 tombstone did still works on top of the new columns.
     */
    @Test
    fun aMigratedRowGetsARealClockOnItsFirstWrite() = runBlocking {
        seedV6()
        val db = openMigrated()
        try {
            db.noteDao.setNotePinned("plain", true, 88_000L, 0, "testnode", "")

            val row = db.noteDao.rowClock("plain")!!
            assertEquals(88_000L, row.hlcMs)
            assertEquals("testnode", row.hlcNode)
            assertTrue("the first write must beat the zero clock", row.rowHlc() > migratedZeroClock)
        } finally {
            db.close()
        }
    }

    private val migratedZeroClock = my.cheysoff.core_domain.sync.Hlc(0L, 0, "")
}
