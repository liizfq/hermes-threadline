package com.hermes.android.data.repository

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.hermes.android.domain.model.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.ImageInfo
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.MsgLikeContent
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.ProfileDetails
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.TextMessageContent
import org.matrix.rustcomponents.sdk.ThreadListEntriesListener
import org.matrix.rustcomponents.sdk.ThreadListItem
import org.matrix.rustcomponents.sdk.ThreadListUpdate
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.TimelineListener
import org.matrix.rustcomponents.sdk.UploadParameters
import org.matrix.rustcomponents.sdk.UploadSource
import org.matrix.rustcomponents.sdk.VideoInfo
import uniffi.matrix_sdk_ui.ThreadListPaginationState
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SessionRepo"

/**
 * Discovery state for one shared room subscription. Carries the room-level
 * timeline belonging to this discovery subscription, the async scope used for
 * the debounce scheduling, and the de-duplication set of already-seen event ids.
 *
 * Lifecycle is tied to the owning [SharedThreadList.refCount]: created when the
 * first collector joins, torn down when the last collector leaves.
 */
private data class DiscoveryResources(
    val timeline: Timeline,
    @Volatile var handle: org.matrix.rustcomponents.sdk.TaskHandle? = null,
    val scope: CoroutineScope,
    @Volatile var pendingJob: Job? = null,
    val seenEventIds: MutableSet<String> = mutableSetOf(),
)

/**
 * Timeline listener that detects externally-created session root events
 * (events from another client) which [ThreadListService] does not auto-discover.
 *
 * Besides the incremental [TimelineDiff] forms (Append/PushBack/PushFront/Insert/Set),
 * some Reset items can be synchronously delivered by FFI when [Timeline.addListener]
 * first runs; those are ignored, and scheduling is gated on [markInitialized],
 * which flips to `true` only after `addListener()` returns.
 *
 * Any root event matching the predicate is merged into a single 500 ms debounced
 * refresh call via the same [refresh] closure that powers manual refreshes — there
 * is no second reset+paginate loop inside discovery.
 */
private class DiscoveryListener(
    private val resources: DiscoveryResources,
    private val refresh: suspend () -> Unit,
) : TimelineListener {

    @Volatile
    private var initialized = false

    override fun onUpdate(diffs: List<TimelineDiff>) {
        for (diff in diffs) {
            // Only the incremental diff variants carry meaningful items;
            // Reset (and the initial snapshot) is intentionally ignored so that
            // pre-existing messages do not trigger a refresh.
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
                // Only a remote (synced) echo; skip local echo.
                if (!ev.isRemote) continue
                // Must have a concrete EventId (skip TransactionId echoes).
                if (ev.eventOrTransactionId !is EventOrTransactionId.EventId) continue
                // Need MsgLike content so we can read threadRoot + kind.
                val msgLike = ev.content as? TimelineItemContent.MsgLike ?: continue
                // threadRoot != null -> this event is inside a thread -> not a root.
                if (msgLike.content.threadRoot != null) continue
                val kind = msgLike.content.kind
                // Drop redacted and anything that isn't a plain Message.
                if (kind is MsgLikeKind.Redacted) continue
                if (kind !is MsgLikeKind.Message) continue
                val eventId =
                    (ev.eventOrTransactionId as EventOrTransactionId.EventId).eventId

                val scope = resources.scope
                // Decide inside the lock whether to schedule; act on the
                // result outside. This avoids `continue` inside an inline
                // lambda (experimental feature) and reads the scope's active
                // state through its Job.
                val shouldSchedule = synchronized(resources.seenEventIds) {
                    // Skip if we've already scheduled a debounce for this event.
                    if (!resources.seenEventIds.add(eventId)) {
                        false
                    } else if (!initialized) {
                        // Still inside the initial Reset delivery — don't schedule.
                        false
                    } else {
                        scope.coroutineContext[Job]?.isActive == true
                    }
                }
                if (shouldSchedule) {
                    // Coalesce new candidates into a single debounce job.
                    resources.pendingJob?.cancel()
                    resources.pendingJob = scope.launch {
                        try {
                            delay(500L)
                            refresh()
                        } catch (e: Throwable) {
                            Log.e(TAG, "discovery debounced refresh failed", e)
                        }
                    }
                }
            }
        }
    }

    fun markInitialized() {
        initialized = true
    }
}

private fun teardownDiscovery(resources: DiscoveryResources) {
    resources.pendingJob?.cancel()
    resources.handle?.destroy()
    resources.timeline.close()
    resources.scope.cancel()
    resources.seenEventIds.clear()
}

/**
 * Room-level shared ThreadList subscription. Multiple collectors (SessionListVM,
 * ChatVM reconcile) observe the same underlying [ThreadListService] via a single
 * listener + [MutableSharedFlow]. [refCount] tracks active collectors; the service
 * is torn down only when the last collector disconnects.
 *
 * The [discovery] timeline/listener is shared the same way: one per room, created
 * when the first collector joins and torn down when the last collector leaves.
 */
private data class SharedThreadList(
    val roomId: String,
    val service: org.matrix.rustcomponents.sdk.ThreadListService,
    val items: ArrayList<ThreadListItem>,
    val updates: MutableSharedFlow<List<Session>>,
    var refCount: Int,
    val refresh: suspend () -> Unit,
    var handle: org.matrix.rustcomponents.sdk.TaskHandle?,
    var discovery: DiscoveryResources? = null,
)

/**
 * Manages room-level session concerns: ThreadListService binding, session list
 * observation/refresh, session creation, and session deletion.
 *
 * ActiveThread lifecycle is now owned by [ChatViewModel] via [ActiveThreadFactory].
 *
 * ThreadListService binding is room-level shared: multiple callers invoking
 * [observeSessions] on the same room share a single subscription (refcounted).
 * External-session discovery reuses the same [refresh] closure and the same
 * [refreshInProgress] flag, so discovery-triggered refreshes are de-duplicated
 * against manual or ThreadList-triggered refreshes.
 */
@Singleton
class SessionRepositoryImpl @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : SessionRepository {

    // ---- Room-level shared ThreadList subscription ----
    // Guarded by sharedLock. Use `synchronized(sharedLock)` for both suspend
    // and non-suspend contexts (awaitClose can't call Mutex.withLock).
    private var _shared: SharedThreadList? = null
    private val sharedLock = Any()

    override fun observeSessions(room: Room): Flow<List<Session>> = callbackFlow {
        val roomId = room.id()
        Log.d(TAG, "SessionRepositoryImpl.observeSessions: room $roomId")

        val incoming: MutableSharedFlow<List<Session>>
        val shared: SharedThreadList

        shared = synchronized(sharedLock) {
            val current = _shared
            if (current != null && current.roomId == roomId) {
                // Same room: join the existing subscription.
                current.refCount++
                Log.d(TAG, "DIAG ThreadList shared open ref=${current.refCount} (reuse)")
                current
            } else {
                // Different room or no existing subscription: tear down old, create new.
                if (current != null) {
                    Log.d(TAG, "DIAG ThreadList shared: closing room=${current.roomId} for room=$roomId")
                    current.discovery?.let(::teardownDiscovery)
                    current.discovery = null
                    current.handle?.destroy()
                    current.handle = null
                    current.service.close()
                }

                val items = ArrayList<ThreadListItem>()
                val updates = MutableSharedFlow<List<Session>>(replay = 1)
                val service = room.threadListService()
                // refresh/listener built outside synchronized critical-section analysis;
                // buildSharedRefresh is non-suspend so this call is safe here.
                val (refresh, handle, _) = buildSharedRefresh(service, items, updates)

                val newInstance = SharedThreadList(
                    roomId = roomId,
                    service = service,
                    items = items,
                    updates = updates,
                    refCount = 1,
                    refresh = refresh,
                    handle = handle,
                    discovery = null,
                )
                _shared = newInstance
                Log.d(TAG, "DIAG ThreadList shared open ref=1 (new)")
                newInstance
            }
        }
        incoming = shared.updates

        // Discovery setup runs in the suspend callbackFlow builder, NOT inside
        // `synchronized(sharedLock)`, so callers never wrap suspension points
        // inside a critical section. Exactly one collector per room ends up
        // publishing its DiscoveryResources to _shared.discovery; any duplicate
        // (racing collector) tears its own duplicate down.
        if (shared.discovery == null) {
            val resources = buildSharedDiscovery(room, shared.refresh)
            synchronized(sharedLock) {
                if (_shared === shared && _shared?.discovery == null) {
                    _shared?.discovery = resources
                } else {
                    // Another collector won the race, or shared was replaced
                    // while we were suspended. Tear down our duplicate.
                    resources.pendingJob?.cancel()
                    resources.handle?.destroy()
                    resources.timeline.close()
                    resources.scope.cancel()
                }
            }
        }

        // Emit cached + current items immediately to the new collector.
        val existingShared = _shared
        if (existingShared != null) {
            val cachedSessions = settingsRepository.getSessionCache()
            if (cachedSessions != null && cachedSessions.isNotEmpty()) {
                trySendBlocking(cachedSessions)
            }
            val currentItems = synchronized(existingShared.items) {
                existingShared.items.toSortedSessions()
            }
            if (currentItems.isNotEmpty()) {
                trySendBlocking(currentItems)
            }
        }

        // Start initial pagination (only needed if items aren't yet populated).
        val paginationJob = launch(Dispatchers.IO) {
            try {
                val svc = _shared?.service ?: return@launch
                var paginationDone = false
                var iterations = 0
                while (!paginationDone && iterations < 100) {
                    svc.paginate()
                    iterations++
                    val state = svc.paginationState()
                    if (state is ThreadListPaginationState.Idle && state.endReached) {
                        paginationDone = true
                        Log.d(TAG, "SessionRepositoryImpl.ThreadList pagination complete for room $roomId (iterations=$iterations)")
                    }
                }
                val current = _shared ?: return@launch
                val finalSessions = synchronized(current.items) { current.items.toSortedSessions() }
                if (finalSessions.isNotEmpty()) {
                    settingsRepository.saveSessionCache(finalSessions)
                    current.updates.tryEmit(finalSessions)
                }
            } catch (e: Exception) {
                Log.e(TAG, "SessionRepositoryImpl.ThreadList pagination error", e)
            }
        }

        // Collect shared updates and forward to this collector's channel.
        val collectJob = launch {
            incoming.collect { sessions ->
                trySendBlocking(sessions)
            }
        }

        awaitClose {
            Log.d(TAG, "SessionRepositoryImpl.observeSessions: Closing for room $roomId")
            collectJob.cancel()
            paginationJob.cancel()
            synchronized(sharedLock) {
                val current = _shared
                if (current != null && current.roomId == roomId) {
                    current.refCount--
                    Log.d(TAG, "DIAG ThreadList shared close ref=${current.refCount}")
                    if (current.refCount <= 0) {
                        Log.d(TAG, "DIAG ThreadList shared: last collector left, tearing down")
                        current.handle?.destroy()
                        current.handle = null
                        current.discovery?.let(::teardownDiscovery)
                        current.discovery = null
                        try {
                            current.service.close()
                        } catch (_: Exception) {}
                        _shared = null
                    }
                } else if (current != null && current.roomId != roomId) {
                    // We never actually joined this shared instance (created after us); do nothing.
                    Log.d(TAG, "SessionRepositoryImpl.observeSessions: mismatched room in awaitClose")
                }
            }
        }
    }.distinctUntilChanged()

    override suspend fun refreshSessions() {
        val current = _shared
        if (current == null) {
            Log.w(TAG, "SessionRepositoryImpl.refreshSessions: no active session list flow")
            return
        }
        current.refresh()
    }

    /**
     * Build refresh closure + ThreadList listener. Non-suspend so it is safe to call
     * from inside `synchronized`.
     */
    private fun buildSharedRefresh(
        service: org.matrix.rustcomponents.sdk.ThreadListService,
        items: ArrayList<ThreadListItem>,
        updates: MutableSharedFlow<List<Session>>,
    ): Triple<suspend () -> Unit, org.matrix.rustcomponents.sdk.TaskHandle?, AtomicBoolean> {
        val refreshMutex = Mutex()
        val refreshInProgress = AtomicBoolean(false)

        val refresh: suspend () -> Unit = {
            if (refreshInProgress.compareAndSet(false, true)) {
                try {
                    refreshMutex.withLock {
                        Log.d(TAG, "SessionRepositoryImpl.refresh: reset + paginate")
                        withContext(Dispatchers.IO) {
                            service.reset()
                            var iterations = 0
                            while (iterations < 100) {
                                service.paginate()
                                iterations++
                                val state = service.paginationState()
                                if (state is ThreadListPaginationState.Idle && state.endReached) break
                            }
                        }
                        val sessions = synchronized(items) { items.toSortedSessions() }
                        if (sessions.isNotEmpty()) {
                            settingsRepository.saveSessionCache(sessions)
                            logThreadListLatest("refresh", items, sessions)
                            Log.d(TAG, "SessionRepositoryImpl.refresh: emitting ${sessions.size} sessions")
                            updates.emit(sessions)
                        } else {
                            Log.d(TAG, "SessionRepositoryImpl.refresh: items pending Reset diff, skip emit to avoid white screen")
                        }
                    }
                } finally {
                    refreshInProgress.set(false)
                }
            } else {
                Log.d(TAG, "SessionRepositoryImpl.refresh: already in progress, skip")
            }
        }

        val handle = service.subscribeToItemsUpdates(object : ThreadListEntriesListener {
            override fun onUpdate(tlUpdates: List<ThreadListUpdate>) {
                Log.d(TAG, "SessionRepositoryImpl.ThreadList listener fired: ${tlUpdates.size} updates")
                val updatedSessions = synchronized(items) {
                    applyThreadListUpdates(items, tlUpdates)
                    Log.d(TAG, "SessionRepositoryImpl.ThreadListUpdate: ${tlUpdates.size} updates applied, total items=${items.size}")
                    if (refreshInProgress.get()) {
                        Log.d(TAG, "SessionRepositoryImpl.ThreadList listener: refresh in progress, skipping emit")
                        return
                    }
                    items.toSortedSessions()
                }
                synchronized(items) {
                    logThreadListLatest("listener", items, updatedSessions)
                }
                if (updatedSessions.isNotEmpty()) {
                    settingsRepository.saveSessionCache(updatedSessions)
                }
                updates.tryEmit(updatedSessions)
            }
        })
        return Triple(refresh, handle, refreshInProgress)
    }

    /**
     * Create the DiscoveryResources for a fresh room subscription. Suspends on
     * `room.timeline()` and `timeline.addListener(listener)`.
     *
     * The listener flips to `initialized = true` only after `addListener()` returns,
     * so any initial Reset delivered synchronously during addListener is ignored.
     */
    private suspend fun buildSharedDiscovery(
        room: Room,
        refresh: suspend () -> Unit,
    ): DiscoveryResources {
        val timeline = room.timeline()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val resources = DiscoveryResources(
            timeline = timeline,
            handle = null,
            scope = scope,
            pendingJob = null,
            seenEventIds = mutableSetOf(),
        )

        val listener = DiscoveryListener(resources, refresh)
        val handle = timeline.addListener(listener)
        // Publish the handle and mark listener initialized AFTER addListener() returns,
        // so any initial Reset delivered synchronously is treated as "not yet active".
        resources.handle = handle
        listener.markInitialized()

        return resources
    }

    override suspend fun createSession(room: Room, content: String): Result<String> {
        Log.d(TAG, "createSession: Creating session with content: $content")
        return try {
            val textContent = TextMessageContent(content, null)
            val messageType = MessageType.Text(textContent)
            val timeline = room.timeline()
            val messageContent = timeline.createMessageContent(messageType)
                ?: return Result.failure(IllegalStateException("Failed to create message content"))
            timeline.send(messageContent)
            Log.d(TAG, "createSession: Message sent successfully!")
            timeline.close()
            Result.success("")
        } catch (e: Exception) {
            Log.e(TAG, "createSession failed", e)
            Result.failure(e)
        }
    }

    override suspend fun createSession(
        room: Room,
        content: String,
        title: String?,
        attachmentUri: Uri?,
        attachmentType: String?,
        context: Context?
    ): Result<String> {
        Log.d(TAG, "createSession: content=${content.take(50)}, title=$title, attachment=$attachmentType")
        return try {
            val timeline = room.timeline()
            val completable = kotlinx.coroutines.CompletableDeferred<String>()

            val listener = object : TimelineListener {
                override fun onUpdate(diffs: List<TimelineDiff>) {
                    for (diff in diffs) {
                        val items = when (diff) {
                            is TimelineDiff.PushBack -> listOf(diff.value)
                            is TimelineDiff.Append -> diff.values
                            is TimelineDiff.Set -> listOf(diff.value)
                            else -> emptyList()
                        }
                        for (item in items) {
                            val ev = item.asEvent() ?: continue
                            if (ev.isOwn && ev.eventOrTransactionId is EventOrTransactionId.EventId) {
                                val eid = (ev.eventOrTransactionId as EventOrTransactionId.EventId).eventId
                                Log.d(TAG, "createSession: captured event id=$eid")
                                completable.complete(eid)
                                return
                            }
                        }
                    }
                }
            }
            val handle = timeline.addListener(listener)

            if (attachmentUri != null && context != null) {
                sendAttachmentAsRoot(timeline, attachmentUri, attachmentType ?: "file", content, context)
            } else {
                val textContent = TextMessageContent(content, null)
                val messageType = MessageType.Text(textContent)
                val messageContent = timeline.createMessageContent(messageType)
                    ?: return Result.failure(IllegalStateException("Failed to create message content"))
                timeline.send(messageContent)
            }

            val eventId = withTimeoutOrNull(15_000L) { completable.await() }
            handle.destroy()
            timeline.close()

            if (!eventId.isNullOrBlank()) {
                Log.d(TAG, "createSession: success, eventId=$eventId")
                Result.success(eventId)
            } else {
                Log.w(TAG, "createSession: timed out waiting for event id")
                Result.failure(Exception("Timed out waiting for event id"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "createSession(with attachment) failed", e)
            Result.failure(e)
        }
    }

    override suspend fun deleteSession(room: Room, threadRootId: String): Result<Unit> = runCatching {
        Log.d(TAG, "deleteSession: redacting thread root $threadRootId")
        withContext(Dispatchers.IO) {
            room.redact(threadRootId, null)
        }
        Log.d(TAG, "deleteSession: redacted, refreshing session list")
        refreshSessions()
    }

    // ---- ThreadList diffing / mapping helpers ----

    private fun List<ThreadListItem>.toSortedSessions(): List<Session> {
        val readTimestamps = settingsRepository.getSessionReadTimestamps()
        return mapNotNull { mapThreadListItemToSession(it, readTimestamps) }
            .sortedByDescending { it.lastActivityTime }
    }

    private fun applyThreadListUpdates(items: MutableList<ThreadListItem>, updates: List<ThreadListUpdate>) {
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

    private fun mapThreadListItemToSession(item: ThreadListItem, readTimestamps: Map<String, Long>): Session? {
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
            latestEventId = (item.latestEvent ?: root).eventId
        )
    }

    /**
     * DIAG helper: log ThreadList latest event ids so we can compare against
     * ActiveThread/ChatVM last message ids for the same threadRootId.
     *
     * Layer meaning:
     * - ThreadList has latestEventId, ActiveThread does not → TEC/aggregator gap
     * - ThreadList also missing → sync/sliding-sync gap
     */
    private fun logThreadListLatest(
        source: String,
        items: List<ThreadListItem>,
        sessions: List<com.hermes.android.domain.model.Session>,
    ) {
        if (items.isEmpty()) {
            Log.d(TAG, "DIAG ThreadList[$source]: empty items=0 sessions=${sessions.size}")
            return
        }
        val top = sessions.take(5)
        for (session in top) {
            val item = items.firstOrNull { it.rootEvent.eventId == session.id }
            val latest = item?.latestEvent ?: item?.rootEvent
            val latestId = latest?.eventId ?: "null"
            val latestTs = latest?.timestamp?.toLong() ?: -1L
            Log.d(
                TAG,
                "DIAG ThreadList[$source]: root=${session.id} latestEventId=$latestId " +
                    "latestTs=$latestTs replies=${session.replyCount} unread=${session.unreadCount} " +
                    "preview=${session.lastMessage?.take(40)}"
            )
        }
        if (sessions.size > 5) {
            Log.d(TAG, "DIAG ThreadList[$source]: ... ${sessions.size - 5} more sessions omitted")
        }
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

    private suspend fun sendAttachmentAsRoot(
        timeline: Timeline,
        uri: Uri,
        type: String,
        caption: String,
        context: Context
    ) {
        val resolver = context.contentResolver
        var fileName = "file"
        var fileSize = 0L
        val cursor = resolver.query(uri, null, null, null, null)
        if (cursor != null && cursor.moveToFirst()) {
            val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (nameIdx >= 0) fileName = cursor.getString(nameIdx) ?: fileName
            if (sizeIdx >= 0) fileSize = cursor.getLong(sizeIdx)
            cursor.close()
        }
        val mimeType = resolver.getType(uri) ?: "application/octet-stream"
        val tempFile = java.io.File(context.cacheDir, "newsession_${System.currentTimeMillis()}_$fileName")
        resolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output -> input.copyTo(output) }
        }
        val uploadSource = UploadSource.File(tempFile.absolutePath)
        val captionParam: String? = caption.ifBlank { null }

        when (type) {
            "image" -> {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                val imageInfo = ImageInfo(
                    height = options.outHeight.toULong(),
                    width = options.outWidth.toULong(),
                    mimetype = options.outMimeType ?: "image/jpeg",
                    size = (if (fileSize > 0L) fileSize else tempFile.length()).toULong(),
                    thumbnailInfo = null, thumbnailSource = null, blurhash = null, isAnimated = null
                )
                val params = UploadParameters(source = uploadSource, caption = captionParam, formattedCaption = null, mentions = null, inReplyTo = null)
                val handle = timeline.sendImage(params, null, imageInfo)
                handle.join()
            }
            "video" -> {
                var durationMs = 0L; var width = 0; var height = 0
                runCatching {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(context, uri)
                    durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    retriever.release()
                }
                val videoInfo = VideoInfo(
                    duration = if (durationMs > 0) java.time.Duration.ofMillis(durationMs) else null,
                    width = width.toULong(), height = height.toULong(),
                    mimetype = mimeType,
                    size = (if (fileSize > 0L) fileSize else tempFile.length()).toULong(),
                    thumbnailInfo = null, thumbnailSource = null, blurhash = null
                )
                val params = UploadParameters(source = uploadSource, caption = captionParam, formattedCaption = null, mentions = null, inReplyTo = null)
                val handle = timeline.sendVideo(params, null, videoInfo)
                handle.join()
            }
            else -> {
                val fileInfo = org.matrix.rustcomponents.sdk.FileInfo(
                    mimetype = mimeType,
                    size = (if (fileSize > 0L) fileSize else tempFile.length()).toULong(),
                    thumbnailInfo = null, thumbnailSource = null
                )
                val params = UploadParameters(source = uploadSource, caption = captionParam, formattedCaption = null, mentions = null, inReplyTo = null)
                val handle = timeline.sendFile(params, fileInfo)
                handle.join()
            }
        }
        tempFile.delete()
    }
}
