package com.hermes.android.data.repository

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.hermes.android.domain.model.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.ImageInfo
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.TextMessageContent
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineListener
import org.matrix.rustcomponents.sdk.UploadParameters
import org.matrix.rustcomponents.sdk.UploadSource
import org.matrix.rustcomponents.sdk.VideoInfo
import java.time.Duration
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SessionRepo"

/**
 * Application-scoped wrapper around [RoomSessionListStore] plus the create /
 * delete entry points that still need a per-call [Room].
 *
 * All room-level session-list observation, refresh, discovery, and caching
 * lives in [RoomSessionListStore] — there is exactly one source of truth for
 * the bound room's session list. This class no longer ref-counts collectors
 * or owns a [org.matrix.rustcomponents.sdk.ThreadListService] directly.
 *
 * ActiveThread lifecycle is owned by [com.hermes.android.presentation.chat.ChatViewModel]
 * via [ActiveThreadFactory].
 */
@Singleton
class SessionRepositoryImpl @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val sessionListStore: RoomSessionListStore,
) : SessionRepository {

    override fun ensureSessionsStarted(room: Room, roomId: String): Boolean =
        sessionListStore.ensureStarted(room, roomId)

    override fun observeSessions(): Flow<List<Session>> = sessionListStore.sessions

    override fun sessionsSnapshot(): List<Session> = sessionListStore.sessionsSnapshot()

    override suspend fun refreshIfMissing(roomId: String, threadRootId: String?): Boolean =
        sessionListStore.refreshIfMissing(roomId, threadRootId)

    override suspend fun refreshSessions() {
        sessionListStore.refresh()
    }

    override suspend fun createSession(room: Room, content: String): Result<String> {
        Log.d(TAG, "createSession: Creating session with content: $content")
        return try {
            val textContent = TextMessageContent(content, null)
            val messageType = MessageType.Text(textContent)
            val timeline = room.timeline()
            val messageContent = timeline.createMessageContent(messageType)
                ?: return Result.failure(IllegalStateException("Failed to create message content"))
            timeline.send(messageContent)
            Log.d(TAG, "createSession: Message sent successfully!")
            timeline.close()
            Result.success("")
        } catch (e: Exception) {
            Log.e(TAG, "createSession failed", e)
            Result.failure(e)
        }
    }

    override suspend fun createSession(
        room: Room,
        content: String,
        title: String?,
        attachmentUri: Uri?,
        attachmentType: String?,
        context: Context?
    ): Result<String> {
        Log.d(TAG, "createSession: content=${content.take(50)}, title=$title, attachment=$attachmentType")
        return try {
            val timeline = room.timeline()
            val completable = CompletableDeferred<String>()

            val listener = object : TimelineListener {
                override fun onUpdate(diffs: List<TimelineDiff>) {
                    for (diff in diffs) {
                        val items = when (diff) {
                            is TimelineDiff.PushBack -> listOf(diff.value)
                            is TimelineDiff.Append -> diff.values
                            is TimelineDiff.Set -> listOf(diff.value)
                            else -> emptyList()
                        }
                        for (item in items) {
                            val ev = item.asEvent() ?: continue
                            if (ev.isOwn && ev.eventOrTransactionId is EventOrTransactionId.EventId) {
                                val eid = (ev.eventOrTransactionId as EventOrTransactionId.EventId).eventId
                                Log.d(TAG, "createSession: captured event id=$eid")
                                completable.complete(eid)
                                return
                            }
                        }
                    }
                }
            }
            val handle = timeline.addListener(listener)

            if (attachmentUri != null && context != null) {
                sendAttachmentAsRoot(timeline, attachmentUri, attachmentType ?: "file", content, context)
            } else {
                val textContent = TextMessageContent(content, null)
                val messageType = MessageType.Text(textContent)
                val messageContent = timeline.createMessageContent(messageType)
                    ?: return Result.failure(IllegalStateException("Failed to create message content"))
                timeline.send(messageContent)
            }

            val eventId = withTimeoutOrNull(15_000L) { completable.await() }
            handle.destroy()
            timeline.close()

            if (!eventId.isNullOrBlank()) {
                Log.d(TAG, "createSession: success, eventId=$eventId")
                Result.success(eventId)
            } else {
                Log.w(TAG, "createSession: timed out waiting for event id")
                Result.failure(Exception("Timed out waiting for event id"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "createSession(with attachment) failed", e)
            Result.failure(e)
        }
    }

    override suspend fun deleteSession(room: Room, threadRootId: String): Result<Unit> = runCatching {
        Log.d(TAG, "deleteSession: redacting thread root $threadRootId")
        withContext(Dispatchers.IO) {
            room.redact(threadRootId, null)
        }
        Log.d(TAG, "deleteSession: redacted, refreshing session list")
        refreshSessions()
    }

    // ---- Attachment upload helper (used by createSession with attachment) ----

    @Suppress("UNUSED_PARAMETER")
    private suspend fun sendAttachmentAsRoot(
        timeline: Timeline,
        uri: Uri,
        type: String,
        caption: String,
        context: Context,
    ) {
        val resolver = context.contentResolver
        var fileName = "file"
        var fileSize = 0L
        val cursor = resolver.query(uri, null, null, null, null)
        if (cursor != null && cursor.moveToFirst()) {
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (nameIdx >= 0) fileName = cursor.getString(nameIdx) ?: fileName
            if (sizeIdx >= 0) fileSize = cursor.getLong(sizeIdx)
            cursor.close()
        }
        val mimeType = resolver.getType(uri) ?: "application/octet-stream"
        val tempFile = File(context.cacheDir, "newsession_${System.currentTimeMillis()}_$fileName")
        resolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output -> input.copyTo(output) }
        }
        val uploadSource = UploadSource.File(tempFile.absolutePath)
        val captionParam: String? = caption.ifBlank { null }

        when (type) {
            "image" -> {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                val imageInfo = ImageInfo(
                    height = options.outHeight.toULong(),
                    width = options.outWidth.toULong(),
                    mimetype = options.outMimeType ?: "image/jpeg",
                    size = (if (fileSize > 0L) fileSize else tempFile.length()).toULong(),
                    thumbnailInfo = null, thumbnailSource = null, blurhash = null, isAnimated = null
                )
                val params = UploadParameters(source = uploadSource, caption = captionParam, formattedCaption = null, mentions = null, inReplyTo = null)
                val handle = timeline.sendImage(params, null, imageInfo)
                handle.join()
            }
            "video" -> {
                var durationMs = 0L; var width = 0; var height = 0
                runCatching {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(context, uri)
                    durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    retriever.release()
                }
                val videoInfo = VideoInfo(
                    duration = if (durationMs > 0) Duration.ofMillis(durationMs) else null,
                    width = width.toULong(), height = height.toULong(),
                    mimetype = mimeType,
                    size = (if (fileSize > 0L) fileSize else tempFile.length()).toULong(),
                    thumbnailInfo = null, thumbnailSource = null, blurhash = null
                )
                val params = UploadParameters(source = uploadSource, caption = captionParam, formattedCaption = null, mentions = null, inReplyTo = null)
                val handle = timeline.sendVideo(params, null, videoInfo)
                handle.join()
            }
            else -> {
                val fileInfo = org.matrix.rustcomponents.sdk.FileInfo(
                    mimetype = mimeType,
                    size = (if (fileSize > 0L) fileSize else tempFile.length()).toULong(),
                    thumbnailInfo = null, thumbnailSource = null
                )
                val params = UploadParameters(source = uploadSource, caption = captionParam, formattedCaption = null, mentions = null, inReplyTo = null)
                val handle = timeline.sendFile(params, fileInfo)
                handle.join()
            }
        }
        tempFile.delete()
    }
}
