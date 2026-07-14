package com.hermes.android.presentation.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.android.data.repository.MatrixRepository
import com.hermes.android.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class LoginState {
    object Idle : LoginState()
    object Loading : LoginState()
    object Success : LoginState()
    data class Error(val message: String) : LoginState()
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val matrixRepository: MatrixRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _loginState = MutableStateFlow<LoginState>(LoginState.Idle)
    val loginState: StateFlow<LoginState> = _loginState

    fun login(homeserverUrl: String, username: String, password: String) {
        viewModelScope.launch {
            _loginState.value = LoginState.Loading
            val result = matrixRepository.login(homeserverUrl, username, password)
            result.fold(
                onSuccess = { _loginState.value = LoginState.Success },
                onFailure = { _loginState.value = LoginState.Error(it.message ?: "Login failed") }
            )
        }
    }

    fun getSavedHomeserverUrl(): String = settingsRepository.getHomeserverUrl() ?: ""
}
