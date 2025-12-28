package my.cheysoff.core_crypto.data

import my.cheysoff.core_crypto.domain.AuthRepository
import my.cheysoff.core_crypto.domain.BiometricAuthenticationStatus
import javax.inject.Inject

class AuthRepositoryImpl @Inject constructor(
    private val biometricAuthenticator: BiometricAuthenticator
) : AuthRepository {

    override fun getBiometricAuthStatus(): BiometricAuthenticationStatus {
        return biometricAuthenticator.isBiometricAuthAvailable()
    }
}