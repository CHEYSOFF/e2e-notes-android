package my.cheysoff.feature_auth

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import my.cheysoff.core_crypto.domain.AuthRepository
import my.cheysoff.core_crypto.domain.BiometricAuthenticationStatus
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {
    private val _state = MutableStateFlow(AuthScreenState(areBiometricsEnabled = true)) // todo fix
    val state = _state.asStateFlow()

    fun processIntent(intent: AuthScreenIntent) {
        when (intent) {
            is AuthScreenIntent.BiometricsLoginClickIntent -> {
                startBiometricLogin(intent.activity)
            }

            is AuthScreenIntent.PinLoginClickIntent -> {
                // todo
            }

            is AuthScreenIntent.InitializeIntent -> {
                // todo
            }
        }
    }

    private fun startBiometricLogin(activity: FragmentActivity) {
        viewModelScope.launch {
            val status = authRepository.getBiometricAuthStatus()
            if (status != BiometricAuthenticationStatus.READY) {
                _state.emit(
                    _state.value.copy(
                        error = "Biometric authentication is not ready.",
                        isLoading = false
                    )
                )
                return@launch
            }

            val authManager = BiometricAuthManager(
                fragmentActivity = activity,
                onSuccess = {
                    viewModelScope.launch {
                        _state.emit(_state.value.copy(isLoading = false))
                    }
                    // todo navigator
                },
                onFailed = {
                    viewModelScope.launch {
                        _state.emit(
                            _state.value.copy(
                                error = "Fingerprint or face id doesn't match",
                                isLoading = false
                            )
                        )
                    }
                },
                onError = { _, message ->
                    viewModelScope.launch {
                        _state.emit(
                            _state.value.copy(
                                error = message.toString(),
                                isLoading = false
                            )
                        )
                    }
                }
            )

            authManager.showBiometricPrompt(
                title = "Biometric Login",
                subtitle = "Log in using your biometric credential",
                negativeButtonText = "Cancel"
            )
        }
    }

}
