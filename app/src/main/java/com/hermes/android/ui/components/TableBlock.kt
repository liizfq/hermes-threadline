package com.hermes.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.selection.SelectionContainer
import com.hermes.android.ui.theme.AgentColors

@Composable
fun TableBlock(
    headers: List<String>,
    rows: List<List<String>>,
    alignments: List<TableAlignment>,
    modifier: Modifier = Modifier,
) {
    val gridColor = AgentColors.TextSecondary.copy(alpha = 0.15f)
    val scrollState = rememberScrollState()
    val columnCount = headers.size

    // Pre-calculate fixed width per column from max content length.
    val colWidths = remember(headers, rows) {
        (0 until columnCount).map { col ->
            val allValues = listOf(headers.getOrNull(col).orEmpty()) +
                rows.map { it.getOrNull(col).orEmpty() }
            val maxLen = allValues.maxOf { it.length }
            (maxLen * 7 + 16).dp.coerceIn(60.dp, 200.dp)
        }
    }

    val tableWidth = colWidths.sumOf { it.value.toInt() }.dp
    val allRows = listOf(headers) + rows

    Box(
        modifier = modifier
            .fillMaxWidth()
            // Block parent's combinedClickable (long-press → copy all) by
            // intercepting click without doing anything. SelectionContainer
            // handles long-press for text selection internally.
            .combinedClickable(
                enabled = true,
                onClick = { /* swallow */ },
                onLongClick = { /* swallow — prevent bubble long-press copy */ },
            )
            .horizontalScroll(scrollState)
    ) {
        SelectionContainer {
            Column(
            modifier = Modifier
                .width(tableWidth)
                .drawBehind {
                    val w = size.width
                    val h = size.height
                    // Vertical column separators
                    var x = 0f
                    for (i in 0 until columnCount - 1) {
                        x += colWidths[i].toPx()
                        drawLine(gridColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
                    }
                    // Horizontal row separators
                    var y = 0f
                    for (rowIndex in allRows.indices) {
                        // Measure row height — each row may differ due to text wrap.
                        // We'll draw after layout via onGloballyPositioned instead.
                        // For now, draw fixed lines based on measured positions.
                    }
                }
        ) {
            // Render rows, capture heights for drawing horizontal lines
            val rowHeights = remember { mutableListOf<Float>() }

            allRows.forEachIndexed { rowIndex, rowData ->
                val isHeader = rowIndex == 0
                val rowColor = when {
                    isHeader -> AgentColors.Background
                    rowIndex % 2 == 0 -> AgentColors.Card
                    else -> AgentColors.Background.copy(alpha = 0.5f)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(rowColor)
                        .height(IntrinsicSize.Min)
                        .drawBehind {
                            // Draw horizontal line at the bottom of this row
                            drawLine(
                                gridColor,
                                Offset(0f, size.height),
                                Offset(size.width, size.height),
                                strokeWidth = 1f
                            )
                        }
                ) {
                    for (colIndex in 0 until columnCount) {
                        val cellText = rowData.getOrElse(colIndex) { "" }
                        TableCell(
                            text = cellText,
                            alignment = alignments.getOrElse(colIndex) { TableAlignment.LEFT },
                            isHeader = isHeader,
                            modifier = Modifier.width(colWidths[colIndex]),
                        )
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun TableCell(
    text: String,
    alignment: TableAlignment,
    isHeader: Boolean,
    modifier: Modifier = Modifier,
) {
    val textAlign = when (alignment) {
        TableAlignment.CENTER -> TextAlign.Center
        TableAlignment.RIGHT -> TextAlign.End
        TableAlignment.LEFT -> TextAlign.Start
    }
    val baseColor = AgentColors.TextPrimary.copy(alpha = if (isHeader) 1f else 0.85f)
    val annotated = remember(text) { parseInlineMarkdown(text, baseColor) }
    Text(
        text = annotated,
        fontSize = 13.sp,
        fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
        color = baseColor,
        textAlign = textAlign,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

/**
 * Parses inline markdown in cell text: **bold**, *italic*, ~~strike~~, `code`.
 * Strips the markdown markers and applies [SpanStyle] to the matched ranges.
 */
private fun parseInlineMarkdown(text: String, baseColor: Color): AnnotatedString {
    val styles = mutableListOf<Pair<IntRange, SpanStyle>>()
    val sb = StringBuilder()
    var i = 0
    while (i < text.length) {
        // **bold**
        if (text.startsWith("**", i)) {
            val end = text.indexOf("**", i + 2)
            if (end > i + 1) {
                val start = sb.length
                sb.append(text, i + 2, end)
                styles.add(start..sb.length to SpanStyle(fontWeight = FontWeight.Bold))
                i = end + 2
                continue
            }
        }
        // *italic*
        if (i < text.length && text[i] == '*' && (i + 1 < text.length) && text[i + 1] != '*') {
            val end = text.indexOf('*', i + 1)
            if (end > i + 1) {
                val start = sb.length
                sb.append(text, i + 1, end)
                styles.add(start..sb.length to SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic))
                i = end + 1
                continue
            }
        }
        // ~~strike~~
        if (text.startsWith("~~", i)) {
            val end = text.indexOf("~~", i + 2)
            if (end > i + 1) {
                val start = sb.length
                sb.append(text, i + 2, end)
                styles.add(start..sb.length to SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough))
                i = end + 2
                continue
            }
        }
        // `code`
        if (text[i] == '`') {
            val end = text.indexOf('`', i + 1)
            if (end > i + 1) {
                val start = sb.length
                sb.append(text, i + 1, end)
                styles.add(start..sb.length to SpanStyle(fontFamily = FontFamily.Monospace, background = baseColor.copy(alpha = 0.1f)))
                i = end + 1
                continue
            }
        }
        sb.append(text[i])
        i++
    }
    return AnnotatedString.Builder(sb.toString()).apply {
        styles.forEach { (range, style) ->
            addStyle(style, range.first, range.last)
        }
    }.toAnnotatedString()
}
