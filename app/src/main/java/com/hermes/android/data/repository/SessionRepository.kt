package com.hermes.android.data.repository

import com.hermes.android.domain.model.Session
import kotlinx.coroutines.flow.Flow
import org.matrix.rustcomponents.sdk.Room
import android.content.Context
import android.net.Uri

interface SessionRepository {
    fun observeSessions(room: Room): Flow<List<Session>>
    suspend fun refreshSessions()
    suspend fun createSession(room: Room, content: String): Result<String>
    suspend fun createSession(
        room: Room,
        content: String,
        title: String?,
        attachmentUri: Uri?,
        attachmentType: String?,
        context: Context?
    ): Result<String>
    suspend fun deleteSession(room: Room, threadRootId: String): Result<Unit>
}
