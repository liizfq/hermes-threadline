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
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.EventTimelineItem
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
 * Discovery back-pagination cap: walks the live timeline backwards this many
 * 50-event pages (worst case ~1000 events) to harvest historical non-thread
 * cards. Stops early when `paginateBackwards` reports the room start, or on
 * [ActiveInstance.closed] (teardown).
 */
private const val DISCOVERY_BACKPAGINATION_MAX_ITERATIONS = 20

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
 * Stale-rejection contract: every listener entry point checks [closed], and
 * [RoomSessionListStore.publish] gates the public projection on the active
 * room so a non-active / torn-down room's callback never reaches the public
 * flow. A callback racing with teardown becomes a no-op even before the
 * scope finishes cancelling.
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

    /**
     * Non-thread message "card" sessions harvested from the room's Live
     * (discovery) timeline. Keyed by event id; first-seen wins (edits keep
     * the same event id). Merged into [sessionsFlow] by [publish]; access is
     * serialized by `synchronized(cardSessions)`.
     */
    val cardSessions: MutableMap<String, Session> = LinkedHashMap()

    /** True while a shared refresh is paginating; listeners apply diffs but suppress publish. */
    val refreshInProgress: AtomicBoolean = AtomicBoolean(false)

    /** In-flight shared refresh; non-null only while a leader is running. */
    val refreshLeader: AtomicReference<Deferred<Unit>?> = AtomicReference(null)

    /**
     * This room's own session stream. The store's public
     * [RoomSessionListStore.sessions] is a *projection* of the currently
     * active room's flow; background (push) instances keep their own flow
     * here without touching the projection. Exposed read-only via
     * [RoomSessionListStore.sessionsForRoom].
     */
    val sessionsFlow: MutableStateFlow<List<Session>> = MutableStateFlow(emptyList())

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
 * store's active room — so a callback from a torn-down room cannot
 * accidentally refresh a different, currently-bound room.
 */
internal class DiscoveryListener(
    private val instance: ActiveInstance,
    /**
     * Harvest a non-thread message as a card [Session]. Receives the event,
     * its remote event id, and the already-extracted [MsgLikeContent].
     * Returns true iff a NEW card was inserted (dedup by event id).
     *
     * Defaults to a no-op so legacy callers (and the existing lifecycle
     * tests, which only exercise the refresh path via trailing-lambda
     * syntax) keep compiling; the production wiring in [buildDiscovery]
     * passes a real implementation.
     */
    private val harvestCard: (ev: EventTimelineItem, eventId: String, content: MsgLikeContent) -> Boolean = { _, _, _ -> false },
    /**
     * Publish the merged (thread + card) session list for this instance.
     * Invoked at most once per [onUpdate] when at least one card was added.
     * Defaults to a no-op; see [harvestCard].
     */
    private val publishCards: () -> Unit = {},
    // Kept LAST so the trailing-lambda call form `DiscoveryListener(instance) { ... }`
    // (used by tests and historically) binds to refresh, not the card callbacks.
    private val refresh: () -> Unit,
) : TimelineListener {

    @Volatile
    private var initialized = false

    override fun onUpdate(diffs: List<TimelineDiff>) {
        if (instance.closed) return
        var cardsChanged = false
        for (diff in diffs) {
            val items = when (diff) {
                is TimelineDiff.Append -> diff.values
                is TimelineDiff.PushBack -> listOf(diff.value)
                is TimelineDiff.PushFront -> listOf(diff.value)
                is TimelineDiff.Insert -> listOf(diff.value)
                is TimelineDiff.Set -> listOf(diff.value)
                // The initial subscription backfill (and a full re-sync)
                // arrives as Reset(values) — harvest those so historical
                // non-thread messages appear as cards too. Without this, the
                // first Reset delivered during addListener was dropped via the
                // `else` branch and no cards ever materialized.
                is TimelineDiff.Reset -> diff.values
                // The timeline was cleared (e.g. room left / encryption key
                // reset). Drop harvested cards so a later Reset rebuilds them
                // from scratch instead of leaving stale duplicates around.
                is TimelineDiff.Clear -> {
                    synchronized(instance.cardSessions) { instance.cardSessions.clear() }
                    cardsChanged = true
                    continue
                }
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

                // Harvest as a card session. This runs for EVERY matching
                // event — including the initial Reset backfill delivered
                // synchronously during addListener (before markInitialized) —
                // so historical non-thread messages appear as cards too. Dedup
                // is by event id; first-seen wins.
                if (harvestCard(ev, eventId, msgLike.content)) {
                    cardsChanged = true
                }

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
        // Publish once per onUpdate if any cards were added/changed, so the
        // merged list reaches the flow without waiting for the debounced
        // ThreadList refresh (which may not change anything for non-thread
        // messages).
        if (cardsChanged && !instance.closed) {
            publishCards()
        }
    }

    fun markInitialized() {
        initialized = true
    }
}

/**
 * Application-scoped single source of truth for session lists across the
 * active room and the user's push-room set (design §2.2 multi-instance).
 *
 * The store owns, **per started room** (keyed by roomId in [_instances]):
 *  - the [ThreadListService] and its items listener,
 *  - the room-level discovery timeline + listener (external session roots),
 *  - a per-room [ActiveInstance.sessionsFlow] (the active room's flow is
 *    projected onto the public [sessions]; background push rooms keep an
 *    isolated flow exposed via [sessionsForRoom]),
 *  - a single serial refresh path used by manual refresh, discovery, and
 *    push-triggered `refreshIfMissing` (single-flight per instance, all
 *    callers await the same leader).
 *
 * Lifecycle:
 *  - [ensureStarted] binds [roomId] as the **active** room and projects its
 *    flow onto [sessions]. It is idempotent for the same room (NoOp — caller
 *    keeps its handle). It does NOT tear down other rooms: starting a new
 *    active room simply re-points the projection; the previous room keeps
 *    running as a background instance. Only [stopForRoom], [shutdown],
 *    process death, or re-binding a stale same-room instance tear one down.
 *  - [ensureStartedForPushRoom] starts a **background** instance (refresh /
 *    cache only, no active side-effect) for a non-active push room.
 *  - The store stays alive across UI collector churn (no refCount), Activity
 *    recreation, and app backgrounding.
 *  - Stale callbacks are rejected by the [ActiveInstance.closed] flag and by
 *    cancelling the per-room child scope; the active projection is gated on
 *    [_activeRoomId] so a non-active room's publishes never leak into
 *    [sessions].
 *
 * Concurrency:
 *  - `stateLock` guards only [_instances] / [_slots] / [_activeRoomId]
 *    mutation and `markClosed`. Native teardown (TaskHandle.destroy,
 *    Timeline.close, ThreadListService.close, Room.close, scope.cancel) runs
 *    OUTSIDE the lock so it cannot stall other callers (UI thread, push
 *    worker) on slow FFI.
 *  - Start paths are serialized by `stateLock` so an instance is never
 *    created twice for the same room.
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

    /**
     * Multi-instance storage: every started room (the active room plus any
     * background push rooms started via [ensureStartedForPushRoom]) lives here,
     * keyed by roomId. The active room is whichever [_activeRoomId] points at;
     * its [ActiveInstance.sessionsFlow] is projected onto [sessions]. All
     * access is serialized by [stateLock].
     */
    private val _instances: MutableMap<String, ActiveInstance> = mutableMapOf()

    /**
     * Per-room lifecycle slot, retained so the same-room decision still flows
     * through the [RoomSessionListSlot] three-state machine (NoOp / Start).
     * Kept in sync with [_instances] presence: occupied while a live instance
     * exists for the room, closed otherwise. Cross-room Switch never tears a
     * room down (see [ensureStarted]).
     */
    private val _slots: MutableMap<String, RoomSessionListSlot> = mutableMapOf()

    /** The room whose [ActiveInstance.sessionsFlow] is projected onto [sessions]. */
    @Volatile
    private var _activeRoomId: String? = null

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    /**
     * Bind the store to [roomId] as the **active** room and project its
     * session stream onto [sessions].
     *
     * Contract:
     *  - Same-room NoOp: a live instance for [roomId] already exists. The
     *    store does NOT take ownership of [room] (the caller keeps and must
     *    close its handle); it only promotes [roomId] to the active
     *    projection. Returns false.
     *  - Start: no live instance exists for [roomId]. The store takes
     *    ownership of [room] (closes it on teardown), builds the pipeline, and
     *    makes [roomId] active. Returns true.
     *  - Setup failure: the store closes [room] itself. Returns false.
     *
     * Multi-instance (design §2.2): starting [roomId] does NOT tear down any
     * other room's instance — cross-room teardown is gone. Other rooms stay
     * alive in [_instances] (a previously-active room that is no longer
     * projected, or background push rooms). Only [stopForRoom] / [shutdown] /
     * re-binding a stale same-room instance tear one down.
     *
     * The synchronous SDK setup (threadListService + subscribeToItemsUpdates)
     * runs inside [stateLock]; suspend setup (pagination, discovery timeline)
     * is dispatched on the instance scope outside the lock.
     */
    fun ensureStarted(room: Room, roomId: String): Boolean =
        startLocked(room, roomId, makeActive = true)

    /**
     * Start (or reuse) a **background** instance for [roomId] — a non-active
     * room in the push set. It maintains its own [ActiveInstance.sessionsFlow]
     * and room-scoped cache (refresh / cache) but has NO active side-effect:
     * it never touches [sessions] or [_activeRoomId]. Used by the push
     * pipeline so pushes for non-active rooms can be served without binding
     * them as active.
     *
     * Returns true iff a fresh instance was created (the store then owns
     * [room]); false on same-room NoOp (caller keeps its handle) or setup
     * failure.
     */
    fun ensureStartedForPushRoom(room: Room, roomId: String): Boolean =
        startLocked(room, roomId, makeActive = false)

    /**
     * The room-scoped session [StateFlow] for [roomId], or null when no
     * instance is started for that room. For the active room this is the same
     * stream that backs [sessions]; for a background (push) room it is that
     * room's isolated stream. The returned flow is read-only.
     */
    fun sessionsForRoom(roomId: String): StateFlow<List<Session>>? {
        val instance = synchronized(stateLock) { _instances[roomId] } ?: return null
        return instance.sessionsFlow.asStateFlow()
    }

    /**
     * Switch the active projection to [roomId]. Called by the application
     * layer after [SettingsRepository.setActiveRoom] so that [sessions]
     * reflects the newly active room. Copies [roomId]'s current
     * [ActiveInstance.sessionsFlow] value into [sessions]; subsequent
     * publishes for [roomId] keep the projection in sync (publishes for other
     * rooms no longer touch it).
     *
     * If no instance is started for [roomId], the projection is cleared to
     * empty (the caller is expected to [ensureStarted] it).
     */
    fun setActiveProjection(roomId: String) {
        synchronized(stateLock) {
            _activeRoomId = roomId
            val instance = _instances[roomId]
            _sessions.value = instance?.sessionsFlow?.value ?: emptyList()
        }
    }

    /**
     * Tear down the instance for [roomId] only — used when a room is removed
     * from the push set. Other rooms are untouched. If [roomId] was the
     * active room, the public projection is cleared. Safe to call when no
     * instance exists for [roomId] (no-op).
     */
    fun stopForRoom(roomId: String) {
        val (toTeardown, wasActive) = synchronized(stateLock) {
            val instance = _instances.remove(roomId) ?: return
            instance.markClosed()
            _slots.remove(roomId)
            val wasActive = _activeRoomId == roomId
            if (wasActive) _activeRoomId = null
            instance to wasActive
        }
        teardownUnlocked(toTeardown)
        if (wasActive) {
            _sessions.value = emptyList()
        }
    }

    /**
     * Shared start path for [ensureStarted] ([makeActive] = true) and
     * [ensureStartedForPushRoom] ([makeActive] = false). See those methods for
     * the caller-facing contract; this does the slot decision, instance
     * creation, and pipeline launch.
     */
    private fun startLocked(room: Room, roomId: String, makeActive: Boolean): Boolean {
        val toTeardown: ActiveInstance?
        val instance: ActiveInstance
        val startFresh: Boolean
        synchronized(stateLock) {
            val slot = _slots.getOrPut(roomId) { RoomSessionListSlot() }
            val existing = _instances[roomId]
            val liveExisting = existing != null && !existing.closed
            // Keep the per-room slot in sync with actual instance presence so
            // decide() reflects reality (NoOp ⟺ live instance).
            if (liveExisting && slot.closed) slot.occupy(roomId)
            if (!liveExisting && !slot.closed) slot.close()

            when (slot.decide(roomId)) {
                StartDecision.NoOp -> {
                    // Same room already live: do NOT take the caller's handle.
                    Log.d(
                        TAG,
                        "${if (makeActive) "ensureStarted" else "ensureStartedForPushRoom"}: " +
                            "room $roomId already live, no-op (caller owns handle)"
                    )
                    instance = existing!!
                    toTeardown = null
                    startFresh = false
                }
                else -> {
                    // Start (a per-room slot can never return Switch; treat any
                    // non-NoOp as Start). Tear down only a STALE instance for
                    // THIS roomId — never another room.
                    Log.d(
                        TAG,
                        "${if (makeActive) "ensureStarted" else "ensureStartedForPushRoom"}: " +
                            "fresh start for $roomId"
                    )
                    existing?.markClosed()
                    toTeardown = existing
                    _instances.remove(roomId)
                    slot.close()
                    val newInstance = createInstanceLocked(room, roomId) ?: run {
                        slot.close()
                        return false
                    }
                    slot.occupy(roomId)
                    _instances[roomId] = newInstance
                    instance = newInstance
                    startFresh = true
                }
            }

            if (makeActive) {
                _activeRoomId = roomId
            }
        }

        // Native teardown of any stale same-room instance runs OUTSIDE
        // stateLock so it cannot block concurrent callers (UI / push).
        toTeardown?.let { teardownUnlocked(it) }

        if (startFresh) {
            launchInstancePipeline(instance)
        }

        if (makeActive) {
            // Project the now-active room's flow onto the public stream. For a
            // Start this surfaces the seeded cache immediately; for a NoOp
            // (promote) it re-points the projection at this room's flow.
            _sessions.value = instance.sessionsFlow.value
        }
        return startFresh
    }

    /**
     * Synchronous SDK setup for a fresh [ActiveInstance] for [roomId]. MUST be
     * called under [stateLock]. On failure the caller's [room] is already
     * closed and null is returned (the caller resets the slot). On success the
     * instance is fully wired (service handle subscribed) but NOT yet inserted
     * into [_instances] — the caller does that under the lock.
     */
    private fun createInstanceLocked(room: Room, roomId: String): ActiveInstance? {
        val service = try {
            room.threadListService()
        } catch (e: Exception) {
            Log.e(TAG, "start: threadListService() failed for $roomId", e)
            try { room.close() } catch (_: Exception) {}
            return null
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
            Log.e(TAG, "start: subscribeToItemsUpdates failed for $roomId", e)
            try { service.close() } catch (_: Exception) {}
            try { room.close() } catch (_: Exception) {}
            newScope.cancel()
            return null
        }
        newInstance.serviceHandle = handle
        return newInstance
    }

    /**
     * Seed the instance's own [ActiveInstance.sessionsFlow] from the
     * room-scoped cache and launch pagination + discovery. No projection
     * side-effect here: [publish] handles the active-room projection, and the
     * caller ([startLocked]) sets the projection when [makeActive] is true.
     */
    private fun launchInstancePipeline(instance: ActiveInstance) {
        // Seed the instance's own flow with whatever the cache holds so the
        // UI (active room, via projection) or [sessionsForRoom] (background)
        // never shows an empty list while pagination is in flight.
        val cached = settingsRepository.getSessionCache(instance.roomId)
        if (!cached.isNullOrEmpty() && !instance.closed) {
            instance.sessionsFlow.value = cached
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
                publish(instance, sessions)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "pipeline: pagination failed for ${instance.roomId}", e)
            }
        }

        // Discovery timeline is suspend to create; build it on the instance scope.
        instance.scope.launch {
            try {
                buildDiscovery(instance)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "pipeline: discovery setup failed for ${instance.roomId}", e)
            }
        }
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
        val instance = synchronized(stateLock) {
            val activeId = _activeRoomId ?: run {
                Log.w(TAG, "refresh: no active room")
                return
            }
            _instances[activeId]
        } ?: run {
            Log.w(TAG, "refresh: active instance missing for $_activeRoomId")
            return
        }
        if (instance.closed) return
        instance.scope.launch { runRefreshSingleFlight(instance) }
    }

    /**
     * Push-pipeline helper. Returns true iff an instance is started for
     * [roomId] (active OR background) so the store can serve the push. When
     * [threadRootId] is missing from that room's sessions, awaits a single
     * shared refresh on the captured instance; concurrent callers / multiple
     * pushes coalesce onto the same leader and all observe the post-refresh
     * state by the time this call returns.
     *
     * The push worker calls this (awaiting completion) before falling back to
     * the SDK root-body lookup. This never opens a per-thread focused timeline.
     */
    suspend fun refreshIfMissing(roomId: String, threadRootId: String?): Boolean {
        val instance = synchronized(stateLock) { _instances[roomId] } ?: return false
        if (instance.closed) return false
        if (threadRootId == null) return true
        val has = instance.sessionsFlow.value.any { it.id == threadRootId } ||
            settingsRepository.getSessionCache(instance.roomId)
                ?.any { it.id == threadRootId } == true
        if (!has) {
            runRefreshSingleFlight(instance)
        }
        return true
    }

    /**
     * Push-pipeline catch-up. Unlike [refreshIfMissing], this always refreshes
     * when an instance is started for [roomId]: an already-present root only
     * proves that a session exists, not that its `latestEventId` / reply count
     * has caught up to the just-received push. All simultaneous push workers
     * and discovery/timeline callers share [runRefreshSingleFlight].
     */
    suspend fun refreshForPush(roomId: String): Boolean {
        val instance = synchronized(stateLock) { _instances[roomId] } ?: return false
        if (instance.closed) return false
        runRefreshSingleFlight(instance)
        return true
    }

    /**
     * Tear down the active pipeline. Used on logout / explicit teardown. Safe
     * to call repeatedly; a no-op when nothing is active.
     */
    fun shutdown() {
        val toTeardown: List<ActiveInstance>
        synchronized(stateLock) {
            if (_instances.isEmpty()) return
            toTeardown = _instances.values.toList()
            for (instance in toTeardown) instance.markClosed()
            _instances.clear()
            _slots.clear()
            _activeRoomId = null
        }
        for (instance in toTeardown) teardownUnlocked(instance)
        _sessions.value = emptyList()
    }

    // ---- internal teardown / refresh / discovery ----

    /**
     * Tear down an [instance] WITHOUT holding [stateLock]. The caller has
     * already removed the instance from [_instances] (and reset its slot /
     * active-projection state under [stateLock]) and called
     * [ActiveInstance.markClosed] so in-flight listener callbacks are
     * no-ops. The caller is responsible for all [stateLock]-guarded mutation.
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
                publish(instance, sessions)
            }
        }

    /**
     * Publish [sessions] (or the current items cache if omitted) to the
     * instance's own [ActiveInstance.sessionsFlow] and the room-scoped cache,
     * and — only when this instance is the active room — to the public
     * [sessions] projection. This is the single chokepoint that enforces the
     * stale-rejection invariant before any side-effecting publish: a closed
     * instance publishes nothing; a non-active (background) instance refreshes
     * its own flow / cache but never leaks into the public projection.
     */
    private fun publish(instance: ActiveInstance, sessions: List<Session>? = null) {
        if (instance.closed) return
        val threadSessions =
            sessions ?: synchronized(instance.items) { instance.items.toSortedSessions() }
        // Merge non-thread card sessions harvested from the Live timeline.
        // Dedup rule: a thread root id wins over a card event id — if the same
        // event id appears in both (e.g. a non-thread message that later
        // became a thread root when the agent replied), evict the card so the
        // thread session is the single source of truth.
        val toPublish = mergeCards(instance, threadSessions)
        // Always update this room's own flow + room-scoped cache. Background
        // push rooms refresh/cache here; the active room does too.
        instance.sessionsFlow.value = toPublish
        settingsRepository.saveSessionCache(instance.roomId, toPublish)
        // Feed the event-id → thread-root-id index: every session contributes
        // both its root event id and its latest reply event id. This is the
        // ground-truth source for resolving m.replace pushes without an SDK
        // round-trip.
        saveEventThreadRootsFromSessions(instance.roomId, toPublish)
        // Project onto the public flow ONLY for the active room.
        if (_activeRoomId == instance.roomId) {
            _sessions.value = toPublish
        }
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
        // never re-resolves the active room at fire time, so a stale callback
        // from a torn-down room cannot trigger refresh on a different room.
        // The harvestCard / publishCards closures likewise bind to this
        // instance; publish() re-checks [ActiveInstance.closed] and the
        // active-projection guard before any side-effect.
        val listener = DiscoveryListener(
            instance = instance,
            refresh = {
                if (instance.closed) return@DiscoveryListener
                instance.scope.launch { runRefreshSingleFlight(instance) }
            },
            harvestCard = { ev, eventId, content ->
                harvestCardSession(instance, ev, eventId, content)
            },
            publishCards = {
                if (!instance.closed) publish(instance)
            },
        )
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

        // The live timeline window only holds recent events, so historical
        // non-thread messages live outside it. Walk history backwards so they
        // are delivered to onUpdate (as PushFront / Reset diffs) and harvested
        // as cards. Each page flows through the listener above, so harvesting
        // is incremental. The loop exits when the room start is reached, the
        // iteration cap is hit, or the instance is torn down (closed).
        instance.scope.launch {
            try {
                var iterations = 0
                while (iterations < DISCOVERY_BACKPAGINATION_MAX_ITERATIONS && !instance.closed) {
                    val hitStart = withContext(Dispatchers.IO) {
                        timeline.paginateBackwards(50u)
                    }
                    iterations++
                    if (hitStart) {
                        Log.d(
                            TAG,
                            "Card back-pagination reached room start for " +
                                "${instance.roomId} (iterations=$iterations)"
                        )
                        break
                    }
                }
                // Guarantee a final publish once pagination settles, in case
                // the listener did not fire its own cardsChanged publish for
                // the trailing page. publish() re-checks [ActiveInstance.closed].
                if (!instance.closed) publish(instance)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Card back-pagination failed for ${instance.roomId}", e)
            }
        }
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
            publish(instance)
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

    /**
     * Merge [threadSessions] with the non-thread card sessions harvested from
     * the Live timeline, applying the dedup rule: any card whose id collides
     * with a thread-session id is evicted from [ActiveInstance.cardSessions]
     * (the thread session wins). Returns the merged list sorted by
     * [Session.lastActivityTime] descending (newest first). When there are no
     * cards, returns [threadSessions] unchanged (already sorted) with no
     * extra allocation.
     */
    private fun mergeCards(instance: ActiveInstance, threadSessions: List<Session>): List<Session> {
        if (instance.closed) return threadSessions
        val threadIds = threadSessions.mapTo(HashSet(threadSessions.size)) { it.id }
        val cards: List<Session>
        synchronized(instance.cardSessions) {
            if (instance.cardSessions.isEmpty()) return threadSessions
            // Evict cards shadowed by a thread root (a non-thread message that
            // became a thread root after the agent replied).
            if (threadIds.isNotEmpty()) {
                val toEvict = instance.cardSessions.keys.filter { it in threadIds }
                if (toEvict.isNotEmpty()) {
                    for (id in toEvict) instance.cardSessions.remove(id)
                }
            }
            cards = instance.cardSessions.values.toList()
        }
        if (cards.isEmpty()) return threadSessions
        return (threadSessions + cards).sortedByDescending { it.lastActivityTime }
    }

    /**
     * Build a card [Session] from a non-thread Live-timeline message and insert
     * it into [ActiveInstance.cardSessions] (dedup by event id; first-seen
     * wins). Returns true iff a new card was inserted. Caller has already
     * verified the filter: remote, MsgLike, threadRoot == null,
     * kind is Message, not Redacted.
     */
    private fun harvestCardSession(
        instance: ActiveInstance,
        ev: EventTimelineItem,
        eventId: String,
        content: MsgLikeContent,
    ): Boolean {
        if (instance.closed) return false
        // Cards represent messages from the *other* party; skip our own so we
        // never surface a card for something we just sent.
        if (ev.isOwn) return false
        val message = content.kind as? MsgLikeKind.Message ?: return false
        val body = message.content.body
        val title = if (body.length > 50) body.take(50) + "..." else body
        val session = Session(
            id = eventId,
            title = title,
            lastMessage = body,
            lastActivityTime = Instant.ofEpochMilli(ev.timestamp.toLong()),
            replyCount = 0,
            unreadCount = 0,
            isProcessing = false,
            senderAvatarUrl = extractAvatarUrl(ev.senderProfile),
            latestEventId = eventId,
            isCard = true,
        )
        return synchronized(instance.cardSessions) {
            if (instance.cardSessions.containsKey(eventId)) {
                false
            } else {
                instance.cardSessions[eventId] = session
                true
            }
        }
    }
}
