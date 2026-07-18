package com.hermes.android.data.repository

import com.hermes.android.domain.model.Session
import kotlinx.coroutines.flow.Flow
import org.matrix.rustcomponents.sdk.Room
import android.content.Context
import android.net.Uri

interface SessionRepository {
    /**
     * Bind the application-scoped session-list pipeline to this [room] /
     * [roomId]. The store takes ownership of [room] when it actually starts
     * or switches (Start / Switch decisions): on those paths it will close
     * the handle on teardown, so the caller must NOT reuse it. On the NoOp
     * path (same room already active) the store does NOT take ownership and
     * the caller is responsible for closing [room].
     *
     * Returns true iff the store took ownership of [room] (Start / Switch).
     * Returns false on NoOp — caller still owns [room] and must close it.
     *
     * Safe to call from any thread; UI collectors coming and going do NOT
     * close the pipeline.
     */
    fun ensureSessionsStarted(room: Room, roomId: String): Boolean

    /**
     * Public session stream backed by [RoomSessionListStore]. Survives UI
     * collector churn, Activity recreation, and app backgrounding — it only
     * goes away on logout / room switch / process death.
     */
    fun observeSessions(): Flow<List<Session>>

    /** Non-suspending snapshot of the current session list (push pipeline). */
    fun sessionsSnapshot(): List<Session>

    /**
     * Push-pipeline helper for a thread root that may not yet be present in
     * the session list. Returns true iff [roomId] is the active room. Only
     * refreshes when the root is absent; use [refreshForPush] for ordinary
     * message pushes to an already-known session.
     */
    suspend fun refreshIfMissing(roomId: String, threadRootId: String?): Boolean

    /**
     * Push-pipeline catch-up for an event in [roomId]. Returns true iff the
     * application-scoped store is actively bound to that room. Always awaits
     * one coalesced ThreadList reset + pagination, even when the target root
     * is already present: presence proves the session exists, not that its
     * latest-event summary is current.
     */
    suspend fun refreshForPush(roomId: String): Boolean

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
