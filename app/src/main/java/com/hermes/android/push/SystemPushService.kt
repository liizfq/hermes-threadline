package com.hermes.android.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.hermes.android.domain.model.Message
import com.hermes.android.domain.model.MessageContent
import com.hermes.android.ui.settings.LocaleManager
import com.hermes.android.ui.settings.strEnZh
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SystemPush"
private const val CHANNEL_ID_PREFIX = "hermes_push_"
private const val CHANNEL_NAME_DEFAULT_DEFAULT = "Hermes Default"
private const val CHANNEL_NAME_DEFAULT_LOW = "Hermes Low"
private const val CHANNEL_NAME_HIGH = "Hermes High"
private const val CHANNEL_NAME_URGENT = "Hermes Urgent"
private const val NOTIFICATION_GROUP = "hermes_agent_room"
private const val SUMMARY_NOTIFICATION_ID = -1

/**
 * System notification implementation of [PushService]. Posts notifications via
 * NotificationManager with per-priority channels, ringtone, vibration, and
 * per-room grouping (collapse key derived from message sender / room).
 */
@Singleton
class SystemPushService @Inject constructor(
    @ApplicationContext private val context: Context
) : PushService {

    init {
        ensureChannels()
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val audioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val channels = listOf(
            NotificationChannel(
                channelId(PushPriority.LOW),
                CHANNEL_NAME_DEFAULT_LOW,
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Low priority Hermes notifications" },
            NotificationChannel(
                channelId(PushPriority.DEFAULT),
                CHANNEL_NAME_DEFAULT_DEFAULT,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Hermes default notifications"
                enableLights(true)
                enableVibration(true)
                setSound(ringtoneUri, audioAttrs)
            },
            NotificationChannel(
                channelId(PushPriority.HIGH),
                CHANNEL_NAME_HIGH,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "AI reply / completion reminders"
                enableLights(true)
                enableVibration(true)
                setSound(ringtoneUri, audioAttrs)
            },
            NotificationChannel(
                channelId(PushPriority.URGENT),
                CHANNEL_NAME_URGENT,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Timeout / urgent reminders"
                enableLights(true)
                enableVibration(true)
                setSound(ringtoneUri, audioAttrs)
            }
        )
        channels.forEach(manager::createNotificationChannel)
    }

    private fun channelId(priority: PushPriority): String = CHANNEL_ID_PREFIX + priority.name.lowercase()

    private fun notifyIdFor(message: Message, kind: String): Int {
        // Stable per-message-per-kind id so updates replace prior notifications
        // for the same logical event rather than stacking.
        val key = message.id + ":" + kind
        var hash = 0
        for (ch in key) hash = (hash * 31 + ch.code)
        return hash and 0x7FFFFFFF
    }

    override fun sendPush(title: String, message: String, priority: PushPriority) {
        try {
            val builder = NotificationCompat.Builder(context, channelId(priority))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setAutoCancel(true)
                .setGroup(NOTIFICATION_GROUP)
                .setPriority(toCompatPriority(priority))

            val id = System.currentTimeMillis().toInt() and 0x7FFFFFFF
            postNotification(id, builder)
            postSummaryIfNeeded()
        } catch (e: Exception) {
            Log.e(TAG, "sendPush failed", e)
        }
    }

    override fun sendReactionPush(message: Message, reactionKey: String) {
        val sender = message.senderName ?: message.senderId
        val locale = LocaleManager.currentLocale()
        val title = when (reactionKey) {
            PushService.REACTION_DONE -> strEnZh(locale, "Hermes Agent reply complete", "Hermes Agent 回复完成")
            PushService.REACTION_EYES,
            PushService.REACTION_WORKING -> strEnZh(locale, "Hermes Agent processing", "Hermes Agent 处理中")
            else -> "Hermes Agent"
        }
        val body = sender + ": " + previewMessage(message)
        try {
            val builder = NotificationCompat.Builder(context, channelId(PushPriority.HIGH))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setGroup(NOTIFICATION_GROUP)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
            postNotification(notifyIdFor(message, "reaction_" + reactionKey), builder)
            postSummaryIfNeeded()
        } catch (e: Exception) {
            Log.e(TAG, "sendReactionPush failed", e)
        }
    }

    override fun sendTimeoutPush(message: Message, minutes: Int) {
        val sender = message.senderName ?: message.senderId
        val locale = LocaleManager.currentLocale()
        val title = strEnZh(locale, "Hermes Agent processing timeout", "Hermes Agent 处理超时")
        val body = sender + ": " + strEnZh(locale, "Work has exceeded ", "工作已超过 ") + minutes + strEnZh(locale, " min without completion", " 分钟未完成")
        try {
            val builder = NotificationCompat.Builder(context, channelId(PushPriority.URGENT))
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setGroup(NOTIFICATION_GROUP)
                .setPriority(NotificationCompat.PRIORITY_MAX)
            postNotification(notifyIdFor(message, "timeout"), builder)
            postSummaryIfNeeded()
        } catch (e: Exception) {
            Log.e(TAG, "sendTimeoutPush failed", e)
        }
    }

    private fun postNotification(id: Int, builder: NotificationCompat.Builder) {
        try {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        } catch (sec: SecurityException) {
            Log.w(TAG, "POST_NOTIFICATIONS permission missing", sec)
        }
    }

    private fun postSummaryIfNeeded() {
        try {
            val summary = NotificationCompat.Builder(context, channelId(PushPriority.DEFAULT))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Hermes Agent")
                .setContentText("New message")
                .setStyle(NotificationCompat.InboxStyle().setSummaryText("Hermes Agent"))
                .setOnlyAlertOnce(true)
                .setGroup(NOTIFICATION_GROUP)
                .setGroupSummary(true)
                .build()
            NotificationManagerCompat.from(context).notify(SUMMARY_NOTIFICATION_ID, summary)
        } catch (e: Exception) {
            Log.w(TAG, "postSummaryIfNeeded failed", e)
        }
    }

    private fun previewMessage(message: Message): String {
        val locale = LocaleManager.currentLocale()
        return when (val c = message.content) {
            is MessageContent.Text -> c.plainText.take(160)
            is MessageContent.File -> strEnZh(locale, "File: ", "文件: ") + c.fileName
            is MessageContent.Image -> strEnZh(locale, "Image", "图片")
            is MessageContent.Video -> strEnZh(locale, "Video", "视频")
            is MessageContent.Audio -> strEnZh(locale, "Audio", "音频")
            is MessageContent.Voice -> strEnZh(locale, "Voice message", "语音消息")
        }
    }

    private fun toCompatPriority(priority: PushPriority): Int = when (priority) {
        PushPriority.LOW -> NotificationCompat.PRIORITY_LOW
        PushPriority.DEFAULT -> NotificationCompat.PRIORITY_DEFAULT
        PushPriority.HIGH -> NotificationCompat.PRIORITY_HIGH
        PushPriority.URGENT -> NotificationCompat.PRIORITY_MAX
    }
}
