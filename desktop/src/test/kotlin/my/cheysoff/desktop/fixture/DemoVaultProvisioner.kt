package my.cheysoff.desktop.fixture

import my.cheysoff.core_sync_codec.RecordCodec
import kotlinx.coroutines.runBlocking
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NoteContentFormat
import my.cheysoff.desktop.keychain.NoCredentialStore
import my.cheysoff.desktop.store.RecordNotesRepository
import my.cheysoff.desktop.store.RecordStore
import my.cheysoff.desktop.vault.AccountOrigin
import my.cheysoff.desktop.vault.DesktopVault
import my.cheysoff.desktop.vault.SetupResult
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.nio.file.Paths
import java.util.UUID

/**
 * A developer fixture, not a test of anything.
 *
 * It builds a real vault -- real passphrase wrap, real ARK, real sealed records on disk -- at a
 * path given on the command line, so that the unlocked workspace can actually be launched and
 * looked at:
 *
 * ```
 * ./gradlew :desktop:test --tests '*DemoVaultProvisioner*' -Dmanana.demoVault=C:\path\to\vault
 * ./gradlew :desktop:run --args="--vault-dir C:\path\to\vault"
 * ```
 *
 * Without that system property it skips, so it costs an ordinary test run nothing. It exists
 * because the alternative for screenshotting the post-unlock UI is driving a passphrase dialog
 * with synthetic keystrokes, which tests the automation rather than the app.
 */
class DemoVaultProvisioner {

    @Test
    fun provision() {
        val target = System.getProperty("manana.demoVault")
        assumeNotNull(target)

        val directory = Paths.get(target).toAbsolutePath()
        val vault = DesktopVault(directory = directory, credentialStore = NoCredentialStore)
        val created = vault.setUp(PASSPHRASE.toCharArray(), AccountOrigin.CREATED_HERE)
        check(created is SetupResult.Created) { "could not set up a vault at $directory: $created" }

        val session = created.session
        val repository = RecordNotesRepository.load(
            store = RecordStore.open(vault.recordsFile),
            codec = RecordCodec(session.accountKeys),
            node = session.hlcNode,
        )

        val now = System.currentTimeMillis()
        runBlocking {
            seed(repository, "Groceries", "Milk, eggs, coffee. The good coffee, not the one from the corner shop.", now - 22 * 60_000, pinned = true)
            seed(repository, "Standup notes", "Blocked: waiting on the pairing QR spec. Shipped the trash retention pass.", now - 3 * 3_600_000, pinned = true)
            seed(repository, "Ideas", "A notes app that never sees your notes. Keys never leave the device.", now - 24 * 3_600_000)
            seed(repository, "Flat viewing — Thursday", "18:30, ask about the boiler and whether the rent includes the parking space.", now - 4L * 24 * 3_600_000)
            seed(repository, "Wifi", "guest network <ask reception> — rotates monthly", now - 6L * 24 * 3_600_000)
        }
        println("provisioned $directory with 5 notes; passphrase is $PASSPHRASE")
    }

    private suspend fun seed(
        repository: RecordNotesRepository,
        title: String,
        body: String,
        at: Long,
        pinned: Boolean = false,
    ) {
        repository.saveNote(
            Note(
                id = UUID.randomUUID().toString(),
                title = title,
                content = body,
                contentFormat = NoteContentFormat.PLAIN,
                isPinned = pinned,
                createdAt = at,
                updatedAt = at,
            )
        )
    }

    private companion object {
        // Long enough to satisfy PassphrasePolicy; obviously a fixture, so nobody is tempted to
        // reuse it for anything that holds real notes.
        const val PASSPHRASE = "demo-vault-passphrase"
    }
}
