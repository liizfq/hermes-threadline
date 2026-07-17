package com.hermes.android.push

import org.json.JSONObject

/**
 * Extract the thread root ID from a Matrix push notification payload.
 *
 * Semantics:
 *  - `m.thread` relation  → the root event id (definitive).
 *  - `m.replace` relation → the *replaced* event id (provisional). Matrix
 *    allows only one `rel_type` per event, so an edit cannot also declare
 *    its thread membership. The caller (worker) must re-resolve the
 *    replaced event via the SDK to find its real thread root.
 *  - Otherwise           → the notification's own `event_id`.
 *
 * @return the resolved thread root and whether it is provisional
 * (`isReplaceTarget = true`, needs SDK resolution).
 */
fun extractThreadRootId(notification: JSONObject): ResolvedThreadRoot {
    val eventId = notification.optString(NOTIFICATION_EVENT_ID, "")
    if (eventId.isBlank()) return ResolvedThreadRoot(eventId, isReplaceTarget = false)
    val content = notification.optJSONObject("content") ?: return ResolvedThreadRoot(eventId, isReplaceTarget = false)
    val relatesTo = content.optJSONObject(RELATES_TO_KEY) ?: return ResolvedThreadRoot(eventId, isReplaceTarget = false)
    return resolveThreadRoot(
        eventId,
        relatesTo.optString(REL_TYPE_KEY, ""),
        relatesTo.optString(RELATES_TO_EVENT_ID, ""),
    )
}

/**
 * Pure resolver for picking the target thread root from notification fields.
 *
 * - `m.thread` with a non-blank root → use root, definitive.
 * - `m.replace` with a non-blank target → use target, **provisional** (the
 *   target may itself belong to a thread; only SDK resolution can tell).
 * - Otherwise → notification's own event_id, definitive.
 */
fun resolveThreadRoot(notificationEventId: String, relType: String, rootEventId: String): ResolvedThreadRoot {
    return when {
        relType == THREAD_REL_TYPE && rootEventId.isNotBlank() ->
            ResolvedThreadRoot(rootEventId, isReplaceTarget = false)
        relType == REPLACE_REL_TYPE && rootEventId.isNotBlank() ->
            ResolvedThreadRoot(rootEventId, isReplaceTarget = true)
        else -> ResolvedThreadRoot(notificationEventId, isReplaceTarget = false)
    }
}

/**
 * Result of thread-root extraction.
 *
 * @property threadRootId the best candidate for the thread root
 * @property isReplaceTarget when true, [threadRootId] is actually the
 * `m.replace` target event id, not a verified thread root. The worker
 * should re-resolve via the SDK.
 */
data class ResolvedThreadRoot(
    val threadRootId: String,
    val isReplaceTarget: Boolean,
)

// Keys used in push notification JSON — reused by [HermesUnifiedPushReceiver] and tests.
const val NOTIFICATION_EVENT_ID = "event_id"
const val RELATES_TO_KEY = "m.relates_to"
const val REL_TYPE_KEY = "rel_type"
const val THREAD_REL_TYPE = "m.thread"
const val REPLACE_REL_TYPE = "m.replace"
const val RELATES_TO_EVENT_ID = "event_id"
