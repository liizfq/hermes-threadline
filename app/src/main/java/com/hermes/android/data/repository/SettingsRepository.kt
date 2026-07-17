package com.hermes.android.data.repository

import com.hermes.android.domain.model.Session
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    fun getHomeserverUrl(): String?
    fun getUserId(): String?
    fun getAccessToken(): String?
    fun getDeviceId(): String?
    fun getBoundRoomId(): String?
    fun observeBoundRoom(): Flow<String?>
    fun isLoggedIn(): Boolean
    fun getSlidingSyncVersion(): String?
    suspend fun saveLogin(homeserverUrl: String, userId: String, accessToken: String, deviceId: String?)
    suspend fun saveBoundRoomId(roomId: String)
    suspend fun saveSlidingSyncVersion(version: String)
    suspend fun clear()
    fun getSessionReadTimestamps(): Map<String, Long>
    fun saveSessionReadTimestamp(sessionId: String, timestampMs: Long)
    fun clearSessionReadTimestamps()

    /**
     * Persist the session cache for [roomId] under a room-scoped key. Each
     * room's cache is fully isolated — room A's cache can never be seen by a
     * caller asking for room B.
     */
    fun saveSessionCache(roomId: String, sessions: List<Session>)

    /**
     * Read the session cache for [roomId]. Returns null when no entry exists
     * for this room. The legacy single-room cache (pre-room-scoping) is
     * migrated into [roomId]'s key exactly once, and only when [roomId] equals
     * the currently bound room — so legacy data never leaks to a different
     * room.
     */
    fun getSessionCache(roomId: String): List<Session>?

    fun getSessionTitle(threadRootId: String): String?
    fun saveSessionTitle(threadRootId: String, title: String)
    fun getAllSessionTitles(): Map<String, String>
    fun deleteSessionTitle(threadRootId: String)

    /**
     * Persist a provisional session entry for [threadRootId] into [roomId]'s
     * session cache. See [SettingsRepositoryImpl.saveProvisionalSessionTitle]
     * for the truncation rule and the cache-only contract.
     */
    fun saveProvisionalSessionTitle(roomId: String, threadRootId: String, rootBody: String)

    /**
     * Persist an `eventId -> threadRootId` mapping for [roomId]. Populated
     * from live timeline observation (RoomSessionListStore.publishIfActive and
     * ActiveThreadImpl diffs). Used by the push worker to resolve m.replace
     * events to their parent thread root without an SDK round-trip.
     *
     * The index is room-scoped and capped (LRU per room); inserting a new
     * mapping for an existing eventId updates its threadRootId and moves it
     * to most-recent.
     */
    fun saveEventThreadRoot(roomId: String, eventId: String, threadRootId: String)

    /**
     * Batch variant: persists multiple mappings for [roomId] in one write.
     * Used by RoomSessionListStore.publishIfActive which already has the full
     * session list. Each entry updates LRU recency.
     */
    fun saveEventThreadRoots(roomId: String, mappings: Map<String, String>)

    /**
     * Look up the thread root id for [eventId] under [roomId]'s index. Returns
     * null when the mapping is absent (cold cache, eviction, or foreign event).
     */
    fun getEventThreadRoot(roomId: String, eventId: String): String?

    fun getDraft(threadRootId: String): String
    fun saveDraft(threadRootId: String, text: String)
    fun clearDraft(threadRootId: String)
    fun getLanguage(): String
    fun setLanguage(locale: String)
}
