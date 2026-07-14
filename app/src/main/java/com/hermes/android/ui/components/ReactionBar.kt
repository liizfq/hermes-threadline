package com.hermes.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.android.domain.model.Reaction

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReactionBar(
    reactions: List<Reaction>,
    onReactionClick: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (reactions.isEmpty()) return

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        reactions.forEach { reaction ->
            ReactionChip(
                emoji = reaction.key,
                count = reaction.count,
                isOwn = reaction.isOwn,
                onClick = onReactionClick?.let { callback -> { callback(reaction.key) } }
            )
        }
    }
}

@Composable
private fun ReactionChip(
    emoji: String,
    count: Int,
    isOwn: Boolean,
    onClick: (() -> Unit)? = null
) {
    val chipShape = RoundedCornerShape(12.dp)
    val chipColor = if (isOwn) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (isOwn) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val borderModifier = if (isOwn) {
        Modifier.border(1.dp, MaterialTheme.colorScheme.primary, chipShape)
    } else {
        Modifier
    }

    Row(
        modifier = Modifier
            .clip(chipShape)
            .then(borderModifier)
            .background(chipColor)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = emoji,
            fontSize = 16.sp
        )
        if (count > 1) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = textColor
            )
        }
    }
}
