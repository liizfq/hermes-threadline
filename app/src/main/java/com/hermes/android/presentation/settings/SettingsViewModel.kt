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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class PushSettingsState(
    val enabled: Boolean = false,
    val channel: String = PushChannel.SYSTEM,
    val timeoutMinutes: Int = PushSettings.DEFAULT_TIMEOUT_MIN,
    val ntfyServerUrl: String = "",
    val saved: Boolean = false
)

/** A joined room surfaced in the Settings checkbox list. */
data class RoomDisplayInfo(
    val roomId: String,
    val displayName: String
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

    /** User-selected push/drawer room set; reactive over [SettingsRepository.observePushRoomIds]. */
    val pushRoomIds: StateFlow<Set<String>> = settingsRepository.observePushRoomIds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), settingsRepository.getPushRoomIds())

    /** All rooms the logged-in user has joined (drives the checkbox list). */
    private val _availableRooms = MutableStateFlow<List<RoomDisplayInfo>>(emptyList())
    val availableRooms: StateFlow<List<RoomDisplayInfo>> = _availableRooms.asStateFlow()

    private val _roomsLoading = MutableStateFlow(true)
    val roomsLoading: StateFlow<Boolean> = _roomsLoading.asStateFlow()

    init {
        loadAvailableRooms()
    }

    /**
     * Fetch the joined-room list straight from the Matrix SDK client. There is
     * no repository wrapper for this yet, so we go through [MatrixRepository.getClient]
     * as the brief allows. Runs on Dispatchers.IO since the FFI calls may touch
     * the store; failures degrade to an empty list (UI shows the empty state).
     */
    private fun loadAvailableRooms() {
        viewModelScope.launch {
            _roomsLoading.value = true
            try {
                val rooms = withContext(Dispatchers.IO) {
                    matrixRepository.getClient()
                        ?.rooms()
                        .orEmpty()
                        .map { room ->
                            RoomDisplayInfo(
                                roomId = room.id(),
                                displayName = room.displayName()
                                    ?.takeUnless { it.isBlank() }
                                    ?: room.id()
                            )
                        }
                        .sortedBy { it.displayName.lowercase() }
                }
                _availableRooms.value = rooms
            } catch (e: Exception) {
                _availableRooms.value = emptyList()
            } finally {
                _roomsLoading.value = false
            }
        }
    }

    /**
     * Add/remove [roomId] in the push set. Removing the last selected room is
     * blocked (the push set must stay non-empty); the UI surfaces the
     * "at least one room" hint in that case.
     */
    fun togglePushRoom(roomId: String) {
        viewModelScope.launch {
            val current = pushRoomIds.value
            val next = if (roomId in current) current - roomId else current + roomId
            if (next.isEmpty()) return@launch
            settingsRepository.replacePushRoomIds(next)
        }
    }

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
