package com.hermes.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AgentLightColorScheme = lightColorScheme(
    background = AgentColors.Background,
    surface = AgentColors.Card,
    onBackground = AgentColors.TextPrimary,
    onSurface = AgentColors.TextPrimary,
    primary = AgentColors.AccentBlue,
    onPrimary = Color.White,
    secondary = AgentColors.TextSecondary,
    surfaceVariant = AgentColors.UserBubble
)

@Composable
fun HermesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = AgentLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
