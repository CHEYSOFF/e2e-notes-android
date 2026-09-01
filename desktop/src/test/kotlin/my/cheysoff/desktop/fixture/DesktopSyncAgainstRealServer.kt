package my.cheysoff.desktop.fixture

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import my.cheysoff.core_crypto.sync.AccountKeys
import my.cheysoff.core_crypto.sync.AccountRootKey
import my.cheysoff.core_crypto.sync.Base64Url
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_sync_codec.RecordCodec
import my.cheysoff.core_sync_engine.SyncOutcome
import my.cheysoff.core_sync_net.ClaimOutcome
import my.cheysoff.core_sync_net.DeviceCredentials
import my.cheysoff.core_sync_net.SyncHttpClient
import my.cheysoff.core_sync_net.http.ServerEndpoint
import my.cheysoff.desktop.store.RecordNotesRepository
import my.cheysoff.desktop.store.RecordStore
import my.cheysoff.desktop.sync.DesktopDeviceSigner
import my.cheysoff.desktop.sync.DesktopSyncService
import my.cheysoff.desktop.sync.VaultDeviceLabelSealer
import my.cheysoff.desktop.vault.DeviceKeyPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.SecureRandom
import java.util.UUID

/**
 * Two desktop vaults, one account, one real server: a note written on the first appears on the
 * second.
 *
 * This is the claim the whole sync effort exists to support, and it is the one that cannot be made
 * by a unit test. Everything here is production code — the real `SyncEngine`, the real
 * `EnvelopeSyncTransport` over real HTTP, the real record store on two real SQLite files. Only the
 * account claim is arranged by hand, because enrolling the second device by vouching is the phone's
 * job and there is no phone in this process.
 *
 * Skipped unless a server address is given, so an ordinary test run costs nothing:
 *
 * ```
 * cd server && ./gradlew run          # or installDist && build/install/server/bin/server
 * ./gradlew :desktop:test --tests '*DesktopSyncAgainstRealServer*' \
 *     -Dmanana.syncServer=http://127.0.0.1:8477
 * ```
 */
class DesktopSyncAgainstRealServer {

    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `a note written on one desktop arrives at another through the server`() = runBlocking {
        val address = System.getProperty("manana.syncServer")
        assumeNotNull(address)
        val endpoint = ServerEndpoint(address)

        // One account, shared by both vaults exactly as pairing would have shared it.
        val ark = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val keys = AccountRootKey.derive(ark)
        val accountId = Base64Url.encode(keys.accountId)

        // The first device claims the account, and that claim enrols it.
        val firstKey = DeviceKeyPair.generate()
        val claim = api(endpoint, firstKey, ark).claimAccount(accountId, "laptop-one")
        assertTrue("the server refused the claim: $claim", claim is ClaimOutcome.Claimed)
        val firstDeviceId = (claim as ClaimOutcome.Claimed).deviceId

        // The second is vouched for BY the first, signed with the first device's key -- which is
        // what pairing arranges between a phone and a laptop. A device cannot enrol itself, and
        // that is the property being exercised here rather than worked around.
        val secondKey = DeviceKeyPair.generate()
        val enrolled = api(endpoint, firstKey, ark).authorizeDevice(
            accountId = accountId,
            voucherDeviceId = firstDeviceId,
            newPublicKey = secondKey.publicKeySec1,
            deviceLabel = "laptop-two",
        )
        println("vouched: deviceId=${enrolled.deviceId}")

        val one = vault("one", keys, ark, firstKey, firstDeviceId, endpoint, accountId)
        val two = vault("two", keys, ark, secondKey, enrolled.deviceId, endpoint, accountId)

        // -- the note ---------------------------------------------------------------------------
        val id = UUID.randomUUID().toString()
        one.repository.saveNote(
            Note(id = id, title = "From the first laptop", content = "typed on one, read on two")
        )

        val pushed = one.service.syncOnce()
        println("push pass: $pushed")
        assertTrue("the first laptop did not finish its pass: $pushed", pushed is SyncOutcome.Completed)

        val pulled = two.service.syncOnce()
        println("pull pass: $pulled")
        assertTrue("the second laptop did not finish its pass: $pulled", pulled is SyncOutcome.Completed)

        // Re-read from disk rather than from the in-memory snapshot the pass ran against: the point
        // is that the note is *stored* on the second laptop, not merely that it passed through it.
        val arrived = RecordNotesRepository
            .load(two.store, two.codec, node = "two")
            .getNoteById(id)
            .first()
        assertEquals("From the first laptop", arrived?.title)
        assertEquals("typed on one, read on two", arrived?.content)
        println("the note crossed: ${arrived?.title}")
    }

    private class Vault(
        val store: RecordStore,
        val codec: RecordCodec,
        val repository: RecordNotesRepository,
        val service: DesktopSyncService,
    )

    private fun api(endpoint: ServerEndpoint, key: DeviceKeyPair, ark: ByteArray) =
        SyncHttpClient.create(
            endpoint = endpoint,
            signer = DesktopDeviceSigner(key),
            labelSealer = VaultDeviceLabelSealer { ark.copyOf() },
        )

    private fun vault(
        name: String,
        keys: AccountKeys,
        ark: ByteArray,
        deviceKey: DeviceKeyPair,
        deviceId: String,
        endpoint: ServerEndpoint,
        accountId: String,
    ): Vault {
        val store = RecordStore.open(folder.newFolder(name).toPath().resolve("records.db"))
        val codec = RecordCodec(keys)
        val repository = RecordNotesRepository.load(store, codec, node = name)
        return Vault(
            store = store,
            codec = codec,
            repository = repository,
            service = DesktopSyncService(
                endpoint = endpoint,
                deviceKey = deviceKey,
                credentials = DeviceCredentials(accountId, deviceId),
                codec = codec,
                store = store,
                arkProvider = { ark.copyOf() },
                clockObserver = repository.clockObserver,
            ),
        )
    }
}
