package com.hermes.android.presentation.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.android.data.repository.MatrixRepository
import com.hermes.android.data.repository.SettingsRepository
import com.hermes.android.push.PushChannel
import com.hermes.android.push.PushSettings
import com.hermes.android.ui.settings.LocaleManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PushSettingsState(
    val enabled: Boolean = false,
    val channel: String = PushChannel.SYSTEM,
    val timeoutMinutes: Int = PushSettings.DEFAULT_TIMEOUT_MIN,
    val ntfyServerUrl: String = "",
    val saved: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    application: Application,
    private val settingsRepository: SettingsRepository,
    private val matrixRepository: MatrixRepository,
    private val pushSettings: PushSettings
) : AndroidViewModel(application) {

    val boundRoomId: StateFlow<String?> = settingsRepository.observeBoundRoom()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), settingsRepository.getBoundRoomId())

    val homeserverUrl: String? = settingsRepository.getHomeserverUrl()
    val userId: String? = settingsRepository.getUserId()

    private val _logoutState = MutableStateFlow<LogoutState>(LogoutState.Idle)
    val logoutState: StateFlow<LogoutState> = _logoutState.asStateFlow()

    private val _pushState = MutableStateFlow(loadPushState())
    val pushState: StateFlow<PushSettingsState> = _pushState.asStateFlow()

    private val _language = MutableStateFlow(settingsRepository.getLanguage())
    val language: StateFlow<String> = _language.asStateFlow()

    private fun loadPushState(): PushSettingsState = PushSettingsState(
        enabled = pushSettings.enabled,
        channel = pushSettings.channel,
        timeoutMinutes = pushSettings.timeoutMinutes,
        ntfyServerUrl = pushSettings.ntfyServerUrl
    )

    fun updatePush(transform: (PushSettingsState) -> PushSettingsState) {
        _pushState.value = transform(_pushState.value).copy(saved = false)
    }

    fun savePushSettings() {
        val s = _pushState.value
        viewModelScope.launch {
            pushSettings.enabled = s.enabled
            pushSettings.channel = s.channel
            pushSettings.timeoutMinutes = s.timeoutMinutes
            pushSettings.ntfyServerUrl = s.ntfyServerUrl
            _pushState.value = s.copy(saved = true)
            matrixRepository.registerPusher()
        }
    }

    fun saveBoundRoom(roomId: String) {
        viewModelScope.launch { settingsRepository.saveBoundRoomId(roomId) }
    }

    fun logout() {
        viewModelScope.launch {
            _logoutState.value = LogoutState.Loading
            try {
                matrixRepository.logout()
                _logoutState.value = LogoutState.Success
            } catch (e: Exception) {
                _logoutState.value = LogoutState.Error(
                    if (LocaleManager.current.value == LocaleManager.ZH) "登出失败" else "Logout failed"
                )
            }
        }
    }

    fun setLanguage(locale: String) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            settingsRepository.setLanguage(locale)
            LocaleManager.setLocale(app, locale)
            _language.value = locale
        }
    }

    sealed class LogoutState {
        data object Idle : LogoutState()
        data object Loading : LogoutState()
        data object Success : LogoutState()
        data class Error(val message: String) : LogoutState()
    }
}
