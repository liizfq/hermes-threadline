package com.hermes.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermes.android.domain.model.MessageContent

@Composable
fun RichMessageContent(
    content: MessageContent.Text,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (segment in content.segments) {
            when (segment) {
                is MessageSegment.Text -> {
                    HtmlText(
                        html = segment.html,
                        plainText = segment.plainText,
                        modifier = Modifier,
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
                    CodeBlockView(
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
