package com.hermes.android.data.repository

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.hermes.android.domain.model.Message
import com.hermes.android.domain.model.MessageContent
import com.hermes.android.domain.model.Reaction
import com.hermes.android.domain.model.ReactionSender
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.AudioInfo
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.FileInfo
import org.matrix.rustcomponents.sdk.ImageInfo
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.MsgLikeContent
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.ProfileDetails
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.TextMessageContent
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineFilter
import org.matrix.rustcomponents.sdk.TimelineFocus
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.TimelineListener
import org.matrix.rustcomponents.sdk.UploadParameters
import org.matrix.rustcomponents.sdk.UploadSource
import org.matrix.rustcomponents.sdk.VideoInfo
import uniffi.matrix_sdk_ui.TimelineReadReceiptTracking
import java.time.Duration
import java.time.Instant

private const val TAG = "ActiveThread"
private const val MIN_MESSAGES_BEFORE_INITIAL_PAGINATE = 20
private const val AUTO_PAGINATION_RECHECK_DELAY_MS = 150L

/**
 * Single-thread handle. Each instance manages exactly one thread's focused Timeline,
 * messaging, and reaction operations.
 *
 * Lifetimes: [ActiveThreadImpl] is NOT a singleton. The [SessionRepository] creates and
 * owns the sole [ActiveThreadImpl] via [ActiveThreadFactory]. When the user navigates
 * away or the session is deleted, [SessionRepository] calls [close()].
 *
 * @param room the live [Room] for this thread.
 * @param threadRootId the matrix event id of the thread root.
 * @param settingsRepository injected for user id lookups.
 */
class ActiveThreadImpl(
    override val room: Room,
    override val threadRootId: String,
    private val settingsRepository: SettingsRepository,
    private val timeline: Timeline,
) : ActiveThread {

    private val scope: CoroutineScope
    private val items = ArrayList<TimelineItem>()
    private val mutex = Mutex()

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    override val messages: Flow<List<Message>> = _messages.asStateFlow()

    private val _paginationStatus = MutableStateFlow(PaginationStatus())
    override val backwardPaginationStatus: StateFlow<PaginationStatus> = _paginationStatus
    private val paginationMutex = Mutex()

    private val _state = MutableStateFlow<ActiveThreadState>(ActiveThreadState.Starting)
    override val state: StateFlow<ActiveThreadState> = _state.asStateFlow()

    private var listenerJob: Job? = null
    private var autoPaginationJob: Job? = null
    @Volatile
    private var closed = false
    /**
     * Tracks whether at least one message has been successfully extracted
     * from timeline diffs. While false, the timeline is still in its initial
     * load phase — emitting an empty list would be premature (the SDK may
     * send a Reset with only date-dividers before real events arrive).
     */
    @Volatile
    private var hasLoadedMessages = false

    /** Internal visibility: SessionRepository checks this to decide reuse vs recreate. */
    internal val isClosed: Boolean get() = closed

    /** True only while the underlying SDK timeline listener is still collecting diffs. */
    internal val isActive: Boolean
        get() = !closed && listenerJob?.isActive == true

    init {
        Log.d(TAG, "init: using pre-created timeline for thread=$threadRootId room=${room.id()}")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        listenerJob = scope.launch {
            try {
                timeline.timelineDiffFlow(threadRootId).collect { diffs ->
                    Log.d(TAG, "diffStream[$threadRootId]: received ${diffs.size} diffs: types=${diffs.map { it::class.simpleName }.distinct()}")
                    mutex.withLock {
                        val wiped = applyTimelineDiffs(items, diffs)
                        val dateDividers = items.count { it.asEvent() == null }
                        val eventItems = items.size - dateDividers
                        Log.d(TAG, "diffStream[$threadRootId]: after apply -> totalItems=${items.size} (events=$eventItems dividers=$dateDividers) wiped=$wiped")

                        if (wiped) {
                            // SDK shrink_to_last_chunk wiped the timeline. Don't send the empty
                            // list to UI — keep showing current messages and trigger paginate
                            // to reload from the persisted store.
                            Log.d(TAG, "diffStream[$threadRootId]: items wiped, skipping emit, triggering paginate")
                            ensureAutoPagination()
                        } else {
                            val messages = items.mapNotNull { extractMessageFromItem(it) }
                            // DIAG: layer3=focused ThreadEventCache/timeline path.
                            // Compare lastEventId with SessionRepo DIAG ThreadList latestEventId for same root.
                            val last = messages.lastOrNull()
                            Log.d(
                                TAG,
                                "DIAG ActiveThread[$threadRootId]: source=diffStream " +
                                    "msgs=${messages.size} items=${items.size} wiped=false " +
                                    "lastEventId=${last?.id ?: "null"} lastTs=${last?.timestamp?.toEpochMilli() ?: -1L} " +
                                    "hasLoaded=$hasLoadedMessages types=${diffs.map { it::class.simpleName }.distinct()}"
                            )
                            Log.d(TAG, "diffStream[$threadRootId]: extracted ${messages.size} messages from ${items.size} items, hasLoadedMessages=$hasLoadedMessages")

                            if (messages.isNotEmpty()) {
                                hasLoadedMessages = true
                                _messages.value = messages
                                // Feed the event-id → thread-root-id index so
                                // push can resolve m.replace events targeting
                                // any message in this thread without an SDK
                                // round-trip. Skipped when messages is empty
                                // (avoids a no-op write and initial-load races).
                                try {
                                    val mappings = HashMap<String, String>(messages.size)
                                    for (m in messages) {
                                        if (m.id.isNotBlank() && !m.id.startsWith("echo_")) {
                                            mappings[m.id] = threadRootId
                                        }
                                    }
                                    if (mappings.isNotEmpty()) {
                                        settingsRepository.saveEventThreadRoots(
                                            room.id(),
                                            mappings,
                                        )
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "diffStream[$threadRootId]: index write failed", e)
                                }
                            } else if (hasLoadedMessages) {
                                // Previously loaded messages but now empty (e.g. all events were
                                // removed). Safe to emit the empty list.
                                _messages.value = emptyList()
                            } else {
                                // Initial load phase: SDK sent a Reset/diff with no extractable
                                // messages (e.g. only date-dividers). Don't emit empty — keep UI
                                // in Loading state and trigger paginate to fetch real events.
                                Log.d(TAG, "diffStream[$threadRootId]: initial empty, triggering paginate instead of emitting 0 messages")
                                ensureAutoPagination()
                            }

                            if (messages.size <= MIN_MESSAGES_BEFORE_INITIAL_PAGINATE && _paginationStatus.value.canPaginate) {
                                Log.d(TAG, "diffStream[$threadRootId]: messages=${messages.size} <= $MIN_MESSAGES_BEFORE_INITIAL_PAGINATE, ensuring auto pagination")
                                ensureAutoPagination()
                            }
                        }
                    }
                    // First diff fully processed (apply + extract + auto-pagination
                    // succeeded) — transition Starting → Active. If anything inside
                    // mutex.withLock had thrown, this line is skipped and the outer
                    // catch(e: Throwable) reports ListenerFailure instead.
                    _state.value = ActiveThreadStateMachine.transition(
                        _state.value,
                        ActiveThreadEvent.FirstDiffProcessed
                    )
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "diffStream[$threadRootId]: cancelled (normal teardown)")
                _state.value = ActiveThreadStateMachine.transition(
                    _state.value,
                    ActiveThreadEvent.ListenerClosed
                )
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "diffStream[$threadRootId]: flow terminated with error", e)
                _state.value = ActiveThreadStateMachine.transition(
                    _state.value,
                    ActiveThreadEvent.ListenerFailure(e)
                )
            }
        }

        // Initial back-pagination is driven by the first timeline diff below:
        // if the focused thread starts with too few visible messages, paginate.
    }

    // ---- ActiveThread API ----

    override suspend fun paginate(): Boolean = paginateOnce()

    private suspend fun paginateOnce(): Boolean = paginationMutex.withLock {
        val current = _paginationStatus.value
        if (!current.canPaginate) {
            Log.d(TAG, "paginate: skipped status=$current")
            return false
        }
        _paginationStatus.value = current.copy(isPaginating = true)
        return try {
            val reachedEnd = withContext(Dispatchers.IO) {
                timeline.paginateBackwards(50u)
            }
            _paginationStatus.value = _paginationStatus.value.copy(
                isPaginating = false,
                hasMoreToLoad = !reachedEnd
            )
            Log.d(TAG, "paginate: reachedEnd=$reachedEnd, items=${items.size}, messages=${currentMessageCount()}")
            !reachedEnd
        } catch (e: Exception) {
            Log.w(TAG, "paginate failed", e)
            _paginationStatus.value = _paginationStatus.value.copy(isPaginating = false)
            false
        }
    }

    private fun ensureAutoPagination() {
        if (autoPaginationJob?.isActive == true) return
        autoPaginationJob = scope.launch {
            while (!closed) {
                val messageCount = currentMessageCount()
                if (messageCount > MIN_MESSAGES_BEFORE_INITIAL_PAGINATE) {
                    Log.d(TAG, "autoPaginate: stop, messages=$messageCount > $MIN_MESSAGES_BEFORE_INITIAL_PAGINATE")
                    break
                }
                if (!_paginationStatus.value.canPaginate) {
                    Log.d(TAG, "autoPaginate: stop, status=${_paginationStatus.value}, messages=$messageCount")
                    break
                }

                Log.d(TAG, "autoPaginate: messages=$messageCount status=${_paginationStatus.value}")
                val hasMore = paginateOnce()
                if (!hasMore) break
                delay(AUTO_PAGINATION_RECHECK_DELAY_MS)
            }
        }
    }

    private suspend fun currentMessageCount(): Int = mutex.withLock {
        items.count { extractMessageFromItem(it) != null }
    }

    override suspend fun sendMessage(content: String): Result<Unit> = try {
        val textContent = TextMessageContent(content, null)
        val messageType = MessageType.Text(textContent)
        val messageContent = withContext(Dispatchers.IO) {
            timeline.createMessageContent(messageType)
        } ?: return Result.failure(IllegalStateException("Failed to create message content"))
        withContext(Dispatchers.IO) { timeline.send(messageContent) }
        Log.d(TAG, "sendMessage: sent in thread $threadRootId")
        Result.success(Unit)
    } catch (e: Exception) {
        Log.e(TAG, "sendMessage failed", e)
        Result.failure(e)
    }

    override suspend fun sendVoice(audioFile: java.io.File, waveform: List<Float>, durationMs: Long): Result<Unit> {
        return try {
            val bytes = audioFile.readBytes()
            val uploadSource = UploadSource.Data(bytes, "voice.m4a")
            val uploadParams = UploadParameters(
                source = uploadSource,
                caption = null,
                formattedCaption = null,
                mentions = null,
                inReplyTo = threadRootId
            )
            val audioInfo = AudioInfo(
                duration = Duration.ofMillis(durationMs),
                size = bytes.size.toULong(),
                mimetype = "audio/mp4"
            )
            val handle = withContext(Dispatchers.IO) {
                timeline.sendVoiceMessage(uploadParams, audioInfo, waveform)
            }
            handle.join()
            Log.d(TAG, "sendVoice: sent voice message in thread $threadRootId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "sendVoice failed", e)
            Result.failure(e)
        }
    }

    override suspend fun sendImage(uri: Uri, context: Context): Result<Unit> {
        return try {
            val resolver = context.contentResolver
            var fileName = "image.jpg"
            var fileSize = 0L
            val cursor = resolver.query(uri, null, null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (nameIdx >= 0) fileName = cursor.getString(nameIdx) ?: fileName
                if (sizeIdx >= 0) fileSize = cursor.getLong(sizeIdx)
                cursor.close()
            }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            val tempFile = java.io.File(context.cacheDir, "send_${System.currentTimeMillis()}_$fileName")
            resolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            val uploadSource = UploadSource.File(tempFile.absolutePath)
            val imageInfo = ImageInfo(
                height = options.outHeight.toULong(),
                width = options.outWidth.toULong(),
                mimetype = options.outMimeType ?: "image/jpeg",
                size = (if (fileSize > 0L) fileSize else tempFile.length()).toULong(),
                thumbnailInfo = null,
                thumbnailSource = null,
                blurhash = null,
                isAnimated = null
            )
            val uploadParams = UploadParameters(source = uploadSource, caption = null, formattedCaption = null, mentions = null, inReplyTo = threadRootId)
            val handle = withContext(Dispatchers.IO) {
                timeline.sendImage(uploadParams, null, imageInfo)
            }
            handle.join()
            tempFile.delete()
            Log.d(TAG, "sendImage: sent image in thread $threadRootId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "sendImage failed", e)
            Result.failure(e)
        }
    }

    override suspend fun sendVideo(uri: Uri, context: Context): Result<Unit> {
        return try {
            val resolver = context.contentResolver
            var fileName = "video.mp4"
            var fileSize = 0L
            val cursor = resolver.query(uri, null, null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (nameIdx >= 0) fileName = cursor.getString(nameIdx) ?: fileName
                if (sizeIdx >= 0) fileSize = cursor.getLong(sizeIdx)
                cursor.close()
            }
            var durationMs = 0L
            var width = 0
            var height = 0
            runCatching {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, uri)
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                retriever.release()
            }
            val tempFile = java.io.File(context.cacheDir, "send_${System.currentTimeMillis()}_$fileName")
            resolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            val uploadSource = UploadSource.File(tempFile.absolutePath)
            val mimeType = resolver.getType(uri) ?: "video/mp4"
            val videoInfo = VideoInfo(
                duration = if (durationMs > 0) Duration.ofMillis(durationMs) else null,
                width = width.toULong(),
                height = height.toULong(),
                mimetype = mimeType,
                size = (if (fileSize > 0L) fileSize else tempFile.length()).toULong(),
                thumbnailInfo = null,
                thumbnailSource = null,
                blurhash = null
            )
            val uploadParams = UploadParameters(source = uploadSource, caption = null, formattedCaption = null, mentions = null, inReplyTo = threadRootId)
            val handle = withContext(Dispatchers.IO) {
                timeline.sendVideo(uploadParams, null, videoInfo)
            }
            handle.join()
            tempFile.delete()
            Log.d(TAG, "sendVideo: sent video in thread $threadRootId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "sendVideo failed", e)
            Result.failure(e)
        }
    }

    override suspend fun sendFile(uri: Uri, context: Context): Result<Unit> {
        return try {
            val resolver = context.contentResolver
            var fileName = "file"
            var fileSize = 0L
            var mimeType = "application/octet-stream"
            val cursor = resolver.query(uri, null, null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (nameIdx >= 0) fileName = cursor.getString(nameIdx) ?: fileName
                if (sizeIdx >= 0) fileSize = cursor.getLong(sizeIdx)
                cursor.close()
            }
            mimeType = resolver.getType(uri) ?: mimeType
            val tempFile = java.io.File(context.cacheDir, "send_${System.currentTimeMillis()}_$fileName")
            resolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            val uploadSource = UploadSource.File(tempFile.absolutePath)
            val fileInfo = FileInfo(
                mimetype = mimeType,
                size = (if (fileSize > 0L) fileSize else tempFile.length()).toULong(),
                thumbnailInfo = null,
                thumbnailSource = null
            )
            val uploadParams = UploadParameters(source = uploadSource, caption = null, formattedCaption = null, mentions = null, inReplyTo = threadRootId)
            val handle = withContext(Dispatchers.IO) {
                timeline.sendFile(uploadParams, fileInfo)
            }
            handle.join()
            tempFile.delete()
            Log.d(TAG, "sendFile: sent file in thread $threadRootId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "sendFile failed", e)
            Result.failure(e)
        }
    }

    override suspend fun toggleReaction(
        eventOrTransactionId: EventOrTransactionId,
        key: String
    ): Result<Unit> {
        return try {
            withContext(Dispatchers.IO) {
                timeline.toggleReaction(eventOrTransactionId, key)
            }
            Log.d(TAG, "toggleReaction: toggled reaction $key for $eventOrTransactionId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "toggleReaction failed", e)
            Result.failure(e)
        }
    }

    override suspend fun refresh() {
        try {
            Log.d(TAG, "refresh: calling room.refreshThread($threadRootId)")
            withContext(Dispatchers.IO) {
                room.refreshThread(threadRootId)
            }
            Log.d(TAG, "refresh: done")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "refresh: failed", e)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        Log.d(TAG, "close: destroying timeline for $threadRootId (${items.size} items)")
        listenerJob?.cancel()
        autoPaginationJob?.cancel()
        scope.cancel()
        try {
            timeline.close()
        } catch (e: Exception) {
            Log.w(TAG, "close: failed to close timeline", e)
        }
        _paginationStatus.value = PaginationStatus()
        // Ensure Closed is deterministic if the listener coroutine never fires its own handler
        _state.value = ActiveThreadStateMachine.transition(
            _state.value,
            ActiveThreadEvent.ListenerClosed
        )
    }

    // ---- Timeline diffing / mapping helpers (same logic as before, now instance-level) ----

    private fun applyTimelineDiffs(items: MutableList<TimelineItem>, diffs: List<TimelineDiff>): Boolean {
        // Returns true if a Clear/Reset caused items to be wiped (caller should
        // skip sending the empty list and trigger paginate instead).
        var wiped = false
        val before = items.size
        for (diff in diffs) {
            val itemSizeBefore = items.size
            when (diff) {
                is TimelineDiff.Append -> {
                    for (item in diff.values) items.add(item)
                }
                is TimelineDiff.PushBack -> items.add(diff.value)
                is TimelineDiff.PushFront -> items.add(0, diff.value)
                is TimelineDiff.Insert -> {
                    val index = diff.index.toInt()
                    if (index in 0..items.size) items.add(index, diff.value) else items.add(diff.value)
                }
                is TimelineDiff.Remove -> {
                    val idx = diff.index.toInt()
                    if (idx in items.indices) items.removeAt(idx)
                }
                is TimelineDiff.Set -> {
                    val index = diff.index.toInt()
                    if (index in items.indices) items[index] = diff.value
                }
                is TimelineDiff.Reset -> {
                    items.clear()
                    items.addAll(diff.values)
                }
                is TimelineDiff.Clear -> items.clear()
                is TimelineDiff.Truncate -> {
                    val count = diff.length.toInt()
                    while (items.size > count) items.removeAt(items.lastIndex)
                }
                is TimelineDiff.PopBack -> if (items.isNotEmpty()) items.removeAt(items.lastIndex)
                is TimelineDiff.PopFront -> if (items.isNotEmpty()) items.removeAt(0)
            }
            if (diff is TimelineDiff.Clear || diff is TimelineDiff.Reset
                || diff is TimelineDiff.Truncate || diff is TimelineDiff.PopBack
                || diff is TimelineDiff.PopFront
            ) {
                Log.w(TAG, "applyTimelineDiffs: ${diff::class.simpleName} items=$itemSizeBefore->${items.size}")
            }
        }
        Log.d(TAG, "applyTimelineDiffs: batch done ${diffs.size} diffs, items=$before->${items.size}, types=${diffs.map { it::class.simpleName }.groupBy { it }.mapValues { it.value.size }}")
        // If a Clear wiped all items but we had items before, flag it so the caller
        // can trigger paginate to reload from store instead of sending an empty list.
        if (items.isEmpty() && before > 0) {
            wiped = true
        }
        return wiped
    }

    private fun extractMessageFromItem(item: TimelineItem): Message? {
        val event = item.asEvent() ?: return null
        val content = event.content as? TimelineItemContent.MsgLike ?: return null
        val msgLike: MsgLikeContent = content.content
        val messageContent = extractMessageContent(msgLike.kind) ?: return null

        val eventId = when (val eotId = event.eventOrTransactionId) {
            is org.matrix.rustcomponents.sdk.EventOrTransactionId.EventId -> eotId.eventId
            is org.matrix.rustcomponents.sdk.EventOrTransactionId.TransactionId -> {
                "echo_${eotId.transactionId}"
            }
            else -> {
                Log.v(TAG, "extractMessageFromItem: eventOrTransactionId is unknown type: ${event.eventOrTransactionId::class.simpleName}")
                return null
            }
        }

        val profile = event.senderProfile
        val timestamp = ulongToInstant(event.timestamp)
        val reactions = extractReactions(msgLike, settingsRepository.getUserId())

        return Message(
            id = eventId,
            senderId = event.sender,
            senderName = extractDisplayName(profile),
            senderAvatarUrl = extractAvatarUrl(profile),
            content = messageContent,
            timestamp = timestamp,
            isOwn = event.isOwn,
            reactions = reactions
        )
    }

    private fun ulongToInstant(ulongTimestamp: ULong): Instant {
        return Instant.ofEpochMilli(ulongTimestamp.toLong())
    }

    private fun extractMessageContent(kind: MsgLikeKind): MessageContent? {
        val message = kind as? MsgLikeKind.Message ?: return null
        val msgContent = message.content
        val msgType = msgContent.msgType

        return when (msgType) {
            is MessageType.Text -> {
                val text = msgType.content
                val html = text.formatted?.body
                val parser = com.hermes.android.ui.components.MessageContentParser()
                val segments = parser.parse(text.body, html)
                MessageContent.Text(html, text.body, segments)
            }
            is MessageType.Image -> {
                val image = msgType.content
                MessageContent.Image(
                    mxcUrl = image.source.url(),
                    width = image.info?.width?.toInt(),
                    height = image.info?.height?.toInt()
                )
            }
            is MessageType.Video -> {
                val video = msgType.content
                MessageContent.Video(
                    mxcUrl = video.source.url(),
                    thumbnailMxcUrl = video.info?.thumbnailSource?.url(),
                    width = video.info?.width?.toInt(),
                    height = video.info?.height?.toInt(),
                    duration = video.info?.duration?.toMillis(),
                    mimeType = video.info?.mimetype
                )
            }
            is MessageType.Audio -> {
                val audio = msgType.content
                val mimeType = audio.info?.mimetype
                val duration = audio.info?.duration?.toMillis()
                val voice = audio.voice
                if (voice != null) {
                    MessageContent.Voice(
                        mxcUrl = audio.source.url(),
                        duration = duration,
                        waveform = emptyList(),
                        mimeType = mimeType
                    )
                } else {
                    MessageContent.Audio(
                        mxcUrl = audio.source.url(),
                        duration = duration,
                        mimeType = mimeType
                    )
                }
            }
            is MessageType.File -> {
                val file = msgType.content
                MessageContent.File(
                    mxcUrl = file.source.url(),
                    fileName = file.filename,
                    fileSize = file.info?.size?.toLong(),
                    mimeType = file.info?.mimetype
                )
            }
            else -> {
                val parser = com.hermes.android.ui.components.MessageContentParser()
                val segments = parser.parse(msgContent.body, msgContent.body)
                MessageContent.Text(null, msgContent.body, segments)
            }
        }
    }

    private fun extractDisplayName(profile: ProfileDetails): String? {
        return when (profile) {
            is ProfileDetails.Ready -> profile.displayName
            else -> null
        }
    }

    private fun extractAvatarUrl(profile: ProfileDetails): String? {
        return when (profile) {
            is ProfileDetails.Ready -> profile.avatarUrl
            else -> null
        }
    }

    private fun extractReactions(msgLike: MsgLikeContent, currentUserId: String? = null): List<Reaction> {
        val raw = msgLike.reactions
        return raw.map { reaction ->
            val senders = reaction.senders.map { senderData ->
                ReactionSender(
                    senderId = senderData.senderId,
                    timestamp = ulongToInstant(senderData.timestamp)
                )
            }
            Reaction(
                key = reaction.key,
                count = senders.size,
                senders = senders,
                isOwn = currentUserId != null && senders.any { it.senderId == currentUserId }
            )
        }
    }
}

// Extension: SDK Timeline -> Flow of diffs
private fun Timeline.timelineDiffFlow(threadRootId: String): Flow<List<TimelineDiff>> = callbackFlow<List<TimelineDiff>> {
    val listener = object : TimelineListener {
        override fun onUpdate(diff: List<TimelineDiff>) {
            Log.d(TAG, "timelineDiffFlow[$threadRootId]: received ${diff.size} diffs")
            val result = trySend(diff)
            if (!result.isSuccess) {
                Log.w(TAG, "timelineDiffFlow[$threadRootId]: trySend failed, diff lost (${diff.size} diffs)")
            }
        }
    }
    val taskHandle = addListener(listener)
    Log.d(TAG, "timelineDiffFlow[$threadRootId]: registered listener")
    awaitClose {
        Log.w(TAG, "timelineDiffFlow[$threadRootId]: awaitClose triggered — listener destroyed")
        taskHandle.cancelAndDestroy()
    }
}.buffer(Channel.UNLIMITED)

private fun org.matrix.rustcomponents.sdk.TaskHandle.cancelAndDestroy() {
    cancel()
    destroy()
}
