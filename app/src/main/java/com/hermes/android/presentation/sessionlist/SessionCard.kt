package com.hermes.android.presentation.sessionlist

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.android.domain.model.Session
import com.hermes.android.ui.settings.strEnZh
import com.hermes.android.ui.theme.AgentColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

fun sessionIconColor(title: String): Color {
    val index = kotlin.math.abs(title.hashCode()) % AgentColors.IconPalette.size
    return AgentColors.IconPalette[index]
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionCard(
    session: Session,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    unreadCount: Int = 0,
    customTitle: String? = null
) {
    val density = LocalDensity.current
    val revealWidthPx = with(density) { 80.dp.toPx() }
    val offsetX = remember(session.id) { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
    ) {
        // Background: delete action revealed on left swipe
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(MaterialTheme.colorScheme.error),
            contentAlignment = Alignment.CenterEnd
        ) {
            IconButton(onClick = {
                scope.launch { offsetX.animateTo(0f, tween(150)) }
                onDelete()
            }) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = strEnZh("Delete session", "删除会话"),
                    tint = MaterialTheme.colorScheme.onError
                )
            }
        }

        // Foreground: card content
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .pointerInput(session.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (offsetX.value < -revealWidthPx / 2) {
                                    offsetX.animateTo(-revealWidthPx, tween(150))
                                } else {
                                    offsetX.animateTo(0f, tween(150))
                                }
                            }
                        }
                    ) { _, dragAmount ->
                        scope.launch {
                            offsetX.snapTo(
                                (offsetX.value + dragAmount).coerceIn(-revealWidthPx, 0f)
                            )
                        }
                    }
                }
                .clickable(onClick = onClick),
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            colors = CardDefaults.cardColors(containerColor = AgentColors.Card)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val displayTitle = customTitle ?: session.title
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(sessionIconColor(displayTitle)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Email,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayTitle,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = AgentColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    session.lastMessage?.let {
                        Text(
                            text = it,
                            fontSize = 13.sp,
                            color = AgentColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = formatTimestamp(session.lastActivityTime),
                        fontSize = 12.sp,
                        color = AgentColors.TextSecondary
                    )
                    if (unreadCount > 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(AgentColors.AccentBlue, CircleShape)
                        )
                    }
                }
            }
        }
    }
}

private fun formatTimestamp(instant: java.time.Instant): String {
    val zone = java.time.ZoneId.systemDefault()
    val zdt = instant.atZone(zone)
    val now = java.time.ZonedDateTime.now(zone)
    return when {
        zdt.toLocalDate() == now.toLocalDate() -> "%02d:%02d".format(zdt.hour, zdt.minute)
        zdt.year == now.year -> "%02d/%02d".format(zdt.monthValue, zdt.dayOfMonth)
        else -> "%d/%02d/%02d".format(zdt.year, zdt.monthValue, zdt.dayOfMonth)
    }
}
