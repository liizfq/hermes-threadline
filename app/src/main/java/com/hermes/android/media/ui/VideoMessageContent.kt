package com.hermes.android.media.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.hermes.android.domain.model.MessageContent
import com.hermes.android.media.data.MxcRequestData
import com.hermes.android.ui.settings.strEnZh

@Composable
fun VideoMessageContent(
    content: MessageContent.Video,
    modifier: Modifier = Modifier
) {
    var showFullScreen by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth(0.7f)
            .heightIn(max = 250.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { showFullScreen = true }
    ) {
        if (content.thumbnailMxcUrl != null) {
            AsyncImage(
                model = MxcRequestData(content.thumbnailMxcUrl!!),
                contentDescription = strEnZh("Video thumbnail", "视频缩略图"),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        }

        IconButton(
            onClick = { showFullScreen = true },
            modifier = Modifier.align(Alignment.Center)
        ) {
            Icon(
                imageVector = Icons.Default.PlayCircle,
                contentDescription = strEnZh("Play video", "播放视频"),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp)
            )
        }
    }

    if (showFullScreen) {
        FullScreenVideoPlayer(
            mxcUrl = content.mxcUrl,
            mimeType = content.mimeType ?: "video/mp4",
            onDismiss = { showFullScreen = false }
        )
    }
}
