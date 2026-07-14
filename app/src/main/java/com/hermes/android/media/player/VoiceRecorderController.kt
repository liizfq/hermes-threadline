package com.hermes.android.media.player

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "VoiceRecorder"

@Singleton
class VoiceRecorderController @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startTimeMs: Long = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var amplitudeJob: Job? = null

    private val _amplitudes = MutableStateFlow<List<Float>>(emptyList())
    val amplitudes: StateFlow<List<Float>> = _amplitudes.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    fun startRecording() {
        try {
            // Defensive cleanup: release any lingering MediaRecorder from abnormal termination
            if (mediaRecorder != null) {
                Log.w(TAG, "startRecording: previous MediaRecorder not released, cleaning up")
                cancelRecording()
            }

            val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
            outputFile = file
            startTimeMs = System.currentTimeMillis()

            mediaRecorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(64000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            _isRecording.value = true
            _amplitudes.value = emptyList()
            _durationMs.value = 0L

            amplitudeJob = scope.launch {
                while (isActive && _isRecording.value) {
                    val amp = try {
                        mediaRecorder?.maxAmplitude?.toFloat()?.div(32767f) ?: 0f
                    } catch (e: Exception) { 0f }
                    _amplitudes.update { (it + amp).takeLast(60) }
                    _durationMs.value = System.currentTimeMillis() - startTimeMs
                    delay(50)
                }
            }
            Log.d(TAG, "Recording started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            _isRecording.value = false
        }
    }

    fun stopRecording(): Pair<File, List<Float>>? {
        amplitudeJob?.cancel()
        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "MediaRecorder.stop failed (expected if <1s)", e)
        }
        mediaRecorder?.release()
        mediaRecorder = null
        _isRecording.value = false

        val file = outputFile ?: return null
        val waveform = _amplitudes.value
        outputFile = null
        return file to waveform
    }

    fun cancelRecording() {
        amplitudeJob?.cancel()
        try { mediaRecorder?.stop() } catch (_: Exception) {}
        mediaRecorder?.release()
        mediaRecorder = null
        _isRecording.value = false
        outputFile?.delete()
        outputFile = null
        _amplitudes.value = emptyList()
        _durationMs.value = 0L
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface VoiceRecorderEntryPoint {
    fun voiceRecorderController(): VoiceRecorderController
}
