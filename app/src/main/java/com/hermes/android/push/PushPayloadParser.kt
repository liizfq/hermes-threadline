package com.hermes.android.push

import org.json.JSONObject

/**
 * Extract the thread root ID from a Matrix push notification payload.
 *
 * If the notification content has a thread relation (m.thread), the root event
 * ID should be used to route navigation to the parent thread. Otherwise the
 * notification's own `event_id` is the thread root.
 *
 * @param notification the parsed `notification` object under the push payload top-level object
 * @return the thread root ID (may be empty only if the caller did not already blank-check event_id)
 */
fun extractThreadRootId(notification: JSONObject): String {
    val eventId = notification.optString(NOTIFICATION_EVENT_ID, "")
    if (eventId.isBlank()) return eventId
    val content = notification.optJSONObject("content") ?: return eventId
    val relatesTo = content.optJSONObject(RELATES_TO_KEY) ?: return eventId
    return resolveThreadRoot(eventId, relatesTo.optString(REL_TYPE_KEY, ""), relatesTo.optString(RELATES_TO_EVENT_ID, ""))
}

/**
 * Pure resolver for picking the target thread root id from notification fields.
 *
 * If [relType] is `"m.thread"` and [rootEventId] is non-empty, it takes precedence
 * over the notification's own [notificationEventId]. Otherwise the plain
 * notification `event_id` is the route target.
 */
fun resolveThreadRoot(notificationEventId: String, relType: String, rootEventId: String): String {
    return if (relType == THREAD_REL_TYPE && rootEventId.isNotBlank()) rootEventId else notificationEventId
}

// Keys used in push notification JSON — reused by [HermesUnifiedPushReceiver] and tests.
const val NOTIFICATION_EVENT_ID = "event_id"
const val RELATES_TO_KEY = "m.relates_to"
const val REL_TYPE_KEY = "rel_type"
const val THREAD_REL_TYPE = "m.thread"
const val RELATES_TO_EVENT_ID = "event_id"
