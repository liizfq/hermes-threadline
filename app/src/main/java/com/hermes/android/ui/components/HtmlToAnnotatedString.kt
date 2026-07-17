package com.hermes.android.ui.components

import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.text.style.UnderlineSpan
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

/**
 * Converts inline HTML to Compose [AnnotatedString] using Android's
 * [android.text.Html.fromHtml()].
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
 *
 * List blocks ([ul]/[ol]/[li]) are handled specially by prefixing each item
 * with a bullet or number.
 */
fun htmlToAnnotatedString(
    html: String,
    baseColor: Color = AgentColors.TextPrimary,
    linkColor: Color = AgentColors.AccentBlue,
): AnnotatedString {
    if (html.contains("<li>", ignoreCase = true)) {
        return parseListHtml(html, baseColor, linkColor)
    }

    val spanned = android.text.Html.fromHtml(
        html.ifBlank { "" },
        android.text.Html.FROM_HTML_MODE_COMPACT,
    )

    return buildAnnotatedString {
        append(spanned.toString())
        spanned.getSpans(0, spanned.length, Any::class.java).forEach { span ->
            val start = spanned.getSpanStart(span)
            val end = spanned.getSpanEnd(span)
            if (start < 0 || end <= start || start >= length) return@forEach
            val clampedEnd = end.coerceAtMost(length)
            when (span) {
                is StyleSpan -> applyStyleSpan(span, start, clampedEnd)
                is UnderlineSpan -> addStyle(
                    SpanStyle(textDecoration = TextDecoration.Underline),
                    start,
                    clampedEnd,
                )
                is StrikethroughSpan -> addStyle(
                    SpanStyle(textDecoration = TextDecoration.LineThrough),
                    start,
                    clampedEnd,
                )
                is TypefaceSpan -> if (span.family == "monospace") {
                    addStyle(
                        SpanStyle(fontFamily = FontFamily.Monospace),
                        start,
                        clampedEnd,
                    )
                }
                is ForegroundColorSpan -> addStyle(
                    SpanStyle(color = Color(span.foregroundColor)),
                    start,
                    clampedEnd,
                )
                is URLSpan -> {
                    addStyle(
                        SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline,
                        ),
                        start,
                        clampedEnd,
                    )
                    addStringAnnotation(
                        tag = URL_ANNOTATION_TAG,
                        annotation = span.url,
                        start = start,
                        end = clampedEnd,
                    )
                }
            }
        }
    }
}

private fun AnnotatedString.Builder.applyStyleSpan(span: StyleSpan, start: Int, end: Int) {
    val style = when (span.style) {
        android.graphics.Typeface.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
        android.graphics.Typeface.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
        android.graphics.Typeface.BOLD_ITALIC -> SpanStyle(
            fontWeight = FontWeight.Bold,
            fontStyle = FontStyle.Italic,
        )
        else -> return
    }
    addStyle(style, start, end)
}

private fun parseListHtml(
    html: String,
    baseColor: Color,
    linkColor: Color,
): AnnotatedString = buildAnnotatedString {
    val doc = Jsoup.parseBodyFragment(html)
    doc.body().children().forEach { element ->
        renderListElement(element, 0, baseColor, linkColor, null)
    }
}

private fun AnnotatedString.Builder.renderListElement(
    element: Element,
    indentLevel: Int,
    baseColor: Color,
    linkColor: Color,
    listNumber: Int?,
) {
    when (element.tagName().lowercase()) {
        "ul" -> {
            element.children().forEach { child ->
                renderListElement(child, indentLevel + 1, baseColor, linkColor, null)
            }
        }
        "ol" -> {
            element.children().forEachIndexed { index, child ->
                renderListElement(child, indentLevel + 1, baseColor, linkColor, index + 1)
            }
        }
        "li" -> {
            append(" ".repeat(indentLevel * 2))
            val marker = if (listNumber != null) "$listNumber. " else "• "
            append(marker)
            val childHtml = element.html()
            append(htmlToAnnotatedString(childHtml, baseColor, linkColor))
            append("\n")
        }
        else -> {
            append(htmlToAnnotatedString(element.outerHtml(), baseColor, linkColor))
        }
    }
}

private const val URL_ANNOTATION_TAG = "URL"
