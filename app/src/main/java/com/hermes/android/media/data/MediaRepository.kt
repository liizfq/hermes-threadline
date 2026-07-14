package com.hermes.android.media.data

interface MediaRepository {
    suspend fun getContent(mxcUrl: String): ByteArray
    suspend fun getThumbnail(mxcUrl: String, width: Long, height: Long): ByteArray
    suspend fun getFile(mxcUrl: String, fileName: String, mimeType: String): String
}
