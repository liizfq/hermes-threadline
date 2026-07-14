package com.hermes.android.domain.model

import com.hermes.android.ui.components.MessageSegment

sealed class MessageContent {
    data class Text(
        val html: String?,
        val plainText: String,
        val segments: List<MessageSegment> = emptyList(),
    ) : MessageContent()
    data class Image(val mxcUrl: String, val width: Int?, val height: Int?) : MessageContent()
    data class Video(
        val mxcUrl: String,
        val thumbnailMxcUrl: String?,
        val width: Int?,
        val height: Int?,
        val duration: Long?,
        val mimeType: String?
    ) : MessageContent()
    data class Audio(val mxcUrl: String, val duration: Long?, val mimeType: String? = null) : MessageContent()
    data class Voice(
        val mxcUrl: String,
        val duration: Long?,
        val waveform: List<Float> = emptyList(),
        val mimeType: String? = null
    ) : MessageContent()
    data class File(val mxcUrl: String, val fileName: String, val fileSize: Long?, val mimeType: String? = null) : MessageContent()
}
