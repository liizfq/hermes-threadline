package com.hermes.android.ui.components

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import io.element.android.wysiwyg.EditorStyledTextView
import io.element.android.wysiwyg.compose.CodeBackgroundStyle
import io.element.android.wysiwyg.compose.RichTextEditorStyle
import io.element.android.wysiwyg.display.MentionDisplayHandler
import io.element.android.wysiwyg.display.TextDisplay
import io.element.android.wysiwyg.link.Link
import io.element.android.wysiwyg.view.BulletListStyleConfig
import io.element.android.wysiwyg.view.spans.OrderedListSpan
import io.element.android.wysiwyg.view.spans.UnorderedListSpan
import io.element.android.wysiwyg.view.CodeBlockStyleConfig
import io.element.android.wysiwyg.view.InlineCodeStyleConfig
import io.element.android.wysiwyg.view.PillStyleConfig
import io.element.android.wysiwyg.view.StyleConfig

/**
 * Renders HTML via [EditorStyledTextView] with **native text selection** enabled.
 *
 * ## How selection works
 *
 * The wysiwyg library's [EditorStyledTextView] installs a [GestureDetector]
 * whose `onDown` always returns `true`. The view's `onTouchEvent` checks the
 * detector's return value and — when `true` — skips `super.onTouchEvent()`.
 * That means [androidx.appcompat.widget.AppCompatTextView] never sees the
 * touch stream, so Android's built-in selection-handle machinery never starts.
 *
 * We fix this by replacing the internal `gestureDetector` field with a no-op
 * detector (via reflection, guarded by [runCatching]). Once the detector stops
 * stealing events, `AppCompatTextView.onTouchEvent` runs normally and the user
 * can long-press → drag selection handles → copy partial text.
 *
 * ## Initialisation order (prevents the crash)
 *
 * The constructor sets `isInit = true`, but `inlineCodeBgHelper` /
 * `codeBlockBgHelper` are only assigned inside [EditorStyledTextView.updateStyle].
 * Calling `setText()` before `updateStyle()` triggers
 * `UninitializedPropertyAccessException`. So the factory **must** call
 * `updateStyle()` before any `setText()`.
 */
@Composable
fun HtmlText(
    html: String?,
    plainText: String,
    modifier: Modifier = Modifier,
    onLinkClick: ((String) -> Unit)? = null,
    fontSizeMultiplier: Float = 1f,
    forceBold: Boolean = false,
    isListBlock: Boolean = false,
) {
    val (provider, style) = rememberHtmlConverterProvider()

    val rawText = remember(html, plainText, provider, style) {
        if (html != null) {
            try {
                provider.getConverter(style).fromHtmlToSpans(html)
            } catch (e: Exception) {
                plainText
            }
        } else {
            plainText
        }
    }

    // For list blocks, trim only the leading/trailing newlines introduced by
    // the converter's block-element handling. Additionally, compress the
    // pure-`\n` gap between adjacent OrderedListSpan / UnorderedListSpan
    // siblings (the converter emits `\n\n` between non-first `<li>`s) so
    // list items render on consecutive lines instead of with blank gaps.
    // `<br><br>` inside a paragraph run, nested-list spans, and non-list
    // segments are all left untouched.
    val text = remember(rawText, isListBlock) {
        if (isListBlock) trimListSpacing(rawText) else rawText
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            EditorStyledTextView(context).apply {
                logDiag("factory create: view=${System.identityHashCode(this)}")
                // ── 1. Initialise style helpers BEFORE setText ──
                val sc = buildStyleConfig(context, style)
                val mdh = object : MentionDisplayHandler {
                    override fun resolveMentionDisplay(text: String, url: String): TextDisplay = TextDisplay.Plain
                    override fun resolveAtRoomMentionDisplay(): TextDisplay = TextDisplay.Plain
                }
                updateStyle(sc, mdh)
                isNativeCodeEnabled = false

                // ── 1b. Apply text style (font size, colour, line height) ──
                // EditorStyledText composable does this via applyStyleInCompose(),
                // which is internal. We replicate the essential calls here.
                applyTextStyle(style, fontSizeMultiplier, forceBold)

                // ── 2. Replace the gesture-stealing GestureDetector ──
                runCatching {
                    val noop = GestureDetector(
                        context,
                        object : GestureDetector.SimpleOnGestureListener() {},
                    )
                    val fld = EditorStyledTextView::class.java
                        .getDeclaredField("gestureDetector")
                    fld.isAccessible = true
                    val originalDetector = fld.get(this)
                    fld.set(this, noop)
                    val newDetector = fld.get(this)
                    val replaced = newDetector !== originalDetector
                    logDiag("gestureDetector reflect: success, original=${
                        System.identityHashCode(originalDetector)}, new=${
                        System.identityHashCode(newDetector)}, replaced=$replaced")
                }.onFailure { e ->
                    logDiag("gestureDetector reflect: FAIL ${e.javaClass.simpleName}: ${e.message}")
                }

                // ── 3. Enable native selection handles ──
                setTextIsSelectable(true)

                // ── 4. Link click callback ──
                onLinkClick?.let { cb ->
                    onLinkClickedListener = { link: Link -> cb(link.url); Unit }
                }

                // ── 5. Touch dispatch ──
                // Only block the parent (LazyColumn) from intercepting when
                // the user is actively dragging selection handles. On a plain
                // touch or scroll we return false and let the parent handle
                // it normally, so list scrolling stays smooth.
                setOnTouchListener { v, event ->
                    val isSelecting = v.isSelected && selectionStart != selectionEnd
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            // If there's an active selection, hold onto events
                            // so the parent doesn't steal the drag.
                            if (isSelecting) {
                                parent?.requestDisallowInterceptTouchEvent(true)
                            }
                            logDiag("touch: view=${System.identityHashCode(v)}, ACTION_DOWN, " +
                                "x=${event.x}, y=${event.y}, w=${v.width}, h=${v.height}, " +
                                "mH=${v.measuredHeight}, layoutH=${layout?.height ?: -1}, " +
                                "lineCount=${layout?.lineCount ?: -1}, " +
                                "isSelected=${v.isSelected}, selStart=$selectionStart, selEnd=$selectionEnd")
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (isSelecting) {
                                parent?.requestDisallowInterceptTouchEvent(true)
                            }
                            if (selectionStart != selectionEnd) {
                                logDiag("touch: view=${System.identityHashCode(v)}, ACTION_MOVE, " +
                                    "x=${event.x}, y=${event.y}, w=${v.width}, h=${v.height}, " +
                                    "mH=${v.measuredHeight}, layoutH=${layout?.height ?: -1}, " +
                                    "lineCount=${layout?.lineCount ?: -1}, " +
                                    "isSelected=${v.isSelected}, selStart=$selectionStart, selEnd=$selectionEnd")
                            }
                        }
                        MotionEvent.ACTION_UP,
                        MotionEvent.ACTION_CANCEL -> {
                            parent?.requestDisallowInterceptTouchEvent(false)
                            logDiag("touch: view=${System.identityHashCode(v)}, ACTION_${if (event.actionMasked == MotionEvent.ACTION_UP) "UP" else "CANCEL"}, " +
                                "x=${event.x}, y=${event.y}, w=${v.width}, h=${v.height}, " +
                                "mH=${v.measuredHeight}, layoutH=${layout?.height ?: -1}, " +
                                "lineCount=${layout?.lineCount ?: -1}, " +
                                "isSelected=${v.isSelected}, selStart=$selectionStart, selEnd=$selectionEnd")
                        }
                    }
                    // Always return false: let the TextView's onTouchEvent
                    // (and thus selection) run its own course.
                    false
                }

                // ── 6. Set initial text ──
                setText(text, TextView.BufferType.SPANNABLE)

                // ── 7. Clear selection when focus is lost ──
                // This handles "click outside the bubble" — if the touch lands
                // on anything else (even another bubble), this view loses focus
                // and its selection is cleared.
                setOnFocusChangeListener { _, hasFocus ->
                    logDiag("focusChange: hasFocus=$hasFocus, selectionStart=$selectionStart, selectionEnd=$selectionEnd")
                    if (!hasFocus && selectionStart != -1) {
                        clearFocus()
                    }
                }

                // ── 8. Layout change listener (diagnostic) ──
                addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                    if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                        val layout = this.layout
                        logDiag("layoutChange: view=${System.identityHashCode(this)}, " +
                            "w=${width}, h=${height}, mW=${measuredWidth}, mH=${measuredHeight}, " +
                            "layoutW=${layout?.width ?: -1}, layoutH=${layout?.height ?: -1}, " +
                            "lineCount=${layout?.lineCount ?: -1}, " +
                            "padTop=$paddingTop, padBottom=$paddingBottom")
                    }
                }
            }
        },
        update = { tv ->
            if (tv.text?.toString() != text?.toString()) {
                val oldW = tv.width
                val oldH = tv.height
                val oldMw = tv.measuredWidth
                val oldMh = tv.measuredHeight
                val oldLayout = tv.layout
                tv.setText(text, TextView.BufferType.SPANNABLE)
                val newLayout = tv.layout
                logDiag("update setText: view=${System.identityHashCode(tv)}, " +
                    "before(w=$oldW, h=$oldH, mW=$oldMw, mH=$oldMh, " +
                    "layoutW=${oldLayout?.width ?: -1}, layoutH=${oldLayout?.height ?: -1}, " +
                    "lineCount=${oldLayout?.lineCount ?: -1}), " +
                    "after(w=${tv.width}, h=${tv.height}, mW=${tv.measuredWidth}, mH=${tv.measuredHeight}, " +
                    "layoutW=${newLayout?.width ?: -1}, layoutH=${newLayout?.height ?: -1}, " +
                    "lineCount=${newLayout?.lineCount ?: -1})")
            }
            // Re-apply text style on every update so slot reuse with new
            // fontSizeMultiplier / forceBold values keeps the view in sync.
            // Cheap and idempotent.
            tv.applyTextStyle(style, fontSizeMultiplier, forceBold)

            tv.onLinkClickedListener = onLinkClick?.let { cb ->
                { link: Link -> cb(link.url); Unit }
            }
        },
    )
}

/**
 * Post-processes a [CharSequence] produced for a list block (`<ul>`/`<ol>`):
 *
 * 1. Trim only the leading/trailing `\n` characters introduced by the
 *    converter's block-element handling. Internal spans (bullets, numbers,
 *    indents) are preserved by slicing via [SpannableStringBuilder], which
 *    keeps spans that fall within the retained range.
 *
 * 2. Compress the pure-`\n` gap between adjacent sibling list spans.
 *    StyledHtmlConverter (isEditor=false) emits `\n\n` before every non-first
 *    `<li>` *outside* the li span's range, so consecutive
 *    [OrderedListSpan]/[UnorderedListSpan] siblings end up with a two-char
 *    `\n` gap that renders as a blank line. We collapse such gaps to a single
 *    `\n`. Gaps of any other shape (non-`\n` content, length ≤ 1) and gaps
 *    between overlapping nested spans are left alone.
 *
 * If [text] is not a [Spanned] (e.g. conversion threw and we fell back to
 * plain text), only step 1 is applied — no global `\n\n` replacement.
 */
private fun trimListSpacing(text: CharSequence): CharSequence {
    val trimmed = trimNewlineBoundaries(text)
    if (trimmed !is Spanned) return trimmed
    val ssb = if (trimmed is SpannableStringBuilder) {
        trimmed
    } else {
        SpannableStringBuilder(trimmed)
    }

    // Collect both list-span types, then sort by start (then end) so we can
    // walk sibling boundaries in document order. Triple = (span, start, end).
    val spans = (
        ssb.getSpans(0, ssb.length, OrderedListSpan::class.java).toList() +
            ssb.getSpans(0, ssb.length, UnorderedListSpan::class.java).toList()
        )
        .map { Triple(it, ssb.getSpanStart(it), ssb.getSpanEnd(it)) }
        .filter { (_, start, end) -> start >= 0 && end >= 0 }
        .sortedWith(compareBy({ it.second }, { it.third }))

    // Walk spans and record deletions for pure-`\n` gaps between adjacent
    // siblings. `prevEnd` tracks the furthest end seen so far so that a
    // nested span contained inside an outer span is not treated as a sibling
    // (its start ≤ prevEnd, so the gap check is skipped).
    data class Del(val start: Int, val count: Int)
    val dels = mutableListOf<Del>()
    var prevEnd = Int.MIN_VALUE
    for ((_, start, end) in spans) {
        if (prevEnd >= 0 && start > prevEnd) {
            val gapLen = start - prevEnd
            if (gapLen > 1) {
                var allNewline = true
                for (i in prevEnd until start) {
                    if (ssb[i] != '\n') {
                        allNewline = false
                        break
                    }
                }
                if (allNewline) {
                    // Keep one `\n` at prevEnd, delete the remaining
                    // (gapLen - 1) chars that follow it.
                    dels += Del(prevEnd + 1, gapLen - 1)
                }
            }
        }
        if (end > prevEnd) prevEnd = end
    }

    // Apply deletions back-to-front so earlier indices stay valid.
    // SpannableStringBuilder.delete shifts/clamps span ranges automatically.
    for (d in dels.sortedByDescending { it.start }) {
        ssb.delete(d.start, d.start + d.count)
    }
    return ssb
}

/**
 * Trims only the leading and trailing `\n` characters from a list-block
 * [CharSequence]. Internal newlines and spans are preserved by slicing via
 * [SpannableStringBuilder], which keeps spans that fall within the retained
 * range.
 */
private fun trimNewlineBoundaries(text: CharSequence): CharSequence {
    val len = text.length
    if (len == 0) return text
    var start = 0
    while (start < len && text[start] == '\n') start++
    if (start == len) return ""
    var end = len
    while (end > start && text[end - 1] == '\n') end--
    if (start == 0 && end == len) return text
    return when (text) {
        is Spanned -> SpannableStringBuilder(text, start, end)
        else -> text.subSequence(start, end)
    }
}

// ══════════════════════════════════════════════════════════════════════════
//  StyleConfig builder
// ══════════════════════════════════════════════════════════════════════════

private fun buildStyleConfig(
    @Suppress("UNUSED_PARAMETER") context: Context,
    style: RichTextEditorStyle,
): StyleConfig {
    val icBg = style.inlineCode.background
    val inlineCode = InlineCodeStyleConfig(
        /* horizontalPadding */ dpToPx(style.inlineCode.horizontalPadding()),
        /* verticalPadding   */ dpToPx(style.inlineCode.verticalPadding()),
        /* relativeTextSize  */ style.inlineCode.relativeTextSize,
        /* singleLineBg      */ icBg.singleLine.toDrawable(),
        /* multiLineBgLeft   */ icBg.multiLineLeft.toDrawable(),
        /* multiLineBgMid    */ icBg.multiLineMiddle.toDrawable(),
        /* multiLineBgRight  */ icBg.multiLineRight.toDrawable(),
    )

    val codeBg = style.codeBlock.background
    val codeBlock = CodeBlockStyleConfig(
        /* leadingMargin      */ dpToPx(style.codeBlock.leadingMargin()),
        /* verticalPadding    */ dpToPx(style.codeBlock.verticalPadding()),
        /* relativeTextSize   */ style.codeBlock.relativeTextSize,
        /* backgroundDrawable */ codeBg.toDrawable(),
    )

    val bulletList = BulletListStyleConfig(
        /* bulletGapWidth */ style.bulletList.bulletGapWidth(),
        /* bulletRadius   */ style.bulletList.bulletRadius(),
    )

    val pill = PillStyleConfig(style.pill.backgroundColor())

    return StyleConfig(bulletList, inlineCode, codeBlock, pill)
}

// ── Apply Compose text style to the Android View ──────────────────────────

/**
 * Replicates the essential parts of the library's internal
 * `RichTextEditorStyleExtKt.applyStyleInCompose()`:
 * - font size (scaled by [fontSizeMultiplier] for headings)
 * - text colour
 * - line height
 * - include font padding
 * - bold typeface when [forceBold] is set (used by [MessageSegment.Heading])
 *
 * The multiplier applies on top of the global [com.hermes.android.ui.settings.LocalChatFontScale]
 * baked into [style]; passing `1f` leaves all existing behaviour unchanged.
 */
private fun EditorStyledTextView.applyTextStyle(
    style: RichTextEditorStyle,
    fontSizeMultiplier: Float = 1f,
    forceBold: Boolean = false,
) {
    val ts = style.text
    // includeFontPadding
    ts.runCatching { javaClass.getMethod("getIncludeFontPadding").invoke(this) as Boolean }
        .onSuccess { includeFontPadding = it }
    // text colour (Compose Color packed raw value → Android ARGB int).
    // Color(Long) resolves to the top-level ARGB factory and re-encodes the
    // already-packed raw value; going through ULong selects the value-class
    // constructor so toArgb() reads the packed sRGB value directly.
    runCatching {
        val colorLong = ts.callLong("getColor-0d7_KjU")
        setTextColor(Color(colorLong.toULong()).toArgb())
    }
    // font size (TextUnit → sp float) × multiplier
    runCatching {
        val fontSizeLong = ts.callLong("getFontSize-XSAIIZE")
        val floatBits = fontSizeLong.toInt()
        val fontSizeSp = Float.fromBits(floatBits)
        if (fontSizeSp > 0f) {
            val scaled = if (fontSizeMultiplier > 0f) fontSizeSp * fontSizeMultiplier else fontSizeSp
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, scaled)
        }
    }
    // line height (only if specified)
    runCatching {
        val lhLong = ts.callLong("getLineHeight-XSAIIZE")
        val typeBits = (lhLong ushr 32).toInt()
        val valBits = lhLong.toInt()
        if (typeBits != 0 && valBits != 0) {
            val lhSp = Float.fromBits(valBits)
            if (lhSp > 0f) {
                val density = context.resources.displayMetrics.density
                val scaledLh = if (fontSizeMultiplier > 0f) lhSp * fontSizeMultiplier else lhSp
                val lineHeightPx = (scaledLh * density).toInt()
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    setLineHeight(lineHeightPx)
                }
            }
        }
    }
    // Bold typeface for headings — applies to the whole run. Inline formatting
    // spans (e.g. <strong>, <em>) still take precedence on their own ranges.
    typeface = if (forceBold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
}

// ── CodeBackgroundStyle → Drawable ────────────────────────────────────────

private fun CodeBackgroundStyle.toDrawable(): Drawable =
    runCatching {
        val m = CodeBackgroundStyle::class.java
            .getDeclaredMethod("getDrawable\$library_compose_release")
        m.isAccessible = true
        m.invoke(this) as Drawable
    }.getOrElse { ColorDrawable(0x1A808080.toInt()) }

// ── Compose-style property accessors (mangled getter names) ────────────────

private fun io.element.android.wysiwyg.compose.InlineCodeStyle.horizontalPadding(): Float =
    callFloat("getHorizontalPadding-D9Ej5fM")
private fun io.element.android.wysiwyg.compose.InlineCodeStyle.verticalPadding(): Float =
    callFloat("getVerticalPadding-D9Ej5fM")
private fun io.element.android.wysiwyg.compose.CodeBlockStyle.leadingMargin(): Float =
    callFloat("getLeadingMargin-D9Ej5fM")
private fun io.element.android.wysiwyg.compose.CodeBlockStyle.verticalPadding(): Float =
    callFloat("getVerticalPadding-D9Ej5fM")
private fun io.element.android.wysiwyg.compose.BulletListStyle.bulletGapWidth(): Float =
    callFloat("getBulletGapWidth-D9Ej5fM")
private fun io.element.android.wysiwyg.compose.BulletListStyle.bulletRadius(): Float =
    callFloat("getBulletRadius-D9Ej5fM")
private fun io.element.android.wysiwyg.compose.PillStyle.backgroundColor(): Int =
    callLong("getBackgroundColor-0d7_KjU").toInt()

private fun Any.callFloat(method: String): Float = runCatching {
    val m = this::class.java.getMethod(method)
    (m.invoke(this) as Number).toFloat()
}.getOrDefault(0f)

private fun Any.callLong(method: String): Long = runCatching {
    val m = this::class.java.getMethod(method)
    (m.invoke(this) as Number).toLong()
}.getOrDefault(0L)

private fun dpToPx(dp: Float): Int =
    (dp * android.content.res.Resources.getSystem().displayMetrics.density).toInt()

private const val TAG = "HermesTextSelection"
private fun logDiag(msg: String) {
    Log.d(TAG, msg)
}
