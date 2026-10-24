package my.cheysoff.desktop.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime

/**
 * The one-time copy of the vault taken before the first build that can write BLOCKS bodies opens
 * the record store: once per vault, before the store, and never at the price of not starting.
 */
class BlocksBackupTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val at = LocalDateTime.of(2026, 10, 24, 13, 5, 9)

    private fun vault(): Path {
        val dir = temp.root.toPath().resolve("Manana-vault")
        Files.createDirectories(dir.resolve("sub"))
        Files.writeString(dir.resolve("records.db"), "records")
        Files.writeString(dir.resolve("vault.json"), "header")
        Files.writeString(dir.resolve("sub").resolve("nested.txt"), "nested")
        return dir
    }

    private fun backups(): List<Path> = Files.list(temp.root.toPath()).use { s ->
        s.filter { it.fileName.toString().startsWith("Manana-vault-backup-blocks-") }.toList()
    }

    @Test
    fun `backup runs once and before the store opens`() {
        val dir = vault()
        val events = mutableListOf<String>()

        val opened = BlocksBackup.beforeOpening(dir, now = { at }) {
            // What the store would see the moment it opens: the copy is complete and marked.
            events += "open"
            assertTrue(Files.exists(dir.resolve(BlocksBackup.MARKER)))
            assertEquals(1, backups().size)
            "store"
        }
        assertEquals("store", opened)

        val copy = temp.root.toPath().resolve("Manana-vault-backup-blocks-20261024-130509")
        assertEquals(listOf(copy), backups())
        assertEquals("records", Files.readString(copy.resolve("records.db")))
        assertEquals("header", Files.readString(copy.resolve("vault.json")))
        assertEquals("nested", Files.readString(copy.resolve("sub").resolve("nested.txt")))
        assertFalse("the marker is written after the copy, so the copy never holds it", Files.exists(copy.resolve(BlocksBackup.MARKER)))

        // A second launch, a minute later: the marker says it is done.
        BlocksBackup.beforeOpening(dir, now = { at.plusMinutes(1) }) { events += "open" }
        assertEquals(listOf(copy), backups())
        assertEquals(listOf("open", "open"), events)
    }

    @Test
    fun `a failed copy writes no marker and the store still opens`() {
        val dir = vault()
        val logged = mutableListOf<String>()

        val opened = BlocksBackup.beforeOpening(
            dir,
            now = { at },
            copy = { _, _ -> throw IOException("disk full") },
            log = { logged += it },
        ) { "store" }

        assertEquals("store", opened)
        assertFalse(Files.exists(dir.resolve(BlocksBackup.MARKER)))
        assertTrue(logged.single().contains("disk full"))
    }

    @Test
    fun `no vault directory yet means nothing to back up`() {
        val dir = temp.root.toPath().resolve("absent")

        assertNull(BlocksBackup.ensure(dir, now = { at }))

        assertFalse(Files.exists(dir))
        assertTrue(backups().isEmpty())
    }
}
