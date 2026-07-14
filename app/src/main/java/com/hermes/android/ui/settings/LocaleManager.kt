package com.hermes.android.ui.settings

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/**
 * Runtime language manager.
 *
 * Exposes the current locale via a [MutableState] so Compose recomposes
 * automatically when language changes. Persists the choice to
 * SharedPreferences and restores it on the next process start.
 *
 * Fresh install always defaults to English (LocaleManager.DEFAULT_LOCALE),
 * regardless of device locale. Only an explicit user choice in Settings
 * changes the persisted locale; the device language is never auto-detected.
 *
 * Usage:
 *   LocaleManager.init(context)                    // call in Application.onCreate()
 *   val locale = LocaleManager.current.value      // read anywhere
 *   LocaleManager.setLocale(context, "zh")        // switch language at runtime
 *
 * The locale state lives in the application singleton, so it survives
 * configuration changes (rotation, etc.) without extra plumbing.
 */
object LocaleManager {
    const val EN = "en"
    const val ZH = "zh"
    const val DEFAULT_LOCALE = EN
    val SUPPORTED = listOf(EN, ZH)

    private const val PREFS_NAME = "hermes_settings"
    private const val KEY_LOCALE = "app_locale"

    /** Current locale, observable by Composition. */
    val current: MutableState<String> = mutableStateOf(DEFAULT_LOCALE)

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // If no locale has been explicitly set by the user, default to English.
        val stored = prefs.getString(KEY_LOCALE, null)
        val locale = stored ?: DEFAULT_LOCALE
        current.value = locale
        if (stored == null) {
            // Persist the default so subsequent reads are deterministic.
            prefs.edit().putString(KEY_LOCALE, locale).apply()
        }
        initialized = true
    }

    fun setLocale(context: Context, locale: String) {
        if (locale !in SUPPORTED) return
        current.value = locale
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LOCALE, locale)
            .apply()
    }

    /** Non-composable lookup for the active locale (e.g. from a non-Composable context). */
    fun currentLocale(): String = current.value

    fun isInitialized(): Boolean = initialized
}

/**
 * Resolve a user-visible string for the current locale.
 *
 * Use this inside @Composable functions to pick the right language
 * automatically. Reading [LocaleManager.current] makes the composable
 * observe locale changes and recompose when the user switches language.
 */
@androidx.compose.runtime.Composable
fun strEnZh(english: String, chinese: String): String {
    return when (LocaleManager.current.value) {
        LocaleManager.ZH -> chinese
        else -> english
    }
}

/**
 * Non-composable variant of [strEnZh] for use outside of @Composable
 * contexts (e.g., ViewModels, services, pure functions).
 */
fun strEnZh(locale: String, english: String, chinese: String): String {
    return when (locale) {
        LocaleManager.ZH -> chinese
        else -> english
    }
}
