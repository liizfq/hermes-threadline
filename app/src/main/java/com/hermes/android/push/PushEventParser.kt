package com.hermes.android.push

import android.util.Log
import org.json.JSONObject

/**
 * Parse raw push JSON bytes into an [EventPushEvent] suitable for queuing.
 *
 * Performs the same filtering logic as the existing [HermesUnifiedPushReceiver]
 * so duplicate/undesirable events are never queued. Pure & side-effect free —
 * safe to call from any thread or process.
 *
 * event_id_only payloads (server push format with only `room_id`+`event_id`,
 * no `content`/`type`) are accepted: the parser does NOT require `type` or
 * `content`. Tool/status filtering by first-line is deferred to the worker
 * when the body is absent; the worker re-resolves via the SDK `NotificationClient`
 * and re-checks filtering once the true content is known.
 */
class PushEventParser {

    /**
     * Attempt to parse and filter the raw push JSON into an [EventPushEvent].
     *
     * Returns null when the payload:
     *  - is not a Matrix push notification,
     *  - lacks a non-blank event_id or room_id,
     *  - has an explicit non-message type (event_id_only omits type — deferred),
     *  - targets a room that isn't the bound room (when set),
     *  - represents a tool-call / status / AI-working message that the user
     *    has chosen not to be notified about (only when body is available).
     */
    fun parseAndFilter(
        jsonBytes: ByteArray,
        boundRoomId: String?,
        isForeground: Boolean,
        timeoutMinutes: Int
    ): EventPushEvent? {
        if (isForeground) return null  // SDK sync handles it — report-only hook.

        return try {
            val jsonStr = String(jsonBytes, Charsets.UTF_8)
            val json = JSONObject(jsonStr)
            val notification = json.optJSONObject("notification") ?: return null

            val roomId = notification.optString("room_id", "")
            if (roomId.isBlank()) return null  // Need a room to route the notification.

            val eventId = notification.optString("event_id", "")
            if (eventId.isBlank()) return null  // Dedup and SDK resolution need eventId.

            // Filter: only notify for the bound room.
            if (boundRoomId != null && roomId != boundRoomId) {
                return null
            }

            val type = notification.optString("type", "")
            // If the payload explicitly says it's a non-message event, skip.
            // event_id_only payloads may omit type; in that case defer to the
            // worker to decide after SDK resolution.
            if (type.isNotBlank() && type != "m.room.message") {
                return null
            }

            val content = notification.optJSONObject("content")
            val threadRootId = extractThreadRootId(notification)

            // Handle m.replace (edits) — prefer new_content body.
            val newContent = content?.optJSONObject("m.new_content")
            val rawBody = newContent?.optString("body", "") ?: content?.optString("body", "") ?: ""

            // For m.replace, capture the target event id (the message being
            // edited) so the worker can look up the parent thread root via
            // the local event→thread-root index. Only set when the relation
            // is m.replace; m.thread already populates threadRootId.
            val replaceTargetId = content
                ?.optJSONObject("m.relates_to")
                ?.takeIf { it.optString("rel_type", "") == "m.replace" }
                ?.optString("event_id", "")
                ?.ifBlank { null }

            // Tool/status filtering by first line only applies when body is
            // available. event_id_only payloads carry no body; the worker
            // defers filtering until the SDK has resolved the content.
            if (rawBody.isNotBlank()) {
                val firstLine = rawBody.lineSequence().firstOrNull()?.trim() ?: ""
                if (firstLine.isNotBlank() && shouldSkipByFirstLine(firstLine, timeoutMinutes)) {
                    return null
                }
            }

            val sender = notification.optString("sender_display_name", "")
                .ifBlank { notification.optString("sender", "") }

            EventPushEvent(
                roomId = roomId,
                eventId = eventId,
                sender = sender.ifBlank { null },
                body = rawBody.ifBlank { null },
                msgType = content?.optString("msgtype", "")?.ifBlank { null },
                threadRootId = threadRootId,
                replaceTargetId = replaceTargetId,
            )
        } catch (e: Exception) {
            Log.e(TAG, "parseAndFilter failed", e)
            null
        }
    }

    private fun shouldSkipByFirstLine(firstLine: String, timeoutMinutes: Int): Boolean {
        // ⏳ Working — N min — only notify if elapsed >= timeout threshold.
        val workingMin = Regex("⏳\\s+Working.*?(\\d+)\\s*min").find(firstLine)
        if (workingMin != null) {
            val elapsed = workingMin.groupValues[1].toIntOrNull() ?: 0
            if (elapsed < timeoutMinutes) return true
        }

        val skipPrefixes = listOf(
            "💻", "🐍", "💾", "🔧", "📖", "🔀",
            "✍️", "📚", "⚙️", "👁️", "🔎", "📋", "🔍"
        )
        return skipPrefixes.any { firstLine.startsWith(it) } ||
            firstLine.startsWith("[Background process proc") ||
            firstLine.startsWith("```")
    }

    companion object {
        private const val TAG = "PushEventParser"
    }
}
