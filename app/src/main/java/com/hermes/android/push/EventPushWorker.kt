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
        val events = pushEventStore.drain()

        Log.d(TAG, "doWork: draining ${events.size} events")

        if (events.isEmpty()) return Result.success()

        // Resolve before grouping because event_id-only payloads may omit the thread root.
        // resolveReplaceTargetViaIndex runs BEFORE SDK resolution: the local index is
        // cheap and authoritative for events we've already observed via timeline diffs.
        val resolvedEvents = events.map { event ->
            val indexed = resolveReplaceTargetViaIndex(event)
            val sdkResolved = resolveEvent(indexed)
            sdkResolved ?: indexed
        }
        // Keep one notification per room + thread root. Different threads must not overwrite.
        val byThread = resolvedEvents.groupBy { event ->
            event.roomId to (event.threadRootId?.takeIf { it.isNotBlank() } ?: event.eventId)
        }

        // For events targeting the bound room, let the application-scoped store
        // pick up any thread roots that haven't propagated into the cache yet.
        // Awaits each shared refresh so this round's title resolution sees the
        // refreshed snapshot / cache.
        val boundRoomId = settingsRepository.getBoundRoomId()
        val activeKey = activeThreadStore.activeKey()
        var activeThreadHit = false
        if (boundRoomId != null) {
            for ((key, _) in byThread) {
                val eventRoomId = key.first
                val threadRootId = key.second
                if (eventRoomId == boundRoomId) {
                    sessionRepository.refreshIfMissing(boundRoomId, threadRootId)
                    // If the user is currently focused on this thread (app in
                    // foreground or timeline still warm in background), fill
                    // the focused TEC gap via /relations. Does NOT open a new
                    // focused timeline for push-only threads.
                    if (activeKey != null &&
                        activeKey.roomId == boundRoomId &&
                        activeKey.threadRootId == threadRootId
                    ) {
                        activeThreadHit = true
                    }
                }
            }
            if (activeThreadHit) {
                Log.d(TAG, "doWork: push hits active thread $activeKey, catch-up refresh")
                activeThreadStore.refreshActiveIfAny()
            }
        }

        for ((key, threadEvents) in byThread) {
            val notificationId = stableSessionNotificationId(key.first, key.second)
            val latest = threadEvents.last()
            val threadRootId = latest.threadRootId?.takeIf { it.isNotBlank() } ?: latest.eventId
            val title = resolveNotificationTitle(latest, threadRootId)
            showNotification(notificationId, latest, threadEvents.size, title)
        }

        return Result.success()
    }

    /**
     * Resolve the user-visible title for a thread notification.
     *
     * Priority (mirrors ThreadList UI `customTitle ?: session.title`):
     *  1. Local custom session title from [SettingsRepository.getSessionTitle].
     *  2. In-memory session snapshot from the application-scoped
     *     [SessionRepository.sessionsSnapshot] (fresher than the persisted
     *     cache); falls back to the persisted [SettingsRepository.getSessionCache]
     *     scoped to the event's room.
     *  3. Body of the thread root event, formatted by [formatSessionTitle].
     *  4. Literal `"Session"` — never the Matrix event id.
     *
     * When the latest event already IS the root (no thread), its body is reused
     * to avoid a second SDK round-trip. The root body lookup also uses the SDK
     * `NotificationClient` (same as event resolution) so `event_id_only` pushes
     * still yield a meaningful title even when the payload lacks `content.body`.
     * No focused timeline is opened for the push — only the shared
     * [SessionRepository.refreshIfMissing] has run by this point.
     *
     * Each resolution logs its source (`custom` / `store` / `cache` / `sdk` /
     * `fallback`) along with the thread root id — never the message body — so
     * the path actually taken is verifiable from logcat without leaking push
     * contents.
     */
    private suspend fun resolveNotificationTitle(
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

        val rootBody = if (threadRootId == latest.eventId) {
            latest.body
        } else {
            resolveThreadRootBody(latest.roomId, threadRootId)
        }
        val source = if (rootBody.isNullOrBlank()) "fallback" else "sdk"
        Log.d(TAG, "resolveNotificationTitle: source=$source root=$threadRootId")
        return formatSessionTitle(rootBody)
    }

    /**
     * Resolve the thread root event body via the SDK `NotificationClient`.
     *
     * Returns null on any failure (no authenticated client, network error,
     * redaction, not-found) so [formatSessionTitle] can fall back to "Session".
     * The notification client is `AutoCloseable` and is closed here.
     */
    private suspend fun resolveThreadRootBody(roomId: String, threadRootId: String): String? {
        return try {
            val client = matrixRepository.getClient()
                ?: matrixRepository.restoreSession()?.getOrNull()
                ?: return null

            val nc = client.notificationClient(NotificationProcessSetup.MultipleProcesses)
            try {
                when (val status = nc.getNotification(roomId, threadRootId)) {
                    is NotificationStatus.Event -> extractBody(status.item)
                    is NotificationStatus.EventFilteredOut,
                    is NotificationStatus.EventNotFound,
                    is NotificationStatus.EventRedacted -> null
                }
            } finally {
                nc.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolveThreadRootBody: SDK lookup failed for $roomId:$threadRootId", e)
            null
        }
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
     * latest reply per session) and ActiveThreadImpl (every message in the
     * focused thread) to look up the thread root of the edited message. The
     * index is cold-started from live timeline observation; if the event
     * wasn't observed (e.g. very old message edited before the app saw it),
     * lookup returns null and the caller falls back to the existing
     * payload-derived threadRootId (or the event's own id).
     *
     * Returns:
     *  - The original [event] unchanged when no replace target is present.
     *  - The original [event] unchanged when the index lookup misses.
     *  - A copy of [event] with [EventPushEvent.threadRootId] set to the
     *    indexed thread root (overriding any payload-derived fallback).
     */
    private fun resolveReplaceTargetViaIndex(event: EventPushEvent): EventPushEvent {
        val target = event.replaceTargetId?.takeIf { it.isNotBlank() } ?: return event
        val indexed = settingsRepository.getEventThreadRoot(event.roomId, target)
        return applyIndexedThreadRoot(event, indexed, target, "EventPushWorker")
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
        val fullBody = latest.body?.take(200) ?: ""

        val displayBody = when (latest.msgType) {
            "m.image", "m.video" -> strEnZh(locale, "[Image]", "[图片]")
            "m.audio" -> strEnZh(locale, "[Audio]", "[语音]")
            "m.file" -> strEnZh(locale, "[File]", "[文件]")
            else -> fullBody
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

/**
 * Pure decision for applying an index-resolved thread root to a push event.
 *
 * Used by [EventPushWorker.resolveReplaceTargetViaIndex] (and covered by
 * [ApplyIndexedThreadRootTest]) so the override-vs-preserve logic is unit-
 * testable without the worker's Android/SDK dependencies.
 *
 * Returns:
 *  - [event] unchanged when [indexedRoot] is null/blank (cache miss).
 *  - [event] unchanged when [indexedRoot] equals the event's own id (would
 *    be a no-op and signals a self-referential entry).
 *  - [event] unchanged when the payload already carried a real m.thread
 *    root (threadRootId != null AND != event.eventId) — trust the explicit
 *    thread relation over the index in the exotic m.replace + m.thread case.
 *  - Otherwise a copy with [EventPushEvent.threadRootId] = [indexedRoot].
 *
 * [logTag] is used only for the debug log line; passing it keeps the helper
 * pure (no Android Log import needed for tests).
 */
internal fun applyIndexedThreadRoot(
    event: EventPushEvent,
    indexedRoot: String?,
    target: String,
    logTag: String,
): EventPushEvent {
    if (indexedRoot.isNullOrBlank() || indexedRoot == event.eventId) {
        Log.d(logTag, "resolveReplaceTargetViaIndex: miss eventId=${event.eventId} target=$target")
        return event
    }
    if (event.threadRootId != null && event.threadRootId != event.eventId) {
        // Payload already carried an m.thread root; preserve it.
        return event
    }
    Log.d(
        logTag,
        "resolveReplaceTargetViaIndex: hit eventId=${event.eventId} " +
            "target=$target root=$indexedRoot source=index"
    )
    return event.copy(threadRootId = indexedRoot)
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
