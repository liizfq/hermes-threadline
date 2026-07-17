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
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maximum length of a provisional session title. Matches the ThreadList UI
 * rule in `SessionRepositoryImpl.mapThreadListItemToSession` and the push
 * `formatSessionTitle` rule, so the cache lookup returns the same string the
 * user sees in the session list.
 */
private const val PROVISIONAL_TITLE_MAX = 50
private const val PROVISIONAL_ELLIPSIS = "..."

/** Legacy single-room cache key from before the store scoped cache by roomId. */
internal const val LEGACY_SESSION_CACHE_KEY = "session_cache_json"

/** Prefix for room-scoped cache keys; full key is `<prefix><roomId>`. */
internal const val SESSION_CACHE_KEY_PREFIX = "session_cache_json::"

internal fun sessionCacheKey(roomId: String): String = SESSION_CACHE_KEY_PREFIX + roomId

/** Prefix for room-scoped event-id → thread-root-id index keys. */
internal const val EVENT_THREAD_ROOT_KEY_PREFIX = "event_thread_root_index::"

/** Max entries kept per room; LRU eviction when this threshold is exceeded. */
internal const val EVENT_THREAD_ROOT_MAX_PER_ROOM = 1000

internal fun eventThreadRootKey(roomId: String): String = EVENT_THREAD_ROOT_KEY_PREFIX + roomId

/**
 * Read the room-scoped cache JSON for [roomId], migrating the legacy
 * single-room blob exactly once when [roomId] equals [boundRoomId].
 *
 * Returns one of:
 *  - The cached JSON for [roomId], if present.
 *  - The legacy JSON migrated into [roomId]'s key (only when [roomId] equals
 *    [boundRoomId]); the legacy key is removed atomically with the write so
 *    it can never leak to another room.
 *  - null if neither key has data (or [boundRoomId] does not match).
 *
 * Pure over [prefs] — extracted so the migration logic is unit-testable
 * without [SettingsRepositoryImpl] / Android [Context].
 */
internal fun migrateLegacySessionCache(
    prefs: SharedPreferences,
    roomId: String,
    boundRoomId: String?,
): String? {
    val key = sessionCacheKey(roomId)
    prefs.getString(key, null)?.let { return it }
    if (boundRoomId != null && boundRoomId == roomId) {
        val legacy = prefs.getString(LEGACY_SESSION_CACHE_KEY, null) ?: return null
        prefs.edit()
            .putString(key, legacy)
            .remove(LEGACY_SESSION_CACHE_KEY)
            .apply()
        return legacy
    }
    return null
}

internal fun buildProvisionalSession(
    threadRootId: String,
    rootBody: String,
    nowMs: Long,
): Session? {
    if (rootBody.isBlank()) return null
    val title = if (rootBody.length > PROVISIONAL_TITLE_MAX) {
        rootBody.take(PROVISIONAL_TITLE_MAX) + PROVISIONAL_ELLIPSIS
    } else {
        rootBody
    }
    return Session(
        id = threadRootId,
        title = title,
        lastMessage = rootBody,
        lastActivityTime = Instant.ofEpochMilli(nowMs),
        replyCount = 0,
        unreadCount = 0,
        isProcessing = false,
        senderAvatarUrl = null,
        latestEventId = threadRootId,
    )
}

internal fun upsertProvisionalSession(
    existing: List<Session>?,
    provisional: Session,
): List<Session> {
    val without = existing?.filter { it.id != provisional.id } ?: emptyList()
    return listOf(provisional) + without
}

/**
 * Pure LRU update for the event-id → thread-root-id index. Returns a new
 * [LinkedHashMap] with:
 *  - each entry from [updates] inserted/refreshed at the end (most-recent),
 *  - existing entries for the same eventId moved to end with updated value,
 *  - entries beyond [maxEntries] evicted from the head (least-recent).
 *
 * Insertion order is preserved so JSON serialization round-trips to the same
 * LRU semantics. Pure over [existing] — extracted so the LRU logic is unit-
 * testable without Android [Context].
 */
internal fun applyEventThreadRootUpdates(
    existing: Map<String, String>,
    updates: Map<String, String>,
    maxEntries: Int,
): LinkedHashMap<String, String> {
    // Start from existing order, dropping any key that will be refreshed so
    // the refresh lands at the tail (most-recent).
    val result = linkedMapOf<String, String>()
    for ((k, v) in existing) {
        if (k in updates) continue
        result[k] = v
    }
    for ((k, v) in updates) {
        result[k] = v
    }
    while (result.size > maxEntries) {
        val firstKey = result.keys.iterator().next()
        result.remove(firstKey)
    }
    return result
}

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

    override fun saveSessionCache(roomId: String, sessions: List<Session>) {
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
        prefs.edit().putString(sessionCacheKey(roomId), jsonArray.toString()).apply()
    }

    override fun getSessionCache(roomId: String): List<Session>? {
        val json = migrateLegacySessionCache(prefs, roomId, getBoundRoomId()) ?: return null
        return parseSessionCache(json)
    }

    private fun parseSessionCache(json: String): List<Session>? = try {
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

    override fun saveProvisionalSessionTitle(roomId: String, threadRootId: String, rootBody: String) {
        val provisional = buildProvisionalSession(threadRootId, rootBody, System.currentTimeMillis())
            ?: return
        val updated = upsertProvisionalSession(getSessionCache(roomId), provisional)
        saveSessionCache(roomId, updated)
    }

    override fun saveEventThreadRoot(roomId: String, eventId: String, threadRootId: String) {
        saveEventThreadRoots(roomId, mapOf(eventId to threadRootId))
    }

    override fun saveEventThreadRoots(roomId: String, mappings: Map<String, String>) {
        if (mappings.isEmpty()) return
        val existing = readEventThreadRootIndex(roomId)
        val updated = applyEventThreadRootUpdates(
            existing = existing,
            updates = mappings,
            maxEntries = EVENT_THREAD_ROOT_MAX_PER_ROOM,
        )
        val json = JSONObject()
        for ((k, v) in updated) {
            json.put(k, v)
        }
        prefs.edit().putString(eventThreadRootKey(roomId), json.toString()).apply()
    }

    override fun getEventThreadRoot(roomId: String, eventId: String): String? =
        readEventThreadRootIndex(roomId)[eventId]

    private fun readEventThreadRootIndex(roomId: String): Map<String, String> = try {
        val json = prefs.getString(eventThreadRootKey(roomId), null) ?: return emptyMap()
        val obj = JSONObject(json)
        // Build a LinkedHashMap so iteration order reflects file order (which
        // is the LRU order we serialized); required for correct eviction on
        // subsequent updates.
        val ordered = linkedMapOf<String, String>()
        for (key in obj.keys()) {
            ordered[key] = obj.getString(key)
        }
        ordered
    } catch (_: Exception) {
        emptyMap()
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
