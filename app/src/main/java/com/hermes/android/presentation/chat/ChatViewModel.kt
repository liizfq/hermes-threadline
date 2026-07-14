package com.hermes.android.presentation.chat

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.android.data.repository.ActiveThread
import com.hermes.android.data.repository.ActiveThreadFactory
import com.hermes.android.data.repository.ActiveThreadState
import com.hermes.android.data.repository.PaginationStatus
import com.hermes.android.data.repository.RoomRepository
import com.hermes.android.data.repository.SessionRepository
import com.hermes.android.data.repository.SettingsRepository
import com.hermes.android.domain.model.Message
import com.hermes.android.presentation.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import java.io.File
import javax.inject.Inject

private const val TAG = "ChatVM"

/** Reconcile result: triggered when ThreadList latest and ActiveThread last are out of sync. */
data class TimelineLag(
    val listLatestEventId: String,
    val threadLastEventId: String?,
)

/**
 * Pure helper: returns null when the ThreadList and ActiveThread last eventId
 * are both empty / equal, or a [TimelineLag] when both are non-empty and differ.
 * No external dependencies — easy to unit test.
 */
internal fun computeTimelineLag(
    listLatestEventId: String?,
    threadLastEventId: String?,
): TimelineLag? {
    if (listLatestEventId.isNullOrEmpty() || threadLastEventId.isNullOrEmpty()) return null
    if (listLatestEventId == threadLastEventId) return null
    return TimelineLag(
        listLatestEventId = listLatestEventId,
        threadLastEventId = threadLastEventId,
    )
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val activeThreadFactory: ActiveThreadFactory,
    private val roomRepository: RoomRepository,
    private val settingsRepository: SettingsRepository,
    private val sessionRepository: SessionRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val threadRootId: String = savedStateHandle["threadRootId"] ?: ""

    init {
        if (threadRootId.isNotEmpty()) {
            settingsRepository.saveSessionReadTimestamp(threadRootId, System.currentTimeMillis())
        }
    }

    private var lifecycleJob: Job? = null
    private val lifecycleMutex = Mutex()
    private var activeThread: ActiveThread? = null
    private var messagesCollector: Job? = null
    private var paginationCollector: Job? = null
    private var stateCollector: Job? = null
    private var reconcileCollector: Job? = null

    private val _messages = MutableStateFlow<UiState<List<Message>>>(UiState.Loading)
    val messages: StateFlow<UiState<List<Message>>> = _messages

    private val _paginationStatus = MutableStateFlow(PaginationStatus())
    val backwardPaginationStatus: StateFlow<PaginationStatus> = _paginationStatus

    /** True while a batch load-more is in progress (loops paginate until enough loaded). */
    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore

    /** Current thread reconciliation result; non-empty shows a UI banner. */
    private val _timelineLag = MutableStateFlow<TimelineLag?>(null)
    val timelineLag: StateFlow<TimelineLag?> = _timelineLag

    fun enter() {
        if (threadRootId.isEmpty()) return
        lifecycleJob = viewModelScope.launch {
            lifecycleMutex.withLock {
                Log.d(TAG, "DIAG ChatVM[$threadRootId]: enter() begin — teardown+create (rebuild only)")
                teardownInternal()
                setupInternal()
                Log.d(TAG, "DIAG ChatVM[$threadRootId]: enter() setup done active=${activeThread != null}")
            }
        }
    }

    fun leave() {
        viewModelScope.launch {
            lifecycleMutex.withLock {
                Log.d(TAG, "DIAG ChatVM[$threadRootId]: leave() — teardown active thread")
                teardownInternal()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        lifecycleJob?.cancel()
        messagesCollector?.cancel()
        paginationCollector?.cancel()
        stateCollector?.cancel()
        reconcileCollector?.cancel()
        activeThread?.close()
        activeThread = null
    }

    private fun teardownInternal() {
        messagesCollector?.cancel()
        messagesCollector = null
        paginationCollector?.cancel()
        paginationCollector = null
        stateCollector?.cancel()
        stateCollector = null
        reconcileCollector?.cancel()
        reconcileCollector = null
        activeThread?.close()
        activeThread = null
        _paginationStatus.value = PaginationStatus()
        _isLoadingMore.value = false
        _timelineLag.value = null
    }

    private suspend fun setupInternal() {
        val roomId = settingsRepository.getBoundRoomId()
        if (roomId == null) {
            Log.e(TAG, "setupInternal: no bound room")
            _messages.value = UiState.Error("No bound room")
            return
        }
        val room = withContext(Dispatchers.IO) { roomRepository.getRoom(roomId) }
        if (room == null) {
            Log.e(TAG, "setupInternal: room not found $roomId")
            _messages.value = UiState.Error("Room not found")
            return
        }

        Log.d(TAG, "setupInternal: creating active thread for $threadRootId")
        val thread = try {
            activeThreadFactory.create(room, threadRootId)
        } catch (e: CancellationException) {
            Log.w(TAG, "setupInternal: cancelled during create()", e)
            return
        }

        val currentJob = currentCoroutineContext()[Job]
        if (currentJob?.isActive != true) {
            Log.w(TAG, "setupInternal: scope cancelled after create(), closing orphaned thread")
            thread.close()
            return
        }

        activeThread = thread

        messagesCollector = viewModelScope.launch {
            thread.messages
                .map { msgs ->
                    val last = msgs.lastOrNull()
                    Log.d(
                        TAG,
                        "DIAG ChatVM[$threadRootId]: source=messagesCollector " +
                            "count=${msgs.size} lastEventId=${last?.id ?: "null"} " +
                            "lastTs=${last?.timestamp?.toEpochMilli() ?: -1L} " +
                            "firstEventId=${msgs.firstOrNull()?.id ?: "null"}"
                    )
                    if (msgs.isEmpty()) UiState.Loading
                    else UiState.Success(msgs) as UiState<List<Message>>
                }
                .distinctUntilChanged()
                .catch { e ->
                    Log.e(TAG, "messages collector error", e)
                    _messages.value = UiState.Error(e.message ?: "Unknown error")
                }
                .collect { state -> _messages.value = state }
        }

        paginationCollector = viewModelScope.launch {
            thread.backwardPaginationStatus.collect { _paginationStatus.value = it }
        }

        stateCollector = viewModelScope.launch {
            thread.state.collect { state ->
                if (activeThread !== thread) return@collect
                if (state is ActiveThreadState.Failed) {
                    Log.e(TAG, "ActiveThread[${thread.threadRootId}] listener failed: ${state.cause.message}")
                    _messages.value = UiState.Error(state.cause.message ?: "Timeline listener failed")
                }
            }
        }

        // Reconcile: ThreadList latest vs ActiveThread last
        reconcileCollector = viewModelScope.launch {
            combine(
                thread.messages.map { msgs -> msgs.lastOrNull()?.id },
                sessionRepository.observeSessions(room).map { sessions ->
                    sessions.firstOrNull { it.id == threadRootId }?.latestEventId
                }
            ) { threadLast, listLatest ->
                val lag = computeTimelineLag(listLatest, threadLast)
                Log.d(
                    TAG,
                    "DIAG RECONCILE thread=$threadRootId listLatest=$listLatest " +
                        "threadLast=$threadLast lagging=${lag != null}"
                )
                lag
            }.collect { lag ->
                if (activeThread !== thread) return@collect
                _timelineLag.value = lag
                // Auto-refresh: fetch missing events from /relations
                if (lag != null && thread.isActive) {
                    Log.d(TAG, "DIAG RECONCILE[$threadRootId]: lag detected, auto-refreshing via /relations")
                    activeThread?.refresh()
                }
            }
        }
    }

    val sessionTitle: StateFlow<String> = MutableStateFlow(
        if (threadRootId.isNotEmpty()) {
            settingsRepository.getSessionTitle(threadRootId) ?: "Session"
        } else {
            "Session"
        }
    )

    fun saveSessionTitle(title: String) {
        if (threadRootId.isNotEmpty()) {
            settingsRepository.saveSessionTitle(threadRootId, title)
            (sessionTitle as MutableStateFlow).value = title
        }
    }

    private val _draftText = MutableStateFlow(settingsRepository.getDraft(threadRootId))
    val draftText: StateFlow<String> = _draftText

    fun updateDraft(text: String) {
        _draftText.value = text
        settingsRepository.saveDraft(threadRootId, text)
    }

    fun clearDraft() {
        _draftText.value = ""
        settingsRepository.clearDraft(threadRootId)
    }

    fun sendImageMessage(uri: Uri) = sendMedia(uri, "image")
    fun sendVideoMessage(uri: Uri) = sendMedia(uri, "video")
    fun sendFileMessage(uri: Uri) = sendMedia(uri, "file")

    private fun sendMedia(uri: Uri, type: String) {
        Log.d(TAG, "sendMedia: type=$type, uri=$uri")
        viewModelScope.launch {
            val thread = activeThread ?: return@launch
            val result = withContext(Dispatchers.IO) {
                when (type) {
                    "image" -> thread.sendImage(uri, appContext)
                    "video" -> thread.sendVideo(uri, appContext)
                    "file" -> thread.sendFile(uri, appContext)
                    else -> Result.failure(IllegalArgumentException("Unknown type: $type"))
                }
            }
            result.onSuccess { Log.d(TAG, "sendMedia: $type sent successfully") }
            result.onFailure { Log.e(TAG, "sendMedia: $type failed", it) }
        }
    }

    fun loadMoreMessages() {
        Log.d(TAG, "loadMoreMessages triggered")
        viewModelScope.launch {
            val thread = activeThread ?: return@launch
            _isLoadingMore.value = true
            try {
                withContext(Dispatchers.IO) {
                    // Loop paginate until we load at least 20 new messages
                    // or reach the beginning of the thread.
                    val before = (thread.messages as? StateFlow<List<Message>>)?.value?.size ?: 0
                    var iterations = 0
                    while (iterations < 50) {
                        val hasMore = thread.paginate()
                        iterations++
                        val after = (thread.messages as? StateFlow<List<Message>>)?.value?.size ?: 0
                        Log.d(TAG, "loadMoreMessages: iter=$iterations before=$before after=$after hasMore=$hasMore")
                        if (!hasMore) break
                        if (after - before >= 20) break
                    }
                    Log.d(TAG, "loadMoreMessages: done after $iterations iterations")
                }
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    fun toggleReaction(messageId: String, emoji: String) {
        Log.d(TAG, "toggleReaction called: messageId=$messageId, emoji=$emoji")
        viewModelScope.launch {
            val thread = activeThread ?: return@launch
            val eventOrTransactionId = EventOrTransactionId.EventId(messageId)
            val result = withContext(Dispatchers.IO) {
                thread.toggleReaction(eventOrTransactionId, emoji)
            }
            Log.d(TAG, "toggleReaction result: success=${result.isSuccess}")
            result.onFailure { e -> Log.e(TAG, "toggleReaction failed", e) }
        }
    }

    fun sendVoiceMessage(audioFile: File, waveform: List<Float>, durationMs: Long) {
        Log.d(TAG, "sendVoiceMessage: duration=${durationMs}ms")
        viewModelScope.launch {
            val thread = activeThread ?: return@launch
            withContext(Dispatchers.IO) {
                thread.sendVoice(audioFile, waveform, durationMs)
            }
            audioFile.delete()
        }
    }

    fun sendMessage(content: String) {
        Log.d(TAG, "sendMessage called: $content")
        viewModelScope.launch {
            val thread = activeThread ?: return@launch
            val result = withContext(Dispatchers.IO) {
                thread.sendMessage(content)
            }
            Log.d(TAG, "sendMessage result: success=${result.isSuccess}")
        }
    }
}
