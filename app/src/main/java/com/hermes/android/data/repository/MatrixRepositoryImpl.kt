package com.hermes.android.data.repository

import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.HttpPusherData
import org.matrix.rustcomponents.sdk.PusherIdentifiers
import org.matrix.rustcomponents.sdk.PusherKind
import org.matrix.rustcomponents.sdk.RoomLoadSettings
import org.matrix.rustcomponents.sdk.Session
import org.matrix.rustcomponents.sdk.SlidingSyncVersion
import org.matrix.rustcomponents.sdk.SlidingSyncVersionBuilder
import org.matrix.rustcomponents.sdk.SyncService
import org.matrix.rustcomponents.sdk.SyncServiceState
import org.matrix.rustcomponents.sdk.SyncServiceStateObserver
import org.unifiedpush.android.connector.UnifiedPush
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import com.hermes.android.push.PushSettings

private const val TAG = "MatrixRepo"

interface MatrixRepository {
    suspend fun login(homeserverUrl: String, username: String, password: String): Result<Client>
    suspend fun restoreSession(): Result<Client>?
    fun getClient(): Client?
    fun isForeground(): Boolean
    suspend fun registerPusher()
    suspend fun unregisterPusher()
    suspend fun logout()
}

@Singleton
class MatrixRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val sessionRepository: SessionRepository,
    private val sessionListStore: RoomSessionListStore,
    private val activeThreadStore: Lazy<ActiveThreadStore>,
    private val pushSettings: PushSettings
) : MatrixRepository, DefaultLifecycleObserver {
    private var client: Client? = null
    private var syncService: SyncService? = null
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
            Log.e(TAG, "Unhandled coroutine exception", throwable)
        }
    )
    private var lifecycleObserverRegistered = false

    /** Whether the app is currently in the foreground. */
    @Volatile
    private var foreground = false

    /**
     * Timestamp (ms) of when the app last went to background. 0 if app is in
     * foreground or has never been backgrounded.
     */
    private var lastBackgroundedAt: Long = 0L

    /** Serializes all SyncService start/stop lifecycle calls to avoid races. */
    private val syncLifecycleMutex = Mutex()

    /** Pending delayed-stop job; cancelled when returning to foreground. */
    private var stopSyncJob: Job? = null

    /**
     * Background collector over [SettingsRepository.observePushRoomIds] that
     * starts/stops store instances as rooms enter/leave the push set (design
     * §3.4). Seeded to the current set on first login/restore so the initial
     * re-emit does not re-start already-running rooms. Cancelled on logout.
     */
    @Volatile
    private var pushRoomCollectorJob: Job? = null

    /**
     * Last push set seen by [pushRoomCollectorJob], used to diff added/removed
     * rooms. Guarded by [syncLifecycleMutex] on writes to serialize against
     * logout teardown.
     */
    @Volatile
    private var lastObservedPushRooms: Set<String> = emptySet()

    /** Guards one-time startup of the push-room observer (set under syncLifecycleMutex). */
    @Volatile
    private var pushObserverStarted = false

    override fun onStart(owner: LifecycleOwner) {
        foreground = true
        val c = client
        val ss = syncService
        if (c != null && ss != null) {
            val bgDuration = if (lastBackgroundedAt > 0) {
                System.currentTimeMillis() - lastBackgroundedAt
            } else 0L
            lastBackgroundedAt = 0L

            // Cancel the pending delayed-stop job so it won't execute while we
            // restart sync. Short background (<3s) transitions skip the stop.
            val pendingJob = stopSyncJob
            stopSyncJob = null
            pendingJob?.cancel()
            Log.d(TAG, "SYNC_LIFECYCLE: cancel delayed stop=${pendingJob != null} bgDuration=${bgDuration}ms")

            scope.launch {
                try {
                    Log.d(TAG, "SYNC_LIFECYCLE: start begin")
                    // Serialize start with any in-flight stop to avoid races.
                    syncLifecycleMutex.withLock {
                        ss.start()
                    }
                    Log.d(TAG, "SYNC_LIFECYCLE: start end")
                    // Session list + active-thread catch-up run OUTSIDE the
                    // mutex so they cannot block stop. Order: ThreadList
                    // first (reconcile can then see the new latestEventId),
                    // then focused timeline /relations refresh for the
                    // currently open chat (if any).
                    sessionRepository.refreshSessions()
                    Log.d(TAG, "SYNC_LIFECYCLE: refreshSessions finished")
                    // Lazy breaks MatrixRepository <-> ActiveThreadStore cycle.
                    activeThreadStore.get().refreshActiveIfAny()
                    Log.d(TAG, "SYNC_LIFECYCLE: refreshActiveIfAny finished")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "SYNC_LIFECYCLE: failed to start sync / refresh sessions", e)
                }
            }
        } else {
            Log.d(TAG, "SYNC_LIFECYCLE: skip recovery client=${c != null} syncService=${ss != null}")
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        foreground = false
        lastBackgroundedAt = System.currentTimeMillis()
        Log.d(TAG, "SYNC_LIFECYCLE: schedule stop at $lastBackgroundedAt")

        // Cancel any existing delayed-stop job (safety), then schedule a new one.
        stopSyncJob?.cancel()
        stopSyncJob = scope.launch {
            try {
                delay(3000L)
                // Still in background after 3s — stop SyncService.
                Log.d(TAG, "SYNC_LIFECYCLE: stop begin")
                syncLifecycleMutex.withLock {
                    syncService?.stop()
                }
                Log.d(TAG, "SYNC_LIFECYCLE: stop end")
            } catch (e: CancellationException) {
                // Normal cancellation from onStart or logout; rethrow.
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "SYNC_LIFECYCLE: stop error", e)
            } finally {
                // Identity-safe cleanup: only clear if we are still the current
                // job. A newer onStop may have assigned stopSyncJob to a new
                // job by the time this finally runs; do not wipe it out.
                if (stopSyncJob === coroutineContext[Job]) {
                    stopSyncJob = null
                }
            }
        }
    }

    override fun isForeground(): Boolean = foreground

    private fun getDataFolderPath(): String {
        val path = context.filesDir.absolutePath + "/matrix"
        Log.d(TAG, "Matrix data folder: $path")
        return path
    }

    private fun getCacheFolderPath(): String {
        val path = context.cacheDir.absolutePath + "/matrix"
        Log.d(TAG, "Matrix cache folder: $path")
        return path
    }

    private suspend fun startSync(c: Client) {
        try {
            val versions = c.availableSlidingSyncVersions()
            Log.d(TAG, "Available sliding sync versions: $versions")
            val version = c.slidingSyncVersion()
            Log.d(TAG, "Client sliding sync version: $version")

            Log.d(TAG, "Creating SyncService...")
            val ss = c.syncService()
                .withSharePos(true)
                .finish()
            syncService = ss

            // Add state observer
            ss.state(object : SyncServiceStateObserver {
                override fun onUpdate(state: SyncServiceState) {
                    Log.d(TAG, "SyncService state: $state")
                }
            })

            Log.d(TAG, "Starting sync...")
            scope.launch {
                try {
                    // Serialize initial start with lifecycle callbacks.
                    syncLifecycleMutex.withLock {
                        ss.start()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Sync error", e)
                }
            }
            Log.d(TAG, "Sync started")
            if (!lifecycleObserverRegistered) {
                ProcessLifecycleOwner.get().lifecycle.addObserver(this)
                lifecycleObserverRegistered = true
                Log.d(TAG, "Registered lifecycle observer for sync management")
            }
            registerPusher()
            // Kick off the application-scoped session list for the bound room
            // so the push pipeline and post-login UI have a snapshot ready
            // without waiting for SessionList / Chat to open. Idempotent —
            // SessionListViewModel's later ensureStarted for the same room is
            // a NoOp.
            maybeStartSessionStore(c)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start sync", e)
        }
    }

    /**
     * Bind [sessionListStore] to every room in the user's push set once the
     * client is authenticated (design §3.3). The active room (legacy bound
     * room) is started first via [RoomSessionListStore.ensureStarted] so its
     * session stream becomes the active projection; the remaining push rooms
     * are started serially afterwards as background instances via
     * [RoomSessionListStore.ensureStartedForPushRoom] (refresh / discovery /
     * cache only, no UI side-effect). When the push set is empty we fall back
     * to the legacy single bound room.
     *
     * A room may take a few seconds to materialize via sync, so each room is
     * retried briefly. MVP is serial (design §7); per-room start is NoOp-safe.
     * Also kicks off (once) the dynamic push-set observer (§3.4) that starts
     * newly-selected rooms and stops deselected ones.
     */
    private fun maybeStartSessionStore(c: Client) {
        val rooms = effectivePushRooms()
        val activeRoomId = settingsRepository.getBoundRoomId()
        // Order: active room first (it owns the UI projection), then the rest.
        val ordered = buildList {
            if (activeRoomId != null && activeRoomId in rooms) add(activeRoomId)
            for (r in rooms) if (r != activeRoomId) add(r)
        }
        scope.launch {
            for (roomId in ordered) {
                val isActive = roomId == activeRoomId
                startRoomInstance(c, roomId, isActive)
            }
        }
        observePushRoomChanges(c)
    }

    /**
     * The rooms the session-list store should hold: the user's push set, with
     * a fallback to the legacy single bound room when the set is empty (design
     * §3.3, §2.1). The active room is whichever [getBoundRoomId] returns.
     */
    private fun effectivePushRooms(): Set<String> {
        val pushRooms = settingsRepository.getPushRoomIds()
        if (pushRooms.isNotEmpty()) return pushRooms
        val bound = settingsRepository.getBoundRoomId() ?: return emptySet()
        return setOf(bound)
    }

    /**
     * Fetch [roomId] from the client (retrying briefly while sync materializes
     * it) and bind a store instance. Active rooms go through [ensureStarted]
     * (UI projection ownership); non-active push rooms go through
     * [ensureStartedForPushRoom] (background). On a NoOp (room already live)
     * the duplicate handle is closed here.
     */
    private suspend fun startRoomInstance(c: Client, roomId: String, isActive: Boolean) {
        repeat(5) { attempt ->
            try {
                val room = withContext(Dispatchers.IO) { c.getRoom(roomId) }
                if (room != null) {
                    val tookOwnership = if (isActive) {
                        sessionListStore.ensureStarted(room, roomId)
                    } else {
                        sessionListStore.ensureStartedForPushRoom(room, roomId)
                    }
                    if (!tookOwnership) {
                        try { room.close() } catch (_: Exception) {}
                    }
                    return
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(
                    TAG,
                    "startRoomInstance: attempt ${attempt + 1} failed for $roomId: ${e.message}"
                )
            }
            if (attempt < 4) delay(1000)
        }
        Log.w(TAG, "startRoomInstance: room $roomId not available after 5 attempts")
    }

    /**
     * Subscribe (once) to push-set changes (design §3.4): rooms removed from
     * the set have their store instance torn down via [stopForRoom]; rooms
     * added are started (active room as active, others as background). This
     * closes the loop with [maybeStartSessionStore]'s login-time startup —
     * together they keep the running instances in sync with the push set while
     * the client is authenticated.
     *
     * The collector is seeded to the current set so the flow's initial re-emit
     * produces no diff and does not re-start already-running rooms.
     */
    private fun observePushRoomChanges(c: Client) {
        if (pushObserverStarted) return
        pushObserverStarted = true
        lastObservedPushRooms = effectivePushRooms()
        pushRoomCollectorJob?.cancel()
        pushRoomCollectorJob = scope.launch {
            settingsRepository.observePushRoomIds().collect { newSet ->
                val effective = if (newSet.isNotEmpty()) {
                    newSet
                } else {
                    // Legacy fallback: empty set still implies the bound room.
                    val bound = settingsRepository.getBoundRoomId()
                    if (bound != null) setOf(bound) else emptySet()
                }
                val old = lastObservedPushRooms
                if (effective == old) return@collect
                lastObservedPushRooms = effective

                val removed = old - effective
                val added = effective - old

                // Stop deselected rooms synchronously (fast; teardown runs
                // off the instance scope).
                for (roomId in removed) {
                    Log.d(TAG, "push set: stopForRoom $roomId")
                    sessionListStore.stopForRoom(roomId)
                }
                // Start newly-selected rooms without blocking the collector.
                // The active room (if newly added) is started as active so its
                // projection is owned; others as background.
                val activeRoomId = settingsRepository.getBoundRoomId()
                for (roomId in added) {
                    Log.d(TAG, "push set: startRoomInstance $roomId")
                    scope.launch { startRoomInstance(c, roomId, isActive = roomId == activeRoomId) }
                }
            }
        }
    }

    override suspend fun login(
        homeserverUrl: String,
        username: String,
        password: String
    ): Result<Client> {
        Log.d(TAG, "Starting login to $homeserverUrl")
        return try {
            Log.d(TAG, "Building client...")
            val c = withContext(Dispatchers.IO) {
                ClientBuilder()
                    .homeserverUrl(homeserverUrl)
                    .threadsEnabled(true, false)
                    .slidingSyncVersionBuilder(SlidingSyncVersionBuilder.NATIVE)
                    .sessionPaths(getDataFolderPath(), getCacheFolderPath())
                    .build()
            }
            Log.d(TAG, "Client built, calling login...")
            withContext(Dispatchers.IO) {
                c.login(username, password, null, "Hermes Threadline")
            }
            Log.d(TAG, "Login successful!")
            client = c
            val version = c.slidingSyncVersion()
            Log.d(TAG, "Sliding sync version after login: $version")
            startSync(c)
            val session = c.session()
            Log.d(TAG, "Session: userId=${session.userId}, deviceId=${session.deviceId}")
            settingsRepository.saveLogin(
                homeserverUrl = homeserverUrl,
                userId = session.userId,
                accessToken = session.accessToken,
                deviceId = session.deviceId
            )
            settingsRepository.saveSlidingSyncVersion(version.name)
            Result.success(c)
        } catch (e: Exception) {
            Log.e(TAG, "Login failed", e)
            Result.failure(e)
        }
    }

    override suspend fun restoreSession(): Result<Client>? {
        val homeserverUrl = settingsRepository.getHomeserverUrl() ?: return null
        val accessToken = settingsRepository.getAccessToken() ?: return null
        val userId = settingsRepository.getUserId() ?: return null
        val deviceId = settingsRepository.getDeviceId()
        val savedVersion = settingsRepository.getSlidingSyncVersion()

        Log.d(TAG, "Restoring session for $userId, savedVersion=$savedVersion")
        return try {
            val c = withContext(Dispatchers.IO) {
                ClientBuilder()
                    .homeserverUrl(homeserverUrl)
                    .threadsEnabled(true, false)
                    .slidingSyncVersionBuilder(SlidingSyncVersionBuilder.NATIVE)
                    .sessionPaths(getDataFolderPath(), getCacheFolderPath())
                    .build()
            }
            val version = SlidingSyncVersion.NATIVE
            Log.d(TAG, "Using sliding sync version: $version")
            val session = Session(
                userId = userId,
                accessToken = accessToken,
                deviceId = deviceId ?: "",
                homeserverUrl = homeserverUrl,
                oauthData = null,
                refreshToken = null,
                slidingSyncVersion = version
            )
            withContext(Dispatchers.IO) {
                try {
                    c.restoreSessionWith(session, RoomLoadSettings.All)
                } catch (e: Exception) {
                    Log.e(TAG, "restoreSessionWith failed (SDK internal coroutine)", e)
                    throw e  // Re-throw to be caught by outer catch
                }
            }
            Log.d(TAG, "Session restored successfully")
            client = c

            val rooms = c.rooms()
            Log.d(TAG, "Rooms loaded: ${rooms.size}")
            for (room in rooms) {
                Log.d(TAG, "  Room: ${room.id()} - ${room.displayName()}")
            }

            startSync(c)
            Result.success(c)
        } catch (e: Exception) {
            Log.e(TAG, "Session restore failed", e)
            settingsRepository.clear()
            Result.failure(e)
        }
    }

    override fun getClient(): Client? = client

    override suspend fun registerPusher() {
        if (!pushSettings.enabled || !pushSettings.usesNtfy()) {
            Log.d(TAG, "registerPusher skipped: push not enabled or not ntfy channel")
            return
        }
        val c = client ?: run {
            Log.w(TAG, "registerPusher skipped: no client")
            return
        }
        val url = pushSettings.ntfyServerUrl.ifBlank { return }

        try {
            // Step 1: Check if we already have a valid endpoint
            val existingEndpoint = pushSettings.ntfyEndpoint
            if (existingEndpoint.isNotBlank() && pushSettings.upToken.isNotBlank()) {
                Log.d(TAG, "Already registered with endpoint: $existingEndpoint")

                // Step 1a: Verify the pusher is registered with Synapse
                // Try to register again (idempotent on Synapse side)
                val identifiers = PusherIdentifiers(existingEndpoint, "com.hermes.android.ntfy")
                val gatewayUrl = "${url.trimEnd('/')}/_matrix/push/v1/notify"
                val data = HttpPusherData(gatewayUrl, null, null)
                val kind = PusherKind.Http(data)
                withContext(Dispatchers.IO) {
                    c.setPusher(
                        identifiers = identifiers,
                        kind = kind,
                        appDisplayName = "Hermes Threadline",
                        deviceDisplayName = "",
                        profileTag = "",
                        lang = "zh-CN",
                        append = true
                    )
                }
                Log.d(TAG, "Pusher re-registered: pushkey=$existingEndpoint gateway=$gatewayUrl")
                return
            }

            // Step 2: No existing endpoint — register with ntfy via UnifiedPush
            Log.d(TAG, "No existing endpoint, registering via UnifiedPush...")

            // Save distributor (ntfy app)
            UnifiedPush.saveDistributor(context, "io.heckel.ntfy")

            // Generate a unique client secret for this registration
            val clientSecret = "hermes-${UUID.randomUUID()}"
            pushSettings.upToken = clientSecret

            // Register — this sends the REGISTER broadcast to ntfy app
            // The HermesUnifiedPushReceiver.onNewEndpoint() will be called when ntfy responds
            UnifiedPush.register(context = context, instance = clientSecret)

            Log.d(TAG, "UnifiedPush registration initiated with clientSecret=$clientSecret")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register pusher", e)
        }
    }

    override suspend fun unregisterPusher() {
        val c = client ?: return
        val url = pushSettings.ntfyServerUrl.ifBlank { return }

        try {
            // Step 1: Unregister from the Matrix homeserver (Synapse)
            val pushkey = if (pushSettings.ntfyEndpoint.isNotBlank()) {
                pushSettings.ntfyEndpoint
            } else {
                Log.w(TAG, "unregisterPusher: no endpoint stored, skipping Synapse pusher removal")
                null
            }

            if (pushkey != null) {
                val identifiers = PusherIdentifiers(pushkey, "com.hermes.android.ntfy")
                withContext(Dispatchers.IO) {
                    c.deletePusher(identifiers)
                }
                Log.d(TAG, "Pusher unregistered from Synapse")
            }

            // Step 2: Unregister from UnifiedPush (ntfy app)
            val token = pushSettings.upToken
            if (token.isNotBlank()) {
                UnifiedPush.unregister(context, token)
                Log.d(TAG, "UnifiedPush unregistered: $token")
            }

            // Step 3: Clear stored settings
            pushSettings.ntfyEndpoint = ""
            pushSettings.upToken = ""

            Log.d(TAG, "Pusher fully unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister pusher", e)
        }
    }

    override suspend fun logout() {
        unregisterPusher()

        // Cancel any pending delayed-stop job so it won't race with us.
        stopSyncJob?.cancel()
        stopSyncJob = null

        // Cancel the push-set observer so it stops starting/stopping rooms
        // while we tear down, and reset its state so the next login/restore
        // can re-seed. shutdown() below disposes all running instances.
        pushRoomCollectorJob?.cancel()
        pushRoomCollectorJob = null
        pushObserverStarted = false
        lastObservedPushRooms = emptySet()

        // Tear down the application-scoped session-list pipeline so the next
        // login / restore can rebind cleanly to a (possibly different) room.
        sessionListStore.shutdown()

        // Tear down the application-scoped focused-thread timeline so the
        // next login / restore reopens fresh; logout is one of the only paths
        // (with switch-thread / switch-room / process death) that closes it.
        // Lazy breaks the MatrixRepository -> ActiveThreadStore -> RoomRepository
        // -> MatrixRepository DI cycle.
        activeThreadStore.get().closeActive()

        try {
            // Serialize stop with lifecycle callbacks via the shared mutex.
            syncLifecycleMutex.withLock {
                syncService?.stop()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        try {
            withContext(Dispatchers.IO) {
                client?.logout()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        syncService = null
        client = null
        settingsRepository.clear()
    }
}
