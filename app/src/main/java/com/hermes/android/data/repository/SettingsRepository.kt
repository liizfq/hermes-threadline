package com.hermes.android.data.repository

import com.hermes.android.domain.model.Session
import kotlinx.coroutines.flow.Flow

/**
 * Multi-room support: a user-selected set of rooms that receive push and
 * appear in the main-page drawer. The active room is always one of these;
 * [resolveActiveRoomId] is the launch-time entry point that picks it.
 *
 * Boundary (2026-07-29):
 *  - Active is still a single room; the push set only governs "push scope +
 *    drawer candidates".
 *  - Historical `bound_room_id` is NOT auto-added to the set; the user opts
 *    rooms in via Settings.
 *  - [setActiveRoom] is strictly constrained: it only accepts a roomId that
 *    is already in the push set, otherwise it logs and no-ops (preserving
 *    the prior value). It never throws.
 */
interface SettingsRepository {

    /** The user-selected push/drawer room set (possibly empty). */
    fun getPushRoomIds(): Set<String>

    /** Observe the push room set; re-emits on every [replacePushRoomIds]. */
    fun observePushRoomIds(): Flow<Set<String>>

    /**
     * Overwrite the push room set with [ids]. Persists and emits. An empty
     * set is allowed (the prefs key is removed) and coexists with a leftover
     * `bound_room_id` as a fallback.
     */
    suspend fun replacePushRoomIds(ids: Set<String>)

    /**
     * Set the active room to [roomId]. No-op (with a warning log) when
     * [roomId] is not in the push set; never throws. Mutating the active
     * room does NOT mutate the push set.
     */
    suspend fun setActiveRoom(roomId: String)

    /**
     * Launch-time active room resolution:
     *  - `bound_room_id` if it is non-null AND present in the push set;
     *  - else the first element of the push set, if any;
     *  - else null.
     */
    fun resolveActiveRoomId(): String?
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
     * from live timeline observation (RoomSessionListStore.publish and
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
     * Used by RoomSessionListStore.publish which already has the full
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
