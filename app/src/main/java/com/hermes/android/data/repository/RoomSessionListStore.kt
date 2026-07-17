package com.hermes.android.data.repository

import android.util.Log
import com.hermes.android.domain.model.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.MsgLikeContent
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.ProfileDetails
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.ThreadListEntriesListener
import org.matrix.rustcomponents.sdk.ThreadListItem
import org.matrix.rustcomponents.sdk.ThreadListService
import org.matrix.rustcomponents.sdk.ThreadListUpdate
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.TimelineListener
import uniffi.matrix_sdk_ui.ThreadListPaginationState
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "RoomSessionListStore"

/** Pagination iteration cap; the loop also exits early on `endReached`. */
private const val PAGINATION_MAX_ITERATIONS = 100

/** Discovery debounce: coalesce rapid remote-echo thread roots into one refresh. */
private const val DISCOVERY_DEBOUNCE_MS = 500L

/**
 * Pure slot decision for [RoomSessionListStore.ensureStarted]. Covered by
 * [RoomSessionListSlotTest] — extracting this as a pure value lets us unit-test
 * the no-op / switch / start logic without the Matrix SDK.
 */
internal sealed class StartDecision {
    /** Same room is already active; do nothing. */
    object NoOp : StartDecision()
    /** Slot is empty (or previous instance was closed); start fresh. */
    object Start : StartDecision()
    /** A different room is currently active; the caller must close it first. */
    data class Switch(val fromRoomId: String) : StartDecision()
}

/**
 * Pure state machine tracking which room (if any) is currently bound to the
 * application-scoped session-list pipeline. Extracted from
 * [RoomSessionListStore] so the lifecycle decision logic — same-room no-op,
 * room switch, post-close restart — is unit-testable without SDK types.
 *
 * Thread-safety: access is serialized by the store's `stateLock`; this class
 * itself is not thread-safe.
 */
internal class RoomSessionListSlot {
    @Volatile private var activeRoomId: String? = null
    @Volatile private var _closed: Boolean = true

    val currentRoomId: String? get() = activeRoomId
    val closed: Boolean get() = _closed

    fun decide(roomId: String): StartDecision = when {
        activeRoomId == roomId && !_closed -> StartDecision.NoOp
        activeRoomId != null && !_closed -> StartDecision.Switch(activeRoomId!!)
        else -> StartDecision.Start
    }

    fun occupy(roomId: String) {
        activeRoomId = roomId
        _closed = false
    }

    fun close() {
        _closed = true
    }
}

/**
 * One active room's session-list pipeline. Holds the [ThreadListService],
 * the in-memory items cache, the discovery timeline (built lazily), and a
 * child [scope] that is cancelled on every switch / teardown — that
 * cancellation is the primary mechanism for rejecting stale callbacks.
 *
 * Stale-rejection contract: every listener entry point checks [closed] (and
 * callers verify `_active === instance` before publishing). A callback racing
 * with teardown becomes a no-op even before the scope finishes cancelling.
 *
 * [refreshLeader] holds the in-flight shared refresh, if any. Concurrent
 * callers of `runRefreshSingleFlight` await the leader's deferred so all
 * callers observe the post-refresh state.
 */
internal class ActiveInstance(
    val roomId: String,
    val room: Room,
    val service: ThreadListService,
    val items: ArrayList<ThreadListItem>,
    val refreshMutex: Mutex,
    val scope: CoroutineScope,
) {
    @Volatile var serviceHandle: TaskHandle? = null

    @Volatile var discoveryTimeline: Timeline? = null
    @Volatile var discoveryHandle: TaskHandle? = null
    @Volatile var discoveryPendingJob: Job? = null
    val discoverySeenIds: MutableSet<String> = mutableSetOf()

    /** True while a shared refresh is paginating; listeners apply diffs but suppress publish. */
    val refreshInProgress: AtomicBoolean = AtomicBoolean(false)

    /** In-flight shared refresh; non-null only while a leader is running. */
    val refreshLeader: AtomicReference<Deferred<Unit>?> = AtomicReference(null)

    @Volatile private var _closed: Boolean = false
    val closed: Boolean get() = _closed

    fun markClosed() {
        _closed = true
    }

    /**
     * Test-only constructor: build an instance with prefilled SDK handles
     * (typically NoHandle fakes or mocks) so the lifecycle / stale-callback
     * logic can be exercised without the full ensureStarted pipeline. The
     * [scope] is created by the caller so tests can use a controlled
     * [kotlinx.coroutines.test.TestScope].
     */
    internal constructor(
        roomId: String,
        room: Room,
        service: ThreadListService,
        scope: CoroutineScope,
    ) : this(
        roomId = roomId,
        room = room,
        service = service,
        items = ArrayList(),
        refreshMutex = Mutex(),
        scope = scope,
    )
}

/**
 * Timeline listener that detects externally-created session root events
 * (events from another client) which [ThreadListService] does not auto-discover.
 *
 * The implementation mirrors the previous `DiscoveryListener`: incremental
 * diffs are scanned for remote-echo thread roots, and any match is coalesced
 * into a single debounced refresh call. Reset diffs delivered synchronously
 * during `addListener` are ignored via [markInitialized].
 *
 * Stale-rejection: every entry point checks [ActiveInstance.closed] before
 * touching the scope; the instance is captured by identity, so a callback
 * firing after teardown sees `closed == true` and returns. The refresh is
 * always invoked on the captured [instance] — never re-resolved from the
 * store's `_active` field — so a callback from a torn-down room cannot
 * accidentally refresh a different, currently-bound room.
 */
internal class DiscoveryListener(
    private val instance: ActiveInstance,
    private val refresh: () -> Unit,
) : TimelineListener {

    @Volatile
    private var initialized = false

    override fun onUpdate(diffs: List<TimelineDiff>) {
        if (instance.closed) return
        for (diff in diffs) {
            val items = when (diff) {
                is TimelineDiff.Append -> diff.values
                is TimelineDiff.PushBack -> listOf(diff.value)
                is TimelineDiff.PushFront -> listOf(diff.value)
                is TimelineDiff.Insert -> listOf(diff.value)
                is TimelineDiff.Set -> listOf(diff.value)
                else -> continue
            }
            for (item in items) {
                val ev = item.asEvent() ?: continue
                if (!ev.isRemote) continue
                if (ev.eventOrTransactionId !is EventOrTransactionId.EventId) continue
                val msgLike = ev.content as? TimelineItemContent.MsgLike ?: continue
                if (msgLike.content.threadRoot != null) continue
                val kind = msgLike.content.kind
                if (kind is MsgLikeKind.Redacted) continue
                if (kind !is MsgLikeKind.Message) continue
                val eventId =
                    (ev.eventOrTransactionId as EventOrTransactionId.EventId).eventId

                val shouldSchedule = synchronized(instance.discoverySeenIds) {
                    if (!instance.discoverySeenIds.add(eventId)) {
                        false
                    } else if (!initialized) {
                        false
                    } else {
                        !instance.closed
                    }
                }
                if (!shouldSchedule) continue
                if (instance.closed || !instance.scope.isActive) continue
                try {
                    instance.discoveryPendingJob?.cancel()
                    instance.discoveryPendingJob = instance.scope.launch {
                        try {
                            delay(DISCOVERY_DEBOUNCE_MS)
                            if (instance.closed) return@launch
                            refresh()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            Log.e(TAG, "discovery debounced refresh failed", e)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: IllegalStateException) {
                    // Scope was cancelled between the isActive check and launch.
                }
            }
        }
    }

    fun markInitialized() {
        initialized = true
    }
}

/**
 * Application-scoped single source of truth for the bound room's session list.
 *
 * The store owns, for the currently bound room:
 *  - the [ThreadListService] and its items listener,
 *  - the room-level discovery timeline + listener (external session roots),
 *  - the session [StateFlow] consumed by SessionList UI, ChatViewModel
 *    reconcile and the push pipeline,
 *  - a single serial refresh path used by manual refresh, discovery, and
 *    push-triggered `refreshIfMissing` (single-flight, all callers await the
 *    same leader).
 *
 * Lifecycle:
 *  - [ensureStarted] is idempotent for the same room (NoOp — caller keeps its
 *    handle) and switches atomically to a new room when the bound room
 *    changes (Start / Switch — store owns the new handle).
 *  - The store stays alive across UI collector churn (no refCount), Activity
 *    recreation, and app backgrounding. Only [shutdown] (logout / explicit
 *    teardown), an explicit room switch, or process death close it.
 *  - Stale callbacks from a previous room are rejected by instance identity
 *    (the `closed` flag on [ActiveInstance]) and by cancelling the per-room
 *    child scope.
 *
 * Concurrency:
 *  - `stateLock` guards only `_active` / `slot` swap and `markClosed`. Native
 *    teardown (TaskHandle.destroy, Timeline.close, ThreadListService.close,
 *    Room.close, scope.cancel) runs OUTSIDE the lock so it cannot stall
 *    other callers (UI thread, push worker) on slow FFI.
 *  - Start / Switch is serialized by `stateLock` so two instances never run
 *    concurrently.
 *  - Refresh is single-flight per instance: the first caller becomes the
 *    leader, concurrent callers await the same deferred.
 *
 * SDK suspend calls (room.timeline, service.paginate, service.reset) are
 * performed outside `stateLock`; the synchronous FFI calls used inside the
 * critical section (threadListService, subscribeToItemsUpdates) are non-suspend.
 */
@Singleton
class RoomSessionListStore internal constructor(
    private val settingsRepository: SettingsRepository,
    private val dispatcher: CoroutineDispatcher,
) {

    /**
     * Production entry point. Hilt-injected; defaults to [Dispatchers.IO] so
     * SDK suspend calls (paginate, reset, timeline) run off the main thread.
     * Tests use the internal primary constructor to swap in a test dispatcher.
     */
    @Inject
    constructor(settingsRepository: SettingsRepository) : this(settingsRepository, Dispatchers.IO)

    private val storeScope = CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, t ->
            Log.e(TAG, "Unhandled coroutine exception", t)
        }
    )

    private val stateLock = Any()

    @Volatile
    private var _active: ActiveInstance? = null

    private val slot = RoomSessionListSlot()

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    /**
     * Bind the store to [roomId]. Contract:
     *  - Same-room NoOp: the store does NOT take ownership of [room]; the
     *    caller is free to keep using it (e.g. for [ActiveThreadFactory]) and
     *    is responsible for closing it. Returns false.
     *  - Start / Switch: the store takes ownership of [room] and will close it
     *    on teardown / switch. The caller must NOT reuse [room]. Returns true.
     *  - Setup failure: the store closes [room] itself. Returns false.
     *
     * The synchronous SDK setup (threadListService + subscribeToItemsUpdates)
     * runs inside `stateLock`; suspend setup (pagination, discovery timeline)
     * is dispatched on the instance scope outside the lock.
     */
    fun ensureStarted(room: Room, roomId: String): Boolean {
        val toTeardown: ActiveInstance?
        val instance: ActiveInstance
        synchronized(stateLock) {
            val decision = slot.decide(roomId)
            when (decision) {
                StartDecision.NoOp -> {
                    // Same room already active. Do NOT close the caller's
                    // handle — it may still be in use (e.g. ChatViewModel
                    // hands the same Room to ActiveThreadFactory). The caller
                    // owns this handle and must close it.
                    Log.d(TAG, "ensureStarted: same room $roomId, no-op (caller owns handle)")
                    return false
                }
                is StartDecision.Switch -> {
                    Log.d(TAG, "ensureStarted: switch ${decision.fromRoomId} -> $roomId")
                    val old = _active
                    // markClosed UNDER the lock so any in-flight listener
                    // callback on the old instance sees closed == true the
                    // instant we detach. publishIfActive already gates on
                    // `_active === instance`, but the discovery / refresh
                    // paths gate on `instance.closed` only — without this
                    // call they could schedule work against SDK handles
                    // that teardown is about to release.
                    old?.markClosed()
                    toTeardown = old
                    _active = null
                    slot.close()
                }
                StartDecision.Start -> {
                    Log.d(TAG, "ensureStarted: fresh start for $roomId")
                    val old = _active
                    // Defensive: decide() returns Start when slot is empty
                    // OR already closed. The "already closed" case can in
                    // principle leave `_active != null` if a prior path
                    // toggled slot state without clearing `_active`. Mark
                    // any survivor closed and tear it down so the new
                    // instance is the only one bound.
                    old?.markClosed()
                    toTeardown = old
                    _active = null
                    // close() is idempotent and ensures occupy() below
                    // starts from a consistent slot state.
                    slot.close()
                }
            }

            val service = try {
                room.threadListService()
            } catch (e: Exception) {
                Log.e(TAG, "ensureStarted: threadListService() failed for $roomId", e)
                try { room.close() } catch (_: Exception) {}
                slot.close()
                return false
            }

            val newScope = CoroutineScope(
                dispatcher + SupervisorJob(storeScope.coroutineContext[Job]) +
                    CoroutineExceptionHandler { _, t -> Log.e(TAG, "instance coroutine exception", t) }
            )
            val newInstance = ActiveInstance(
                roomId = roomId,
                room = room,
                service = service,
                items = ArrayList(),
                refreshMutex = Mutex(),
                scope = newScope,
            )

            val handle = try {
                service.subscribeToItemsUpdates(buildItemsListener(newInstance))
            } catch (e: Exception) {
                Log.e(TAG, "ensureStarted: subscribeToItemsUpdates failed for $roomId", e)
                try { service.close() } catch (_: Exception) {}
                try { room.close() } catch (_: Exception) {}
                slot.close()
                newScope.cancel()
                return false
            }
            newInstance.serviceHandle = handle

            slot.occupy(roomId)
            _active = newInstance
            instance = newInstance
        }

        // Teardown the previous instance outside stateLock so its native
        // cleanup cannot block concurrent callers (UI / push).
        toTeardown?.let { teardownUnlocked(it) }

        // On a room switch, immediately clear the public flow so UI never
        // shows the old room's sessions while we seed the new room's cache.
        if (toTeardown != null) {
            _sessions.value = emptyList()
        }

        // Seed the public flow with whatever the cache holds so the UI never
        // shows an empty list while pagination is in flight.
        val cached = settingsRepository.getSessionCache(instance.roomId)
        if (!cached.isNullOrEmpty() && !instance.closed) {
            _sessions.value = cached
        }

        // Initial pagination. The listener may have already delivered a Reset
        // with cached items during addListener; this loop also walks the
        // service forward to the end of the thread list. We do NOT publish an
        // empty list here: the SDK may not have delivered any Reset diff yet,
        // so empty is ambiguous (loading vs. truly empty). The listener will
        // publish the authoritative state — including a trusted empty — once
        // a diff arrives.
        instance.scope.launch {
            try {
                paginateFully(instance)
                if (instance.closed) return@launch
                val sessions = synchronized(instance.items) { instance.items.toSortedSessions() }
                if (sessions.isEmpty()) return@launch
                publishIfActive(instance, sessions)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "ensureStarted: pagination failed for ${instance.roomId}", e)
            }
        }

        // Discovery timeline is suspend to create; build it on the instance scope.
        instance.scope.launch {
            try {
                buildDiscovery(instance)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "ensureStarted: discovery setup failed for ${instance.roomId}", e)
            }
        }

        return true
    }

    /** Public, non-suspending snapshot for callers (e.g. push worker) that
     *  cannot collect the [StateFlow]. Returns the in-memory list which may
     *  be empty if the store has not been started yet. */
    fun sessionsSnapshot(): List<Session> = _sessions.value

    /**
     * Trigger a refresh on the currently active instance. No-op if no instance
     * is active or it has been closed. Safe to call from any thread.
     */
    fun refresh() {
        val instance = synchronized(stateLock) { _active } ?: run {
            Log.w(TAG, "refresh: no active instance")
            return
        }
        if (instance.closed) return
        instance.scope.launch { runRefreshSingleFlight(instance) }
    }

    /**
     * Push-pipeline helper. Returns true iff [roomId] is the currently active
     * room (so the push is for our bound room and the store can serve it).
     * When [threadRootId] is missing from the current sessions, awaits a
     * single shared refresh on the captured instance; concurrent callers /
     * multiple pushes coalesce onto the same leader and all observe the
     * post-refresh state by the time this call returns.
     *
     * The push worker calls this (awaiting completion) before falling back to
     * the SDK root-body lookup. This never opens a per-thread focused timeline.
     */
    suspend fun refreshIfMissing(roomId: String, threadRootId: String?): Boolean {
        val instance = synchronized(stateLock) { _active } ?: return false
        if (instance.closed || instance.roomId != roomId) return false
        if (threadRootId == null) return true
        val has = _sessions.value.any { it.id == threadRootId } ||
            settingsRepository.getSessionCache(instance.roomId)
                ?.any { it.id == threadRootId } == true
        if (!has) {
            runRefreshSingleFlight(instance)
        }
        return true
    }

    /**
     * Tear down the active pipeline. Used on logout / explicit teardown. Safe
     * to call repeatedly; a no-op when nothing is active.
     */
    fun shutdown() {
        val toTeardown = synchronized(stateLock) {
            val current = _active ?: return
            current.markClosed()
            _active = null
            slot.close()
            current
        }
        teardownUnlocked(toTeardown)
        _sessions.value = emptyList()
    }

    // ---- internal teardown / refresh / discovery ----

    /**
     * Tear down an [instance] WITHOUT holding [stateLock]. The caller has
     * already detached the instance from `_active` (or is in shutdown where
     * no other thread can race) and called [ActiveInstance.markClosed] so
     * in-flight listener callbacks are no-ops. The caller is also responsible
     * for slot mutations, which MUST happen under [stateLock].
     *
     * Order: cancel pending discovery job, drop SDK listener handles, cancel
     * the per-instance scope (drops pending coroutines), then close native
     * resources. [Room.close] is last — the discovery timeline / service are
     * backed by the room and must close first.
     */
    private fun teardownUnlocked(instance: ActiveInstance) {
        try { instance.discoveryPendingJob?.cancel() } catch (_: Exception) {}
        try { instance.discoveryHandle?.destroy() } catch (_: Exception) {}
        try { instance.discoveryTimeline?.close() } catch (_: Exception) {}
        instance.discoveryTimeline = null
        instance.discoveryHandle = null
        synchronized(instance.discoverySeenIds) { instance.discoverySeenIds.clear() }
        try { instance.serviceHandle?.destroy() } catch (_: Exception) {}
        instance.serviceHandle = null
        try { instance.scope.cancel() } catch (_: Exception) {}
        try { instance.service.close() } catch (_: Exception) {}
        try { instance.room.close() } catch (_: Exception) {}
    }

    private fun buildItemsListener(instance: ActiveInstance): ThreadListEntriesListener =
        object : ThreadListEntriesListener {
            override fun onUpdate(tlUpdates: List<ThreadListUpdate>) {
                if (instance.closed) return
                val sessions: List<Session>
                synchronized(instance.items) {
                    try {
                        applyThreadListUpdates(instance.items, tlUpdates)
                    } catch (e: Exception) {
                        Log.e(TAG, "ThreadList listener apply failed", e)
                        return
                    }
                    if (instance.refreshInProgress.get()) {
                        // Refresh is in progress; apply the diff (so items
                        // stays current) but suppress the intermediate publish.
                        // The refresh leader publishes the final state when it
                        // completes.
                        return
                    }
                    sessions = instance.items.toSortedSessions()
                }
                publishIfActive(instance, sessions)
            }
        }

    /**
     * Publish [sessions] (or the current items cache if omitted) to the public
     * flow + room-scoped cache, but only if [instance] is still the active
     * identity AND not closed. This is the single chokepoint that enforces the
     * stale-rejection invariant before any side-effecting publish.
     */
    private fun publishIfActive(instance: ActiveInstance, sessions: List<Session>? = null) {
        if (instance.closed) return
        val stillActive = synchronized(stateLock) { _active === instance } && !instance.closed
        if (!stillActive) return
        val toPublish = sessions ?: synchronized(instance.items) { instance.items.toSortedSessions() }
        // Empty list is a trusted final state (e.g. after a Clear / Reset to
        // empty, or after refresh completes with no items). Publish it so the
        // UI never shows stale sessions from the previous room.
        settingsRepository.saveSessionCache(instance.roomId, toPublish)
        // Feed the event-id → thread-root-id index: every session contributes
        // both its root event id and its latest reply event id. This is the
        // ground-truth source for resolving m.replace pushes without an SDK
        // round-trip.
        saveEventThreadRootsFromSessions(instance.roomId, toPublish)
        _sessions.value = toPublish
    }

    private fun saveEventThreadRootsFromSessions(roomId: String, sessions: List<Session>) {
        val mappings = HashMap<String, String>()
        for (session in sessions) {
            mappings[session.id] = session.id
            session.latestEventId?.let { mappings[it] = session.id }
        }
        if (mappings.isNotEmpty()) {
            settingsRepository.saveEventThreadRoots(roomId, mappings)
        }
    }

    private suspend fun buildDiscovery(instance: ActiveInstance) {
        val timeline = instance.room.timeline()
        if (instance.closed) {
            try { timeline.close() } catch (_: Exception) {}
            return
        }
        // The refresh closure runs against the captured instance only — it
        // never re-resolves `_active` at fire time, so a stale callback from
        // a torn-down room cannot trigger refresh on a different room.
        val listener = DiscoveryListener(instance) {
            if (instance.closed) return@DiscoveryListener
            instance.scope.launch { runRefreshSingleFlight(instance) }
        }
        val handle = try {
            timeline.addListener(listener)
        } catch (e: Exception) {
            Log.e(TAG, "discovery addListener failed for ${instance.roomId}", e)
            try { timeline.close() } catch (_: Exception) {}
            return
        }
        if (instance.closed) {
            try { handle.destroy() } catch (_: Exception) {}
            try { timeline.close() } catch (_: Exception) {}
            return
        }
        instance.discoveryTimeline = timeline
        instance.discoveryHandle = handle
        listener.markInitialized()
    }

    /**
     * Single-flight refresh: the first caller becomes the leader and runs
     * [runRefresh]; concurrent callers (and the leader itself) await the same
     * [CompletableDeferred], so all callers observe the post-refresh state
     * before this suspend returns.
     *
     * The leader slot is claimed via CAS on [ActiveInstance.refreshLeader];
     * if a caller loses the race, it awaits the winner's deferred.
     */
    private suspend fun runRefreshSingleFlight(instance: ActiveInstance) {
        if (instance.closed) return
        val newLeader = CompletableDeferred<Unit>()
        val won = instance.refreshLeader.compareAndSet(null, newLeader)
        if (!won) {
            // Another caller is leading; await their result. Any refresh
            // failure is logged inside runRefresh; awaiters don't propagate.
            val existing = instance.refreshLeader.get()
            if (existing != null && !existing.isCompleted) {
                try { existing.await() } catch (e: CancellationException) { throw e } catch (_: Exception) {}
                return
            }
            // Leader finished between our CAS failure and read — re-enter.
            return runRefreshSingleFlight(instance)
        }
        try {
            runRefresh(instance)
            newLeader.complete(Unit)
        } catch (e: CancellationException) {
            newLeader.cancel(e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "refresh leader failed for ${instance.roomId}", e)
            newLeader.completeExceptionally(e)
        } finally {
            instance.refreshLeader.compareAndSet(newLeader, null)
        }
    }

    private suspend fun runRefresh(instance: ActiveInstance) {
        if (instance.closed) return
        instance.refreshMutex.withLock {
            if (instance.closed) return@withLock
            instance.refreshInProgress.set(true)
            try {
                Log.d(TAG, "refresh: reset + paginate room=${instance.roomId}")
                instance.service.reset()
                var iterations = 0
                while (iterations < PAGINATION_MAX_ITERATIONS && !instance.closed) {
                    instance.service.paginate()
                    iterations++
                    val state = instance.service.paginationState()
                    if (state is ThreadListPaginationState.Idle && state.endReached) break
                }
                if (instance.closed) return@withLock
            } finally {
                instance.refreshInProgress.set(false)
            }
            // Final publish from the items cache. The listener may have
            // already published via the path below; this publish guarantees
            // the final post-refresh state reaches the flow even when the SDK
            // delivered no further diffs after the leader's paginate loop.
            publishIfActive(instance)
        }
    }

    private suspend fun paginateFully(instance: ActiveInstance) {
        var iterations = 0
        while (iterations < PAGINATION_MAX_ITERATIONS && !instance.closed) {
            instance.service.paginate()
            iterations++
            val state = instance.service.paginationState()
            if (state is ThreadListPaginationState.Idle && state.endReached) {
                Log.d(
                    TAG,
                    "ThreadList pagination complete for ${instance.roomId} (iterations=$iterations)"
                )
                break
            }
        }
    }

    // ---- ThreadList diffing / mapping helpers (moved from SessionRepositoryImpl) ----

    private fun List<ThreadListItem>.toSortedSessions(): List<Session> {
        val readTimestamps = settingsRepository.getSessionReadTimestamps()
        return mapNotNull { mapThreadListItemToSession(it, readTimestamps) }
            .sortedByDescending { it.lastActivityTime }
    }

    private fun applyThreadListUpdates(
        items: MutableList<ThreadListItem>,
        updates: List<ThreadListUpdate>,
    ) {
        for (update in updates) {
            when (update) {
                is ThreadListUpdate.Append -> items.addAll(update.values)
                is ThreadListUpdate.PushBack -> items.add(update.value)
                is ThreadListUpdate.PushFront -> items.add(0, update.value)
                is ThreadListUpdate.Insert -> {
                    val index = update.index.toInt()
                    if (index in 0..items.size) items.add(index, update.value) else items.add(update.value)
                }
                is ThreadListUpdate.Remove -> {
                    val idx = update.index.toInt()
                    if (idx in items.indices) items.removeAt(idx)
                }
                is ThreadListUpdate.Set -> {
                    val index = update.index.toInt()
                    if (index in items.indices) items[index] = update.value
                }
                is ThreadListUpdate.Reset -> { items.clear(); items.addAll(update.values) }
                is ThreadListUpdate.Clear -> items.clear()
                is ThreadListUpdate.Truncate -> {
                    val count = update.length.toInt()
                    while (items.size > count) items.removeAt(items.lastIndex)
                }
                is ThreadListUpdate.PopBack -> if (items.isNotEmpty()) items.removeAt(items.lastIndex)
                is ThreadListUpdate.PopFront -> if (items.isNotEmpty()) items.removeAt(0)
            }
        }
    }

    private fun mapThreadListItemToSession(
        item: ThreadListItem,
        readTimestamps: Map<String, Long>,
    ): Session? {
        val root = item.rootEvent
        val rootContent = root.content ?: return null
        val rootMsgLike = rootContent as? TimelineItemContent.MsgLike ?: return null
        if (rootMsgLike.content.kind is MsgLikeKind.Redacted) return null

        val latest = item.latestEvent ?: root
        val rootBody = root.content?.let { extractBodyFromTimelineItemContent(it) }
        val latestBody = latest.content?.let { extractBodyFromTimelineItemContent(it) }

        val title = rootBody?.take(50)?.let { if (rootBody.length > 50) "$it..." else it } ?: "Session"

        val lastReadMs = readTimestamps[root.eventId] ?: 0L
        val hasUnread = if (lastReadMs == 0L) {
            item.numReplies.toInt() > 0
        } else {
            latest.timestamp.toLong() > lastReadMs
        }

        return Session(
            id = root.eventId,
            title = title,
            lastMessage = latestBody ?: rootBody ?: "",
            lastActivityTime = Instant.ofEpochMilli(latest.timestamp.toLong()),
            replyCount = item.numReplies.toInt(),
            unreadCount = if (hasUnread) 1 else 0,
            isProcessing = false,
            senderAvatarUrl = extractAvatarUrl(root.senderProfile),
            latestEventId = (item.latestEvent ?: root).eventId,
        )
    }

    private fun extractBodyFromTimelineItemContent(content: TimelineItemContent): String? {
        val msgLike = content as? TimelineItemContent.MsgLike ?: return null
        val msgLikeContent: MsgLikeContent = msgLike.content
        val message = msgLikeContent.kind as? MsgLikeKind.Message ?: return null
        return message.content.body
    }

    private fun extractAvatarUrl(profile: ProfileDetails): String? = when (profile) {
        is ProfileDetails.Ready -> profile.avatarUrl
        else -> null
    }
}
