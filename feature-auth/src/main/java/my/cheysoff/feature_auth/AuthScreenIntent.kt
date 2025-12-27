package my.cheysoff.feature_auth

sealed class AuthScreenIntent {
    class InitializeIntent() : AuthScreenIntent()
    class BiometricsLoginClickIntent() : AuthScreenIntent()
    class PinLoginClickIntent() : AuthScreenIntent()
}