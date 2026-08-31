package my.cheysoff.core_data

import my.cheysoff.core_data.data.local.NOTE_DATABASE_VERSION
import my.cheysoff.core_data.data.local.NoteDatabase
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The migration chain is complete, on the JVM, on every `./gradlew test`.
 *
 * Room only discovers a missing migration when it opens a database that has to walk past the gap —
 * on a device, on an install that predates the bump, which is the one configuration nobody tests
 * before merging. This asserts the same thing without a device: `ALL_MIGRATIONS` must be exactly
 * 1 -> 2 -> ... -> [NOTE_DATABASE_VERSION], in order.
 *
 * What it catches: bumping `NOTE_DATABASE_VERSION` without writing the migration, writing the
 * migration without adding it to `ALL_MIGRATIONS`, and adding it without bumping the version. Any
 * of those three is a failed upgrade for every existing install.
 *
 * What it does NOT catch, and should not be read as covering: whether a migration's SQL is
 * *correct*. That needs a real database and stays the job of the instrumented `Migration*Test`
 * classes in `src/androidTest` — see docs/design/running-the-tests.md for how to run them.
 */
class MigrationChainTest {

    @Test
    fun theMigrationChainRunsUnbrokenToTheCurrentSchemaVersion() {
        val expected = (1 until NOTE_DATABASE_VERSION).map { it to it + 1 }
        val actual = NoteDatabase.ALL_MIGRATIONS.map { it.startVersion to it.endVersion }

        assertEquals(expected, actual)
    }
}
