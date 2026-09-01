package my.cheysoff.desktop.vault

import my.cheysoff.core_crypto.sync.AccountRootKey
import my.cheysoff.core_crypto.sync.SyncProtocol
import my.cheysoff.desktop.keychain.CredentialStore
import my.cheysoff.desktop.keychain.NoCredentialStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

/** An in-memory credential store, so the keychain rules can be driven without an OS. */
private class FakeCredentialStore(
    override val isAvailable: Boolean = true,
    private val refuseWrites: Boolean = false,
) : CredentialStore {
    var stored: ByteArray? = null
    override val description = "fake store"
    override fun isRemembered() = stored != null
    override fun remember(secret: ByteArray): Boolean {
        if (refuseWrites) return false
        stored = secret.copyOf()
        return true
    }

    override fun recall(): ByteArray? = stored?.copyOf()
    override fun forget() {
        stored = null
    }
}

class DesktopVaultTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val passphrase get() = "correct-horse-battery".toCharArray()

    private fun vault(store: CredentialStore = NoCredentialStore) =
        DesktopVault(folder.root.toPath().resolve("vault"), store)

    // -------------------------------------------------------------------------------------------
    // Setup and unlock
    // -------------------------------------------------------------------------------------------

    @Test
    fun `a fresh directory is not set up`() {
        assertFalse(vault().isSetUp())
    }

    @Test
    fun `set up then unlock with the same passphrase yields the same account keys`() {
        val vault = vault()
        val created = vault.setUp(passphrase, AccountOrigin.CREATED_HERE) as SetupResult.Created
        val accountId = created.session.accountKeys.accountId.copyOf()
        val kContent = created.session.accountKeys.kContent.copyOf()

        val unlocked = vault.unlock(passphrase) as UnlockResult.Unlocked
        assertArrayEquals(accountId, unlocked.session.accountKeys.accountId)
        assertArrayEquals(kContent, unlocked.session.accountKeys.kContent)
    }

    @Test
    fun `the wrong passphrase does not open the vault`() {
        val vault = vault()
        vault.setUp(passphrase, AccountOrigin.CREATED_HERE)
        assertEquals(UnlockResult.WrongPassphrase, vault.unlock("wrong-horse-battery".toCharArray()))
    }

    @Test
    fun `unlocking a directory with no vault reports NotSetUp rather than a wrong passphrase`() {
        assertEquals(UnlockResult.NotSetUp, vault().unlock(passphrase))
    }

    @Test
    fun `a passphrase that fails the policy creates nothing`() {
        val vault = vault()
        val result = vault.setUp("123456".toCharArray(), AccountOrigin.CREATED_HERE)
        assertTrue(result is SetupResult.Rejected)
        assertFalse(vault.isSetUp())
    }

    @Test
    fun `the vault is written with the desktop iteration count`() {
        val vault = vault()
        vault.setUp(passphrase, AccountOrigin.CREATED_HERE)
        val header = VaultHeader.decode(Files.readAllBytes(vault.headerFile))
        assertEquals(PassphrasePolicy.ITERATIONS, header!!.keyWrap.iterations)
    }

    // -------------------------------------------------------------------------------------------
    // The one rule: exactly one ARK, ever
    // -------------------------------------------------------------------------------------------

    /**
     * The account-forking guard. A second setup on the same directory must not mint a second ARK —
     * doing so would leave every existing record sealed under a key nothing can derive again.
     */
    @Test
    fun `a second setup is refused and leaves the first account intact`() {
        val vault = vault()
        val first = vault.setUp(passphrase, AccountOrigin.CREATED_HERE) as SetupResult.Created
        val accountId = first.session.accountKeys.accountId.copyOf()

        assertEquals(
            SetupResult.AlreadySetUp,
            vault.setUp("another-passphrase-entirely".toCharArray(), AccountOrigin.CREATED_HERE),
        )

        val unlocked = vault.unlock(passphrase) as UnlockResult.Unlocked
        assertArrayEquals(accountId, unlocked.session.accountKeys.accountId)
    }

    /**
     * Two separate first runs must produce different accounts — that is the whole hazard the
     * first-run screen warns about, and this pins that it is real rather than rhetorical.
     */
    @Test
    fun `two standalone vaults are different accounts`() {
        val a = DesktopVault(folder.newFolder("a").toPath(), NoCredentialStore)
        val b = DesktopVault(folder.newFolder("b").toPath(), NoCredentialStore)
        val first = a.setUp(passphrase, AccountOrigin.CREATED_HERE) as SetupResult.Created
        val second = b.setUp(passphrase, AccountOrigin.CREATED_HERE) as SetupResult.Created
        assertNotEquals(
            first.session.accountKeys.accountId.toList(),
            second.session.accountKeys.accountId.toList(),
        )
    }

    /**
     * The pairing seam. A paired device adopts the ARK it was handed; it must derive exactly the
     * account keys the other device has, or the two hold notes neither can read.
     */
    @Test
    fun `a paired setup adopts the supplied ARK rather than minting one`() {
        val phoneArk = AccountRootKey.generateArk()
        val phoneKeys = AccountRootKey.derive(phoneArk)

        val vault = vault()
        val created = vault.setUp(passphrase, AccountOrigin.PAIRED, phoneArk) as SetupResult.Created

        assertEquals(AccountOrigin.PAIRED, created.origin)
        assertArrayEquals(phoneKeys.accountId, created.session.accountKeys.accountId)
        assertArrayEquals(phoneKeys.kContent, created.session.accountKeys.kContent)
        assertArrayEquals(phoneKeys.kId, created.session.accountKeys.kId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `pairing without an ARK is a programming error, not a silent standalone setup`() {
        vault().setUp(passphrase, AccountOrigin.PAIRED, ark = null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a standalone setup may not be handed an ARK`() {
        vault().setUp(passphrase, AccountOrigin.CREATED_HERE, AccountRootKey.generateArk())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an ARK of the wrong length is refused`() {
        vault().setUp(passphrase, AccountOrigin.PAIRED, ByteArray(SyncProtocol.ARK_BYTES - 1))
    }

    // -------------------------------------------------------------------------------------------
    // Damage
    // -------------------------------------------------------------------------------------------

    /**
     * A header that will not parse must NOT read as "no vault here", because that is the state in
     * which the app would offer first-run and mint a replacement ARK over an existing account.
     */
    @Test
    fun `an unreadable header is Damaged, not NotSetUp`() {
        val vault = vault()
        vault.setUp(passphrase, AccountOrigin.CREATED_HERE)
        Files.write(vault.headerFile, "{ not a header }".toByteArray())

        val result = vault.unlock(passphrase)
        assertTrue("expected Damaged, was $result", result is UnlockResult.Damaged)
    }

    /**
     * The passphrase opens the outer wrap but the ARK does not come out — a half-restored file.
     * Retyping cannot help, so it must not present as a wrong passphrase.
     */
    @Test
    fun `an ARK that will not unwrap is Damaged, not WrongPassphrase`() {
        val vault = vault()
        vault.setUp(passphrase, AccountOrigin.CREATED_HERE)
        val header = VaultHeader.decode(Files.readAllBytes(vault.headerFile))!!
        header.arkWrap.ciphertext[0] = (header.arkWrap.ciphertext[0] + 1).toByte()
        Files.write(vault.headerFile, VaultHeader.encode(header))

        val result = vault.unlock(passphrase)
        assertTrue("expected Damaged, was $result", result is UnlockResult.Damaged)
    }

    // -------------------------------------------------------------------------------------------
    // The credential store is a convenience layer, never a replacement
    // -------------------------------------------------------------------------------------------

    @Test
    fun `a remembered vault opens without the passphrase`() {
        val store = FakeCredentialStore()
        val vault = vault(store)
        val created = vault.setUp(passphrase, AccountOrigin.CREATED_HERE) as SetupResult.Created
        assertTrue(vault.rememberOnThisComputer(created.session))

        val session = vault.unlockFromCredentialStore()
        assertNotNull(session)
        assertArrayEquals(created.session.accountKeys.accountId, session!!.accountKeys.accountId)
    }

    /**
     * **The invariant.** Remembering the key must not weaken, replace or remove the passphrase
     * wrap — otherwise the vault becomes machine-bound and a copy of the directory is a brick.
     */
    @Test
    fun `the passphrase still opens a remembered vault`() {
        val store = FakeCredentialStore()
        val vault = vault(store)
        val created = vault.setUp(passphrase, AccountOrigin.CREATED_HERE) as SetupResult.Created
        vault.rememberOnThisComputer(created.session)

        assertTrue(vault.unlock(passphrase) is UnlockResult.Unlocked)
    }

    /**
     * Moving the directory to a machine whose credential store knows nothing about it. This is the
     * scenario the whole "never a replacement" rule exists for.
     */
    @Test
    fun `a vault copied to a machine with no stored key still opens with the passphrase`() {
        val store = FakeCredentialStore()
        val vault = vault(store)
        val created = vault.setUp(passphrase, AccountOrigin.CREATED_HERE) as SetupResult.Created
        vault.rememberOnThisComputer(created.session)

        // Same files, different machine: a credential store that has never seen this vault.
        val elsewhere = DesktopVault(vault.directory, FakeCredentialStore())
        assertNull(elsewhere.unlockFromCredentialStore())
        assertTrue(elsewhere.unlock(passphrase) is UnlockResult.Unlocked)
    }

    @Test
    fun `a credential store that refuses reports false rather than pretending`() {
        val vault = vault(FakeCredentialStore(refuseWrites = true))
        val created = vault.setUp(passphrase, AccountOrigin.CREATED_HERE) as SetupResult.Created
        assertFalse(vault.rememberOnThisComputer(created.session))
        assertNull(vault.unlockFromCredentialStore())
    }

    @Test
    fun `an unavailable credential store is never asked to remember anything`() {
        val store = FakeCredentialStore(isAvailable = false)
        val vault = vault(store)
        val created = vault.setUp(passphrase, AccountOrigin.CREATED_HERE) as SetupResult.Created
        assertFalse(vault.rememberOnThisComputer(created.session))
        assertNull(store.stored)
        assertNull(vault.credentialStoreDescription)
    }

    @Test
    fun `forgetting removes the stored key and leaves the passphrase working`() {
        val store = FakeCredentialStore()
        val vault = vault(store)
        val created = vault.setUp(passphrase, AccountOrigin.CREATED_HERE) as SetupResult.Created
        vault.rememberOnThisComputer(created.session)
        vault.forgetOnThisComputer()

        assertFalse(vault.isRemembered())
        assertNull(vault.unlockFromCredentialStore())
        assertTrue(vault.unlock(passphrase) is UnlockResult.Unlocked)
    }

    /**
     * A stored key that no longer matches the header — the vault was recreated, or the store holds
     * something else entirely. It must be treated as "ask for the passphrase", not as damage.
     */
    @Test
    fun `a stale stored key falls back to the passphrase rather than reporting damage`() {
        val store = FakeCredentialStore()
        val vault = vault(store)
        vault.setUp(passphrase, AccountOrigin.CREATED_HERE)
        store.stored = ByteArray(SyncProtocol.DERIVED_KEY_BYTES) { 0x7A }

        assertNull(vault.unlockFromCredentialStore())
        assertTrue(vault.unlock(passphrase) is UnlockResult.Unlocked)
    }

    // -------------------------------------------------------------------------------------------
    // Session hygiene
    // -------------------------------------------------------------------------------------------

    @Test
    fun `closing a session zeroes the key material it holds`() {
        val vault = vault()
        val created = vault.setUp(passphrase, AccountOrigin.CREATED_HERE) as SetupResult.Created
        val session = created.session
        session.close()

        assertTrue(session.ark.all { it == 0.toByte() })
        assertTrue(session.vaultKey.all { it == 0.toByte() })
        assertTrue(session.accountKeys.kContent.all { it == 0.toByte() })
        assertTrue(session.accountKeys.kId.all { it == 0.toByte() })
    }

    @Test
    fun `the hlc node is derived from the ARK and is stable across unlocks`() {
        val vault = vault()
        val created = vault.setUp(passphrase, AccountOrigin.CREATED_HERE) as SetupResult.Created
        val node = created.session.hlcNode
        val unlocked = vault.unlock(passphrase) as UnlockResult.Unlocked

        assertEquals(node, unlocked.session.hlcNode)
        assertTrue(node.isNotEmpty())
    }
}
