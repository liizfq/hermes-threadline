package com.hermes.android.push

import org.json.JSONObject

/**
 * Represents a parsed push event ready for queuing/persistence.
 *
 * Created from the raw Matrix push notification JSON. Only the stable
 * identifiers (roomId, eventId) are required; all other fields are
 * optional fallback data in case the SDK notification-client API is
 * unavailable.
 */
data class EventPushEvent(
    val roomId: String,
    val eventId: String,
    val receivedAt: Long = System.currentTimeMillis(),
    val sender: String? = null,
    val body: String? = null,
    val msgType: String? = null,
    val threadRootId: String? = null
) {
    /** Stable dedup key — the tuple that uniquely identifies a Matrix event. */
    val dedupKey: String get() = "$roomId:$eventId"

    fun toJson(): String = JSONObject().apply {
        put(KEY_ROOM_ID, roomId)
        put(KEY_EVENT_ID, eventId)
        put(KEY_RECEIVED_AT, receivedAt)
        sender?.let { put(KEY_SENDER, it) }
        body?.let { put(KEY_BODY, it) }
        msgType?.let { put(KEY_MSG_TYPE, it) }
        threadRootId?.let { put(KEY_THREAD_ROOT_ID, it) }
    }.toString()

    companion object {
        private const val KEY_ROOM_ID = "roomId"
        private const val KEY_EVENT_ID = "eventId"
        private const val KEY_RECEIVED_AT = "receivedAt"
        private const val KEY_SENDER = "sender"
        private const val KEY_BODY = "body"
        private const val KEY_MSG_TYPE = "msgType"
        private const val KEY_THREAD_ROOT_ID = "threadRootId"

        fun fromJson(raw: String): EventPushEvent? = try {
            val obj = JSONObject(raw)
            val roomId = optStringSafe(obj, KEY_ROOM_ID) ?: return null
            val eventId = optStringSafe(obj, KEY_EVENT_ID) ?: return null
            EventPushEvent(
                roomId = roomId,
                eventId = eventId,
                receivedAt = obj.optLong(KEY_RECEIVED_AT, 0L),
                sender = optStringSafe(obj, KEY_SENDER),
                body = optStringSafe(obj, KEY_BODY),
                msgType = optStringSafe(obj, KEY_MSG_TYPE),
                threadRootId = optStringSafe(obj, KEY_THREAD_ROOT_ID)
            )
        } catch (_: Exception) {
            null
        }

        private fun optStringSafe(obj: JSONObject, key: String): String? =
            if (obj.isNull(key)) null else obj.optString(key, "").ifBlank { null }
    }
}
