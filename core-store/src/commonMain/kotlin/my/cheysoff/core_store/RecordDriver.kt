package my.cheysoff.core_store

import app.cash.sqldelight.db.SqlDriver
import my.cheysoff.core_store.db.RecordDatabase

/**
 * Opens the device's record database, creating it if it does not exist.
 *
 * The one thing about this store that cannot be written portably, and the only unverified line of
 * code in this module — see the build script for why that is worth the arrangement it took.
 *
 * ## There is no passphrase here, and that is the design
 *
 * The Android store encrypts the whole database file with SQLCipher, because its rows are
 * plaintext: `notes.title` is a column with a title in it, and without file encryption anyone with
 * the file has the notes. This store has nothing to protect at the file level, because every row is
 * already a sealed `RecordEnvelope` — the file is exactly as informative as the server's copy, which
 * is to say a list of unlinkable IDs and a list of blob lengths rounded to 4 KiB.
 *
 * That is not "less secure with an excuse". It is a different and stronger place to draw the line:
 * SQLCipher protects the file and leaves each record readable once the file is open, while this
 * leaves the file readable and protects each record. A backup, a crash dump, an iCloud sync of the
 * container, or a future bug that copies the file somewhere is a non-event here and a disclosure
 * there.
 *
 * What it does mean is that the store is useless without `AccountKeys`, which is why [RecordStore]
 * takes them in its constructor rather than looking them up.
 */
internal expect fun openRecordDatabase(name: String): SqlDriver

/** The database, on the platform's default driver. */
fun recordDatabase(name: String = DEFAULT_DATABASE_NAME): RecordDatabase =
    RecordDatabase(openRecordDatabase(name))

/**
 * The file name.
 *
 * Not `notes.db`: that is what the Android build's SQLCipher database is called, and the two have
 * incompatible contents. A shared name would be a trap the first time someone tries to copy one
 * device's file to another to "migrate".
 */
const val DEFAULT_DATABASE_NAME: String = "manana-records.db"
