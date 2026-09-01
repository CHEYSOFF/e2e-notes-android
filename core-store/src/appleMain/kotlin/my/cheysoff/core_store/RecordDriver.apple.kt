package my.cheysoff.core_store

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import my.cheysoff.core_store.db.RecordDatabase

/**
 * iOS and macOS: SQLDelight's native driver, over SQLiter, over the SQLite that ships in the OS.
 *
 * NOT COMPILED. It is, however, the *only* line of this module that has not been exercised: every
 * other line — `RecordStore`, `RecordNotesRepository`, `NoteRecords`, the schema and its queries —
 * runs against a real SQLite database in `jvmTest` on the machine this was written on. See the
 * build script.
 *
 * The database lands in the app's Documents directory, which is where SQLiter puts it by default.
 * Two consequences worth knowing before the first App Store submission, neither of which this file
 * can decide alone:
 *
 *  - **Documents is backed up to iCloud and to iTunes/Finder.** That backup is a copy of this file,
 *    and this file is a table of sealed envelopes, so it discloses no note content — the same
 *    argument `RecordDriver.kt` makes about the file generally. It does disclose how many records
 *    the account has and roughly how large each is, which is exactly what the server already knows.
 *  - **Documents is user-visible if the app ever sets `UIFileSharingEnabled`.** It does not, and
 *    should not.
 *
 * If either becomes unwanted, the fix is `NativeSqliteDriver`'s `DatabaseConfiguration` with an
 * explicit path under Application Support, plus `NSURLIsExcludedFromBackupKey`. That is a change to
 * make deliberately, on a Mac, with the migration of an existing file thought through — not a
 * default to flip.
 */
internal actual fun openRecordDatabase(name: String): SqlDriver =
    NativeSqliteDriver(schema = RecordDatabase.Schema, name = name)
