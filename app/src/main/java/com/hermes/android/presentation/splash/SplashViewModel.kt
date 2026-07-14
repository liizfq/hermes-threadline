package com.hermes.android.presentation.splash

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.android.data.repository.MatrixRepository
import com.hermes.android.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "SplashVM"

@HiltViewModel
class SplashViewModel @Inject constructor(
    private val matrixRepository: MatrixRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    sealed class SplashState {
        object Loading : SplashState()
        object LoggedIn : SplashState()
        object NotLoggedIn : SplashState()
    }

    private val _splashState = MutableStateFlow<SplashState>(SplashState.Loading)
    val splashState: StateFlow<SplashState> = _splashState

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Unhandled coroutine exception", throwable)
    }

    init {
        checkSession()
    }

    private fun checkSession() {
        viewModelScope.launch(exceptionHandler) {
            if (!settingsRepository.isLoggedIn()) {
                _splashState.value = SplashState.NotLoggedIn
                return@launch
            }


            // Activity recreation (process survived from background kill):
            // the client is still alive, skip restoreSession to avoid
            // re-creating Client and a loading flash.
            if (matrixRepository.getClient() != null) {
                _splashState.value = SplashState.LoggedIn
                return@launch
            }

            try {
                val result = matrixRepository.restoreSession()
                if (result != null && result.isSuccess) {
                    _splashState.value = SplashState.LoggedIn
                } else {
                    _splashState.value = SplashState.NotLoggedIn
                }
            } catch (e: Exception) {
                Log.e(TAG, "restoreSession failed (token expired or network error)", e)
                settingsRepository.clear()
                _splashState.value = SplashState.NotLoggedIn
            }
        }
    }
}
