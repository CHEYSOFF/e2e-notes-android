package my.cheysoff.core_store

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import my.cheysoff.core_store.db.RecordDatabase

/**
 * The JVM: SQLDelight over sqlite-jdbc.
 *
 * `JdbcSqliteDriver` does not create the schema by itself the way the native driver does — it has
 * no `schema` parameter — so [RecordDatabase.Schema] is applied here. Doing it unconditionally is
 * safe only because the schema is at version 1 and every statement in it would fail on a second
 * run; the moment there is a `.sqm` migration this has to become a version check against
 * `user_version`, and the comment is here so that is not discovered by a corrupted database.
 *
 * Compiled and run: every test in this module goes through this function. It is what stands in for
 * the Apple driver on a machine that cannot build one.
 */
internal actual fun openRecordDatabase(name: String): SqlDriver {
    val url = if (name == IN_MEMORY) JdbcSqliteDriver.IN_MEMORY else "jdbc:sqlite:$name"
    val driver = JdbcSqliteDriver(url)
    if (isNewDatabase(driver)) {
        RecordDatabase.Schema.create(driver)
        driver.execute(null, "PRAGMA user_version = ${RecordDatabase.Schema.version}", 0)
    }
    return driver
}

/** Pass as the database name for a database that lives only as long as the driver. */
const val IN_MEMORY: String = ":memory:"

/**
 * True for a database SQLite has just created.
 *
 * `user_version` is 0 in a fresh file and is set by [openRecordDatabase] once the schema is
 * created, which is the standard way to tell the two apart without a round trip through
 * `sqlite_master`.
 */
private fun isNewDatabase(driver: SqlDriver): Boolean =
    driver.executeQuery(
        identifier = null,
        sql = "PRAGMA user_version",
        mapper = { cursor ->
            app.cash.sqldelight.db.QueryResult.Value(
                if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L
            )
        },
        parameters = 0,
    ).value == 0L
