package com.hermes.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.hermes.android.media.data.MxcImageLoaderFactory
import com.hermes.android.push.PushServiceManager
import dagger.hilt.android.HiltAndroidApp
import org.matrix.rustcomponents.sdk.LogLevel
import org.matrix.rustcomponents.sdk.TracingConfiguration
import org.matrix.rustcomponents.sdk.initPlatform
import javax.inject.Inject

private const val TAG = "HermesApp"

@HiltAndroidApp
class HermesApp : Application(), ImageLoaderFactory {
    @Inject
    lateinit var mxcImageLoaderFactory: MxcImageLoaderFactory

    @Inject
    lateinit var pushServiceManager: PushServiceManager

    override fun onCreate() {
        super.onCreate()
        com.hermes.android.ui.settings.LocaleManager.init(this)
        initPlatform(
            TracingConfiguration(
                logLevel = LogLevel.INFO,
                traceLogPacks = emptyList(),
                extraTargets = emptyList(),
                writeToStdoutOrSystem = true,
                writeToFiles = null,
                sentryConfig = null
            ),
            useLightweightTokioRuntime = false
        )
        // Create notification channel for push notifications
        createNotificationChannel()

        // Load push config at app startup so ntfy works in background
        pushServiceManager.refreshConfigFromSettings()
        // Global uncaught exception handler — set AFTER initPlatform() so SDK
        // doesn't override it. Last-resort safety net for SDK internal coroutine
        // exceptions (e.g. expired token on restore). Logs instead of crashing.
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception on thread ${thread.name}", throwable)
            try {
                val msg = throwable.message ?: ""
                val isAuthError = msg.contains("token", ignoreCase = true) ||
                    msg.contains("unauthorized", ignoreCase = true) ||
                    msg.contains("SigningKeyChanged", ignoreCase = true)
                if (isAuthError) {
                    val prefs = getSharedPreferences("hermes_settings", Context.MODE_PRIVATE)
                    prefs.edit()
                        .remove("access_token")
                        .remove("refresh_token")
                        .remove("device_id")
                        .apply()
                    Log.d(TAG, "Cleared auth tokens due to: $msg")
                }
            } catch (_: Exception) {}
        }
    }

    override fun newImageLoader(): ImageLoader {
        return mxcImageLoaderFactory.newImageLoader()
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "hermes_messages"
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Messages",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "New message notifications from Matrix"
            enableVibration(true)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }
}
