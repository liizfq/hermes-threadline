package com.hermes.android.media.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hermes.android.media.player.VoiceRecorderController
import com.hermes.android.ui.settings.strEnZh
import java.io.File

@Composable
fun VoiceRecorderBar(
    voiceRecorderController: VoiceRecorderController,
    onSend: (File, List<Float>, Long) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val amplitudes by voiceRecorderController.amplitudes.collectAsState()
    val isRecording by voiceRecorderController.isRecording.collectAsState()
    val durationMs by voiceRecorderController.durationMs.collectAsState()

    if (!isRecording) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Recording indicator
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(Color.Red)
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Live waveform
        WaveformView(
            amplitudes = amplitudes,
            modifier = Modifier.weight(1f)
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Timer
        val sec = durationMs / 1000
        Text(
            text = "${sec / 60}:${(sec % 60).toString().padStart(2, '0')}",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Cancel
        IconButton(onClick = {
            voiceRecorderController.cancelRecording()
            onCancel()
        }) {
            Icon(Icons.Default.Close, strEnZh("Cancel", "取消"), tint = MaterialTheme.colorScheme.error)
        }

        // Send
        IconButton(onClick = {
            voiceRecorderController.stopRecording()?.let { (file, waveform) ->
                onSend(file, waveform, durationMs)
            }
        }) {
            Icon(Icons.AutoMirrored.Filled.Send, strEnZh("Send", "发送"), tint = MaterialTheme.colorScheme.primary)
        }
    }
}
