package com.hermes.android.push

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Push channel selection — values are persisted verbatim. */
object PushChannel {
    const val SYSTEM = "system"
    const val NTFY = "ntfy"
    const val BOTH = "both"

    val ALL = listOf(SYSTEM, NTFY, BOTH)
}

/**
 * Read/write access to push settings persisted in the application's
 * shared preferences (hermes_settings). Kept separate from
 * [com.hermes.android.data.repository.SettingsRepository] to avoid
 * changing the existing interface contract.
 */
@Singleton
class PushSettings @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("hermes_settings", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var channel: String
        get() = prefs.getString(KEY_CHANNEL, PushChannel.SYSTEM) ?: PushChannel.SYSTEM
        set(value) = prefs.edit().putString(KEY_CHANNEL, value).apply()

    var timeoutMinutes: Int
        get() = prefs.getInt(KEY_TIMEOUT_MIN, DEFAULT_TIMEOUT_MIN)
        set(value) = prefs.edit().putInt(KEY_TIMEOUT_MIN, value.coerceAtLeast(1)).apply()

    var ntfyServerUrl: String
        get() = prefs.getString(KEY_NTFY_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_NTFY_URL, value).apply()

    /**
     * The endpoint URL assigned by the ntfy app via UnifiedPush registration.
     * Format: https://ntfy.example.com/upXYZ?up=1
     */
    var ntfyEndpoint: String
        get() = prefs.getString(KEY_NTFY_ENDPOINT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_NTFY_ENDPOINT, value).apply()

    /**
     * The UnifiedPush token (app-specific key / instance) used to identify this app
     * when communicating with the ntfy app's BroadcastReceiver.
     */
    var upToken: String
        get() = prefs.getString(KEY_UP_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_UP_TOKEN, value).apply()

    fun toNtfyConfig(): NtfyConfig = NtfyConfig(
        serverUrl = ntfyServerUrl
    )

    fun usesSystem(): Boolean = enabled && (channel == PushChannel.SYSTEM || channel == PushChannel.BOTH)
    fun usesNtfy(): Boolean = enabled && (channel == PushChannel.NTFY || channel == PushChannel.BOTH)

    companion object {
        private const val KEY_ENABLED = "push_enabled"
        private const val KEY_CHANNEL = "push_channel"
        private const val KEY_TIMEOUT_MIN = "push_timeout_min"
        private const val KEY_NTFY_URL = "push_ntfy_url"
        private const val KEY_NTFY_ENDPOINT = "push_ntfy_endpoint"
        private const val KEY_UP_TOKEN = "push_up_token"

        const val DEFAULT_TIMEOUT_MIN = 5
    }
}
