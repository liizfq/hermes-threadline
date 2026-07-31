package com.hermes.android.presentation.card

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.hermes.android.domain.model.Message
import com.hermes.android.domain.model.MessageContent
import com.hermes.android.presentation.chat.MessageBubble
import com.hermes.android.ui.settings.strEnZh
import com.hermes.android.ui.theme.AgentColors
import java.time.Instant

/**
 * Detail screen for a non-thread "card" session: shows the single original
 * message as a bubble and a slim input bar to compose a reply. Hitting Send
 * opens a confirmation dialog; confirming dispatches [onSend] (which the
 * caller wires to [com.hermes.android.presentation.card.CardViewModel.sendReply]
 * and pops back to the session list).
 *
 * Layout: portrait = full-width bubble; landscape = width-capped (600dp),
 * centered — mirrors [com.hermes.android.presentation.chat.ChatScreen]'s
 * landscape handling.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardScreen(
    originalText: String,
    onSend: (String) -> Unit,
    onBack: () -> Unit,
) {
    var input by remember { mutableStateOf(TextFieldValue("")) }
    var showConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strEnZh("Card", "卡片")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = strEnZh("Back", "返回")
                        )
                    }
                }
            )
        },
        bottomBar = {
            CardInputBar(
                value = input,
                onValueChange = { input = it },
                onSend = {
                    if (input.text.isNotBlank()) showConfirm = true
                }
            )
        }
    ) { padding ->
        val isLandscape =
            LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Render the original non-thread message as a bubble. isOwn = false
            // so it shows the incoming (agent/sender) side.
            val message = remember(originalText) {
                Message(
                    id = "card-original",
                    senderId = "",
                    senderName = null,
                    senderAvatarUrl = null,
                    content = MessageContent.Text(html = null, plainText = originalText),
                    timestamp = Instant.EPOCH,
                    isOwn = false
                )
            }
            val bubbleModifier = if (isLandscape) {
                Modifier
                    .widthIn(max = 600.dp)
                    .align(Alignment.CenterHorizontally)
                    .fillMaxWidth()
            } else {
                Modifier.fillMaxWidth()
            }
            Column(modifier = bubbleModifier) {
                MessageBubble(message = message)
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(strEnZh("Start new conversation?", "开启新对话？")) },
            text = {
                Text(
                    strEnZh(
                        "This will send your reply and return to the session list.",
                        "这将发送你的回复并返回会话列表。"
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val text = input.text
                        showConfirm = false
                        input = TextFieldValue("")
                        onSend(text)
                    }
                ) {
                    Text(strEnZh("Send", "发送"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text(strEnZh("Cancel", "取消"))
                }
            }
        )
    }
}

/**
 * Slimmed-down input bar for the Card screen: a rounded multiline TextField
 * plus a single Send button. No attachment menu, no /stop, no voice recording
 * (Mic omitted by design — Send is always shown, disabled-by-noop when blank).
 */
@Composable
private fun CardInputBar(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
) {
    Surface(color = AgentColors.Card, shadowElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = value,
                onValueChange = {
                    // Keep the caret at the end on programmatic resets.
                    onValueChange(
                        it.copy(
                            selection = if (it.text.isEmpty()) TextRange(0) else it.selection
                        )
                    )
                },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        strEnZh("Type a message...", "输入消息..."),
                        color = AgentColors.TextSecondary
                    )
                },
                maxLines = 4,
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = AgentColors.Background,
                    unfocusedContainerColor = AgentColors.Background,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                )
            )

            Spacer(modifier = Modifier.size(4.dp))

            IconButton(
                onClick = onSend,
                enabled = value.text.isNotBlank(),
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (value.text.isNotBlank()) AgentColors.AccentBlue
                        else AgentColors.AccentBlue.copy(alpha = 0.38f)
                    )
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = strEnZh("Send", "发送"),
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
