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
 * Separate from [PairingSeamModule] on purpose: this is a real, finished binding to real
 * AndroidKeyStore code, and it must not be read as one of the Phase-1 placeholders that module
 * holds.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PairingIdentityModule {

    @Binds
    @Singleton
    abstract fun bindDeviceIdentity(impl: DeviceIdentityKey): DeviceIdentity
}
