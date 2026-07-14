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
    fun saveSessionCache(sessions: List<Session>)
    fun getSessionCache(): List<Session>?
    fun getSessionTitle(threadRootId: String): String?
    fun saveSessionTitle(threadRootId: String, title: String)
    fun getAllSessionTitles(): Map<String, String>
    fun deleteSessionTitle(threadRootId: String)
    fun getDraft(threadRootId: String): String
    fun saveDraft(threadRootId: String, text: String)
    fun clearDraft(threadRootId: String)
    fun getLanguage(): String
    fun setLanguage(locale: String)
}
