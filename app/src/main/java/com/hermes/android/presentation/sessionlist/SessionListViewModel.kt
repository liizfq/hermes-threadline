package com.hermes.android.presentation.sessionlist

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.android.data.repository.ActiveThreadStore
import com.hermes.android.data.repository.MatrixRepository
import com.hermes.android.data.repository.RoomRepository
import com.hermes.android.data.repository.RoomSessionListStore
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
import kotlinx.coroutines.ExperimentalCoroutinesApi

private const val TAG = "SessionListVM"

/** A push-set room surfaced in the SessionListScreen navigation drawer. */
data class RoomDrawerItem(
    val roomId: String,
    val displayName: String
)

@HiltViewModel
class SessionListViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val roomRepository: RoomRepository,
    private val settingsRepository: SettingsRepository,
    private val matrixRepository: MatrixRepository,
    private val roomSessionListStore: RoomSessionListStore,
    private val activeThreadStore: ActiveThreadStore
) : ViewModel() {

    val searchQuery = MutableStateFlow("")
    val isSearchActive = MutableStateFlow(false)

    val boundRoomId: StateFlow<String?> = settingsRepository.observeBoundRoom()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), settingsRepository.getBoundRoomId())

    /**
     * Push-set rooms shown in the navigation drawer. The displayName map is
     * rebuilt from the SDK client's joined-room list whenever the push set
     * changes; [Room.id] is the fallback when displayName is null/blank.
     */
    val drawerRooms: StateFlow<List<RoomDrawerItem>> = settingsRepository.observePushRoomIds()
        .map { ids -> resolveRoomNames(ids) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Display name of the currently active room (fallback: app title / roomId). */
    val activeRoomDisplayName: StateFlow<String> = settingsRepository.observeBoundRoom()
        .map { id -> resolveRoomName(id) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            settingsRepository.getBoundRoomId()?.let { resolveRoomName(it) }
                ?: "Hermes Threadline"
        )

    /**
     * Resolve a room's display name from the SDK client's joined rooms.
     * Returns the roomId when the room can't be found (cold client, left
     * room) so the user always sees a non-empty identifier.
     */
    private fun resolveRoomName(roomId: String?): String {
        if (roomId == null) return "Hermes Threadline"
        val room = runCatching {
            matrixRepository.getClient()?.rooms()?.firstOrNull { it.id() == roomId }
        }.getOrNull()
        return room?.displayName()?.takeIf { it.isNotBlank() } ?: roomId
    }

    /** Build the drawer list, preserving the push set's iteration order. */
    private fun resolveRoomNames(ids: Set<String>): List<RoomDrawerItem> {
        val rooms = runCatching {
            matrixRepository.getClient()?.rooms().orEmpty()
        }.getOrNull().orEmpty()
        val byId = rooms.associateBy { it.id() }
        return ids.map { id ->
            RoomDrawerItem(
                roomId = id,
                displayName = byId[id]?.displayName()?.takeIf { it.isNotBlank() } ?: id
            )
        }
    }

    /**
     * Switch the active room to [roomId]. Chains the three phase-A primitives:
     * persist active room (no-op if not in push set) → switch the session
     * projection → close the now-stale thread timeline. The [sessions] flow
     * recomputes from [settingsRepository.observeBoundRoom] when the active
     * room id actually changes.
     */
    fun setActiveRoom(roomId: String) {
        viewModelScope.launch {
            if (roomId == settingsRepository.getBoundRoomId()) return@launch
            settingsRepository.setActiveRoom(roomId)
            roomSessionListStore.setActiveProjection(roomId)
            activeThreadStore.closeActive()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val sessions: StateFlow<UiState<List<Session>>> = settingsRepository.observeBoundRoom()
        .filterNotNull()
        .distinctUntilChanged()
        .flatMapLatest { roomId ->
            flow {
                Log.d(TAG, "sessions flow: roomId=$roomId")
                val room = withContext(Dispatchers.IO) {
                    roomRepository.getRoom(roomId)
                }
                Log.d(TAG, "sessions flow: room=${room?.id() ?: "null"}")
                if (room != null) {
                    // Bind the application-scoped store to this room. The
                    // store survives UI collector churn; subsequent collectors
                    // (and ChatViewModel reconcile) read the same state. On
                    // Start/Switch the store takes ownership of `room`; on
                    // NoOp (duplicate handle) the caller owns it and must
                    // close it to avoid leaking the SDK handle.
                    val tookOwnership = sessionRepository.ensureSessionsStarted(room, roomId)
                    if (!tookOwnership) {
                        try { room.close() } catch (_: Exception) {}
                    }
                    emitAll(sessionRepository.observeSessions())
                } else {
                    emit(emptyList())
                }
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
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState.Loading)

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
                } else if (!eventId.isNullOrBlank()) {
                    // No user-supplied title — still prime the session cache with
                    // the root body so the first push notification (which can
                    // arrive before the 5s ThreadList refresh) resolves to a
                    // real title instead of falling through to "Session".
                    // The next ThreadList refresh overwrites this with the
                    // canonical session data.
                    Log.d(TAG, "createNewSession: saving provisional title for eventId=$eventId")
                    settingsRepository.saveProvisionalSessionTitle(roomId, eventId, message)
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
