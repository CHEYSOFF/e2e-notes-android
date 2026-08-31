package my.cheysoff.notes.sync

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import my.cheysoff.core_sync_net.auth.DeviceLabelSealer
import my.cheysoff.core_sync_net.auth.DeviceSigner
import javax.inject.Singleton

/**
 * Binds the sync transport's two key-holding seams: signing, onto the AndroidKeyStore key
 * `:feature-pairing` owns, and device-label sealing, onto the Account Root Key
 * `SecureUnlockManager` guards.
 *
 * This is the whole of the sync transport's dependency injection today, and deliberately so. There
 * is no binding for `SyncApi` itself, because building one needs a `ServerEndpoint`, and a
 * `ServerEndpoint` needs a server URL the user has configured -- which nothing in the app can
 * supply yet. The sync coordinator that will own that configuration is later work; it constructs a
 * client with `SyncHttpClient.create(endpoint, signer, labelSealer)` and injects these bindings.
 *
 * Providing a `SyncApi` now would mean either inventing a default server address -- for an app
 * whose premise is talking to nobody -- or a provider that throws.
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
}
