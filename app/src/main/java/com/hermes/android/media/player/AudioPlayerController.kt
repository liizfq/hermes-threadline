package com.hermes.android.media.player

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.hermes.android.media.data.MediaRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AudioPlayer"

enum class AudioPlayerState { IDLE, LOADING, PLAYING, PAUSED }

data class AudioState(
    val currentUrl: String? = null,
    val state: AudioPlayerState = AudioPlayerState.IDLE,
    val position: Long = 0L,
    val duration: Long = 0L
)

@Singleton
class AudioPlayerController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaRepository: MediaRepository,
    private val audioFocusManager: AudioFocusManager
) {
    private var exoPlayer: ExoPlayer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var positionUpdateJob: Job? = null
    private var autoReleaseJob: Job? = null

    private val _audioState = MutableStateFlow(AudioState())
    val audioState: StateFlow<AudioState> = _audioState.asStateFlow()

    fun playOrToggle(mxcUrl: String, mimeType: String? = null) {
        val current = _audioState.value
        if (current.currentUrl == mxcUrl) {
            when (current.state) {
                AudioPlayerState.PLAYING -> pause()
                AudioPlayerState.PAUSED -> resume()
                else -> startPlayback(mxcUrl, mimeType)
            }
        } else {
            startPlayback(mxcUrl, mimeType)
        }
    }

    private fun startPlayback(mxcUrl: String, mimeType: String?) {
        autoReleaseJob?.cancel()  // Cancel pending auto-release
        _audioState.value = AudioState(currentUrl = mxcUrl, state = AudioPlayerState.LOADING)

        val granted = audioFocusManager.requestFocus(
            onPause = { pause() },
            onResume = { resume() }
        )
        if (!granted) {
            Log.w(TAG, "Audio focus not granted")
            _audioState.value = AudioState()
            return
        }

        scope.launch {
            try {
                val ext = when {
                    mimeType?.contains("ogg") == true -> "ogg"
                    mimeType?.contains("mp3") == true -> "mp3"
                    mimeType?.contains("wav") == true -> "wav"
                    mimeType?.contains("m4a") == true -> "m4a"
                    else -> "ogg"
                }
                val localPath = withContext(Dispatchers.IO) {
                    mediaRepository.getFile(mxcUrl, "audio.$ext", mimeType ?: "audio/ogg")
                }

                val player = getOrCreatePlayer()
                val mediaItem = MediaItem.fromUri(localPath)
                player.setMediaItem(mediaItem)
                player.prepare()
                player.playWhenReady = true
                _audioState.value = _audioState.value.copy(state = AudioPlayerState.PLAYING)
                startPositionUpdates()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load audio", e)
                _audioState.value = AudioState()
                audioFocusManager.abandonFocus()
            }
        }
    }

    private fun pause() {
        exoPlayer?.playWhenReady = false
        _audioState.value = _audioState.value.copy(state = AudioPlayerState.PAUSED)
        positionUpdateJob?.cancel()
    }

    private fun resume() {
        exoPlayer?.playWhenReady = true
        _audioState.value = _audioState.value.copy(state = AudioPlayerState.PLAYING)
        startPositionUpdates()
    }

    fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
    }

    private fun getOrCreatePlayer(): ExoPlayer {
        return exoPlayer ?: ExoPlayer.Builder(context).build().also { player ->
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        _audioState.value = AudioState()
                        positionUpdateJob?.cancel()
                        audioFocusManager.abandonFocus()
                        // Auto-release ExoPlayer after 30s of inactivity
                        autoReleaseJob?.cancel()
                        autoReleaseJob = scope.launch {
                            delay(30_000)
                            release()
                        }
                    }
                }
            })
            exoPlayer = player
        }
    }

    private fun startPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = scope.launch {
            while (true) {
                val player = exoPlayer ?: break
                _audioState.value = _audioState.value.copy(
                    position = player.currentPosition,
                    duration = player.duration.coerceAtLeast(0)
                )
                delay(200)
            }
        }
    }

    fun release() {
        positionUpdateJob?.cancel()
        exoPlayer?.release()
        exoPlayer = null
        audioFocusManager.abandonFocus()
        _audioState.value = AudioState()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AudioPlayerEntryPoint {
    fun audioPlayerController(): AudioPlayerController
}
