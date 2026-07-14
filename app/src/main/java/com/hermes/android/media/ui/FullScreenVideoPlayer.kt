package com.hermes.android.media.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.hermes.android.media.data.MediaRepository
import com.hermes.android.ui.settings.strEnZh
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@EntryPoint
@InstallIn(SingletonComponent::class)
interface MediaRepositoryEntryPoint {
    fun mediaRepository(): MediaRepository
}

@Composable
fun FullScreenVideoPlayer(
    mxcUrl: String,
    mimeType: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    var exoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(mxcUrl) {
        try {
            isLoading = true
            val mediaRepo = EntryPointAccessors.fromApplication(
                context.applicationContext,
                MediaRepositoryEntryPoint::class.java
            ).mediaRepository()

            val ext = if (mimeType.contains("mp4")) "mp4" else "video"
            val localPath = withContext(Dispatchers.IO) {
                mediaRepo.getFile(mxcUrl, "video.$ext", mimeType)
            }

            val player = ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(localPath))
                prepare()
                playWhenReady = true
            }
            exoPlayer = player
            isLoading = false
        } catch (e: Exception) {
            error = e.message
            isLoading = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer?.release()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        when {
            isLoading -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White
                )
            }
            error != null -> {
                Text(
                    text = strEnZh("Playback failed: $error", "播放失败: $error"),
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            exoPlayer != null -> {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = true
                            layoutParams = android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        IconButton(
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
        ) {
            Icon(Icons.Default.Close, strEnZh("Close", "关闭"), tint = Color.White)
        }
    }
}
