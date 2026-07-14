package com.hermes.android.presentation.sessionlist

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.android.data.repository.RoomRepository
import com.hermes.android.data.repository.SessionRepository
import com.hermes.android.data.repository.SettingsRepository
import com.hermes.android.domain.model.Session
import com.hermes.android.presentation.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val TAG = "SessionListVM"

@HiltViewModel
class SessionListViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val roomRepository: RoomRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val searchQuery = MutableStateFlow("")
    val isSearchActive = MutableStateFlow(false)

    val boundRoomId: StateFlow<String?> = settingsRepository.observeBoundRoom()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), settingsRepository.getBoundRoomId())

    val sessions: StateFlow<UiState<List<Session>>> = flow {
        val roomId = settingsRepository.observeBoundRoom().filterNotNull().first()
        Log.d(TAG, "sessions flow: roomId=$roomId")
        val room = withContext(Dispatchers.IO) {
            roomRepository.getRoom(roomId)
        }
        Log.d(TAG, "sessions flow: room=${room?.id() ?: "null"}")
        if (room != null) {
            emitAll(sessionRepository.observeSessions(room))
        }
    }
            .map<List<Session>, UiState<List<Session>>> {
                Log.d(TAG, "sessions updated: ${it.size} items")
                UiState.Success(it)
            }
            .catch {
                Log.e(TAG, "sessions flow error", it)
                emit(UiState.Error(it.message ?: "Unknown error"))
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, UiState.Loading)

    val filteredSessions: StateFlow<UiState<List<Session>>> =
        combine(sessions, searchQuery) { state, query ->
            if (query.isBlank()) {
                state
            } else {
                when (state) {
                    is UiState.Success -> {
                        val filtered = state.data.filter { session ->
                            session.title.contains(query, ignoreCase = true) ||
                                (session.lastMessage?.contains(query, ignoreCase = true) == true)
                        }
                        UiState.Success(filtered)
                    }
                    else -> state
                }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState.Loading)

    fun refresh() {
        viewModelScope.launch {
            Log.d(TAG, "refresh: triggering thread list refresh")
            sessionRepository.refreshSessions()
        }
    }

    fun createNewSession(
        message: String,
        title: String? = null,
        attachmentUri: Uri? = null,
        attachmentType: String? = null,
        context: Context? = null,
        onResult: (Boolean) -> Unit
    ) {
        Log.d(TAG, "createNewSession called: message=${message.take(50)}, title=$title, attachment=$attachmentType")
        viewModelScope.launch {
            val roomId = settingsRepository.getBoundRoomId()
            Log.d(TAG, "boundRoomId: $roomId")
            if (roomId == null) {
                Log.e(TAG, "No room bound!")
                onResult(false)
                return@launch
            }
            val room = withContext(Dispatchers.IO) {
                roomRepository.getRoom(roomId)
            }
            Log.d(TAG, "getRoom result: ${room?.id() ?: "null"}")
            if (room == null) {
                Log.e(TAG, "Room not found for $roomId")
                onResult(false)
                return@launch
            }
            Log.d(TAG, "Calling createSession...")
            val result = withContext(Dispatchers.IO) {
                sessionRepository.createSession(room, message, title, attachmentUri, attachmentType, context?.applicationContext)
            }
            Log.d(TAG, "createSession result: success=${result.isSuccess}, eventId=${result.getOrNull()}, error=${result.exceptionOrNull()?.message ?: ""}")
            // SDK's ThreadListService doesn't auto-discover new threads via sync.
            // Delayed re-pagination to pick up the new thread after bot replies.
            if (result.isSuccess) {
                val eventId = result.getOrNull()
                if (!title.isNullOrBlank() && !eventId.isNullOrBlank()) {
                    Log.d(TAG, "createNewSession: saving title for eventId=$eventId")
                    settingsRepository.saveSessionTitle(eventId, title)
                }
                viewModelScope.launch {
                    delay(5000)
                    Log.d(TAG, "createNewSession: triggering delayed refresh")
                    sessionRepository.refreshSessions()
                    delay(10000)
                    Log.d(TAG, "createNewSession: second refresh attempt")
                    sessionRepository.refreshSessions()
                }
            }
            onResult(result.isSuccess)
        }
    }

    fun openSearch() {
        isSearchActive.value = true
    }

    fun closeSearch() {
        isSearchActive.value = false
        searchQuery.value = ""
    }

    fun onSearchQueryChange(query: String) {
        searchQuery.value = query
    }

    fun getSessionTitles(): Map<String, String> = settingsRepository.getAllSessionTitles()

    fun markSessionRead(sessionId: String) {
        settingsRepository.saveSessionReadTimestamp(sessionId, System.currentTimeMillis())
    }

    fun deleteSession(session: Session, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val roomId = settingsRepository.getBoundRoomId()
            if (roomId == null) {
                onResult(false)
                return@launch
            }
            val room = withContext(Dispatchers.IO) {
                roomRepository.getRoom(roomId)
            }
            if (room == null) {
                onResult(false)
                return@launch
            }
            val result = sessionRepository.deleteSession(room, session.id)
            if (result.isSuccess) {
                settingsRepository.deleteSessionTitle(session.id)
            }
            onResult(result.isSuccess)
        }
    }
}
