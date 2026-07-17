package com.hermes.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.android.domain.model.MessageContent
import com.hermes.android.ui.theme.AgentColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@Composable
fun RichMessageContent(
    content: MessageContent.Text,
    modifier: Modifier = Modifier,
) {
    SelectionContainer {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (segment in content.segments) {
                when (segment) {
                    is MessageSegment.Text -> {
                        val annotated = remember(segment.html, segment.plainText) {
                            htmlToAnnotatedString(
                                segment.html ?: segment.plainText,
                                AgentColors.TextPrimary,
                            )
                        }
                        Text(
                            text = annotated,
                            color = AgentColors.TextPrimary,
                            fontSize = 16.sp,
                            modifier = Modifier,
                        )
                    }
                    is MessageSegment.Heading -> {
                        val annotated = remember(segment.html, segment.plainText) {
                            htmlToAnnotatedString(
                                segment.html,
                                AgentColors.TextPrimary,
                            )
                        }
                        Text(
                            text = annotated,
                            color = AgentColors.TextPrimary,
                            fontSize = headingSp(segment.level),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    is MessageSegment.Table -> {
                        TableBlock(
                            headers = segment.headers,
                            rows = segment.rows,
                            alignments = segment.alignments,
                        )
                    }
                    is MessageSegment.CodeBlock -> {
                        CodeBlockText(
                            code = segment.code,
                        )
                    }
                    is MessageSegment.Thinking, is MessageSegment.ToolCall -> {
                        // Deprecated: emoji-based segments are no longer produced
                    }
                }
            }
        }
    }
}

private fun headingSp(level: Int): androidx.compose.ui.unit.TextUnit = when (level) {
    1 -> 22.sp
    2 -> 20.sp
    3 -> 18.sp
    4 -> 17.sp
    5 -> 16.sp
    6 -> 15.sp
    else -> 16.sp
}

@Composable
private fun CodeBlockText(
    code: String,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(AgentColors.Background)
            .padding(12.dp)
    ) {
        Text(
            text = code,
            color = AgentColors.TextPrimary,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            modifier = Modifier.fillMaxWidth().padding(end = 28.dp),
        )
        IconButton(
            onClick = {
                clipboard.setText(AnnotatedString(code))
                scope.launch {
                    copied = true
                    delay(2000)
                    copied = false
                }
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(32.dp)
        ) {
            Icon(
                imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                contentDescription = if (copied) "Copied" else "Copy code",
                tint = AgentColors.TextSecondary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
