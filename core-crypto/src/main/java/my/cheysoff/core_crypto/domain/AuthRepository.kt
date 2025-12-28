package my.cheysoff.core_crypto.domain

interface AuthRepository {

    fun getBiometricAuthStatus(): BiometricAuthenticationStatus

}