package com.hermes.android.ui.components

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.element.android.wysiwyg.EditorStyledTextView
import io.element.android.wysiwyg.compose.CodeBackgroundStyle
import io.element.android.wysiwyg.compose.RichTextEditorStyle
import io.element.android.wysiwyg.display.MentionDisplayHandler
import io.element.android.wysiwyg.display.TextDisplay
import io.element.android.wysiwyg.link.Link
import io.element.android.wysiwyg.view.BulletListStyleConfig
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
) {
    val (provider, style) = rememberHtmlConverterProvider()

    val text = remember(html, plainText, provider, style) {
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
                applyTextStyle(style)

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
            tv.onLinkClickedListener = onLinkClick?.let { cb ->
                { link: Link -> cb(link.url); Unit }
            }
        },
    )
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
 * - font size
 * - text colour
 * - line height
 * - include font padding
 */
private fun EditorStyledTextView.applyTextStyle(style: RichTextEditorStyle) {
    val ts = style.text
    // includeFontPadding
    ts.runCatching { javaClass.getMethod("getIncludeFontPadding").invoke(this) as Boolean }
        .onSuccess { includeFontPadding = it }
    // text colour (Compose Color long → argb int)
    runCatching {
        val colorLong = ts.callLong("getColor-0d7_KjU")
        // Compose Color(long).toArgb() — use reflection on Color companion
        val colorObj = androidx.compose.ui.graphics.Color(colorLong)
        val toArgbMethod = androidx.compose.ui.graphics.Color::class.java
            .getMethod("component1") // Color.toArgb() is internal; use known conversion
        // Actually Color(long) stores the argb value directly as the long's bits.
        // The simplest reliable approach: the Color constructor accepts a long
        // whose value IS the packed ARGB. We can extract it directly.
        setTextColor(colorLong.toInt())
    }
    // font size (TextUnit → sp float)
    runCatching {
        val fontSizeLong = ts.callLong("getFontSize-XSAIIZE")
        // TextUnit.getValue() — extract the raw float via reflection
        val tuClass = Class.forName("androidx.compose.ui.unit.TextUnit")
        val companion = tuClass.getDeclaredField("Companion").get(null)
        // The getValue method is internal — but TextUnit is an inline ULong class.
        // The long encodes (type << 32 | value). Lower 32 bits = the float bits.
        val floatBits = fontSizeLong.toInt()
        val fontSizeSp = Float.fromBits(floatBits)
        if (fontSizeSp > 0f) {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, fontSizeSp)
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
                val lineHeightPx = (lhSp * density).toInt()
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    setLineHeight(lineHeightPx)
                }
            }
        }
    }
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
