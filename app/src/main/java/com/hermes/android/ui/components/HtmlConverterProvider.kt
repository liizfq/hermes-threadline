package com.hermes.android.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.hermes.android.ui.settings.LocalChatFontScale
import io.element.android.wysiwyg.compose.RichTextEditorDefaults
import io.element.android.wysiwyg.compose.RichTextEditorStyle
import io.element.android.wysiwyg.compose.StyledHtmlConverter
import io.element.android.wysiwyg.display.MentionDisplayHandler
import io.element.android.wysiwyg.display.TextDisplay
import io.element.android.wysiwyg.utils.HtmlConverter

/**
 * Lazily builds and caches a [StyledHtmlConverter] (wysiwyg 2.42.0 API).
 *
 * The converter must be configured with a [RichTextEditorStyle] before use;
 * we feed it the library default style produced by [RichTextEditorDefaults.style].
 * Because that factory is a @Composable, the style is resolved once in
 * [rememberHtmlConverterProvider] and threaded in here.
 */
class HtmlConverterProvider(private val context: Context) {
    @Volatile
    private var converter: StyledHtmlConverter? = null

    fun getConverter(style: RichTextEditorStyle): HtmlConverter {
        val existing = converter
        if (existing != null) {
            return existing
        }
        synchronized(this) {
            converter?.let { return it }
            val created = StyledHtmlConverter(
                context = context.applicationContext,
                mentionDisplayHandler = object : MentionDisplayHandler {
                    override fun resolveAtRoomMentionDisplay(): TextDisplay = TextDisplay.Plain
                    override fun resolveMentionDisplay(text: String, url: String): TextDisplay = TextDisplay.Plain
                },
                isEditor = false,
                isMention = { _, _ -> false },
            ).apply {
                configureWith(style)
            }
            converter = created
            return created
        }
    }
}

@Composable
fun rememberHtmlConverterProvider(): Pair<HtmlConverterProvider, RichTextEditorStyle> {
    val context = LocalContext.current
    val fontScale = LocalChatFontScale.current
    val provider = remember { HtmlConverterProvider(context) }
    // RichTextEditorDefaults.style() is @Composable and reads the current
    // MaterialTheme typography/colors, so it must be invoked in composition.
    // We then override the font size with our global chat font scale.
    val defaultStyle = RichTextEditorDefaults.style()
    val style = remember(defaultStyle, fontScale) {
        defaultStyle.copy(
            text = defaultStyle.text.copy(
                fontSize = (defaultStyle.text.fontSize.value * fontScale).sp,
            ),
        )
    }
    // Invalidate converter cache when font scale changes
    return remember(provider, style) { provider to style }
}
