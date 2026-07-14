package com.hermes.android.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hermes.android.ui.settings.LocaleManager
import com.hermes.android.ui.settings.strEnZh
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

private const val TAG = "TimeoutAlarm"

@AndroidEntryPoint
class TimeoutAlarmReceiver : BroadcastReceiver() {
    @Inject lateinit var systemPushService: SystemPushService

    override fun onReceive(context: Context, intent: Intent) {
        val locale = LocaleManager.currentLocale()
        val title = intent.getStringExtra(EXTRA_TITLE) ?: strEnZh(locale, "Hermes Agent processing timeout", "Hermes Agent 处理超时")
        val body = intent.getStringExtra(EXTRA_BODY) ?: strEnZh(locale, "Work timed out", "工作已超时")
        val roomId = intent.getStringExtra(EXTRA_ROOM_ID) ?: ""
        Log.d(TAG, "Timeout alarm fired: roomId=$roomId title=$title body=$body")
        systemPushService.sendPush(title, body, PushPriority.URGENT)
    }

    companion object {
        const val EXTRA_TITLE = "timeout_title"
        const val EXTRA_BODY = "timeout_body"
        const val EXTRA_ROOM_ID = "timeout_room_id"

        const val ACTION_TIMEOUT = "com.hermes.android.TIMEOUT_ALARM"
    }
}
