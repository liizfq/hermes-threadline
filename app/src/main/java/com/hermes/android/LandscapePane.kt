package com.hermes.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermes.android.ui.settings.strEnZh

/**
 * Adaptive two-pane layout for landscape / large screens.
 *
 * Left pane (session list): proportional width clamped to 280–480dp.
 * Right pane (chat): fills remaining space.
 *
 * - Phone landscape (~700dp+): left pane ≈ 280–320dp
 * - Small tablet (~1000dp): left pane ≈ 380dp
 * - Large tablet (~1280dp+): left pane ≈ 480dp
 *
 * @param listContent  the session list composable
 * @param chatContent  the chat area composable (null = no session selected)
 */
@Composable
fun TwoPaneLayout(
    listContent: @Composable () -> Unit,
    chatContent: (@Composable () -> Unit)?
) {
    Row(modifier = Modifier.fillMaxSize()) {
        // Left pane: ~38% of screen width, clamped to [280, 480] dp
        // (was 30% / 240–400 — session list felt too narrow in phone landscape)
        Surface(
            modifier = Modifier
                .fillMaxHeight()
                .weight(0.38f)
                .widthIn(min = 280.dp, max = 480.dp),
            tonalElevation = 2.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            listContent()
        }

        // Vertical divider
        Surface(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        ) {}

        // Right pane: remaining space
        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            color = MaterialTheme.colorScheme.background
        ) {
            if (chatContent != null) {
                chatContent()
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        strEnZh("Select a session", "选择一个会话"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
