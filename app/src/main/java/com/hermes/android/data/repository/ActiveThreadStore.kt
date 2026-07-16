package com.hermes.android.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.hermes.android.domain.model.Message
import com.hermes.android.presentation.UiState
import com.hermes.android.presentation.chat.TimelineLag
import com.hermes.android.presentation.chat.computeTimelineLag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.Room
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ActiveThreadStore"

private const val LOAD_MORE_TARGET_DELTA = 20
private const val LOAD_MORE_MAX_ITERATIONS = 50

/**
 * Identifier of the currently open focused thread. Two opens with the same
 * [ActiveThreadKey] collapse onto a single holder; a different key triggers a
 * switch (the previous holder is closed exactly once).
 */
data class ActiveThreadKey(
    val roomId: String,
    val threadRootId: String,
)

/**
 * Aggregate state surfaced to consumers (ChatViewModel). All fields are kept
 * in a single value so a new collector observes a coherent snapshot rather
 * than racing four independent flows.
 */
data class ActiveThreadSnapshot(
    val key: ActiveThreadKey?,
    val messages: UiState<List<Message>>,
    val pagination: PaginationStatus,
    val isLoadingMore: Boolean,
    val timelineLag: TimelineLag?,
) {
    companion object {
        val EMPTY = ActiveThreadSnapshot(
            key = null,
            messages = UiState.Loading,
            pagination = PaginationStatus(),
            isLoadingMore = false,
            timelineLag = null,
        )

        fun loading(key: ActiveThreadKey) = ActiveThreadSnapshot(
            key = key,
            messages = UiState.Loading,
            pagination = PaginationStatus(),
            isLoadingMore = false,
            timelineLag = null,
        )
    }
}

/**
 * Application-scoped single source of truth for the focused thread timeline.
 *
 * Lifecycle:
 *  - At most one [Holder] is bound at any time, addressed by [ActiveThreadKey].
 *  - [open] with the current key is a synchronous no-op.
 *  - [open] with a different key switches: the previous holder is marked closed
 *    under [stateLock], then torn down (timeline + room + scope) OUTSIDE the
 *    lock; the new holder is built on [setupMutex] so concurrent opens serialize.
 *  - UI dispose, Activity recreation, config changes, and app backgrounding do
 *    NOT close the holder — it is app-scoped, not collector-scoped.
 *  - [closeActive] (logout) and process death are the only other paths that
 *    tear the holder down.
 *
 * Stale-rejection:
 *  - Every collector captures its [Holder] by identity; writes are dropped
 *    unless `holder === captured && !captured.closed` (see [updateState]).
 *  - [Holder.markClosed] is set under [stateLock] before teardown so any
 *    in-flight collector short-circuits.
 *
 * Concurrency:
 *  - `stateLock` guards only holder swap / markClosed / state writes.
 *  - Native teardown (timeline.close, room.close, scope.cancel) runs OUTSIDE
 *    the lock so it cannot block UI / push threads on slow FFI.
 *  - [setupMutex] serializes async setup; suspend SDK calls (getRoom,
 *    ActiveThreadFactory.create) happen outside `stateLock` but inside
 *    `setupMutex`.
 */
@Singleton
class ActiveThreadStore internal constructor(
    private val activeThreadFactory: ActiveThreadFactory,
    private val roomRepository: RoomRepository,
    private val sessionRepository: SessionRepository,
    private val settingsRepository: SettingsRepository,
    private val dispatcher: CoroutineDispatcher,
) {
    /**
     * Production entry point. Hilt-injected; defaults to [Dispatchers.IO] so
     * SDK suspend calls run off the main thread. Tests use the internal
     * primary constructor to swap in a test dispatcher.
     */
    @Inject
    constructor(
        activeThreadFactory: ActiveThreadFactory,
        roomRepository: RoomRepository,
        sessionRepository: SessionRepository,
        settingsRepository: SettingsRepository,
    ) : this(activeThreadFactory, roomRepository, sessionRepository, settingsRepository, Dispatchers.IO)

    private val storeScope = CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, t ->
            Log.e(TAG, "unhandled coroutine exception", t)
        }
    )

    private val stateLock = Any()

    @Volatile
    private var holder: Holder? = null

    private val setupMutex = Mutex()

    private val _state = MutableStateFlow(ActiveThreadSnapshot.EMPTY)
    val state: StateFlow<ActiveThreadSnapshot> = _state.asStateFlow()

    /**
     * Open or reuse the focused thread for [roomId]/[threadRootId].
     *
     * Same key as the current holder (and not closed): synchronous no-op.
     * Different / null: dispatches an async setup on [storeScope]; the actual
     * close-and-replace happens inside [setupHolder] so concurrent opens
     * serialize.
     */
    fun open(roomId: String, threadRootId: String) {
        val key = ActiveThreadKey(roomId, threadRootId)
        synchronized(stateLock) {
            val current = holder
            if (current != null && current.key == key && !current.closed) {
                Log.d(TAG, "open: reuse $key")
                return
            }
        }
        Log.d(TAG, "open: dispatching setup for $key")
        storeScope.launch { setupHolder(key) }
    }

    /**
     * Tear down the active holder. Used on logout / explicit teardown. Safe to
     * call repeatedly; a no-op when nothing is active.
     */
    fun closeActive() {
        val toTeardown: Holder? = synchronized(stateLock) {
            val current = holder ?: return
            current.markClosed()
            holder = null
            _state.value = ActiveThreadSnapshot.EMPTY
            current
        }
        toTeardown?.let { teardownUnlocked(it) }
    }

    // ---- Command forwarders ----

    fun sendMessage(content: String) {
        val h = currentHolder() ?: return
        h.scope.launch {
            val result = withContext(Dispatchers.IO) { h.thread.sendMessage(content) }
            result.onFailure { e -> Log.e(TAG, "sendMessage failed", e) }
        }
    }

    fun sendImage(uri: Uri, context: Context) {
        val h = currentHolder() ?: return
        h.scope.launch {
            val result = withContext(Dispatchers.IO) { h.thread.sendImage(uri, context) }
            result.onFailure { e -> Log.e(TAG, "sendImage failed", e) }
        }
    }

    fun sendVideo(uri: Uri, context: Context) {
        val h = currentHolder() ?: return
        h.scope.launch {
            val result = withContext(Dispatchers.IO) { h.thread.sendVideo(uri, context) }
            result.onFailure { e -> Log.e(TAG, "sendVideo failed", e) }
        }
    }

    fun sendFile(uri: Uri, context: Context) {
        val h = currentHolder() ?: return
        h.scope.launch {
            val result = withContext(Dispatchers.IO) { h.thread.sendFile(uri, context) }
            result.onFailure { e -> Log.e(TAG, "sendFile failed", e) }
        }
    }

    fun sendVoice(audioFile: File, waveform: List<Float>, durationMs: Long) {
        val h = currentHolder() ?: return
        h.scope.launch {
            val result = withContext(Dispatchers.IO) {
                h.thread.sendVoice(audioFile, waveform, durationMs)
            }
            result.onFailure { e -> Log.e(TAG, "sendVoice failed", e) }
            audioFile.delete()
        }
    }

    fun toggleReaction(messageId: String, emoji: String) {
        val h = currentHolder() ?: return
        h.scope.launch {
            val eid = EventOrTransactionId.EventId(messageId)
            val result = withContext(Dispatchers.IO) { h.thread.toggleReaction(eid, emoji) }
            result.onFailure { e -> Log.e(TAG, "toggleReaction failed", e) }
        }
    }

    /**
     * Loop paginate until either at least [LOAD_MORE_TARGET_DELTA] new messages
     * arrive or the beginning of the thread is reached. Updates
     * [ActiveThreadSnapshot.isLoadingMore] for the duration.
     */
    fun loadMoreMessages() {
        val h = currentHolder() ?: return
        h.scope.launch {
            updateState(h) { it.copy(isLoadingMore = true) }
            try {
                withContext(Dispatchers.IO) {
                    val before = (h.thread.messages as? StateFlow<List<Message>>)?.value?.size ?: 0
                    var iterations = 0
                    while (iterations < LOAD_MORE_MAX_ITERATIONS) {
                        val hasMore = h.thread.paginate()
                        iterations++
                        val after = (h.thread.messages as? StateFlow<List<Message>>)?.value?.size ?: 0
                        Log.d(TAG, "loadMore: iter=$iterations before=$before after=$after hasMore=$hasMore")
                        if (!hasMore) break
                        if (after - before >= LOAD_MORE_TARGET_DELTA) break
                    }
                }
            } finally {
                updateState(h) { it.copy(isLoadingMore = false) }
            }
        }
    }

    /** Snapshot of the currently active key, or null when nothing is open. */
    fun activeKey(): ActiveThreadKey? = synchronized(stateLock) { holder?.key }

    /**
     * Foreground recovery: pull the latest events for the currently focused
     * thread via SDK `room.refreshThread` (`/relations`).
     *
     * Why this exists: after background, [MatrixRepositoryImpl] stops
     * SyncService (3s grace) and restarts it on return. Session list is
     * refreshed there, but the focused timeline can still miss tail events
     * that arrived while sync was down / while the listener was starved.
     * Recreating the timeline is the wrong fix (loses warm TEC state and
     * caused historical white-screen races). A single `/relations` catch-up
     * is enough.
     *
     * Safe no-op when nothing is open or the holder is closed. Concurrent
     * calls coalesce via [Holder.refreshInFlight].
     */
    suspend fun refreshActiveIfAny() {
        val h = synchronized(stateLock) { holder } ?: return
        if (h.closed) return
        // Coalesce concurrent foreground recoveries onto one in-flight call.
        if (!h.refreshInFlight.compareAndSet(false, true)) {
            Log.d(TAG, "refreshActiveIfAny: already in flight for ${h.key}")
            return
        }
        try {
            if (h.closed || holder !== h) return
            Log.d(TAG, "refreshActiveIfAny: refreshing ${h.key}")
            h.thread.refresh()
            Log.d(TAG, "refreshActiveIfAny: done ${h.key}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "refreshActiveIfAny failed for ${h.key}", e)
        } finally {
            h.refreshInFlight.set(false)
        }
    }

    // ---- internal ----

    private fun currentHolder(): Holder? {
        val h = holder
        if (h == null || h.closed) {
            Log.w(TAG, "command dropped: no active holder (or closed)")
            return null
        }
        return h
    }

    /**
     * Build the holder for [key]. Serialized by [setupMutex]; re-checks the
     * current holder at entry and on completion so concurrent opens collapse
     * onto the latest key. Native teardown of the previous holder runs outside
     * [stateLock] (still inside [setupMutex]).
     */
    private suspend fun setupHolder(key: ActiveThreadKey) {
        try {
        setupMutex.withLock {
            val toTeardown: Holder?
            synchronized(stateLock) {
                val current = holder
                if (current != null && current.key == key && !current.closed) {
                    Log.d(TAG, "setupHolder: $key already active")
                    return@withLock
                }
                current?.markClosed()
                toTeardown = current
                // Detach the old holder UNDER the lock so any in-flight
                // collector reading `holder` after this point sees null and
                // short-circuits via the identity check. Teardown of its
                // native resources runs outside the lock.
                holder = null
                _state.value = ActiveThreadSnapshot.loading(key)
            }
            toTeardown?.let { teardownUnlocked(it) }

            val room = try {
                roomRepository.getRoom(key.roomId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "setupHolder: getRoom failed for ${key.roomId}", e)
                publishError(key, "Room not available")
                return@withLock
            }
            if (room == null) {
                Log.w(TAG, "setupHolder: room not found for ${key.roomId}")
                publishError(key, "Room not found")
                return@withLock
            }

            val thread = try {
                activeThreadFactory.create(room, key.threadRootId)
            } catch (e: CancellationException) {
                try { room.close() } catch (_: Exception) {}
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "setupHolder: create failed for $key", e)
                try { room.close() } catch (_: Exception) {}
                publishError(key, e.message ?: "Failed to open thread")
                return@withLock
            }

            val holderScope = CoroutineScope(
                SupervisorJob(storeScope.coroutineContext[Job]) + dispatcher +
                    CoroutineExceptionHandler { _, t ->
                        Log.e(TAG, "holder coroutine exception for $key", t)
                    }
            )
            val newHolder = Holder(key, thread, room, holderScope)
            attachCollectors(newHolder)

            synchronized(stateLock) {
                // A concurrent open for a different key may have landed while
                // we were setting up. If so, abandon ours.
                val existing = holder
                if (existing != null && existing.key != key) {
                    Log.w(TAG, "setupHolder: $key superseded by ${existing.key}, discarding")
                    holderScope.cancel()
                    try { thread.close() } catch (_: Exception) {}
                    try { room.close() } catch (_: Exception) {}
                    return@withLock
                }
                holder = newHolder
                Log.d(TAG, "setupHolder: bound $key")
            }
        }
        } catch (e: Throwable) {
            Log.e(TAG, "setupHolder crashed for $key", e)
            throw e
        }
    }

    private fun attachCollectors(h: Holder) {
        val thread = h.thread

        // Messages -> UiState
        h.scope.launch {
            thread.messages
                .map { msgs ->
                    if (msgs.isEmpty()) UiState.Loading
                    else UiState.Success(msgs) as UiState<List<Message>>
                }
                .distinctUntilChanged()
                .collect { uiState ->
                    if (h.closed) return@collect
                    updateState(h) { it.copy(messages = uiState) }
                }
        }

        // Pagination status
        h.scope.launch {
            thread.backwardPaginationStatus.collect { ps ->
                if (h.closed) return@collect
                updateState(h) { it.copy(pagination = ps) }
            }
        }

        // Listener state — surface Failed as UiState.Error
        h.scope.launch {
            thread.state.collect { state ->
                if (h.closed) return@collect
                if (state is ActiveThreadState.Failed) {
                    Log.e(TAG, "ActiveThread[${h.key}] listener failed: ${state.cause.message}")
                    updateState(h) {
                        it.copy(messages = UiState.Error(state.cause.message ?: "Timeline listener failed"))
                    }
                }
            }
        }

        // Reconcile: ThreadList latest vs ActiveThread last; auto-refresh on lag.
        h.scope.launch {
            combine(
                thread.messages.map { msgs -> msgs.lastOrNull()?.id },
                sessionRepository.observeSessions().map { sessions ->
                    sessions.firstOrNull { it.id == h.key.threadRootId }?.latestEventId
                }
            ) { threadLast, listLatest ->
                computeTimelineLag(listLatest, threadLast)
            }.collect { lag ->
                if (h.closed) return@collect
                updateState(h) { it.copy(timelineLag = lag) }
                if (lag != null && thread.isActive) {
                    Log.d(TAG, "reconcile[${h.key}]: lag detected, refreshing via /relations")
                    thread.refresh()
                }
            }
        }
    }

    /**
     * Write [transform] onto [_state] only if [h] is still the active holder.
     * Uses CAS via [MutableStateFlow.update] so concurrent collectors do not
     * stomp each other. The identity check is the primary stale-rejection
     * mechanism — a callback firing after teardown sees a different
     * [holder] (or [Holder.closed]) and short-circuits.
     */
    private inline fun updateState(
        h: Holder,
        transform: (ActiveThreadSnapshot) -> ActiveThreadSnapshot,
    ) {
        if (h.closed) return
        _state.update { current ->
            val active = holder
            if (active !== h || h.closed) current
            else transform(current)
        }
    }

    private fun publishError(key: ActiveThreadKey, message: String) {
        synchronized(stateLock) {
            val current = holder
            if (current != null && current.key != key && !current.closed) {
                // Newer key already bound — don't clobber it.
                return
            }
            _state.value = ActiveThreadSnapshot(
                key = key,
                messages = UiState.Error(message),
                pagination = PaginationStatus(),
                isLoadingMore = false,
                timelineLag = null,
            )
        }
    }

    /**
     * Tear down a [Holder] WITHOUT holding [stateLock]. Caller has already
     * detached it (or is in [closeActive] where no other thread can race) and
     * called [Holder.markClosed]. Order: cancel scope (drops collectors),
     * close timeline, close room.
     */
    private fun teardownUnlocked(h: Holder) {
        try { h.scope.cancel() } catch (_: Exception) {}
        try { h.thread.close() } catch (e: Exception) {
            Log.w(TAG, "teardown: thread.close failed for ${h.key}", e)
        }
        try { h.room.close() } catch (e: Exception) {
            Log.w(TAG, "teardown: room.close failed for ${h.key}", e)
        }
        Log.d(TAG, "teardown: ${h.key} done")
    }

    /**
     * Active holder. Holds the [ActiveThreadImpl], the owning [Room] handle,
     * the per-holder [CoroutineScope] for collectors, and a [closed] flag used
     * by [markClosed] / collectors to short-circuit stale callbacks before the
     * identity check.
     */
    private class Holder(
        val key: ActiveThreadKey,
        val thread: ActiveThreadImpl,
        val room: Room,
        val scope: CoroutineScope,
    ) {
        @Volatile private var _closed: Boolean = false
        val closed: Boolean get() = _closed

        /** Coalesces concurrent [refreshActiveIfAny] calls on this holder. */
        val refreshInFlight: AtomicBoolean = AtomicBoolean(false)

        fun markClosed() { _closed = true }
    }
}
