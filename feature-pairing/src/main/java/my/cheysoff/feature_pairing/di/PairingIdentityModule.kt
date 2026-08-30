package my.cheysoff.feature_pairing.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import my.cheysoff.feature_pairing.identity.DeviceIdentity
import my.cheysoff.feature_pairing.identity.DeviceIdentityKey
import javax.inject.Singleton

/**
 * Binds the device identity key.
 *
 * Separate from [PairingSeamModule] on purpose: that module is the seam onto `:core-crypto`'s key
 * hierarchy, and this is a binding onto AndroidKeyStore code inside this module. Keeping them
 * apart means a reviewer can see at a glance which bindings cross a module boundary.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PairingIdentityModule {

    @Binds
    @Singleton
    abstract fun bindDeviceIdentity(impl: DeviceIdentityKey): DeviceIdentity
}
