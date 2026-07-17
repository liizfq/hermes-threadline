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
     * Parses formatted HTML into segments by walking [body]'s childNodes with
     * a recursive walker.
     *
     * Each top-level (or container-descendant) block element is emitted as its
     * own segment; an inline buffer is flushed at every block boundary so
     * sibling paragraphs/lists/blockquotes no longer collapse into a single
     * [MessageSegment.Text]. Inter-block spacing is owned by the Compose
     * `spacedBy(4.dp)` in [RichMessageContent], not by `\n\n` inside one
     * HtmlText run.
     *
     * Headings (h1-h6, direct child of body): wysiwyg 2.42.0 silently drops
     * h1-h6 in its [HtmlToSpansParser] dispatch, so we extract them here as
     * explicit [MessageSegment.Heading] segments. Headings nested inside
     * other containers are renamed to `<p>` by [renameNestedHeadingsToParagraph]
     * so wysiwyg still shows their text.
     *
     * Lists (`<ul>`, `<ol>`): emitted as their own [MessageSegment.Text] with
     * `isListBlock=true` so [HtmlText]'s list-span gap fix can apply.
     */
    private fun parseHtmlSegments(html: String): List<MessageSegment> {
        val doc = Jsoup.parse(html)
        val body = doc.body()
        renameNestedHeadingsToParagraph(body)

        val result = mutableListOf<MessageSegment>()
        val htmlBuffer = StringBuilder()
        walkNodes(body.childNodes(), htmlBuffer, result)
        flushText(htmlBuffer, result)

        if (result.isNotEmpty()) return result
        // Safety fallback: input was non-empty HTML but yielded no segments
        // (e.g. body held only whitespace). Use the transformed body, never
        // the raw input html, and don't emit an empty Text for blank input.
        val plain = body.text()
        return if (plain.isBlank()) emptyList()
        else listOf(MessageSegment.Text(html = body.html(), plainText = plain))
    }

    /**
     * Recursive walker. Appends inline content (text nodes + inline elements)
     * to [buffer]; on every block boundary flushes [buffer] into [result] and
     * emits the block as its own segment. Wrapper semantics live in
     * [handleElement]:
     *  - Block containers (`div`, `section`, `article`, `main`, `header`,
     *    `footer`, `nav`, `aside`): flush → recurse → flush.
     *  - Inline whitelist (`a`, `b`, `strong`, …): append outerHtml verbatim.
     *  - Unknown wrappers (`span`, `font`, custom tags, …): recurse without
     *    flushing so they stay inline with surrounding text; inner blocks
     *    still flush themselves via their own handlers.
     *  - `mx-reply`: discard the subtree as a block boundary — flush, then
     *    neither recurse nor emit (see [handleElement] for rationale).
     */
    private fun walkNodes(
        nodes: List<Node>,
        buffer: StringBuilder,
        result: MutableList<MessageSegment>,
    ) {
        for (node in nodes) {
            when (node) {
                is TextNode -> buffer.append(node.text())
                is Element -> handleElement(node, buffer, result)
                else -> Unit // comments, doctypes, etc. — ignored
            }
        }
    }

    private fun handleElement(
        el: Element,
        buffer: StringBuilder,
        result: MutableList<MessageSegment>,
    ) {
        val tag = el.tagName()
        when (tag) {
            "table" -> {
                flushText(buffer, result)
                parseHtmlTable(el)?.let { result.add(it) }
            }
            "pre" -> {
                flushText(buffer, result)
                parseHtmlCodeBlock(el)?.let { result.add(it) }
            }
            "hr" -> {
                // Block boundary with no content of its own; flush and skip.
                flushText(buffer, result)
            }
            "ul", "ol" -> {
                flushText(buffer, result)
                val plain = el.text()
                if (plain.isNotBlank()) {
                    result.add(MessageSegment.Text(
                        html = el.outerHtml(),
                        plainText = plain,
                        isListBlock = true,
                    ))
                }
            }
            "blockquote", "p" -> {
                flushText(buffer, result)
                val plain = el.text()
                if (plain.isBlank()) return
                // Matrix senders usually wrap content in <p> and don't enable
                // GFM table extension, so markdown tables survive as raw
                // "| a | b |\n|---|---|\n| 1 | 2 |"-style text inside <p>.
                // Try markdown-table extraction on the br-preserved text;
                // if no table pattern is found, fall back to a Text segment.
                val multiline = elementToMultilineText(el)
                if (multiline.contains('|') && looksLikeMarkdownTable(multiline)) {
                    result.addAll(parseMarkdownTables(multiline))
                } else {
                    result.add(MessageSegment.Text(
                        html = el.outerHtml(),
                        plainText = plain,
                    ))
                }
            }
            // Inline tags that wysiwyg 2.42.0's HtmlToSpansParser handles
            // directly (verified by decompiling parseElement's lookupswitch:
            // a, b, strong, em, i, u, del, code, br all have explicit cases
            // that recurse into children). Preserve outerHtml verbatim so
            // formatting survives the buffer-flush boundary. NOTE: `span`,
            // `font`, `sub`, `sup`, `mark`, … are NOT in the switch — they
            // fall to wysiwyg's default branch which just logs and does NOT
            // recurse, silently dropping the whole subtree. Those are routed
            // through the unknown-wrapper path below (recurse without flush).
            "a", "b", "strong", "em", "i", "u", "del", "code", "br" -> {
                buffer.append(el.outerHtml())
            }
            // Known block containers — wrapper elements that establish a
            // block boundary. Flush surrounding inline first, walk children
            // so inner blocks/text become real segments, then flush trailing
            // inline so the next sibling doesn't merge with this container's
            // tail.
            "div", "section", "article", "main", "header", "footer", "nav", "aside" -> {
                flushText(buffer, result)
                walkNodes(el.childNodes(), buffer, result)
                flushText(buffer, result)
            }
            // Matrix reply fallback. The `<mx-reply>` subtree holds the
            // quoted ancestor message (typically `<blockquote>In reply to
            // @user: …</blockquote>`) and exists for clients that don't
            // understand replies. Both the WYSIWYG default and Matrix's
            // reply-handling drop it before display; recursing into it
            // would re-render the quoted body as if it were the sender's
            // own words. Treat it as a block boundary (flush surrounding
            // inline) and discard the entire subtree — no recurse, no emit.
            "mx-reply" -> {
                flushText(buffer, result)
            }
            else -> {
                // Direct-child heading: emit Heading segment.
                val level = headingLevel(el)
                if (level != null) {
                    flushText(buffer, result)
                    parseHeading(el)?.let { result.add(it) }
                } else {
                    // Unknown inline-ish wrapper (custom-widget tags, span,
                    // font, mark, sub, sup, …). wysiwyg 2.42.0's
                    // HtmlToSpansParser.parseElement default branch only
                    // logs and never recurses, so emitting outerHtml
                    // verbatim would drop the subtree at render time.
                    // Recurse into children WITHOUT flushing the buffer:
                    // any inner block (p, ul, table, …) flushes itself via
                    // its own handler, while inline-only wrappers stay in
                    // the current Text segment alongside surrounding text.
                    walkNodes(el.childNodes(), buffer, result)
                }
            }
        }
    }

    /** h1-h6 → 1..6, otherwise null. */
    private fun headingLevel(el: Element): Int? {
        val tag = el.tagName()
        if (tag.length == 2 && tag[0] == 'h' && tag[1] in '1'..'6') {
            return tag[1] - '0'
        }
        return null
    }

    /**
     * For every h1-h6 that is NOT a direct child of [body], rename the tag
     * to `p`. This keeps nested heading content visible because wysiwyg
     * recognises `<p>` but silently drops `<hN>`. Direct-child headings are
     * left intact so the walker can extract them as Heading segments.
     */
    private fun renameNestedHeadingsToParagraph(body: Element) {
        for (level in 1..6) {
            for (h in body.select("h$level")) {
                if (h.parent() !== body) {
                    h.tagName("p")
                }
            }
        }
    }

    private fun parseHeading(el: Element): MessageSegment.Heading? {
        val level = headingLevel(el) ?: return null
        val plain = el.text()
        if (plain.isBlank()) return null
        return MessageSegment.Heading(
            html = el.html(),
            plainText = plain,
            level = level,
        )
    }

    private fun flushText(buffer: StringBuilder, result: MutableList<MessageSegment>) {
        if (buffer.isBlank()) {
            buffer.clear()
            return
        }
        val fragment = buffer.toString().trim()
        buffer.clear()
        if (fragment.isBlank()) return
        result.add(MessageSegment.Text(
            html = fragment,
            plainText = Jsoup.parse(fragment).text(),
        ))
    }

    /**
     * Walks [el]'s children, converting `<br>` to `\n` and preserving the
     * relative order of text nodes. Unlike [Element.text] (which collapses
     * whitespace and joins with spaces), this keeps line breaks so markdown
     * table rows stay on separate lines.
     */
    private fun elementToMultilineText(el: Element): String {
        val sb = StringBuilder()
        fun walk(node: Node) {
            when (node) {
                is TextNode -> sb.append(node.text())
                is Element -> {
                    if (node.tagName() == "br") {
                        sb.append('\n')
                    } else {
                        node.childNodes().forEach(::walk)
                    }
                }
                else -> Unit
            }
        }
        walk(el)
        return sb.toString()
    }

    /**
     * Quick positive-check for a GFM-style table anywhere in [text]: finds
     * the first line containing `|`, then verifies the line immediately
     * after is a valid separator (`---`, `:--:`, …) with matching column
     * count. Leading prose lines are allowed.
     */
    private fun looksLikeMarkdownTable(text: String): Boolean {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val headerIdx = lines.indexOfFirst { it.contains('|') }
        if (headerIdx < 0 || headerIdx + 1 >= lines.size) return false
        val headers = splitTableRow(lines[headerIdx])
        if (headers.isEmpty() || headers.all { it.isBlank() }) return false
        if (!isTableSeparator(lines[headerIdx + 1])) return false
        val aligns = parseAlignments(lines[headerIdx + 1])
        return aligns.size == headers.size
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
