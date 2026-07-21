package com.hermes.android.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.hermes.android.ui.theme.AgentColors
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Converts inline HTML to Compose [AnnotatedString] using Jsoup for parsing.
 *
 * Handles:
 * - [b], [strong] → bold
 * - [i], [em] → italic
 * - [u] → underline
 * - [del], [s] → strikethrough
 * - [code] → monospace
 * - [a href] → link colour + underline + URL annotation
 * - [br] → newline
 * - [span] → preserved text
 * - [ul]/[ol]/[li] → bulleted/numbered lists
 *
 * The HTML is parsed once with Jsoup, then traversed as a DOM tree to build
 * the [AnnotatedString]. This avoids the previous O(n²) behavior where each
 * recursive call re-parsed the HTML.
 */
fun htmlToAnnotatedString(
    html: String,
    baseColor: Color = AgentColors.TextPrimary,
    linkColor: Color = AgentColors.AccentBlue,
): AnnotatedString {
    val doc = Jsoup.parseBodyFragment(html.ifBlank { "" })
    return buildAnnotatedString {
        doc.body().childNodes().forEach { node ->
            renderNode(node, 0, baseColor, linkColor, null)
        }
    }
}

private fun AnnotatedString.Builder.renderNode(
    node: Node,
    indentLevel: Int,
    baseColor: Color,
    linkColor: Color,
    listNumber: Int?,
) {
    when (node) {
        is TextNode -> append(node.text())
        is Element -> renderElement(node, indentLevel, baseColor, linkColor, listNumber)
        else -> append(node.outerHtml())
    }
}

private fun AnnotatedString.Builder.renderElement(
    element: Element,
    indentLevel: Int,
    baseColor: Color,
    linkColor: Color,
    listNumber: Int?,
) {
    when (element.tagName().lowercase()) {
        "ul" -> {
            element.children().forEach { child ->
                renderElement(child, indentLevel + 1, baseColor, linkColor, null)
            }
        }
        "ol" -> {
            element.children().forEachIndexed { index, child ->
                renderElement(child, indentLevel + 1, baseColor, linkColor, index + 1)
            }
        }
        "li" -> {
            append(" ".repeat(indentLevel * 2))
            val marker = if (listNumber != null) "$listNumber. " else "• "
            append(marker)
            element.childNodes().forEach { node ->
                renderNode(node, indentLevel, baseColor, linkColor, listNumber)
            }
            append("\n")
        }
        "p", "div" -> {
            element.childNodes().forEach { node ->
                renderNode(node, indentLevel, baseColor, linkColor, null)
            }
            append("\n")
        }
        "br" -> append("\n")
        "b", "strong" -> {
            val start = length
            element.childNodes().forEach { node ->
                renderNode(node, indentLevel, baseColor, linkColor, null)
            }
            addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, length)
        }
        "i", "em" -> {
            val start = length
            element.childNodes().forEach { node ->
                renderNode(node, indentLevel, baseColor, linkColor, null)
            }
            addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, length)
        }
        "u" -> {
            val start = length
            element.childNodes().forEach { node ->
                renderNode(node, indentLevel, baseColor, linkColor, null)
            }
            addStyle(SpanStyle(textDecoration = TextDecoration.Underline), start, length)
        }
        "del", "s" -> {
            val start = length
            element.childNodes().forEach { node ->
                renderNode(node, indentLevel, baseColor, linkColor, null)
            }
            addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start, length)
        }
        "code" -> {
            val start = length
            element.childNodes().forEach { node ->
                renderNode(node, indentLevel, baseColor, linkColor, null)
            }
            addStyle(SpanStyle(fontFamily = FontFamily.Monospace), start, length)
        }
        "a" -> {
            val start = length
            element.childNodes().forEach { node ->
                renderNode(node, indentLevel, baseColor, linkColor, null)
            }
            addStyle(
                SpanStyle(
                    color = linkColor,
                    textDecoration = TextDecoration.Underline,
                ),
                start,
                length,
            )
            element.attr("href").takeIf { it.isNotEmpty() }?.let { href ->
                addStringAnnotation(
                    tag = URL_ANNOTATION_TAG,
                    annotation = href,
                    start = start,
                    end = length,
                )
            }
        }
        else -> {
            element.childNodes().forEach { node ->
                renderNode(node, indentLevel, baseColor, linkColor, null)
            }
        }
    }
}

private const val URL_ANNOTATION_TAG = "URL"
