package com.hermes.android.presentation.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.android.ui.theme.AgentColors

@Composable
fun SlashCommandDropdown(
    query: String,
    onCommandSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val filtered = SlashCommandRegistry.filterByPrefix(query)
    if (filtered.isEmpty()) return

    val grouped = filtered.groupBy { it.category }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp),
        color = AgentColors.Card,
        shadowElevation = 8.dp,
        shape = MaterialTheme.shapes.medium
    ) {
        LazyColumn(
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            grouped.forEach { (category, commands) ->
                item(key = "header_$category") {
                    Text(
                        text = category,
                        style = MaterialTheme.typography.labelSmall,
                        color = AgentColors.TextSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
                items(commands, key = { it.name }) { cmd ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onCommandSelected("/${cmd.name} ") }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "/${cmd.name}",
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp,
                            color = AgentColors.TextPrimary,
                            maxLines = 1
                        )
                        if (cmd.argsHint.isNotEmpty()) {
                            Text(
                                text = " ${cmd.argsHint}",
                                fontSize = 12.sp,
                                color = AgentColors.TextSecondary,
                                maxLines = 1
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = cmd.description,
                            fontSize = 12.sp,
                            color = AgentColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 160.dp)
                        )
                    }
                }
            }
        }
    }
}
