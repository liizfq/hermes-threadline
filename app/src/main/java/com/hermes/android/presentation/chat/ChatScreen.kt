package com.hermes.android.presentation.chat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hermes.android.data.repository.PaginationStatus
import com.hermes.android.domain.model.Message
import com.hermes.android.domain.model.MessageContent
import com.hermes.android.media.player.AudioPlayerController
import com.hermes.android.media.player.AudioPlayerEntryPoint
import com.hermes.android.media.player.VoiceRecorderController
import com.hermes.android.media.player.VoiceRecorderEntryPoint
import com.hermes.android.media.ui.AudioMessageContent
import com.hermes.android.media.ui.ImageMessageContent
import com.hermes.android.media.ui.VideoMessageContent
import com.hermes.android.media.ui.VoiceMessageContent
import com.hermes.android.media.ui.VoiceRecorderBar
import dagger.hilt.android.EntryPointAccessors
import com.hermes.android.presentation.UiState
import com.hermes.android.ui.components.AttachmentCard
import com.hermes.android.ui.components.HtmlText
import com.hermes.android.ui.components.ReactionBar
import com.hermes.android.ui.components.MessageSegment
import com.hermes.android.ui.components.RichMessageContent
import com.hermes.android.ui.components.ThinkingBlock
import com.hermes.android.ui.settings.strEnZh
import com.hermes.android.ui.theme.AgentColors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsState()
    val paginationStatus by viewModel.backwardPaginationStatus.collectAsState()
    val timelineLag by viewModel.timelineLag.collectAsState()
    val listState = rememberLazyListState()
    val draftText by viewModel.draftText.collectAsState()
    var inputText by remember { mutableStateOf(TextFieldValue(draftText)) }
    var hasScrolledToBottom by remember { mutableStateOf(false) }
    var showTitleEditDialog by remember { mutableStateOf(false) }
    var isAgentCollapsed by remember { mutableStateOf(false) }
    val sessionTitle by viewModel.sessionTitle.collectAsState()

    // Scroll position preservation during back-pagination.
    // LazyColumn's firstVisibleItemIndex is a numeric index, NOT key-based.
    // When messages are prepended, existing items shift to higher indices,
    // but firstVisibleItemIndex stays the same → view jumps to new (older) messages.
    // Fix: record the message ID at the current position before paginate,
    // then scrollToItem to its new index after data updates.
    var scrollAnchorMsgId by remember { mutableStateOf<String?>(null) }
    var scrollAnchorOffset by remember { mutableIntStateOf(0) }
    // Pull distance captured at trigger time — used for smooth scroll-into-position
    var pullDistanceForRestore by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(draftText) {
        Log.d("ChatScreen", "draftText=$draftText, inputText=${inputText.text}")
        if (inputText.text != draftText) {
            inputText = TextFieldValue(draftText)
        }
    }

    // Safety net: persist current input text when leaving composition so it
    // survives navigation even if onValueChange hasn't fired for the latest
    // state (e.g. process death, rapid back navigation).
    val currentInputText = rememberUpdatedState(inputText.text)
    DisposableEffect(Unit) {
        onDispose {
            if (currentInputText.value.isNotBlank()) {
                viewModel.updateDraft(currentInputText.value)
            }
        }
    }

    // Voice recording state
    val context = LocalContext.current
    var isRecording by remember { mutableStateOf(false) }
    val voiceRecorderController = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            VoiceRecorderEntryPoint::class.java
        ).voiceRecorderController()
    }
    var hasRecordPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    val recordPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasRecordPermission = granted }

    // Attachment launchers
    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { viewModel.sendImageMessage(it) } }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { viewModel.sendVideoMessage(it) } }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { viewModel.sendFileMessage(it) } }

    // Camera
    val photoDir = remember { File(context.cacheDir, "camera").apply { mkdirs() } }
    val photoFile = remember { File(photoDir, "photo_${System.currentTimeMillis()}.jpg") }
    val photoUri = remember {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile)
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success -> if (success) viewModel.sendImageMessage(photoUri) }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) cameraLauncher.launch(photoUri) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        sessionTitle,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.combinedClickable(
                            onClick = { /* no-op on regular click */ },
                            onLongClick = { showTitleEditDialog = true }
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = strEnZh("Back", "返回")
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { isAgentCollapsed = !isAgentCollapsed }) {
                        Icon(
                            imageVector = if (isAgentCollapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                            contentDescription = if (isAgentCollapsed) strEnZh("Expand agent replies", "展开 Agent 回复") else strEnZh("Collapse agent replies", "折叠 Agent 回复"),
                            tint = if (isAgentCollapsed) MaterialTheme.colorScheme.primary else AgentColors.TextSecondary
                        )
                    }
                }
            )
        },
        bottomBar = {
            ChatInputBar(
                value = inputText,
                onValueChange = {
                    inputText = it
                    viewModel.updateDraft(it.text)
                },
                onSend = {
                    if (inputText.text.isNotBlank()) {
                        viewModel.sendMessage(inputText.text)
                        viewModel.clearDraft()
                        hasScrolledToBottom = false
                    }
                },
                isRecordingMode = isRecording,
                onStartRecording = {
                    if (hasRecordPermission) {
                        isRecording = true
                        voiceRecorderController.startRecording()
                    } else {
                        recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onStopAgent = { viewModel.sendMessage("/stop") },
                onSendFile = { filePickerLauncher.launch(arrayOf("*/*")) },
                onSendImage = { imagePickerLauncher.launch("image/*") },
                onSendVideo = { videoPickerLauncher.launch("video/*") },
                onTakePhoto = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                voiceRecorderContent = {
                    VoiceRecorderBar(
                        voiceRecorderController = voiceRecorderController,
                        onSend = { file, waveform, durationMs ->
                            isRecording = false
                            viewModel.sendVoiceMessage(file, waveform, durationMs)
                        },
                        onCancel = {
                            isRecording = false
                        }
                    )
                }
            )
        }
    ) { padding ->
        when (val state = messages) {
            is UiState.Loading -> {
                Log.w("ChatScreen", "Loading state shown for threadRootId=${viewModel.threadRootId}")
                Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is UiState.Success -> {

                val coroutineScope = rememberCoroutineScope()

                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                    // Flatten messages to individual items for stable LazyColumn keys.
                    // Each message = one LazyColumn item with key = message.id.
                    // This lets LazyColumn automatically preserve scroll position
                    // when back-pagination prepends older messages.
                    // AgentGroup is only used when isAgentCollapsed=true (summary view).
                    val renderItems = remember(state.data, isAgentCollapsed) {
                        if (isAgentCollapsed) {
                            state.data.toGroupedRenderItems()
                        } else {
                            state.data.map { ChatRenderItem.SingleMsg(it) }
                        }
                    }
                    Log.d("ChatScreen", "Success: ${state.data.size} messages → ${renderItems.size} renderItems collapsed=$isAgentCollapsed (threadRootId=${viewModel.threadRootId})")

                    // Use derivedStateOf to avoid recomposing the entire ChatScreen
                    // on every layout frame — layoutInfo changes ~120fps during idle
                    // scroll/animation, which was causing an infinite recomposition loop.
                    val currentRenderItems = rememberUpdatedState(renderItems)
                    val isLatestVisible by remember {
                        derivedStateOf {
                            !listState.canScrollForward && currentRenderItems.value.isNotEmpty()
                        }
                    }

                    PullToLoadHistory(
                        lazyListState = listState,
                        onLoadMore = {
                            // Record anchor: the message ID at the current first visible position.
                            val firstIndex = listState.firstVisibleItemIndex
                            if (firstIndex in renderItems.indices) {
                                val item = renderItems[firstIndex]
                                val anchorId = when (item) {
                                    is ChatRenderItem.SingleMsg -> item.message.id
                                    is ChatRenderItem.AgentGroup -> item.messages.first().id
                                }
                                scrollAnchorMsgId = anchorId
                                scrollAnchorOffset = listState.firstVisibleItemScrollOffset
                                Log.d("ChatScreen", "onLoadMore: firstIndex=$firstIndex anchorId=$anchorId offset=$scrollAnchorOffset")
                            }
                            viewModel.loadMoreMessages()
                        },
                        isPaginating = paginationStatus.isPaginating,
                        canPaginate = paginationStatus.canPaginate,
                        onPullDistance = { dist -> pullDistanceForRestore = dist },
                    ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            items = renderItems,
                            key = { it.key }
                        ) { renderItem ->
                            when (renderItem) {
                                is ChatRenderItem.SingleMsg -> {
                                    MessageBubble(
                                        message = renderItem.message,
                                        onReactionClick = { emoji ->
                                            viewModel.toggleReaction(renderItem.message.id, emoji)
                                        },
                                    )
                                }
                                is ChatRenderItem.AgentGroup -> {
                                    // Collapsed mode: show summary bar
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(0.7f),
                                        color = AgentColors.Card,
                                        shape = MaterialTheme.shapes.small
                                    ) {
                                        Text(
                                            text = renderItem.summary,
                                            fontSize = 12.sp,
                                            color = AgentColors.TextSecondary,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    } // PullToLoadHistory

                    // TimelineLag 提示条：ThreadList 与 ActiveThread 不一致时
                    val currentLag = timelineLag
                    if (currentLag != null) {
                        val lagHint = when (currentLag.direction) {
                            LagDirection.ChatBehind ->
                                strEnZh(
                                    "Thread list is ahead; catching up chat…",
                                    "列表更新于聊天，正在同步时间线…"
                                )
                            LagDirection.SessionListBehind ->
                                strEnZh(
                                    "Chat is ahead; refreshing session list…",
                                    "聊天新于列表，正在刷新会话列表…"
                                )
                            null ->
                                strEnZh(
                                    "List and thread timeline out of sync",
                                    "列表与线程时间线可能未对齐"
                                )
                        }
                        Surface(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 8.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.small,
                            tonalElevation = 2.dp
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                                Text(
                                    lagHint,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    strEnZh(
                                        "ThreadList latest eventId does not match current timeline",
                                        "ThreadList 与当前时间线最新 eventId 不一致"
                                    ),
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }

                    // "Jump to bottom" FAB — shown whenever the newest message is not visible.
                    AnimatedVisibility(
                        visible = renderItems.isNotEmpty() && !isLatestVisible,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(start = 16.dp, end = 16.dp, bottom = 48.dp),
                        enter = fadeIn(tween(150)) + scaleIn(tween(150)),
                        exit = fadeOut(tween(150)) + scaleOut(tween(150))
                    ) {
                        SmallFloatingActionButton(
                            onClick = {
                                coroutineScope.launch {
                                    scrollToChatBottom(listState)
                                }
                            },
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = strEnZh("Scroll to bottom", "滚动到底部")
                            )
                        }
                    }

                    LaunchedEffect(renderItems.isNotEmpty()) {
                        if (renderItems.isNotEmpty() && !hasScrolledToBottom) {
                            hasScrolledToBottom = true
                            scrollToChatBottom(listState)
                        }
                    }
                    // Auto-scroll to bottom on new messages when user is already at the bottom.
                    // For back-pagination, LazyColumn with stable keys preserves scroll position
                    // automatically — we just add a smooth scroll-up by pull distance to reveal
                    // newly loaded messages.
                    val previousMsgCount = remember { mutableStateOf(0) }
                    LaunchedEffect(state.data.size) {
                        val currentSize = state.data.size
                        val previousSize = previousMsgCount.value
                        if (currentSize > previousSize && previousSize > 0 && hasScrolledToBottom) {
                            if (scrollAnchorMsgId != null) {
                                // Back-pagination: find anchor's new index and restore position.
                                // scrollToItem is instant (no animation) to prevent visible jump.
                                // Then animateScrollBy reveals new messages smoothly.
                                val anchorId = scrollAnchorMsgId!!
                                val newIndex = renderItems.indexOfFirst { item ->
                                    when (item) {
                                        is ChatRenderItem.SingleMsg -> item.message.id == anchorId
                                        is ChatRenderItem.AgentGroup -> item.messages.any { it.id == anchorId }
                                    }
                                }
                                if (newIndex >= 0) {
                                    listState.scrollToItem(newIndex, scrollAnchorOffset)
                                    val dist = pullDistanceForRestore
                                    if (dist > 0f) {
                                        listState.animateScrollBy(-dist)
                                        pullDistanceForRestore = 0f
                                    }
                                }
                                scrollAnchorMsgId = null
                            } else {
                                // Normal new-message auto-scroll: only when already at bottom.
                                val snapshotTotal = listState.layoutInfo.totalItemsCount
                                val snapshotLastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                                val atBottom = snapshotTotal > 0 &&
                                    snapshotLastVisible >= snapshotTotal - SCROLL_BOTTOM_THRESHOLD
                                if (atBottom) {
                                    listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
                                }
                            }
                        }
                        previousMsgCount.value = currentSize
                    }
                }

            }
            is UiState.Error -> {
                Log.e("ChatScreen", "Error state: ${state.message}")
                Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text(strEnZh("Error: ${state.message}", "错误: ${state.message}"), color = MaterialTheme.colorScheme.error)
                }
            }
        }

        val currentMessages = messages
        if (currentMessages is UiState.Success && currentMessages.data.isEmpty()) {
            Log.w("ChatScreen", "WARNING: Success with 0 messages for threadRootId=${viewModel.threadRootId}")
        }

        if (showTitleEditDialog) {
            var titleInput by remember { mutableStateOf(sessionTitle) }
            AlertDialog(
                onDismissRequest = { showTitleEditDialog = false },
                title = { Text(strEnZh("Edit session title", "编辑会话标题")) },
                text = {
                    OutlinedTextField(
                        value = titleInput,
                        onValueChange = { titleInput = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(strEnZh("Enter new title", "输入新标题")) }
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (titleInput.isNotBlank()) {
                            viewModel.saveSessionTitle(titleInput.trim())
                        }
                        showTitleEditDialog = false
                    }) {
                        Text(strEnZh("Confirm", "确认"))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showTitleEditDialog = false }) {
                        Text(strEnZh("Cancel", "取消"))
                    }
                }
            )
        }
    }
}


/**
 * Render model for the message list.
 *
 * - [SingleMsg]: a user message, always rendered as a [MessageBubble].
 * - [AgentGroup]: consecutive agent messages between two user messages,
 *   rendered individually when expanded or as a compact bar when collapsed.
 */
sealed class ChatRenderItem {
    data class SingleMsg(val message: Message) : ChatRenderItem()

    data class AgentGroup(val messages: List<Message>) : ChatRenderItem() {
        val summary: String
            get() {
                val locale = com.hermes.android.ui.settings.LocaleManager.currentLocale()
                if (messages.size == 1) return strEnZh(locale, "🤖 1 reply", "🤖 1 条回复")
                val toolCount = messages.count { it.isToolOrThinking() }
                return if (toolCount > 0) strEnZh(locale, "🤖 ${messages.size} replies · $toolCount steps", "🤖 ${messages.size} 条回复 · $toolCount 步骤")
                else strEnZh(locale, "🤖 ${messages.size} replies", "🤖 ${messages.size} 条回复")
            }
    }

    val key: String
        get() = when (this) {
            is SingleMsg -> message.id
            // Use LAST message ID — prepending older agent messages never
            // changes the last message, so the LazyColumn item key stays
            // stable across back-pagination. Using first().id caused the key
            // to change on every prepend, making LazyColumn treat it as a
            // brand-new item and destroying scroll state.
            is AgentGroup -> "agent_${messages.last().id}"
        }
}

private fun Message.isToolOrThinking(): Boolean {
    val textContent = content as? MessageContent.Text ?: return false
    return textContent.segments.any { it is MessageSegment.ToolCall || it is MessageSegment.Thinking }
}

/**
 * Groups consecutive agent (non-own) messages into [ChatRenderItem.AgentGroup];
 * user messages become [ChatRenderItem.SingleMsg].
 */

/**
 * Scrolls the LazyColumn to the very bottom. Uses the canonical Compose
 * (large index, large offset) idiom with a one-frame delay + verification
 * correction to handle dynamic layout changes (keyboard, input bar).
 */

private const val SCROLL_BOTTOM_THRESHOLD = 3

/**
 * Scrolls the LazyColumn to the very bottom. Uses scrollToItem (non-animated)
 * with a large offset that Compose clamps to the valid range, ensuring we
 * always land at the absolute bottom regardless of item heights.
 *
 * NOTE: We deliberately use scrollToItem (not animateScrollToItem) because
 * the latter tries to animate to the target offset, and an impossibly large
 * offset causes the animation to never complete — freezing the UI.
 */
private suspend fun scrollToChatBottom(listState: androidx.compose.foundation.lazy.LazyListState) {
    val lastIndex = listState.layoutInfo.totalItemsCount - 1
    if (lastIndex < 0) return
    listState.scrollToItem(lastIndex, Int.MAX_VALUE / 2)
}

private fun List<Message>.toGroupedRenderItems(): List<ChatRenderItem> {
    if (isEmpty()) return emptyList()
    val result = mutableListOf<ChatRenderItem>()
    val agentBuffer = mutableListOf<Message>()

    fun flushBuffer() {
        if (agentBuffer.isNotEmpty()) {
            result.add(ChatRenderItem.AgentGroup(agentBuffer.toList()))
            agentBuffer.clear()
        }
    }

    for (msg in this) {
        if (msg.isOwn) {
            flushBuffer()
            result.add(ChatRenderItem.SingleMsg(msg))
        } else {
            agentBuffer.add(msg)
        }
    }
    flushBuffer()
    return result
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: Message,
    onReactionClick: ((String) -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val isUser = message.isOwn
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current

    val bubbleClickModifier = if (onLongClick != null) {
        Modifier.combinedClickable(
            onClick = {},
            onLongClick = {
                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                onLongClick.invoke()
            },
        )
    } else {
        Modifier
    }

    val bubbleColor = if (isUser) AgentColors.UserBubble else AgentColors.Card
    val bubbleShape = if (isUser) {
        RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
    } else {
        RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
    }

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val bubbleMaxWidthFraction = if (isLandscape) 0.85f else 0.95f

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {

        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            modifier = Modifier.fillMaxWidth(bubbleMaxWidthFraction)
        ) {
            Surface(
                shape = bubbleShape,
                color = bubbleColor,
                shadowElevation = 1.dp,
                modifier = bubbleClickModifier,
            ) {
                when (val content = message.content) {
                    is MessageContent.Text -> {
                        if (content.segments.isNotEmpty()) {
                            RichMessageContent(
                                content = content,
                                modifier = Modifier.padding(12.dp),
                            )
                        } else {
                            HtmlText(
                                html = content.html,
                                plainText = content.plainText,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                    is MessageContent.Image -> {
                        ImageMessageContent(
                            content = content,
                            modifier = Modifier.padding(4.dp)
                        )
                    }
                    is MessageContent.Video -> {
                        VideoMessageContent(
                            content = content,
                            modifier = Modifier.padding(4.dp)
                        )
                    }
                    is MessageContent.Audio -> {
                        val controller = rememberAudioPlayerController()
                        AudioMessageContent(
                            content = content,
                            audioPlayerController = controller,
                            modifier = Modifier.padding(4.dp)
                        )
                    }
                    is MessageContent.Voice -> {
                        val controller = rememberAudioPlayerController()
                        VoiceMessageContent(
                            content = content,
                            audioPlayerController = controller,
                            modifier = Modifier.padding(4.dp)
                        )
                    }
                    is MessageContent.File -> {
                        val context = LocalContext.current
                        val scope = rememberCoroutineScope()
                        var isDownloading by remember { mutableStateOf(false) }
                        AttachmentCard(
                            fileName = content.fileName,
                            fileSize = content.fileSize ?: 0,
                            mxcUrl = content.mxcUrl,
                            modifier = Modifier.padding(4.dp),
                            onDownload = { url ->
                                if (isDownloading) return@AttachmentCard
                                scope.launch {
                                    isDownloading = true
                                    try {
                                        val mediaRepo = EntryPointAccessors.fromApplication(
                                            context.applicationContext,
                                            com.hermes.android.media.ui.MediaRepositoryEntryPoint::class.java
                                        ).mediaRepository()
                                        val mimeType = content.mimeType ?: "application/octet-stream"
                                        val localPath = withContext(Dispatchers.IO) {
                                            mediaRepo.getFile(url, content.fileName, mimeType)
                                        }
                                        val file = java.io.File(localPath)
                                        val uri = FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            file
                                        )
                                        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(uri, mimeType)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(viewIntent)
                                    } catch (_: Exception) {
                                    } finally {
                                        isDownloading = false
                                    }
                                }
                            }
                        )
                    }
                }
            }

            if (message.reactions.isNotEmpty()) {
                ReactionBar(
                    reactions = message.reactions,
                    onReactionClick = onReactionClick,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
fun ChatInputBar(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    isRecordingMode: Boolean = false,
    onStartRecording: () -> Unit = {},
    onStopAgent: () -> Unit = {},
    onSendFile: () -> Unit = {},
    onSendImage: () -> Unit = {},
    onSendVideo: () -> Unit = {},
    onTakePhoto: () -> Unit = {},
    voiceRecorderContent: @Composable () -> Unit = {}
) {
    val showSlashCommands = value.text.startsWith("/")
    val slashQuery = if (showSlashCommands) value.text.drop(1) else ""

    Surface(
        color = AgentColors.Card,
        shadowElevation = 2.dp
    ) {
        Column {
            voiceRecorderContent()

            // Slash command dropdown — rendered above the input row,
            // expands upward toward the chat content (away from the keyboard).
            if (showSlashCommands && !isRecordingMode) {
                SlashCommandDropdown(
                    query = slashQuery,
                    onCommandSelected = { selectedCommand ->
                        onValueChange(TextFieldValue(selectedCommand, TextRange(selectedCommand.length)))
                    }
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Attachment button (+) with dropdown menu
                var showAttachmentMenu by remember { mutableStateOf(false) }

                Box {
                    IconButton(
                        onClick = { showAttachmentMenu = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = strEnZh("Attachment", "附件"),
                            tint = AgentColors.TextSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = showAttachmentMenu,
                        onDismissRequest = { showAttachmentMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(strEnZh("Send file", "发送文件")) },
                            onClick = { showAttachmentMenu = false; onSendFile() },
                            leadingIcon = { Icon(Icons.Default.AttachFile, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text(strEnZh("Send image", "发送图像")) },
                            onClick = { showAttachmentMenu = false; onSendImage() },
                            leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text(strEnZh("Send video", "发送视频")) },
                            onClick = { showAttachmentMenu = false; onSendVideo() },
                            leadingIcon = { Icon(Icons.Default.Movie, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text(strEnZh("Take photo", "拍照")) },
                            onClick = { showAttachmentMenu = false; onTakePhoto() },
                            leadingIcon = { Icon(Icons.Default.PhotoCamera, contentDescription = null) }
                        )
                    }
                }

                // Stop button
                IconButton(onClick = onStopAgent, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = strEnZh("Stop generation", "停止生成"),
                        tint = AgentColors.TextSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                }

                if (!isRecordingMode) {
                    // Text field
                    TextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(strEnZh("Type a message...", "输入消息..."), color = AgentColors.TextSecondary) },
                        maxLines = 4,
                        shape = RoundedCornerShape(24.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = AgentColors.Background,
                            unfocusedContainerColor = AgentColors.Background,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        )
                    )

                    Spacer(modifier = Modifier.width(4.dp))

                    // Dynamic mic/send button
                    IconButton(
                        onClick = {
                            if (value.text.isBlank()) onStartRecording() else onSend()
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(AgentColors.AccentBlue)
                    ) {
                        Icon(
                            imageVector = if (value.text.isBlank()) Icons.Default.Mic else Icons.AutoMirrored.Filled.Send,
                            contentDescription = if (value.text.isBlank()) strEnZh("Voice", "语音") else strEnZh("Send", "发送"),
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberAudioPlayerController(): AudioPlayerController {
    val context = LocalContext.current
    return remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            AudioPlayerEntryPoint::class.java
        ).audioPlayerController()
    }
}


/**
 * Pull-to-load-more history gesture handler.
 *
 * When the list is scrolled to the very top (oldest message visible) and the
 * user pulls DOWN past [LOAD_THRESHOLD], triggers [onLoadMore] with a
 * spring-back animation and progress indicator.
 *
 * Replaces the old scroll-near-top auto-trigger with an intentional gesture.
 */
@Composable
private fun PullToLoadHistory(
    lazyListState: androidx.compose.foundation.lazy.LazyListState,
    onLoadMore: () -> Unit,
    isPaginating: Boolean = false,
    canPaginate: Boolean = true,
    onPullDistance: (Float) -> Unit = {},
    content: @Composable () -> Unit
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val loadThresholdPx = with(density) { LOAD_THRESHOLD.toPx() }

    // 0f = idle, >0 = pulling, >=1 = past threshold
    var pullProgress by remember { mutableFloatStateOf(0f) }
    var isAtTop by remember { mutableStateOf(false) }
    var isGestureActive by remember { mutableStateOf(false) }
    val isTriggered = remember { mutableStateOf(false) }
    // How far the user pulled, in pixels — used by caller for smooth scroll restoration
    var pullDistancePx by remember { mutableFloatStateOf(0f) }
    val latestOnLoadMore by rememberUpdatedState(onLoadMore)

    // Track whether the list is at the very top
    LaunchedEffect(lazyListState) {
        snapshotFlow {
            lazyListState.firstVisibleItemIndex == 0 &&
                lazyListState.firstVisibleItemScrollOffset == 0
        }.collect { atTop -> isAtTop = atTop }
    }

    // Snap-back animation when gesture ends
    LaunchedEffect(isGestureActive) {
        if (!isGestureActive && pullProgress > 0f) {
            val start = pullProgress
            val steps = 12
            for (i in 1..steps) {
                pullProgress = start * (1f - i.toFloat() / steps)
                kotlinx.coroutines.delay(16)
            }
            pullProgress = 0f
        }
    }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // User scrolled UP while indicator is showing → consume to shrink
                if (source == NestedScrollSource.Drag && pullProgress > 0f && available.y < 0f) {
                    val consumed = available.y
                    pullProgress = (pullProgress + consumed / loadThresholdPx).coerceIn(0f, 1.2f)
                    if (pullProgress <= 0f) {
                        pullProgress = 0f
                        isGestureActive = false
                    }
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (source == NestedScrollSource.Drag && isAtTop && available.y > 0f) {
                    // At top + pulling DOWN → grow indicator, consume overscroll
                    isGestureActive = true
                    pullProgress = (pullProgress + available.y / loadThresholdPx).coerceIn(0f, 1.2f)
                    return available
                }
                return Offset.Zero
            }
        }
    }

    // Detect gesture end (finger release) via scroll state.
    // When the user releases the finger (and any fling finishes),
    // isScrollInProgress transitions from true to false. At that point,
    // if pullProgress crossed the threshold, trigger onLoadMore.
    // Then reset isGestureActive so the snap-back animation can run.
    LaunchedEffect(lazyListState) {
        var wasScrolling = false
        snapshotFlow { lazyListState.isScrollInProgress }
            .collect { isScrolling ->
                if (wasScrolling && !isScrolling && isGestureActive) {
                    if (pullProgress >= 1f && !isTriggered.value) {
                        isTriggered.value = true
                        pullDistancePx = pullProgress * loadThresholdPx
                        latestOnLoadMore()
                    }
                    isGestureActive = false
                }
                wasScrolling = isScrolling
            }
    }

    // Reset isTriggered when pagination completes (isPaginating goes false)
    // or when there's nothing more to load (canPaginate goes false).
    LaunchedEffect(isPaginating, canPaginate) {
        if ((!isPaginating || !canPaginate) && isTriggered.value) {
            isTriggered.value = false
        }
    }

    // Notify caller of the pull distance when triggered
    LaunchedEffect(pullDistancePx) {
        if (pullDistancePx > 0f) {
            onPullDistance(pullDistancePx)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(nestedScrollConnection)
    ) {
        content()

        // Pull indicator at the top — appears when pulling down at top
        AnimatedVisibility(
            visible = canPaginate && (isGestureActive || isTriggered.value),
            enter = fadeIn(tween(100)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isTriggered.value || isPaginating) {
                    // Indeterminate (spinning) while loading
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    // Determinate (static arc) that fills as user pulls
                    CircularProgressIndicator(
                        progress = pullProgress.coerceIn(0f, 1f),
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

private val LOAD_THRESHOLD = 120.dp