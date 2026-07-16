package com.hermes.android.presentation.chat

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.android.data.repository.ActiveThreadSnapshot
import com.hermes.android.data.repository.ActiveThreadStore
import com.hermes.android.data.repository.PaginationStatus
import com.hermes.android.data.repository.SettingsRepository
import com.hermes.android.domain.model.Message
import com.hermes.android.presentation.UiState
import com.hermes.android.push.dismissSessionNotification
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.File
import javax.inject.Inject

private const val TAG = "ChatVM"

/** Reconcile result when ThreadList latest and ActiveThread last differ. */
data class TimelineLag(
    val listLatestEventId: String,
    val threadLastEventId: String?,
    /**
     * Which side is behind, when we can tell from membership of
     * [listLatestEventId] in the focused chat message list.
     * Null = mismatch only (legacy / unknown direction).
     */
    val direction: LagDirection? = null,
)

/**
 * Direction of a ThreadList ↔ focused-timeline mismatch.
 *
 * - [ChatBehind]: ThreadList's latest event is **not** in the chat message
 *   list — chat is missing a tail event → refresh thread (`/relations`).
 * - [SessionListBehind]: ThreadList's latest event **is** already in the chat
 *   list (and is not the last message) — SessionList summary is stale →
 *   refresh ThreadList once.
 */
enum class LagDirection {
    ChatBehind,
    SessionListBehind,
}

/**
 * Pure helper for ThreadList latest vs focused-timeline last.
 *
 * Without message membership we only know "unequal ids" (direction = null).
 * With [threadMessageIds] (or full message list last-id check via
 * [listLatestInThreadMessages]):
 *  - listLatest not in chat → [LagDirection.ChatBehind]
 *  - listLatest in chat and != threadLast → [LagDirection.SessionListBehind]
 *  - equal / empty → null (in sync or not enough data)
 */
internal fun computeTimelineLag(
    listLatestEventId: String?,
    threadLastEventId: String?,
    listLatestInThreadMessages: Boolean? = null,
): TimelineLag? {
    if (listLatestEventId.isNullOrEmpty() || threadLastEventId.isNullOrEmpty()) return null
    if (listLatestEventId == threadLastEventId) return null
    val direction = when (listLatestInThreadMessages) {
        false -> LagDirection.ChatBehind
        true -> LagDirection.SessionListBehind
        null -> null
    }
    return TimelineLag(
        listLatestEventId = listLatestEventId,
        threadLastEventId = threadLastEventId,
        direction = direction,
    )
}

/**
 * Convenience: compute lag using the set of event ids currently visible in
 * the focused chat. Prefer this over the 2-arg form when messages are available.
 */
internal fun computeTimelineLag(
    listLatestEventId: String?,
    threadLastEventId: String?,
    threadMessageIds: Collection<String>,
): TimelineLag? {
    if (listLatestEventId.isNullOrEmpty() || threadLastEventId.isNullOrEmpty()) return null
    if (listLatestEventId == threadLastEventId) return null
    val inChat = listLatestEventId in threadMessageIds
    return computeTimelineLag(listLatestEventId, threadLastEventId, listLatestInThreadMessages = inChat)
}

/**
 * Consumer of the application-scoped [ActiveThreadStore].
 *
 * The ViewModel is no longer the lifecycle owner of the focused timeline —
 * it forwards commands to the store and projects the store's snapshot (filtered
 * to this ViewModel's [threadRootId]) onto the UI state. UI dispose, Activity
 * recreation, config change, and backgrounding do NOT close the timeline; only
 * switch-thread / switch-room / logout / process death do.
 *
 * Session title and draft remain VM-scoped since they are pure local settings
 * with no SDK interaction.
 */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val activeThreadStore: ActiveThreadStore,
    private val settingsRepository: SettingsRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val threadRootId: String = savedStateHandle["threadRootId"] ?: ""

    init {
        if (threadRootId.isNotEmpty()) {
            settingsRepository.saveSessionReadTimestamp(threadRootId, System.currentTimeMillis())
            val roomId = settingsRepository.getBoundRoomId()
            // Opening a session means the user has seen it: drop any shade
            // notification for this thread (list tap, notification tap, or
            // landscape dual-pane open). No-op if none is posted.
            dismissSessionNotification(appContext, roomId, threadRootId)
            if (roomId != null) {
                Log.d(TAG, "init: opening active thread room=$roomId thread=$threadRootId")
                activeThreadStore.open(roomId, threadRootId)
            } else {
                Log.w(TAG, "init: no bound room; cannot open active thread")
            }
        }
    }

    private val storeState: StateFlow<ActiveThreadSnapshot> = activeThreadStore.state

    /** Filtered view of the store snapshot — only emits while the active key
     *  matches this VM's [threadRootId], so a stale VM (still in the backstack)
     *  does not render a different thread's data. */
    private val ownState: StateFlow<ActiveThreadSnapshot> = storeState
        .filter { it.key?.threadRootId == threadRootId }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ActiveThreadSnapshot.EMPTY)

    val messages: StateFlow<UiState<List<Message>>> = ownState
        .map { it.messages }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, UiState.Loading)

    val backwardPaginationStatus: StateFlow<PaginationStatus> = ownState
        .map { it.pagination }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, PaginationStatus())

    val timelineLag: StateFlow<TimelineLag?> = ownState
        .map { it.timelineLag }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** True while a batch load-more is in progress (loops paginate until enough loaded). */
    val isLoadingMore: StateFlow<Boolean> = ownState
        .map { it.isLoadingMore }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // ---- Commands (forwarded to the app-scoped store) ----

    fun sendMessage(content: String) = activeThreadStore.sendMessage(content)

    fun sendImageMessage(uri: Uri) = activeThreadStore.sendImage(uri, appContext)

    fun sendVideoMessage(uri: Uri) = activeThreadStore.sendVideo(uri, appContext)

    fun sendFileMessage(uri: Uri) = activeThreadStore.sendFile(uri, appContext)

    fun sendVoiceMessage(audioFile: File, waveform: List<Float>, durationMs: Long) =
        activeThreadStore.sendVoice(audioFile, waveform, durationMs)

    fun toggleReaction(messageId: String, emoji: String) =
        activeThreadStore.toggleReaction(messageId, emoji)

    fun loadMoreMessages() = activeThreadStore.loadMoreMessages()

    // ---- Local-only settings (title, draft) ----

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
}
