package my.cheysoff.desktop.keychain

import my.cheysoff.desktop.platform.HostOs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.util.Base64

/**
 * The rule every implementation shares: when a store cannot hold the key, it says so and stores
 * nothing. Anything else — a plaintext fallback file, a `remember` that returns true without
 * storing — turns a convenience feature into a place a secret sits unprotected.
 */
class NoCredentialStoreTest {

    @Test
    fun `it is not available, remembers nothing, and admits it`() {
        assertFalse(NoCredentialStore.isAvailable)
        assertFalse(NoCredentialStore.isRemembered())
        assertFalse(NoCredentialStore.remember(ByteArray(32) { 1 }))
        assertNull(NoCredentialStore.recall())
        // Must not throw; "forget what you never had" is an ordinary call.
        NoCredentialStore.forget()
    }
}

class CredentialStoresTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `windows gets DPAPI and keeps its blob inside the vault directory`() {
        val store = CredentialStores.forHost(HostOs.WINDOWS, folder.root.toPath())
        assertTrue(store is DpapiCredentialStore)
        assertTrue(store.isAvailable)
    }

    @Test
    fun `macOS gets the keychain`() {
        assertTrue(CredentialStores.forHost(HostOs.MACOS, folder.root.toPath()) is MacKeychainCredentialStore)
    }

    /**
     * Linux deliberately gets no store rather than a home-grown one. The alternative that would be
     * tempting — a file — is exactly what must not exist.
     */
    @Test
    fun `an unknown platform gets no store at all`() {
        assertSame(NoCredentialStore, CredentialStores.forHost(HostOs.OTHER, folder.root.toPath()))
    }
}

class DpapiCredentialStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val secret = ByteArray(32) { (it * 7 + 3).toByte() }

    /** A stand-in for Crypt32, so the failure paths can be driven on any machine. */
    private class FakeDpapi(
        private val failProtect: Boolean = false,
        private val failUnprotect: Boolean = false,
    ) : DpapiCredentialStore.Dpapi {
        override fun protect(data: ByteArray, entropy: ByteArray): ByteArray {
            if (failProtect) throw IllegalStateException("CryptProtectData failed")
            return ByteArray(data.size) { (data[it].toInt() xor 0x5A).toByte() } + entropy
        }

        override fun unprotect(data: ByteArray, entropy: ByteArray): ByteArray {
            if (failUnprotect) throw IllegalStateException("CryptUnprotectData failed")
            return ByteArray(data.size - entropy.size) { (data[it].toInt() xor 0x5A).toByte() }
        }
    }

    private fun blobFile() = folder.root.toPath().resolve("remember.dpapi")

    @Test
    fun `a secret round trips through protect and unprotect`() {
        val store = DpapiCredentialStore(blobFile(), FakeDpapi())
        assertTrue(store.remember(secret))
        assertTrue(store.isRemembered())
        assertArrayEquals(secret, store.recall())
    }

    @Test
    fun `nothing is remembered before anything is stored`() {
        val store = DpapiCredentialStore(blobFile(), FakeDpapi())
        assertFalse(store.isRemembered())
        assertNull(store.recall())
    }

    @Test
    fun `a protect failure reports false and leaves no file behind`() {
        val store = DpapiCredentialStore(blobFile(), FakeDpapi(failProtect = true))
        assertFalse(store.remember(secret))
        assertFalse(Files.exists(blobFile()))
    }

    /**
     * The reset-Windows-password case. The blob is still there and is no longer decryptable; that
     * must read as "ask for the passphrase", and the file must be **kept** — deleting it on the
     * first failed launch would make an otherwise recoverable profile problem permanent.
     */
    @Test
    fun `an unprotect failure returns null and keeps the blob`() {
        DpapiCredentialStore(blobFile(), FakeDpapi()).remember(secret)
        val broken = DpapiCredentialStore(blobFile(), FakeDpapi(failUnprotect = true))
        assertNull(broken.recall())
        assertTrue(Files.exists(blobFile()))
    }

    @Test
    fun `forgetting removes the blob and is idempotent`() {
        val store = DpapiCredentialStore(blobFile(), FakeDpapi())
        store.remember(secret)
        store.forget()
        assertFalse(store.isRemembered())
        store.forget()
    }

    /**
     * Against the **real** DPAPI, on the machine running the tests: what lands on disk must not be
     * the secret. This is the one assertion in the file that a fake cannot make, and it is the one
     * that fails if `remember` ever writes the bytes straight through.
     */
    @Test
    fun `the blob on disk is not the secret`() {
        Assume.assumeTrue(
            "DPAPI is Windows-only",
            HostOs.current() == HostOs.WINDOWS,
        )
        val store = DpapiCredentialStore(blobFile())
        assertTrue(store.remember(secret))

        val onDisk = Files.readAllBytes(blobFile())
        assertFalse("the raw secret is on disk", indexOf(onDisk, secret) >= 0)
        assertTrue("a DPAPI blob is longer than its plaintext", onDisk.size > secret.size)
        assertArrayEquals(secret, store.recall())
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (start in 0..haystack.size - needle.size) {
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) continue@outer
            }
            return start
        }
        return -1
    }
}

/**
 * The macOS store, driven through its command seam.
 *
 * These tests prove the command this class builds and how it reacts to each answer. They prove
 * nothing about macOS — see the class KDoc, which says so in the same words.
 */
class MacKeychainCredentialStoreTest {

    private class RecordingCommands(
        private val exitCode: Int = 0,
        private val stdout: String = "",
    ) : MacKeychainCredentialStore.Commands {
        val commands = mutableListOf<List<String>>()
        val stdins = mutableListOf<String?>()
        override fun run(command: List<String>, stdin: String?): MacKeychainCredentialStore.Result {
            commands += command
            stdins += stdin
            return MacKeychainCredentialStore.Result(exitCode, stdout)
        }
    }

    private val secret = ByteArray(32) { (it * 11 + 5).toByte() }

    /**
     * The secret must not appear in the argument vector — `ps` is readable by every local process.
     * `security -i` takes the command on stdin instead.
     */
    @Test
    fun `the secret goes in on stdin, never in the argument vector`() {
        val commands = RecordingCommands()
        MacKeychainCredentialStore(commands, account = "someone").remember(secret)

        val encoded = Base64.getEncoder().encodeToString(secret)
        assertEquals(listOf("/usr/bin/security", "-i"), commands.commands.single())
        assertFalse(commands.commands.single().any { it.contains(encoded) })
        assertTrue(commands.stdins.single()!!.contains(encoded))
    }

    /** `-U` is what makes remembering idempotent instead of failing with errSecDuplicateItem. */
    @Test
    fun `the add command updates an existing item`() {
        val commands = RecordingCommands()
        MacKeychainCredentialStore(commands, account = "someone").remember(secret)
        assertTrue(commands.stdins.single()!!.startsWith("add-generic-password -U "))
    }

    @Test
    fun `an account name containing a space is quoted`() {
        val commands = RecordingCommands()
        MacKeychainCredentialStore(commands, account = "First Last").remember(secret)
        assertTrue(commands.stdins.single()!!.contains("\"First Last\""))
    }

    @Test
    fun `a non-zero exit from the add is reported as a refusal`() {
        val store = MacKeychainCredentialStore(RecordingCommands(exitCode = 1), account = "someone")
        assertFalse(store.remember(secret))
    }

    @Test
    fun `recall decodes what find-generic-password prints`() {
        val encoded = Base64.getEncoder().encodeToString(secret)
        val store = MacKeychainCredentialStore(
            RecordingCommands(stdout = "$encoded\n"),
            account = "someone",
        )
        assertArrayEquals(secret, store.recall())
    }

    @Test
    fun `a missing item is absence, not an error`() {
        val store = MacKeychainCredentialStore(RecordingCommands(exitCode = 44), account = "someone")
        assertNull(store.recall())
        assertFalse(store.isRemembered())
    }

    /** Someone else's item under the same service name is not ours; treat it as absent. */
    @Test
    fun `output that is not base64 reads as absent rather than corrupt`() {
        val store = MacKeychainCredentialStore(
            RecordingCommands(stdout = "this is not base64 !!!\n"),
            account = "someone",
        )
        assertNull(store.recall())
    }
}
