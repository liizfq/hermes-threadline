package com.hermes.android.data.repository

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import com.hermes.android.domain.model.Session
import com.hermes.android.ui.settings.LocaleManager
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : SettingsRepository {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("hermes_settings", Context.MODE_PRIVATE)

    private val boundRoomFlow = MutableStateFlow(getBoundRoomId())

    override fun getHomeserverUrl(): String? = prefs.getString("homeserver_url", null)
    override fun getUserId(): String? = prefs.getString("user_id", null)
    override fun getAccessToken(): String? = prefs.getString("access_token", null)
    override fun getDeviceId(): String? = prefs.getString("device_id", null)
    override fun getBoundRoomId(): String? = prefs.getString("bound_room_id", null)
    override fun getSlidingSyncVersion(): String? = prefs.getString("sliding_sync_version", null)

    override fun observeBoundRoom(): Flow<String?> = boundRoomFlow

    override fun isLoggedIn(): Boolean =
        getHomeserverUrl() != null && getAccessToken() != null

    override suspend fun saveLogin(homeserverUrl: String, userId: String, accessToken: String, deviceId: String?) {
        prefs.edit()
            .putString("homeserver_url", homeserverUrl)
            .putString("user_id", userId)
            .putString("access_token", accessToken)
            .putString("device_id", deviceId)
            .apply()
    }

    override suspend fun saveBoundRoomId(roomId: String) {
        prefs.edit().putString("bound_room_id", roomId).apply()
        boundRoomFlow.value = roomId
    }

    override suspend fun saveSlidingSyncVersion(version: String) {
        prefs.edit().putString("sliding_sync_version", version).apply()
    }

    override suspend fun clear() {
        prefs.edit().clear().apply()
        boundRoomFlow.value = null
    }

    override fun getSessionReadTimestamps(): Map<String, Long> {
        return try {
            val json = prefs.getString("session_read_timestamps", null) ?: return emptyMap()
            val obj = JSONObject(json)
            obj.keys().asSequence().associateWith { obj.getLong(it) }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    override fun saveSessionReadTimestamp(sessionId: String, timestampMs: Long) {
        val current = getSessionReadTimestamps().toMutableMap()
        current[sessionId] = timestampMs
        val obj = JSONObject()
        for ((k, v) in current) {
            obj.put(k, v)
        }
        prefs.edit().putString("session_read_timestamps", obj.toString()).apply()
    }

    override fun clearSessionReadTimestamps() {
        prefs.edit().remove("session_read_timestamps").apply()
    }

    override fun saveSessionCache(sessions: List<Session>) {
        val jsonArray = JSONArray()
        for (session in sessions) {
            val json = JSONObject()
            json.put("id", session.id)
            json.put("title", session.title)
            json.put("lastMessage", session.lastMessage ?: "")
            json.put("lastActivityTime", session.lastActivityTime.toEpochMilli())
            json.put("replyCount", session.replyCount)
            json.put("unreadCount", session.unreadCount)
            json.put("isProcessing", session.isProcessing)
            json.put("senderAvatarUrl", session.senderAvatarUrl ?: "")
            session.latestEventId?.let { json.put("latestEventId", it) }
            jsonArray.put(json)
        }
        prefs.edit().putString("session_cache_json", jsonArray.toString()).apply()
    }

    override fun getSessionCache(): List<Session>? {
        val json = prefs.getString("session_cache_json", null) ?: return null
        return try {
            val jsonArray = JSONArray(json)
            val sessions = mutableListOf<Session>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                sessions.add(Session(
                    id = obj.getString("id"),
                    title = obj.getString("title"),
                    lastMessage = obj.optString("lastMessage").ifEmpty { null },
                    lastActivityTime = java.time.Instant.ofEpochMilli(obj.getLong("lastActivityTime")),
                    replyCount = obj.getInt("replyCount"),
                    unreadCount = obj.getInt("unreadCount"),
                    isProcessing = obj.getBoolean("isProcessing"),
                    senderAvatarUrl = obj.optString("senderAvatarUrl").ifEmpty { null },
                    latestEventId = if (obj.has("latestEventId")) obj.getString("latestEventId") else null
                ))
            }
            sessions
        } catch (e: Exception) {
            null
        }
    }

    override fun getSessionTitle(threadRootId: String): String? {
        return getAllSessionTitles()[threadRootId]
    }

    override fun saveSessionTitle(threadRootId: String, title: String) {
        val current = getAllSessionTitles().toMutableMap()
        current[threadRootId] = title
        val obj = JSONObject()
        for ((k, v) in current) {
            obj.put(k, v)
        }
        prefs.edit().putString("session_titles", obj.toString()).apply()
    }

    override fun getAllSessionTitles(): Map<String, String> {
        return try {
            val json = prefs.getString("session_titles", null) ?: return emptyMap()
            val obj = JSONObject(json)
            obj.keys().asSequence().associateWith { obj.getString(it) }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    override fun deleteSessionTitle(threadRootId: String) {
        val current = getAllSessionTitles().toMutableMap()
        current.remove(threadRootId)
        val obj = JSONObject()
        for ((k, v) in current) {
            obj.put(k, v)
        }
        prefs.edit().putString("session_titles", obj.toString()).apply()
    }

    private val draftCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    override fun getDraft(threadRootId: String): String = draftCache[threadRootId] ?: ""
    override fun saveDraft(threadRootId: String, text: String) { draftCache[threadRootId] = text }
    override fun clearDraft(threadRootId: String) { draftCache.remove(threadRootId) }

    override fun getLanguage(): String = prefs.getString("app_locale", LocaleManager.DEFAULT_LOCALE)
        ?: LocaleManager.DEFAULT_LOCALE

    override fun setLanguage(locale: String) {
        prefs.edit().putString("app_locale", locale).apply()
    }
}
