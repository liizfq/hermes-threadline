package com.hermes.android.push

import android.content.Context
import android.util.Log
import com.hermes.android.data.repository.MatrixRepository
import com.hermes.android.data.repository.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 * UnifiedPush messaging receiver — the single entry point for Matrix push
 * notifications delivered via any UnifiedPush distributor (e.g. ntfy).
 *
 * Responsibilities:
 *  - onNewEndpoint / onUnregistered / onRegistrationFailed: lifecycle & pusher
 *    registration with the Matrix homeserver (preserved from the original
 *    implementation — DO NOT turn this into a no-op).
 *  - onMessage: parse → dedup → enqueue via the new event pipeline
 *    (PushEventParser → PushEventStore → EventPushWorker).
 *
 * There must be only ONE receiver registered for these actions in the
 * manifest. Competing receivers would race to handle the same broadcasts
 * and break endpoint registration or message delivery.
 */
@AndroidEntryPoint
class HermesUnifiedPushReceiver : MessagingReceiver() {

    @Inject lateinit var pushSettings: PushSettings
    @Inject lateinit var matrixRepository: MatrixRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var pushEventStore: PushEventStore
    @Inject lateinit var pushEventParser: PushEventParser

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
                        appDisplayName = "Hermes Threadline",
                        deviceDisplayName = "",
                        profileTag = "",
                        lang = com.hermes.android.ui.settings.LocaleManager.currentLocale(),
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
        Log.d(TAG, "onMessage: instance=$instance, bytes=${message.content?.size}")

        val jsonBytes = message.content ?: return

        val boundRoomId = settingsRepository.getBoundRoomId()
        val isForeground = matrixRepository.isForeground()
        val timeoutMinutes = pushSettings.timeoutMinutes

        // Parse and filter. Returns null if the event should be skipped
        // (non-message, bound-room mismatch, tool/status, working-below-threshold, etc.)
        val event = pushEventParser.parseAndFilter(
            jsonBytes,
            boundRoomId = boundRoomId,
            isForeground = isForeground,
            timeoutMinutes = timeoutMinutes
        ) ?: return

        // Dedup by stable roomId:eventId key.
        val isNew = pushEventStore.storeIfAbsent(event)
        if (isNew) {
            Log.d(TAG, "New push event stored: ${event.dedupKey}")
            // Asynchronously drain the queue and show notifications.
            EventPushWorker.enqueue(context)
        } else {
            Log.d(TAG, "Duplicate push event ignored: ${event.dedupKey}")
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
