package com.hermes.android.media.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermes.android.domain.model.MessageContent
import com.hermes.android.media.player.AudioPlayerController
import com.hermes.android.media.player.AudioPlayerState
import com.hermes.android.ui.settings.strEnZh

@Composable
fun VoiceMessageContent(
    content: MessageContent.Voice,
    audioPlayerController: AudioPlayerController,
    modifier: Modifier = Modifier
) {
    val audioState by audioPlayerController.audioState.collectAsState()
    val isThisPlaying = audioState.currentUrl == content.mxcUrl && audioState.state == AudioPlayerState.PLAYING
    val isLoading = audioState.currentUrl == content.mxcUrl && audioState.state == AudioPlayerState.LOADING

    val displayDuration = content.duration ?: 0L
    val displayPosition = if (audioState.currentUrl == content.mxcUrl) audioState.position else 0L
    val progress = if (displayDuration > 0) (displayPosition.toFloat() / displayDuration).coerceIn(0f, 1f) else 0f

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.widthIn(max = 300.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 2.dp)
            } else {
                IconButton(
                    onClick = { audioPlayerController.playOrToggle(content.mxcUrl, content.mimeType) },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = if (isThisPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isThisPlaying) strEnZh("Pause", "暂停") else strEnZh("Play", "播放"),
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            WaveformView(
                amplitudes = content.waveform.ifEmpty { List(30) { 0.3f } },
                progress = progress,
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(8.dp))

            val totalSec = displayDuration / 1000
            Text(
                text = "${totalSec / 60}:${(totalSec % 60).toString().padStart(2, '0')}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
