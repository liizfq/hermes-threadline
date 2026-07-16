package com.hermes.android.push

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationManagerCompat

private const val TAG = "SessionNotifIds"

/**
 * Stable notification id for a bound-room thread session.
 *
 * Must match [EventPushWorker] so that opening a chat can cancel the same
 * shade entry that was posted for that thread. Hash is over
 * `"$roomId:$threadRootId"` — never the raw event body.
 */
fun stableSessionNotificationId(roomId: String, threadRootId: String): Int {
    var hash = 17
    for (ch in "$roomId:$threadRootId") hash = hash * 31 + ch.code
    return hash and 0x7FFFFFFF
}

/**
 * Remove the shade notification for [threadRootId] in [roomId], if any.
 *
 * Safe to call when nothing is posted: [NotificationManagerCompat.cancel]
 * is a no-op for unknown ids. Does not require POST_NOTIFICATIONS.
 */
fun dismissSessionNotification(context: Context, roomId: String?, threadRootId: String?) {
    if (roomId.isNullOrBlank() || threadRootId.isNullOrBlank()) return
    val id = stableSessionNotificationId(roomId, threadRootId)
    try {
        NotificationManagerCompat.from(context.applicationContext).cancel(id)
        Log.d(TAG, "dismissSessionNotification: cancelled id=$id room=$roomId root=$threadRootId")
    } catch (e: Exception) {
        Log.w(TAG, "dismissSessionNotification failed for root=$threadRootId", e)
    }
}
