package my.cheysoff.desktop.app

import my.cheysoff.core_crypto.sync.AccountRootKey
import my.cheysoff.core_crypto.sync.Base64Url
import my.cheysoff.core_sync_codec.RecordCodec
import my.cheysoff.core_sync_net.DeviceCredentials
import my.cheysoff.core_sync_net.http.ServerEndpoint
import my.cheysoff.desktop.keychain.NoCredentialStore
import my.cheysoff.desktop.store.RecordNotesRepository
import my.cheysoff.desktop.store.RecordStore
import my.cheysoff.desktop.sync.DesktopSyncService
import my.cheysoff.desktop.vault.AccountOrigin
import my.cheysoff.desktop.vault.DesktopVault
import my.cheysoff.desktop.vault.DeviceKeyPair
import my.cheysoff.desktop.vault.PairedEnrolment
import my.cheysoff.desktop.vault.SetupResult
import my.cheysoff.desktop.vault.UnlockResult
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Everything `AppController.open` does for a **paired** vault, with no window in the way.
 *
 * This exists because a crash reported as "failed to launch JVM after the passphrase" could not be
 * reproduced through the UI: driving a Compose window with synthetic keystrokes kept failing to
 * land, and a run that never unlocked looks exactly like a run that unlocked fine. Two attempts
 * "passed" while having done nothing at all.
 *
 * So the same sequence runs here directly. A standalone vault never touches the sync half, so the
 * vault under test is set up as PAIRED — which is the difference between the path that works and
 * the path that was reported broken.
 */
class OpenPairedVaultTest {

    @get:Rule val folder = TemporaryFolder()

    private val passphrase = "open-paired-vault-passphrase"

    @Test
    fun `a paired vault opens its store, repository and sync service`() {
        val vault = DesktopVault(
            directory = folder.newFolder("vault").toPath(),
            credentialStore = NoCredentialStore,
        )
        val created = vault.setUp(
            passphrase.toCharArray(),
            AccountOrigin.PAIRED,
            AccountRootKey.generateArk(),
            PairedEnrolment(
                serverUrl = "https://notes.example.com",
                deviceId = "device-id",
                deviceKey = DeviceKeyPair.generate(),
            ),
        )
        assertTrue("set up did not succeed: $created", created is SetupResult.Created)

        val unlocked = vault.unlock(passphrase.toCharArray())
        assertTrue("unlock did not succeed: $unlocked", unlocked is UnlockResult.Unlocked)
        val session = (unlocked as UnlockResult.Unlocked).session
        // JUnit's assertNotNull returns void, so it cannot be used to narrow the type here.
        assertNotNull("a paired vault must carry a sync identity", session.sync)
        val identity = session.sync!!

        // From here down this mirrors AppController.open, in order.
        val store = RecordStore.open(vault.recordsFile)
        val repository = RecordNotesRepository.load(
            store = store,
            codec = RecordCodec(session.accountKeys),
            node = session.hlcNode,
        )
        val service = DesktopSyncService(
            endpoint = ServerEndpoint(identity.serverUrl),
            deviceKey = identity.deviceKey,
            credentials = DeviceCredentials(
                accountId = Base64Url.encode(session.accountKeys.accountId),
                deviceId = identity.deviceId,
            ),
            codec = RecordCodec(session.accountKeys),
            store = store,
            arkProvider = { session.ark.copyOf() },
            clockObserver = repository.clockObserver,
        )

        assertNotNull(service)
        store.close()
    }
}
