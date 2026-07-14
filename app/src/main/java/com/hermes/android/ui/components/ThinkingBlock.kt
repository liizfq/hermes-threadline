package com.hermes.android.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermes.android.ui.settings.strEnZh
import com.hermes.android.ui.theme.AgentColors

@Composable
fun ThinkingBlock(
    thinkingText: String,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable { expanded = !expanded },
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
        color = AgentColors.Background
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (expanded) strEnZh("💬 Thinking process", "💬 思考过程") else strEnZh("💬 Agent is thinking...", "💬 Agent 正在思考..."),
                    style = MaterialTheme.typography.labelMedium,
                    color = AgentColors.TextSecondary
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = if (expanded) "▲" else "▼",
                    style = MaterialTheme.typography.labelSmall
                )
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = thinkingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = AgentColors.TextSecondary
                )
            }
        }
    }
}
