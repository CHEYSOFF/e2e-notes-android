package my.cheysoff.desktop.vault

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * A one-time copy of the whole vault directory, taken by the first build whose editor is the
 * block editor, before that build opens the record store.
 *
 * **Why.** This build is the first that can rewrite note bodies as BLOCKS. A bug in the
 * conversion would damage the only copy of a note on this computer, and sync would then carry the
 * damage to the phone. A plain directory copy taken while nothing has the store open is a
 * consistent snapshot of `records.db` (and any SQLite side files) that the owner can restore by
 * hand, whatever goes wrong later.
 *
 * **Once.** A marker file, [MARKER], inside the vault says the copy was made. It is written only
 * after the copy completed, so a crash or a failure mid-copy leads to another attempt on the next
 * launch rather than to a vault that believes it is backed up. The marker is not in the copy.
 *
 * **Never at the price of starting.** A failed copy (disk full, a locked file) is logged and the
 * app opens anyway: refusing to open the notes would be worse than running without the snapshot,
 * and the next launch tries again.
 *
 * The copy sits beside the vault as `"$vaultDir-backup-blocks-<yyyyMMdd-HHmmss>"`, so it is in the
 * same per-user location and never inside the install directory (see [VaultLocation]).
 */
object BlocksBackup {

    /** Present in the vault directory once the backup has been made. */
    const val MARKER = ".blocks-backup-done"

    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /**
     * Runs [ensure], then [open]. The one entry point `main` uses, so the order "backup, then
     * store" is a property of this function rather than of the order of two lines in `main`.
     */
    fun <T> beforeOpening(
        vaultDir: Path,
        now: () -> LocalDateTime = LocalDateTime::now,
        copy: (from: Path, to: Path) -> Unit = ::copyTree,
        log: (String) -> Unit = { System.err.println(it) },
        open: () -> T,
    ): T {
        ensure(vaultDir, now, copy, log)
        return open()
    }

    /**
     * Makes the backup if [vaultDir] exists and has no [MARKER]. Returns the backup directory, or
     * null when none was made (already done, nothing to back up yet, or the copy failed).
     *
     * A vault directory that does not exist yet is a first run: there are no notes to protect,
     * and creating the directory here could change what the first-run screen sees, so nothing is
     * written. The next launch backs up the then-new vault.
     */
    fun ensure(
        vaultDir: Path,
        now: () -> LocalDateTime = LocalDateTime::now,
        copy: (from: Path, to: Path) -> Unit = ::copyTree,
        log: (String) -> Unit = { System.err.println(it) },
    ): Path? {
        if (!Files.isDirectory(vaultDir)) return null
        val marker = vaultDir.resolve(MARKER)
        if (Files.exists(marker)) return null
        val target = vaultDir.resolveSibling("${vaultDir.fileName}-backup-blocks-${now().format(STAMP)}")
        return try {
            copy(vaultDir, target)
            Files.writeString(marker, "Backup made at $target before the first BLOCKS-capable build opened this vault.\n")
            target
        } catch (e: Exception) {
            log("BlocksBackup: could not back up $vaultDir to $target, continuing without a backup: $e")
            null
        }
    }

    /** Copies [from] recursively to [to], which must not exist yet; never follows links out of the tree. */
    private fun copyTree(from: Path, to: Path) {
        if (Files.exists(to)) throw IOException("$to already exists")
        Files.walkFileTree(from, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.createDirectories(to.resolve(from.relativize(dir).toString()))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (file.fileName.toString() != MARKER) {
                    Files.copy(file, to.resolve(from.relativize(file).toString()), StandardCopyOption.COPY_ATTRIBUTES)
                }
                return FileVisitResult.CONTINUE
            }
        })
    }
}
