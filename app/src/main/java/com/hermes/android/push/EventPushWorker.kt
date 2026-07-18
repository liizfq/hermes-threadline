package com.hermes.android.push

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hermes.android.MainActivity
import com.hermes.android.R
import com.hermes.android.data.repository.ActiveThreadStore
import com.hermes.android.data.repository.MatrixRepository
import com.hermes.android.data.repository.SessionRepository
import com.hermes.android.data.repository.SettingsRepository
import com.hermes.android.ui.settings.LocaleManager
import com.hermes.android.ui.settings.strEnZh
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.matrix.rustcomponents.sdk.NotificationEvent
import org.matrix.rustcomponents.sdk.NotificationProcessSetup
import org.matrix.rustcomponents.sdk.NotificationStatus
import org.matrix.rustcomponents.sdk.TimelineEventContent

/**
 * Format a thread-root body into a display title using the same rule as the
 * ThreadList UI (`SessionRepositoryImpl.mapThreadListItemToSession`):
 * - Blank/null body  -> "Session".
 * - Body <= 50 chars  -> body verbatim.
 * - Body >  50 chars  -> first 50 chars + "...".
 *
 * This is a parallel copy of that rule, not a shared/centralized helper —
 * `SessionRepositoryImpl` still owns its own implementation. The two are kept
 * in sync so a push notification shows the same title the user sees in the
 * session list, instead of leaking the underlying Matrix event id.
 */
internal fun formatSessionTitle(rootBody: String?): String {
    if (rootBody.isNullOrBlank()) return SESSION_TITLE_FALLBACK
    return if (rootBody.length > SESSION_TITLE_MAX) {
        rootBody.take(SESSION_TITLE_MAX) + ELLIPSIS
    } else {
        rootBody
    }
}

/**
 * Pick a non-blank thread title from the cached session list.
 *
 * Returns null when [sessions] is null, no entry matches [threadRootId],
 * or the matched title is blank — letting the caller fall through to the
 * SDK root-body lookup. Pure (no Android/SDK deps); covered by
 * [FormatSessionTitleTest].
 *
 * Mirrors the ThreadList UI rule `customTitle ?: session.title`: when the
 * cache holds a title for this thread root, the push notification shows the
 * same title the user already sees in the session list — even when the SDK
 * root lookup would fail (redaction, network, eviction).
 */
internal fun pickCacheSessionTitle(
    sessions: List<com.hermes.android.domain.model.Session>?,
    threadRootId: String,
): String? =
    sessions
        ?.firstOrNull { it.id == threadRootId }
        ?.title
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

private const val SESSION_TITLE_MAX = 50
private const val SESSION_TITLE_FALLBACK = "Session"
private const val ELLIPSIS = "..."

/**
 * Drains the [EventPushEvent] queue and emits a system notification for each.
 *
 * Runs in its own background worker, independent of the ChatViewModel / timeline
 * process lifecycle. For each queued event, it first tries to resolve the full
 * Matrix event via the SDK `NotificationClient` (so `event_id_only` pushes —
 * which lack `content.body` and `msgtype` — still display the right sender and
 * body). When resolution fails (no client, network, redaction, not-found), the
 * worker falls back to the payload fields parsed by [PushEventParser].
 *
 * Dependencies are injected via Hilt: [MatrixRepository] knows how to build/restore
 * an authenticated [Client]; [PushEventStore] is the process-independent queue.
 */
@HiltWorker
class EventPushWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val matrixRepository: MatrixRepository,
    private val pushEventStore: PushEventStore,
    private val settingsRepository: SettingsRepository,
    private val sessionRepository: SessionRepository,
    private val activeThreadStore: ActiveThreadStore,
) : CoroutineWorker(appContext, params) {

    private val notificationManager =
        applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result {
        val workerStartedAt = android.os.SystemClock.elapsedRealtime()
        val events = pushEventStore.drain()

        Log.d(TAG, "doWork: draining ${events.size} events")

        if (events.isEmpty()) return Result.success()

        // Resolve only when the payload lacks a display body. Normal Matrix
        // message pushes already carry body + m.thread root, so synchronously
        // calling NotificationClient for every push just adds an avoidable
        // network/FFI delay before the user sees a notification. m.replace
        // still performs its separate target-root resolution below.
        val resolvedEvents = events.map { event ->
            val indexed = resolveReplaceTargetViaIndex(event)
            if (indexed.body.isNullOrBlank()) resolveEvent(indexed) ?: indexed else indexed
        }
        // Feed the index from every resolved event so future edits of these
        // events can be resolved locally. Covers the gap where the edited
        // message arrived via push but never flowed through a live timeline
        // diff (e.g. app was backgrounded, SDK sync lagged, or the thread was
        // never opened in chat UI).
        indexResolvedEvents(resolvedEvents)
        // Keep one notification per room + thread root. Different threads must not overwrite.
        val byThread = resolvedEvents.groupBy { event ->
            event.roomId to (event.threadRootId?.takeIf { it.isNotBlank() } ?: event.eventId)
        }

        // Critical path: show the notification from the payload/cache first.
        // Do NOT wait for ThreadList reset+pagination or /relations here;
        // those network calls repair in-app state but must never postpone the
        // alert the user receives.
        for ((key, threadEvents) in byThread) {
            val notificationId = stableSessionNotificationId(key.first, key.second)
            val latest = threadEvents.last()
            val threadRootId = latest.threadRootId?.takeIf { it.isNotBlank() } ?: latest.eventId
            val title = resolveNotificationTitle(latest, threadRootId)
            showNotification(notificationId, latest, threadEvents.size, title)
        }
        val firstReceivedAt = resolvedEvents.minOf { it.receivedAt }
        Log.d(
            TAG,
            "doWork: notifications posted groups=${byThread.size} " +
                "queueAgeMs=${System.currentTimeMillis() - firstReceivedAt} " +
                "criticalPathMs=${android.os.SystemClock.elapsedRealtime() - workerStartedAt}"
        )

        // Non-critical reconciliation: a push is evidence that the server has
        // newer room state. Refresh the app-scoped ThreadList even when this
        // root already exists: presence only proves the session is known, not
        // that latestEventId/replyCount are current. This runs AFTER posting;
        // refreshForPush is single-flight, so a burst of pushes and
        // discovery/timeline reconciliation still produces one reset.
        refreshInAppStateAfterPush(byThread)

        return Result.success()
    }

    /**
     * Reconcile app state after notifications are already visible. This work
     * deliberately remains inside the Worker (rather than an untracked
     * coroutine) so WorkManager retains it while the app is backgrounded;
     * it simply is no longer on the notification critical path.
     */
    private suspend fun refreshInAppStateAfterPush(
        byThread: Map<Pair<String, String>, List<EventPushEvent>>,
    ) {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val boundRoomId = settingsRepository.getBoundRoomId()
        val activeKey = activeThreadStore.activeKey()
        val hasBoundRoomPush = boundRoomId != null && byThread.keys.any { it.first == boundRoomId }
        if (!hasBoundRoomPush) return

        var activeThreadHit = false
        for ((key, _) in byThread) {
            val eventRoomId = key.first
            val threadRootId = key.second
            if (activeKey != null &&
                activeKey.roomId == eventRoomId &&
                activeKey.threadRootId == threadRootId
            ) {
                activeThreadHit = true
            }
        }

        try {
            sessionRepository.refreshForPush(boundRoomId!!)
            if (activeThreadHit) {
                Log.d(TAG, "refreshAfterPush: active thread $activeKey, catch-up refresh")
                activeThreadStore.refreshActiveIfAny()
            }
            Log.d(
                TAG,
                "refreshAfterPush: done activeThreadHit=$activeThreadHit " +
                    "durationMs=${android.os.SystemClock.elapsedRealtime() - startedAt}"
            )
        } catch (e: Exception) {
            // Notification was already posted. A reconciliation failure must
            // not convert a delivered push into a WorkManager retry/duplicate
            // alert; the next sync or push will attempt recovery again.
            Log.w(TAG, "refreshAfterPush: state reconciliation failed", e)
        }
    }

    /**
     * Resolve the user-visible title without network/SDK I/O so notification
     * posting remains bounded by local work.
     *
     * Priority mirrors ThreadList UI (`customTitle ?: session.title`):
     *  1. Local custom title.
     *  2. In-memory app-scoped session snapshot.
     *  3. Persisted room-scoped session cache.
     *  4. The payload body when the pushed event itself is the thread root.
     *  5. Literal `"Session"` — never a Matrix event id.
     *
     * Deliberately does NOT call NotificationClient for a missing thread-root
     * body. That lookup may require network / FFI and used to make a system
     * notification wait behind an unbounded SDK request. The following
     * refreshInAppStateAfterPush repairs the cache asynchronously for future
     * pushes.
     */
    private fun resolveNotificationTitle(
        latest: EventPushEvent,
        threadRootId: String,
    ): String {
        settingsRepository.getSessionTitle(threadRootId)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let {
                Log.d(TAG, "resolveNotificationTitle: source=custom root=$threadRootId")
                return it
            }

        val inMemory = sessionRepository.sessionsSnapshot()
        if (inMemory.isNotEmpty()) {
            pickCacheSessionTitle(inMemory, threadRootId)?.let {
                Log.d(TAG, "resolveNotificationTitle: source=store root=$threadRootId")
                return it
            }
        }

        pickCacheSessionTitle(settingsRepository.getSessionCache(latest.roomId), threadRootId)
            ?.let {
                Log.d(TAG, "resolveNotificationTitle: source=cache root=$threadRootId")
                return it
            }

        val rootBody = latest.body?.takeIf { threadRootId == latest.eventId }
        val source = if (rootBody.isNullOrBlank()) "fallback" else "payload-root"
        Log.d(TAG, "resolveNotificationTitle: source=$source root=$threadRootId")
        return formatSessionTitle(rootBody)
    }

    /**
     * Resolve an m.replace event's thread root via the local event-id →
     * thread-root index.
     *
     * Matrix edits (`m.replace`) carry a `m.relates_to.event_id` pointing to
     * the message being edited, but not a `m.thread` relation to the thread
     * root. SDK lookups on the edit event id return null threadRootEventId()
     * for the same reason, so SDK-based resolution is unreliable for edits.
     *
     * This method uses the index populated by RoomSessionListStore (root +
     * latest reply per session), ActiveThreadImpl (every message in the
     * focused thread), and prior push deliveries (every resolved event is
     * written back via [indexResolvedEvents]) to look up the thread root of
     * the edited message.
     *
     * When the index misses, falls back to an SDK lookup on the **target**
     * event (the message being edited). Unlike the edit itself, the target
     * is a plain m.thread message, so its threadRootEventId is reliable.
     *
     * Returns:
     *  - The original [event] unchanged when no replace target is present.
     *  - The original [event] unchanged when both index and SDK miss.
     *  - A copy of [event] with [EventPushEvent.threadRootId] set to the
     *    resolved thread root.
     */
    private suspend fun resolveReplaceTargetViaIndex(event: EventPushEvent): EventPushEvent {
        val target = event.replaceTargetId?.takeIf { it.isNotBlank() } ?: return event
        // Only override when the payload didn't already carry an m.thread root.
        if (event.threadRootId != null && event.threadRootId != event.eventId) return event

        val indexed = settingsRepository.getEventThreadRoot(event.roomId, target)
        if (!indexed.isNullOrBlank() && indexed != event.eventId) {
            Log.d(
                TAG,
                "resolveReplaceTargetViaIndex: hit eventId=${event.eventId} " +
                    "target=$target root=$indexed source=index"
            )
            return event.copy(threadRootId = indexed)
        }

        // Index miss: fall back to an SDK lookup on the target event. The
        // target is a plain message that carries its own m.thread relation,
        // so the SDK can reliably return its threadRootEventId.
        val sdkRoot = resolveTargetThreadRootViaSdk(event.roomId, target)
        if (!sdkRoot.isNullOrBlank() && sdkRoot != event.eventId) {
            Log.d(
                TAG,
                "resolveReplaceTargetViaIndex: sdk-hit eventId=${event.eventId} " +
                    "target=$target root=$sdkRoot source=sdk-target"
            )
            return event.copy(threadRootId = sdkRoot)
        }

        Log.d(
            TAG,
            "resolveReplaceTargetViaIndex: miss eventId=${event.eventId} " +
                "target=$target (index + sdk both empty)"
        )
        return event
    }

    /**
     * Index-resolve fallback: query the SDK for the [targetEventId] (the
     * message being edited in an m.replace). Unlike the edit event itself,
     * the target is a plain message that carries its own m.thread relation,
     * so SDK `getNotification(target).threadRootEventId()` is reliable.
     *
     * Returns the resolved thread root, or null on any failure (no client,
     * network, redaction, not-found, or the SDK still returned null). The
     * caller falls back to the event's payload-derived root in that case.
     *
     * Side effect: on a successful lookup the result is written into the
     * local index so subsequent edits of the same target hit locally.
     */
    private suspend fun resolveTargetThreadRootViaSdk(
        roomId: String,
        targetEventId: String,
    ): String? {
        return try {
            val client = matrixRepository.getClient()
                ?: matrixRepository.restoreSession()?.getOrNull()
                ?: return null

            val nc = client.notificationClient(NotificationProcessSetup.MultipleProcesses)
            try {
                val root = when (val status = nc.getNotification(roomId, targetEventId)) {
                    is NotificationStatus.Event -> threadRootOf(status.item.event)
                    is NotificationStatus.EventFilteredOut,
                    is NotificationStatus.EventNotFound,
                    is NotificationStatus.EventRedacted -> null
                }
                if (root != null && root.isNotBlank()) {
                    settingsRepository.saveEventThreadRoot(roomId, targetEventId, root)
                }
                root
            } finally {
                nc.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolveTargetThreadRootViaSdk: failed for $roomId:$targetEventId", e)
            null
        }
    }

    /**
     * Persist `(eventId → threadRootId)` for every resolved event whose
     * threadRootId is known and differs from its own event id (i.e. it's a
     * real thread reply, not a standalone root). One batched write per doWork
     * pass; safe to call with an empty list.
     *
     * This is the push-side counterpart to the timeline-side writes in
     * RoomSessionListStore and ActiveThreadImpl. It closes the gap where an
     * event arrives via push faster than SDK sync feeds the timeline, so the
     * local index is populated before the matching m.replace edit arrives.
     */
    private fun indexResolvedEvents(events: List<EventPushEvent>) {
        val byRoom = HashMap<String, MutableMap<String, String>>()
        for (event in events) {
            val root = event.threadRootId?.takeIf { it.isNotBlank() } ?: continue
            // Skip self-mapping (root events map to themselves implicitly and
            // would waste an index slot).
            if (root == event.eventId) continue
            byRoom.getOrPut(event.roomId) { HashMap() }[event.eventId] = root
            // Also index the replace target if present — the SDK may have
            // populated it during resolveTargetThreadRootViaSdk, but if the
            // index hit happened before that, writing here is idempotent.
            event.replaceTargetId?.takeIf { it.isNotBlank() && it != root }?.let { target ->
                byRoom.getOrPut(event.roomId) { HashMap() }[target] = root
            }
        }
        for ((roomId, mappings) in byRoom) {
            settingsRepository.saveEventThreadRoots(roomId, mappings)
        }
    }

    /**
     * Resolve a queued event via the SDK `NotificationClient`.
     *
     * Returns null on any failure (no authenticated client, network error,
     * redaction, not-found) so caller can fall back to the push payload.
     * The notification client is `AutoCloseable` — it is closed here after
     * the lookup.
     */
    private suspend fun resolveEvent(event: EventPushEvent): EventPushEvent? {
        return try {
            val client = matrixRepository.getClient()
                ?: matrixRepository.restoreSession()?.getOrNull()
                ?: return null

            val nc = client.notificationClient(NotificationProcessSetup.MultipleProcesses)
            try {
                when (val status = nc.getNotification(event.roomId, event.eventId)) {
                    is NotificationStatus.Event -> {
                        val resolved = notificationItemToEventPushEvent(
                            roomId = event.roomId,
                            eventId = event.eventId,
                            item = status.item,
                        )
                        // Preserve the index-resolved thread root and replace
                        // target id: SDK threadRootEventId() is null for
                        // m.replace edits (no m.thread relation on the edit),
                        // which would otherwise discard the value we just
                        // recovered via the local index.
                        val mergedThreadRoot = resolved.threadRootId
                            ?: event.threadRootId
                        resolved.copy(
                            threadRootId = mergedThreadRoot,
                            replaceTargetId = event.replaceTargetId ?: resolved.replaceTargetId,
                        )
                    }
                    is NotificationStatus.EventFilteredOut,
                    is NotificationStatus.EventNotFound,
                    is NotificationStatus.EventRedacted -> null  // fall back to payload
                }
            } finally {
                // NotificationClient is AutoCloseable / Disposable.
                nc.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolveEvent: SDK lookup failed for ${event.dedupKey}", e)
            null
        }
    }

    private fun showNotification(notificationId: Int, latest: EventPushEvent, count: Int, title: String) {
        val locale = LocaleManager.currentLocale()
        val threadRootId = latest.threadRootId?.takeIf { it.isNotBlank() } ?: latest.eventId
        val payloadBody = latest.body?.take(200)?.takeIf { it.isNotBlank() }
            ?: strEnZh(locale, "New message", "新消息")
        val queuedForMs = System.currentTimeMillis() - latest.receivedAt
        Log.d(
            TAG,
            "showNotification: queueAgeMs=$queuedForMs root=$threadRootId " +
                "bodySource=${if (latest.body.isNullOrBlank()) "fallback" else "payload"}"
        )
        val displayBody = when (latest.msgType) {
            "m.image", "m.video" -> strEnZh(locale, "[Image]", "[图片]")
            "m.audio" -> strEnZh(locale, "[Audio]", "[语音]")
            "m.file" -> strEnZh(locale, "[File]", "[文件]")
            else -> payloadBody
        }

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_NAVIGATE_TO_THREAD, threadRootId)
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, notificationId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, "hermes_messages")
            .setSmallIcon(R.drawable.ic_notification)
            // The notification represents the conversation thread, not the sender ID.
            .setContentTitle(title)
            .setContentText(displayBody)
            .setStyle(NotificationCompat.BigTextStyle().bigText(displayBody))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setNumber(count)

        notificationManager.notify(notificationId, notification.build())
        Log.d(TAG, "Notification shown: id=$notificationId count=$count threadRoot=$threadRootId")
    }

    companion object {
        private const val TAG = "EventPushWorker"
        const val WORK_NAME = "hermes_event_push_worker"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<EventPushWorker>()
                .addTag(WORK_NAME)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME, ExistingWorkPolicy.KEEP, request
            )
        }
    }
}

/**
 * Convert a resolved SDK notification item into an [EventPushEvent].
 *
 * The [NotificationItem] (and its children — event, senderInfo) are `Disposable`.
 * They are owned by the caller (still alive after `nc.getNotification()` returns),
 * and the SDK destroys them when the `NotificationClient` (or the parent
 * `NotificationStatus`) is closed. We copy the needed primitive values out of
 * them here and deliberately do NOT close them — closing happens in the caller's
 * `finally` block via `nc.close()`.
 */
private fun notificationItemToEventPushEvent(
    roomId: String,
    eventId: String,
    item: org.matrix.rustcomponents.sdk.NotificationItem,
): EventPushEvent = EventPushEvent(
    roomId = roomId,
    eventId = eventId,
    sender = item.senderInfo.displayName,
    body = extractBody(item),
    msgType = extractMsgType(item),
    threadRootId = threadRootOf(item.event),
)

/** Extract a display body from the SDK notification item. */
private fun extractBody(item: org.matrix.rustcomponents.sdk.NotificationItem): String? = when (val ev = item.event) {
    is NotificationEvent.Timeline -> timelineEventBody(ev.event)
    is NotificationEvent.Invite -> null
}

/** Extract the thread root event id from the SDK notification event. */
private fun threadRootOf(event: NotificationEvent): String? = when (event) {
    is NotificationEvent.Timeline -> event.event.threadRootEventId()
    is NotificationEvent.Invite -> null
}

/** Pure body extraction from a timeline event; supports message types. */
private fun timelineEventBody(event: org.matrix.rustcomponents.sdk.TimelineEvent): String? =
    when (val c = event.content()) {
        is TimelineEventContent.MessageLike -> messageLikeBody(c)
        else -> null
    }

/** Pure msgtype extraction from a [NotificationItem]. */
private fun extractMsgType(item: org.matrix.rustcomponents.sdk.NotificationItem): String? = when (val ev = item.event) {
    is NotificationEvent.Timeline -> when (val c = ev.event.content()) {
        is TimelineEventContent.MessageLike -> messageLikeMsgType(c)
        else -> null
    }
    is NotificationEvent.Invite -> null
}

/** Extract the body string from a `MessageLike` content variant. */
private fun messageLikeBody(content: TimelineEventContent.MessageLike): String? {
    val inner = content.content
    return when (inner) {
        is org.matrix.rustcomponents.sdk.MessageLikeEventContent.RoomMessage ->
            messageTypeBody(inner.messageType)
        is org.matrix.rustcomponents.sdk.MessageLikeEventContent.Poll ->
            inner.question
        // Sticker is an object with no fields in MessageLikeEventContent.
        else -> null
    }
}

/** Extract msgtype label from a `MessageLike` content variant. */
private fun messageLikeMsgType(content: TimelineEventContent.MessageLike): String? {
    val inner = content.content
    return when (inner) {
        is org.matrix.rustcomponents.sdk.MessageLikeEventContent.RoomMessage ->
            messageTypeLabel(inner.messageType)
        is org.matrix.rustcomponents.sdk.MessageLikeEventContent.Sticker -> "m.sticker"
        is org.matrix.rustcomponents.sdk.MessageLikeEventContent.Poll -> "m.poll.start"
        else -> null
    }
}

/** Body from a `MessageType`. */
private fun messageTypeBody(messageType: org.matrix.rustcomponents.sdk.MessageType): String? =
    when (messageType) {
        is org.matrix.rustcomponents.sdk.MessageType.Text -> messageType.content.body
        is org.matrix.rustcomponents.sdk.MessageType.Emote -> messageType.content.body
        is org.matrix.rustcomponents.sdk.MessageType.Notice -> messageType.content.body
        else -> null
    }

/** Human msgtype label from a `MessageType` (mirrors matrix event `msgtype`). */
private fun messageTypeLabel(messageType: org.matrix.rustcomponents.sdk.MessageType): String? =
    when (messageType) {
        is org.matrix.rustcomponents.sdk.MessageType.Text -> "m.text"
        is org.matrix.rustcomponents.sdk.MessageType.Emote -> "m.emote"
        is org.matrix.rustcomponents.sdk.MessageType.Image -> "m.image"
        is org.matrix.rustcomponents.sdk.MessageType.Audio -> "m.audio"
        is org.matrix.rustcomponents.sdk.MessageType.Video -> "m.video"
        is org.matrix.rustcomponents.sdk.MessageType.File -> "m.file"
        is org.matrix.rustcomponents.sdk.MessageType.Notice -> "m.notice"
        is org.matrix.rustcomponents.sdk.MessageType.Gallery -> "m.gallery"
        is org.matrix.rustcomponents.sdk.MessageType.Location -> "m.location"
        is org.matrix.rustcomponents.sdk.MessageType.Other -> messageType.msgtype
    }
