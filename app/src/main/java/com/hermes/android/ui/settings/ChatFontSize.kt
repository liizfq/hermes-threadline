package com.hermes.android.ui.settings

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Global font scale for message content.
 *
 * Usage in settings:
 *   ChatFontSize.scale = 1.2f  // 20% larger
 *
 * Consumers read via [LocalChatFontScale]:
 *   val scale = LocalChatFontScale.current
 *
 * Persists to SharedPreferences automatically.
 */
object ChatFontSize {
    private const val PREFS_NAME = "chat_settings"
    private const val KEY_FONT_SCALE = "message_font_scale"

    const val MIN_SCALE = 0.75f
    const val MAX_SCALE = 1.75f
    const val DEFAULT_SCALE = 1.0f

    /**
     * Read the saved font scale, clamped to [MIN_SCALE]..[MAX_SCALE].
     * Caches in memory after first read.
     */
    @Volatile
    private var cached: Float? = null

    fun get(context: Context): Float {
        cached?.let { return it }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getFloat(KEY_FONT_SCALE, DEFAULT_SCALE)
        val clamped = raw.coerceIn(MIN_SCALE, MAX_SCALE)
        cached = clamped
        return clamped
    }

    fun set(context: Context, scale: Float) {
        val clamped = scale.coerceIn(MIN_SCALE, MAX_SCALE)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_FONT_SCALE, clamped)
            .apply()
        cached = clamped
    }
}

/**
 * Global mutable state for chat font scale.
 *
 * Provide via [LocalChatFontScale]:
 *   CompositionLocalProvider(LocalChatFontScale provides chatFontScale.value) { ... }
 *
 * Update from anywhere:
 *   ChatFontScaleState.value = 1.2f  // also persist
 *   ChatFontSize.set(context, 1.2f)  // persist
 *
 * On app start, initialise: ChatFontScaleState.value = ChatFontSize.get(context)
 */
object ChatFontScaleState {
    val state = mutableFloatStateOf(1.0f)
}

/**
 * CompositionLocal that exposes the current chat font scale.
 *
 * Provided at the app root, reads from [ChatFontScaleState].
 */
val LocalChatFontScale = compositionLocalOf { 1.0f }
