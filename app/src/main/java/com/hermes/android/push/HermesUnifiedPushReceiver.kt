package com.hermes.android.push

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.hermes.android.HermesApp
import com.hermes.android.MainActivity
import com.hermes.android.data.repository.MatrixRepository
import com.hermes.android.data.repository.SettingsRepository
import com.hermes.android.ui.settings.LocaleManager
import com.hermes.android.ui.settings.strEnZh
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.HttpPusherData
import org.matrix.rustcomponents.sdk.PusherIdentifiers
import org.matrix.rustcomponents.sdk.PusherKind
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.MessagingReceiver
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import javax.inject.Inject

private const val TAG = "HermesUPReceiver"

/**
 * UnifiedPush messaging receiver — handles callbacks from the ntfy app
 * (or any UnifiedPush distributor) via the official connector library.
 *
 * The connector library internally uses BroadcastOptions.setShareIdentityEnabled(true)
 * on SDK 34+, which is required for the ntfy app to identify the sender app.
 */
@AndroidEntryPoint
class HermesUnifiedPushReceiver : MessagingReceiver() {

    @Inject lateinit var pushSettings: PushSettings
    @Inject lateinit var matrixRepository: MatrixRepository
    @Inject lateinit var settingsRepository: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewEndpoint(context: Context, endpoint: PushEndpoint, instance: String) {
        Log.d(TAG, "onNewEndpoint: endpoint=${endpoint.url}, instance=$instance")

        // Step 1: Store endpoint URL and instance (client secret)
        pushSettings.ntfyEndpoint = endpoint.url
        pushSettings.upToken = instance

        // Step 2: Extract push gateway from endpoint URL
        // endpoint.url is like: https://ntfy.example.com/upXYZ?up=1
        // We need: https://ntfy.example.com/_matrix/push/v1/notify
        val gatewayUrl = try {
            val uri = android.net.Uri.parse(endpoint.url)
            val base = "${uri.scheme}://${uri.host}${if (uri.port != -1 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""}"
            "${base}/_matrix/push/v1/notify"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse endpoint URL: ${endpoint.url}", e)
            // Fallback: use the configured server URL
            val serverUrl = pushSettings.ntfyServerUrl.trimEnd('/')
            "$serverUrl/_matrix/push/v1/notify"
        }

        // Step 3: Register pusher with Matrix homeserver
        scope.launch {
            try {
                val c = matrixRepository.getClient()
                if (c == null) {
                    Log.w(TAG, "onNewEndpoint: no MatrixClient available, pusher registration deferred")
                    return@launch
                }

                val identifiers = PusherIdentifiers(endpoint.url, "com.hermes.android.ntfy")
                val data = HttpPusherData(gatewayUrl, null, null)
                val kind = PusherKind.Http(data)

                withContext(Dispatchers.IO) {
                    c.setPusher(
                        identifiers = identifiers,
                        kind = kind,
                        appDisplayName = "Hermes Android",
                        deviceDisplayName = "",
                        profileTag = "",
                        lang = LocaleManager.currentLocale(),
                        append = true
                    )
                }
                Log.d(TAG, "Pusher registered via onNewEndpoint: pushkey=${endpoint.url} gateway=$gatewayUrl")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register pusher in onNewEndpoint", e)
            }
        }
    }

    override fun onMessage(context: Context, message: PushMessage, instance: String) {
        Log.d(TAG, "onMessage: instance=$instance, messageBytes=${message.content?.size}")

        try {
            val jsonBytes = message.content ?: return
            val jsonStr = String(jsonBytes, Charsets.UTF_8)
            Log.d(TAG, "onMessage raw JSON: $jsonStr")
            val json = JSONObject(jsonStr)
            val notification = json.optJSONObject("notification") ?: return

            val roomId = notification.optString("room_id", "")
            val type = notification.optString("type", "")
            val eventId = notification.optString("event_id", "")

            // Skip empty notifications (read receipts, etc.)
            if (eventId.isBlank() || type != "m.room.message") {
                Log.d(TAG, "Skipping non-message push: type=$type, eventId=$eventId")
                return
            }

            // Filter: only notify for the bound room
            val boundRoomId = settingsRepository.getBoundRoomId()
            if (boundRoomId != null && roomId != boundRoomId) {
                Log.d(TAG, "Skipping push for non-bound room: $roomId (bound=$boundRoomId)")
                return
            }

            // Filter: skip notifications when app is in foreground (SDK sync handles it)
            if (matrixRepository.isForeground()) {
                Log.d(TAG, "Skipping push: app is in foreground")
                return
            }

            val sender = notification.optString("sender_display_name", "")
                .ifBlank { notification.optString("sender", "") }
            val roomName = notification.optString("room_name", "").ifBlank { roomId }
            val content = notification.optJSONObject("content")

            val threadRootId = extractThreadRootId(notification)

            // Handle m.replace (edits) — use m.new_content.body if available
            val newContent = content?.optJSONObject("m.new_content")
            val rawBody = newContent?.optString("body", "") ?: content?.optString("body", "") ?: ""

            // Skip tool-call / status messages (emoji prefix)
            val firstLine = rawBody.lineSequence().firstOrNull()?.trim() ?: ""

            // ⏳ Working — N min — only notify if elapsed >= timeout threshold
            val workingMin = Regex("⏳\\s+Working.*?(\\d+)\\s*min").find(firstLine)
            if (workingMin != null) {
                val elapsed = workingMin.groupValues[1].toIntOrNull() ?: 0
                val threshold = pushSettings.timeoutMinutes
                if (elapsed < threshold) {
                    Log.d(TAG, "Skipping working push: ${elapsed}min < ${threshold}min threshold")
                    return
                }
            } else if (
                firstLine.startsWith("💻") ||
                firstLine.startsWith("🐍") ||
                firstLine.startsWith("💾") ||
                firstLine.startsWith("🔧") ||
                firstLine.startsWith("📖") ||
                firstLine.startsWith("🔀") ||
                firstLine.startsWith("✍️") ||
                firstLine.startsWith("📚") ||
                firstLine.startsWith("⚙️") ||
                firstLine.startsWith("👁️") ||
                firstLine.startsWith("🔎") ||
                firstLine.startsWith("📋") ||
                firstLine.startsWith("🔍") ||
                firstLine.startsWith("[Background process proc") ||
                firstLine.startsWith("```")

                ) {
                Log.d(TAG, "Skipping tool/status push: $firstLine")
                return
            }

            val body = rawBody
                .replace(Regex("```[\\s\\S]*?```"), "") // strip code blocks for preview
                .trim()
                .take(200)
            val msgType = content?.optString("msgtype", "") ?: ""
            val counts = notification.optJSONObject("counts")
            val unread = counts?.optInt("unread", 0) ?: 0

            val displayBody = when {
                msgType == "m.image" || msgType == "m.video" -> strEnZh(LocaleManager.currentLocale(), "[Image]", "[图片]")
                msgType == "m.audio" -> strEnZh(LocaleManager.currentLocale(), "[Audio]", "[语音]")
                msgType == "m.file" -> strEnZh(LocaleManager.currentLocale(), "[File]", "[文件]")
                body.isNotBlank() -> body
                else -> return // no content to show
            }

            Log.d(TAG, "Push received: roomId=$roomId, sender=$sender, body=$displayBody, unread=$unread")

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_NAVIGATE_TO_THREAD, threadRootId)
            }
            val requestCode = if (roomId.isNotBlank()) {
                (threadRootId.hashCode() xor roomId.hashCode()) and 0x7FFFFFFF
            } else {
                threadRootId.hashCode().and(0x7FFFFFFF)
            }
            val pendingIntent = PendingIntent.getActivity(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val title = sender.ifBlank { roomName }

            val notif = NotificationCompat.Builder(context, HermesApp.NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(com.hermes.android.R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(displayBody)
                .setStyle(NotificationCompat.BigTextStyle().bigText(displayBody))
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setNumber(unread)

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notificationId = roomId.hashCode().and(0x7FFFFFFF)
            notificationManager.notify(notificationId, notif.build())

            Log.d(TAG, "Notification shown: id=$notificationId, title=$title, body=$displayBody")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show push notification", e)
        }
    }

    override fun onUnregistered(context: Context, instance: String) {
        Log.d(TAG, "onUnregistered: instance=$instance")
        // Clear stored settings
        pushSettings.ntfyEndpoint = ""
        pushSettings.upToken = ""
    }

    override fun onRegistrationFailed(context: Context, reason: FailedReason, instance: String) {
        Log.e(TAG, "onRegistrationFailed: instance=$instance, reason=$reason")
    }
}
