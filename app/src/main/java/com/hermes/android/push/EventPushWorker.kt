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
import com.hermes.android.data.repository.MatrixRepository
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
) : CoroutineWorker(appContext, params) {

    private val notificationManager =
        applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result {
        val events = pushEventStore.drain()

        Log.d(TAG, "doWork: draining ${events.size} events")

        if (events.isEmpty()) return Result.success()

        // Resolve before grouping because event_id-only payloads may omit the thread root.
        val resolvedEvents = events.map { event -> resolveEvent(event) ?: event }
        // Keep one notification per room + thread root. Different threads must not overwrite.
        val byThread = resolvedEvents.groupBy { event ->
            event.roomId to (event.threadRootId?.takeIf { it.isNotBlank() } ?: event.eventId)
        }

        for ((key, threadEvents) in byThread) {
            val notificationId = stableNotificationId(key.first, key.second)
            showNotification(notificationId, threadEvents.last(), threadEvents.size)
        }

        return Result.success()
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
                    is NotificationStatus.Event -> notificationItemToEventPushEvent(
                        roomId = event.roomId,
                        eventId = event.eventId,
                        item = status.item,
                    )
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

    private fun showNotification(notificationId: Int, latest: EventPushEvent, count: Int) {
        val locale = LocaleManager.currentLocale()
        val threadRootId = latest.threadRootId?.takeIf { it.isNotBlank() } ?: latest.eventId
        val threadTitle = settingsRepository.getSessionTitle(threadRootId)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: threadRootId
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
            .setContentTitle(threadTitle)
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

    private fun stableNotificationId(roomId: String, threadRootId: String): Int {
        var hash = 17
        for (ch in "$roomId:$threadRootId") hash = hash * 31 + ch.code
        return hash and 0x7FFFFFFF
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
