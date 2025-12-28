package my.cheysoff.feature_auth

import androidx.fragment.app.FragmentActivity

sealed class AuthScreenIntent {
    object InitializeIntent: AuthScreenIntent()
    data class BiometricsLoginClickIntent(val activity: FragmentActivity) : AuthScreenIntent()
    object PinLoginClickIntent : AuthScreenIntent()
}