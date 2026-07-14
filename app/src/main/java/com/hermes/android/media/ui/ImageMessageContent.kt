package com.hermes.android.media.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.hermes.android.domain.model.MessageContent
import com.hermes.android.media.data.MxcRequestData
import com.hermes.android.ui.settings.strEnZh

@Composable
fun ImageMessageContent(
    content: MessageContent.Image,
    modifier: Modifier = Modifier
) {
    var showFullScreen by remember { mutableStateOf(false) }

    AsyncImage(
        model = MxcRequestData(content.mxcUrl),
        contentDescription = strEnZh("Image", "图片"),
        contentScale = ContentScale.Crop,
        modifier = modifier
            .fillMaxWidth(0.7f)
            .heightIn(max = 250.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { showFullScreen = true }
    )

    if (showFullScreen) {
        FullScreenImageViewer(
            mxcUrl = content.mxcUrl,
            onDismiss = { showFullScreen = false }
        )
    }
}
