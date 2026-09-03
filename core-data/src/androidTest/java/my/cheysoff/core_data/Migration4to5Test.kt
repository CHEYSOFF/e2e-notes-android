package my.cheysoff.core_data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.cheysoff.core_data.data.local.NOTE_DATABASE_VERSION
import my.cheysoff.core_data.data.local.NoteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises MIGRATION_4_5 against a v4 database built by hand, because v4 predates exportSchema
 * and MigrationTestHelper therefore has no schema to start from.
 *
 * This runs on a PLAIN (unencrypted) SQLite file. SQLCipher is an open-helper swap and changes
 * nothing about what the migration SQL does, so dropping it here costs no fidelity and removes the
 * need for a Keystore-wrapped passphrase inside a test.
 *
 * The fixtures are the note shapes that actually broke, not a happy path. Content that is "<"
 * followed by an unterminated name used to read one character past the end of the string and
 * throw; because the classifier runs inside the migration, that throw rolled the whole thing back
 * on every launch, leaving the notes intact on disk but permanently unreachable.
 */
@RunWith(AndroidJUnit4::class)
class Migration4to5Test {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration_4_5_test.db"

    /** v5's exported DDL minus the contentFormat column, i.e. exactly what v4 shipped. */
    private val v4Notes = "CREATE TABLE IF NOT EXISTS `notes` (`id` TEXT NOT NULL, " +
        "`title` TEXT NOT NULL, `content` TEXT NOT NULL, `checklist` TEXT NOT NULL, " +
        "`isPinned` INTEGER NOT NULL, `isFavorite` INTEGER NOT NULL, `folderId` TEXT, " +
        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"

    private val v4Folders = "CREATE TABLE IF NOT EXISTS `folders` (`id` TEXT NOT NULL, " +
        "`name` TEXT NOT NULL, `colorArgb` INTEGER, PRIMARY KEY(`id`))"

    private val centeredHtml = "<p style=\"text-align:center;\">centered</p>"

    /** Note id to its v4 content, and the contentFormat the backfill must give it. */
    private val fixtures = listOf(
        // --- the shapes that used to throw ---
        Triple("crash-div", "<div", "plain"),
        Triple("crash-pre", "<pre", "plain"),
        Triple("crash-h1", "<h1", "plain"),
        Triple("crash-33", "<33", "plain"),
        Triple("crash-ws", "\n  <html", "plain"),
        // --- prose the old "contains a tag" heuristic destroyed ---
        Triple("email", "Email John <john@example.com> about Q3", "plain"),
        Triple("spec", "TODO <see attached spec> before Friday", "plain"),
        Triple("cmp", "if a<b> then", "plain"),
        // --- tags off the whitelist, so prose is free to open with them ---
        Triple("li-prose", "<li> must live in a <ul>", "plain"),
        Triple("body-prose", "<body> language matters", "plain"),
        // --- ordinary plain notes ---
        Triple("plain", "just some plain text", "plain"),
        Triple("empty", "", "plain"),
        Triple("multiline", "line one\nline two\n\nline four", "plain"),
        // --- genuine editor output, which must keep rendering as rich text ---
        Triple("html-p", "<p>hello</p>", "html"),
        Triple("html-h1", "<h1>Title</h1><p>body</p>", "html"),
        Triple("html-ul", "<ul><li>one</li><li>two</li></ul>", "html"),
        Triple("html-attr", centeredHtml, "html"),
        Triple("html-lead-ws", "\n  <p>hello</p>", "html"),
    )

    /** Bigger than the ~2MB CursorWindow the migration used to stream whole bodies through. */
    private val hugeContent = "<p>" + "x".repeat(3_000_000) + "</p>"

    @Before
    fun setUp() {
        ctx.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        ctx.deleteDatabase(dbName)
    }

    private fun seedV4() {
        val path = ctx.getDatabasePath(dbName)
        path.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(path, null)
        db.execSQL(v4Notes)
        db.execSQL(v4Folders)
        db.execSQL("INSERT INTO folders VALUES ('f1', 'Work', 123)")
        val insert = "INSERT INTO notes (id, title, content, checklist, isPinned, isFavorite, " +
            "folderId, createdAt, updatedAt) VALUES (?, ?, ?, '', 0, 0, 'f1', 1000, 2000)"
        fixtures.forEach { (id, content, _) ->
            db.execSQL(insert, arrayOf<Any>(id, "title-$id", content))
        }
        db.execSQL(insert, arrayOf<Any>("huge", "title-huge", hugeContent))
        db.execSQL("PRAGMA user_version = 4")
        db.close()
    }

    private fun openMigrated(): NoteDatabase =
        Room.databaseBuilder(ctx, NoteDatabase::class.java, dbName)
            .addMigrations(
                NoteDatabase.MIGRATION_1_2,
                NoteDatabase.MIGRATION_2_3,
                NoteDatabase.MIGRATION_3_4,
                NoteDatabase.MIGRATION_4_5,
                // Every migration from here on must be appended too. Room opens the database at
                // the CURRENT @Database version, so a v4 file has to walk the whole chain: leaving
                // one out throws "A migration from 4 to N was required but not found" and every
                // test in this class fails. That is exactly what happened when v6 landed — the
                // class still compiled, so nothing surfaced it until the suite was next executed.
                NoteDatabase.MIGRATION_5_6,
                NoteDatabase.MIGRATION_6_7,
                NoteDatabase.MIGRATION_7_8,
                NoteDatabase.MIGRATION_8_9,
            )
            .build()

    @Test
    fun migrationClassifiesEveryRowAndLeavesContentUntouched() {
        seedV4()
        val db = openMigrated()
        try {
            // Room opens lazily, so touching the helper is what actually runs the migration.
            assertEquals(NOTE_DATABASE_VERSION, db.openHelper.readableDatabase.version)

            fixtures.forEach { (id, content, expectedFormat) ->
                db.openHelper.readableDatabase.query(
                    "SELECT content, contentFormat FROM notes WHERE id = ?",
                    arrayOf<Any>(id),
                ).use { c ->
                    assertEquals("row $id missing after migration", 1, c.count)
                    c.moveToFirst()
                    assertEquals("content of $id was rewritten", content, c.getString(0))
                    assertEquals("format of $id", expectedFormat, c.getString(1))
                }
            }
        } finally {
            db.close()
        }
    }

    /**
     * A single note larger than the CursorWindow must not abort the migration. Asserted by length
     * and prefix rather than by pulling the body back, which is the same limit the migration has
     * to respect.
     */
    @Test
    fun aNoteLargerThanTheCursorWindowDoesNotAbortTheMigration() {
        seedV4()
        val db = openMigrated()
        try {
            db.openHelper.readableDatabase.query(
                "SELECT length(content), substr(content, 1, 3), contentFormat " +
                    "FROM notes WHERE id = 'huge'"
            ).use { c ->
                assertEquals(1, c.count)
                c.moveToFirst()
                assertEquals(hugeContent.length, c.getInt(0))
                assertEquals("<p>", c.getString(1))
                assertEquals("html", c.getString(2))
            }
        } finally {
            db.close()
        }
    }

    /** Nothing may be added or dropped: the backfill only labels rows. */
    @Test
    fun rowCountIsUnchanged() {
        seedV4()
        val db = openMigrated()
        try {
            db.openHelper.readableDatabase.query("SELECT count(*) FROM notes").use { c ->
                c.moveToFirst()
                assertEquals(fixtures.size + 1, c.getInt(0))
            }
            db.openHelper.readableDatabase.query("SELECT count(*) FROM folders").use { c ->
                c.moveToFirst()
                assertEquals(1, c.getInt(0))
            }
        } finally {
            db.close()
        }
    }
}
