package my.cheysoff.notes.sync

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import my.cheysoff.core_domain.repository.SyncSettingsRepository
import my.cheysoff.core_domain.sync.SyncController
import my.cheysoff.core_domain.sync.SyncTransportStatus
import my.cheysoff.core_sync_net.auth.DeviceLabelSealer
import my.cheysoff.core_sync_net.auth.DeviceSigner
import my.cheysoff.feature_pairing.di.PairedServerStore
import my.cheysoff.feature_pairing.identity.DeviceEnroller
import javax.inject.Singleton

/**
 * Everything the sync transport needs from dependency injection, in one place.
 *
 * Five bindings, in the order they became possible:
 *
 *  - **[DeviceSigner]** onto the AndroidKeyStore key `:feature-pairing` owns.
 *  - **[DeviceLabelSealer]** onto the Account Root Key `SecureUnlockManager` guards.
 *  - **[SyncSettingsRepository]** — the server address the user set, which is the piece that was
 *    missing. Until it existed, `SyncHttpClient.create(endpoint, signer, labelSealer)` had no
 *    `endpoint` to be called with and therefore no caller at all.
 *  - **[SyncTransportProvider]** — a `SyncApi` for that address, or a stated reason there is none.
 *  - **[SyncTransportStatus]** — the narrow, transport-free view of the two above that the
 *    settings screen consumes, so `:feature-settings` sees neither `SyncApi` nor the pairing key.
 *
 * ## Why the fourth binding is a provider and not a `SyncApi`
 *
 * A `@Provides SyncApi` must return one, and the server address is a setting that may be absent.
 * Every way of forcing an object out of that state is worse than saying so in the type: throwing
 * makes every injection site a crash site, a no-op client reports "the server has no records" for
 * a device that never asked a server anything, and a client that throws on every call cannot be
 * built honestly because `SyncException` is sealed in `:core-sync-net` and has no "not configured"
 * case. [SyncTransportProvider] states the reasoning in full; the short version is that
 * "not configured" is a real state of this app and it belongs in the return type where a caller
 * has to look at it.
 *
 * ## What is still not here
 *
 * A sync engine. Nothing pushes, pulls, merges or schedules; the coordinator that will do so is
 * separate work, and it injects [SyncTransportProvider]. Building a client opens no connection —
 * OkHttp connects on the first call — so an app that is paired and configured still sends nothing
 * until something calls a method on the returned client. Today the only thing that does is the
 * settings screen's "Check server" action.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SyncTransportModule {

    @Binds
    @Singleton
    abstract fun bindDeviceSigner(impl: KeystoreDeviceSigner): DeviceSigner

    @Binds
    @Singleton
    abstract fun bindDeviceLabelSealer(impl: ArkDeviceLabelSealer): DeviceLabelSealer

    @Binds
    @Singleton
    abstract fun bindSyncSettingsRepository(
        impl: DataStoreSyncSettingsRepository,
    ): SyncSettingsRepository

    @Binds
    @Singleton
    abstract fun bindSyncTransportProvider(
        impl: DefaultSyncTransportProvider,
    ): SyncTransportProvider

    @Binds
    @Singleton
    abstract fun bindSyncTransportStatus(impl: AppSyncTransportStatus): SyncTransportStatus

    /**
     * Vouching for a device that has just shown this phone its key.
     *
     * `:feature-pairing` declares the seam and cannot implement it: it can see neither the account
     * key's owner nor the HTTP client, and giving the pairing screen either would put a server
     * connection in the class that holds the ARK. The same argument [DeviceSigner] and
     * [DeviceLabelSealer] already make.
     */
    @Binds
    @Singleton
    abstract fun bindDeviceEnroller(impl: SyncDeviceEnroller): DeviceEnroller

    /**
     * Where a pairing's server configuration is written on the device that received it.
     *
     * The other half of [DeviceEnroller]'s seam, and in this module for the same reason: the
     * address and the id come out of a sealed bundle in `:feature-pairing`, and the two stores they
     * belong in are here.
     */
    @Binds
    @Singleton
    abstract fun bindPairedServerStore(impl: SyncPairedServerStore): PairedServerStore

    /**
     * The sync engine's entry point, as the two feature modules are allowed to see it.
     *
     * `:feature-notes` and `:feature-settings` depend on `:core-domain` and `:core-ui` only, so
     * neither can see `SyncApi`, the account keys or Room. `SyncController` is the whole of what a
     * pull-to-refresh gesture and a status line need, and `:app` is the only module that can build
     * one — the same argument every other binding in this module makes.
     */
    @Binds
    @Singleton
    abstract fun bindSyncController(impl: DefaultSyncController): SyncController
}
