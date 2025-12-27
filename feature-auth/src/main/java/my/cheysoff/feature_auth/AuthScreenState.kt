package my.cheysoff.feature_auth

data class AuthScreenState (
    val hasAccount: Boolean = false,
    val areBiometricsEnabled: Boolean = false,
    val isPinEnabled: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
)