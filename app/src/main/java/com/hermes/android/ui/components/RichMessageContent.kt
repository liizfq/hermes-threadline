package com.hermes.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.hermes.android.domain.model.MessageContent

/**
 * Renders all text-like segments of a message inside a **single** [HtmlText]
 * (and therefore a single native [EditorStyledTextView]).
 *
 * Because every block is merged into one selectable text run, the user can
 * long-press and drag the selection handles across paragraphs, headings, lists,
 * tables and code blocks instead of being trapped inside one chunk.
 *
 * Tables and code blocks are converted to HTML; if the underlying wysiwyg
 * converter drops an unknown tag their text content still appears inline.
 */
@Composable
fun RichMessageContent(
    content: MessageContent.Text,
    modifier: Modifier = Modifier,
) {
    val unified = rememberUnifiedText(content)
    HtmlText(
        html = unified.html,
        plainText = unified.plainText,
        modifier = modifier,
    )
}

/** Cached conversion from segment list to one combined HTML document. */
@Composable
private fun rememberUnifiedText(content: MessageContent.Text): UnifiedText {
    return androidx.compose.runtime.remember(content.segments, content.html, content.plainText) {
        content.toUnifiedText()
    }
}

private data class UnifiedText(
    val html: String?,
    val plainText: String,
)

private fun MessageContent.Text.toUnifiedText(): UnifiedText {
    if (segments.isEmpty()) {
        return UnifiedText(html = html, plainText = plainText)
    }

    val htmlOut = StringBuilder()
    val plainOut = StringBuilder()

    for (segment in segments) {
        when (segment) {
            is MessageSegment.Text -> {
                if (!segment.html.isNullOrBlank()) {
                    htmlOut.append(segment.html)
                } else {
                    htmlOut.append("<p>${escapeHtml(segment.plainText)}</p>")
                }
                plainOut.append(segment.plainText)
            }
            is MessageSegment.Heading -> {
                htmlOut.append("<h${segment.level}>${segment.html}</h${segment.level}>")
                plainOut.append(segment.plainText)
            }
            is MessageSegment.Table -> {
                htmlOut.append(segment.toHtmlTable())
                plainOut.append(segment.toPlainText())
            }
            is MessageSegment.CodeBlock -> {
                val langAttr = segment.language?.let { " class=\"language-$it\"" } ?: ""
                htmlOut.append("<pre><code$langAttr>${escapeHtml(segment.code)}</code></pre>")
                plainOut.append(segment.code)
            }
            is MessageSegment.Thinking, is MessageSegment.ToolCall -> {
                // Deprecated: no longer produced by the parser.
            }
        }
    }

    val combinedHtml = htmlOut.toString()
    return UnifiedText(
        html = combinedHtml.ifBlank { null },
        plainText = plainOut.toString().ifBlank { plainText },
    )
}

private fun MessageSegment.Table.toHtmlTable(): String = buildString {
    append("<table>")
    append("<tr>")
    for (h in headers) append("<th>${escapeHtml(h)}</th>")
    append("</tr>")
    for (row in rows) {
        append("<tr>")
        for (cell in row) append("<td>${escapeHtml(cell)}</td>")
        append("</tr>")
    }
    append("</table>")
}

private fun MessageSegment.Table.toPlainText(): String = buildString {
    append(headers.joinToString(" | "))
    append('\n')
    for (row in rows) {
        append(row.joinToString(" | "))
        append('\n')
    }
}

private fun escapeHtml(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
