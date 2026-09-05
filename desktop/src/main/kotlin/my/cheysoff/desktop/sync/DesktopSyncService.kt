package my.cheysoff.desktop.sync

import my.cheysoff.core_sync_codec.EnvelopeSyncTransport
import my.cheysoff.core_sync_codec.RecordCodec
import my.cheysoff.core_sync_engine.ClockObserver
import my.cheysoff.core_sync_engine.SyncEngine
import my.cheysoff.core_sync_engine.SyncOutcome
import my.cheysoff.core_sync_net.DeviceCredentials
import my.cheysoff.core_sync_net.SyncHttpClient
import my.cheysoff.core_sync_net.http.ServerEndpoint
import my.cheysoff.desktop.store.RecordStore
import my.cheysoff.desktop.vault.DeviceKeyPair

/**
 * Assembles the sync engine for this computer and runs a pass.
 *
 * Everything below the assembly already existed and is shared with the phone: the engine is pure
 * `commonMain`, the transport and the codec are the same classes the Android app builds. This class
 * is the wiring and nothing else, which is why it holds no state of its own and no policy — a
 * second place deciding when to sync, or how to merge, is how two platforms start disagreeing.
 *
 * **Foreground only, and one pass per call.** There is no timer and no background service here, for
 * the reason the architecture gives: a desktop that syncs while locked would need key material
 * available while locked, and lock-on-close is one of this app's genuinely strong properties. A
 * pass runs when the vault opens and when the user asks.
 */
class DesktopSyncService(
    endpoint: ServerEndpoint,
    deviceKey: DeviceKeyPair,
    credentials: DeviceCredentials,
    codec: RecordCodec,
    store: RecordStore,
    /**
     * Hands back a **copy** of the account root key, which the label sealer zeroes after each use.
     * A provider returning the live array would have it wiped underneath its owner.
     */
    arkProvider: () -> ByteArray?,
    /**
     * The generator local writes draw from — `RecordNotesRepository.clockObserver`, and not a new
     * one. See its documentation: an observer that is not the writer's own generator drifts.
     */
    clockObserver: ClockObserver,
) {

    private val syncStore = RecordSyncStore(store, codec, credentials.accountId)

    private val engine = SyncEngine(
        store = syncStore,
        transport = EnvelopeSyncTransport(
            api = SyncHttpClient.create(
                endpoint = endpoint,
                signer = DesktopDeviceSigner(deviceKey),
                labelSealer = VaultDeviceLabelSealer(arkProvider),
            ),
            credentials = credentials,
            codec = codec,
            // The store is asked, rather than the engine being told, because `createdAt` is not a
            // merged field: a record this device already holds keeps the value it was created with,
            // and only a record arriving for the first time needs one invented.
            createdAtOf = syncStore::createdAtOf,
            // Asked of the store for the same reason, and one more: `meta` is opaque to this
            // build, so pushing a hardcoded `""` would erase whatever a newer build wrote there.
            metaOf = syncStore::metaOf,
        ),
        clock = clockObserver,
    )

    /**
     * Runs one pass and reports what happened.
     *
     * The outcome is returned rather than logged. A pass that halts — a server that rolled back, a
     * device that was revoked — is something the person using this computer has to be told about,
     * and a halt is persisted, so swallowing it here would leave the app silently never syncing
     * again with nothing on screen to explain why.
     */
    suspend fun syncOnce(): SyncOutcome = engine.runPass()

    /**
     * Forgets a recorded halt and runs one pass. Only in response to a person asking -- see
     * `SyncStore.clearHalt` for why this repairs nothing and is still worth having.
     *
     * Cleared here rather than as a separate call the caller makes first, so that "clear it and
     * look again" cannot be split across two awaits with a pass slipping between them.
     */
    suspend fun clearHaltAndSyncOnce(): SyncOutcome {
        syncStore.clearHalt()
        return engine.runPass()
    }
}
