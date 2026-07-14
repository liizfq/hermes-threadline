package com.hermes.android.ui.components

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

class MessageContentParser {

    fun parse(body: String, formattedBody: String?): List<MessageSegment> {
        if (formattedBody != null) {
            return parseHtmlSegments(formattedBody)
        }
        return parseMarkdownTables(body)
    }

    // ════════════════════════════════════════════════════════════════════════
    //  HTML path (formattedBody != null)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Parses formatted HTML into segments, splitting around `<table>` elements.
     * Non-table elements are grouped into Text segments that retain their HTML
     * so wysiwyg can render formatting (bold, code, links, etc.).
     */
    private fun parseHtmlSegments(html: String): List<MessageSegment> {
        val doc = Jsoup.parse(html)
        val body = doc.body()
        val result = mutableListOf<MessageSegment>()
        val htmlBuffer = StringBuilder()

        for (child in body.childNodes()) {
            if (child is Element && child.tagName() == "table") {
                flushText(htmlBuffer, result)
                parseHtmlTable(child)?.let { result.add(it) }
            } else if (child is Element && child.tagName() == "pre") {
                flushText(htmlBuffer, result)
                parseHtmlCodeBlock(child)?.let { result.add(it) }
            } else {
                // Append both text nodes and non-table elements
                htmlBuffer.append(when (child) {
                    is TextNode -> child.text()
                    is Element -> child.outerHtml()
                    else -> child.toString()
                })
            }
        }
        flushText(htmlBuffer, result)

        return result.ifEmpty {
            listOf(MessageSegment.Text(html = html, plainText = body.text()))
        }
    }

    private fun flushText(buffer: StringBuilder, result: MutableList<MessageSegment>) {
        if (buffer.isNotBlank()) {
            val fragment = buffer.toString().trim()
            if (fragment.isNotBlank()) {
                result.add(MessageSegment.Text(
                    html = fragment,
                    plainText = Jsoup.parse(fragment).text(),
                ))
            }
            buffer.clear()
        }
    }

    private fun parseHtmlTable(table: Element): MessageSegment.Table? {
        // Headers: thead > tr > th/td, or first tr > th/td
        val headerRow = table.selectFirst("thead tr")
            ?: table.selectFirst("tr")
            ?: return null
        val headerCells = headerRow.select("th, td")
        val headers = headerCells.map { it.text().trim() }
        if (headers.isEmpty() || headers.all { it.isBlank() }) return null

        // Alignments from align attr or style text-align
        val alignments = headerCells.map { cell ->
            val raw = cell.attr("align").ifEmpty {
                Regex("text-align:\\s*(\\w+)").find(cell.attr("style"))
                    ?.groupValues?.get(1) ?: ""
            }.lowercase()
            when (raw) {
                "center" -> TableAlignment.CENTER
                "right" -> TableAlignment.RIGHT
                else -> TableAlignment.LEFT
            }
        }

        // Data rows: tbody > tr, or all tr minus header
        val dataRows = table.select("tbody tr").ifEmpty {
            table.select("tr").drop(1)
        }
        val rows = dataRows.mapNotNull { row ->
            val cells = row.select("td, th").map { it.text().trim() }
            if (cells.isNotEmpty()) cells else null
        }
        if (rows.isEmpty()) return null

        return MessageSegment.Table(
            headers = headers,
            rows = rows,
            alignments = alignments,
        )
    }

    private fun parseHtmlCodeBlock(pre: Element): MessageSegment.CodeBlock? {
        val codeElement = pre.selectFirst("code")
        val code = (codeElement ?: pre).wholeText().trimEnd()
        if (code.isBlank()) return null

        val language = codeElement?.classNames()
            ?.firstOrNull { it.startsWith("language-") }
            ?.removePrefix("language-")

        return MessageSegment.CodeBlock(code = code, language = language)
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Plain text fallback (formattedBody == null)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Scans plain text for markdown tables and returns a mixed list of
     * [MessageSegment.Text] and [MessageSegment.Table] segments.
     * If no table is found, returns a single Text segment.
     */
    private fun parseMarkdownTables(text: String): List<MessageSegment> {
        if (!text.contains('|')) return listOf(MessageSegment.Text(html = null, plainText = text))

        val lines = text.lines()
        val result = mutableListOf<MessageSegment>()
        val textBuffer = StringBuilder()
        var i = 0

        while (i < lines.size) {
            val table = tryParseTable(lines, i)
            if (table != null) {
                if (textBuffer.isNotBlank()) {
                    result.add(MessageSegment.Text(html = null, plainText = textBuffer.toString().trim()))
                    textBuffer.clear()
                }
                result.add(table.first)
                i = table.second
            } else {
                if (textBuffer.isNotEmpty()) textBuffer.append('\n')
                textBuffer.append(lines[i])
                i++
            }
        }

        if (textBuffer.isNotBlank()) {
            result.add(MessageSegment.Text(html = null, plainText = textBuffer.toString().trim()))
        }

        return result
    }

    private fun tryParseTable(lines: List<String>, startIndex: Int): Pair<MessageSegment.Table, Int>? {
        val headerLine = lines[startIndex].trim()
        if (!headerLine.contains('|')) return null

        val headers = splitTableRow(headerLine)
        if (headers.isEmpty() || headers.all { it.isBlank() }) return null

        if (startIndex + 1 >= lines.size) return null
        val separatorLine = lines[startIndex + 1].trim()
        if (!isTableSeparator(separatorLine)) return null

        val alignments = parseAlignments(separatorLine)
        if (alignments.size != headers.size) return null

        val rows = mutableListOf<List<String>>()
        var i = startIndex + 2
        while (i < lines.size) {
            val line = lines[i].trim()
            if (!line.contains('|')) break
            val row = splitTableRow(line)
            if (row.isEmpty()) break
            rows.add(row)
            i++
        }

        if (rows.isEmpty()) return null

        return Pair(
            MessageSegment.Table(
                headers = headers,
                rows = rows,
                alignments = alignments,
            ),
            i
        )
    }

    private fun splitTableRow(line: String): List<String> {
        var s = line.trim()
        if (s.startsWith('|')) s = s.substring(1)
        if (s.endsWith('|')) s = s.dropLast(1)
        return s.split('|').map { it.trim() }
    }

    private fun isTableSeparator(line: String): Boolean {
        if (!line.contains('|')) return false
        val cells = splitTableRow(line)
        if (cells.isEmpty()) return false
        return cells.all { it.isNotEmpty() && it.all { c -> c == '-' || c == ':' || c == ' ' } }
    }

    private fun parseAlignments(separatorLine: String): List<TableAlignment> {
        return splitTableRow(separatorLine).map { cell ->
            val trimmed = cell.trim()
            val leftColon = trimmed.startsWith(':')
            val rightColon = trimmed.endsWith(':')
            when {
                leftColon && rightColon -> TableAlignment.CENTER
                rightColon -> TableAlignment.RIGHT
                else -> TableAlignment.LEFT
            }
        }
    }
}
