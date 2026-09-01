package my.cheysoff.core_data.data.sync

import android.util.Log
import my.cheysoff.core_data.data.local.NoteDatabase
import java.io.File

/**
 * A copy of the encrypted database, taken once, immediately before this device pulls from a sync
 * server for the first time.
 *
 * ## Why this exists
 *
 * It is the mitigation the architecture doc's risk table asks for against its highest-severity
 * risk, and the risk is worth restating plainly: **with sync, one merge bug damages every device at
 * once, and there is no undo.** Every other bug in this app is a bug on one phone. A merge that
 * decides the server's empty account beats a full local library propagates that decision to every
 * paired device in seconds, and the Trash does not help — a merge does not go through it.
 *
 * The first pull is where that risk is concentrated. Before it, the library is only local and only
 * this device's writes have ever touched it. After it, every pass is incremental and the account
 * has a history to argue from. So one copy, at the one moment, is most of the protection available
 * for a small fraction of the cost of continuous backups.
 *
 * ## What it is and is not
 *
 * It is a byte copy of `notes.db`, still SQLCipher-encrypted under the same passphrase, sitting
 * beside it in the app's private databases directory. It is **not** a backup product: nothing
 * restores it automatically, it is invisible in the UI, and it is one file rather than a history.
 * Restoring it is a deliberate act — stop the app, swap the file — and that is the intended shape.
 * An automatic restore would be a second write path into the user's library, which is one more than
 * this design can defend.
 *
 * It is taken **once per account** and never overwritten. A second snapshot would replace the
 * pre-sync library with a post-sync one, which is precisely the thing it exists to preserve.
 *
 * ## The checkpoint
 *
 * SQLite in WAL mode keeps recent commits in `notes.db-wal`, not in `notes.db`. Copying the main
 * file without checkpointing first would produce a database missing everything written since the
 * last automatic checkpoint — most likely the notes the user wrote today. `wal_checkpoint(TRUNCATE)`
 * folds the log back into the main file and empties it, so the single file that is copied is a
 * complete database.
 */
class SyncSnapshot(private val database: NoteDatabase) {

    /**
     * Takes the snapshot if this account has never had one, and returns the file it wrote or null
     * if there was nothing to do.
     *
     * **Never throws.** A snapshot that cannot be written is a reason to be careful, not a reason to
     * refuse to sync — the alternative is an app that will not sync because its disk is full — so a
     * failure is logged and swallowed and the caller carries on. The one thing it must not do is
     * report success it did not have, which is why the return value is the file rather than a
     * boolean.
     */
    fun takeOnce(accountId: String): File? {
        val main = File(database.openHelper.writableDatabase.path ?: return null)
        val target = File(main.parentFile, "${main.name}$SUFFIX${fingerprint(accountId)}")
        if (target.exists()) return null

        return try {
            // TRUNCATE rather than PASSIVE: PASSIVE gives up if any reader is active, and gives up
            // silently, which would leave a copy missing the most recent commits while reporting
            // that it had been taken.
            database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            // Written under a temporary name and renamed, so a snapshot interrupted half way
            // through cannot be mistaken for a complete one by the `exists()` check above -- which
            // is the check that decides never to take another.
            val partial = File(target.path + PARTIAL_SUFFIX)
            main.copyTo(partial, overwrite = true)
            if (!partial.renameTo(target)) {
                partial.delete()
                return null
            }
            target
        } catch (e: Exception) {
            Log.w(TAG, "Could not take the pre-sync snapshot; syncing anyway", e)
            null
        }
    }

    /**
     * A short, stable, non-reversible stand-in for the account handle, so two accounts on one
     * install get two snapshots and neither filename discloses the handle to anything that can
     * read a directory listing.
     */
    private fun fingerprint(accountId: String): String =
        accountId.hashCode().toUInt().toString(16).padStart(8, '0')

    private companion object {
        const val TAG = "SyncSnapshot"
        const val SUFFIX = ".presync-"
        const val PARTIAL_SUFFIX = ".partial"
    }
}
