package my.cheysoff.core_crypto.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import my.cheysoff.core_crypto.EncryptedPrefsStore
import my.cheysoff.core_crypto.KeystoreEncryptedPrefsStore
import my.cheysoff.core_crypto.domain.AuthRepository
import my.cheysoff.core_crypto.data.AuthRepositoryImpl
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(
        authRepositoryImpl: AuthRepositoryImpl
    ): AuthRepository

    /**
     * The app always gets the real Keystore-backed store; the interface exists so that
     * SecureUnlockManager's key-loss classification is reachable from a JVM test, not so that
     * anything in production can swap it. See [EncryptedPrefsStore].
     */
    @Binds
    @Singleton
    abstract fun bindEncryptedPrefsStore(
        keystoreEncryptedPrefsStore: KeystoreEncryptedPrefsStore
    ): EncryptedPrefsStore

}