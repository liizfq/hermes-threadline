package com.hermes.android.data.repository

import android.content.Context
import android.net.Uri
import com.hermes.android.domain.model.Message
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import java.io.File

data class PaginationStatus(
    val isPaginating: Boolean = false,
    val hasMoreToLoad: Boolean = true,
) {
    val canPaginate: Boolean get() = !isPaginating && hasMoreToLoad
}

/**
 * Handle to a single open thread's timeline. Created and owned by [SessionRepository];
 * not a singleton — each instance manages exactly one thread's focused Timeline,
 * messaging, and reaction operations.
 *
 * The [room] and [threadRootId] are fixed for the lifetime of the handle; they are
 * NOT re-passed on every method call. All send/paginate/reaction methods operate on
 * this single thread directly.
 */
interface ActiveThread {
    val threadRootId: String
    val room: org.matrix.rustcomponents.sdk.Room
    val messages: Flow<List<Message>>
    val backwardPaginationStatus: StateFlow<PaginationStatus>
    val state: StateFlow<ActiveThreadState>

    suspend fun paginate(): Boolean
    suspend fun sendMessage(content: String): Result<Unit>
    suspend fun sendImage(uri: Uri, context: Context): Result<Unit>
    suspend fun sendVideo(uri: Uri, context: Context): Result<Unit>
    suspend fun sendFile(uri: Uri, context: Context): Result<Unit>
    suspend fun sendVoice(audioFile: File, waveform: List<Float>, durationMs: Long): Result<Unit>
    suspend fun toggleReaction(eventOrTransactionId: EventOrTransactionId, key: String): Result<Unit>
    suspend fun refresh()
    fun close()
}
