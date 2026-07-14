package com.hermes.android.media.data

import android.content.Context
import com.hermes.android.data.repository.MatrixRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import org.matrix.rustcomponents.sdk.MediaSource
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val matrixRepository: MatrixRepository
) : MediaRepository {

    override suspend fun getContent(mxcUrl: String): ByteArray {
        val client = matrixRepository.getClient() ?: throw IllegalStateException("No Matrix client")
        val source = MediaSource.fromUrl(mxcUrl)
        return try {
            client.getMediaContent(source)
        } finally {
            source.close()
        }
    }

    override suspend fun getThumbnail(mxcUrl: String, width: Long, height: Long): ByteArray {
        val client = matrixRepository.getClient() ?: throw IllegalStateException("No Matrix client")
        val source = MediaSource.fromUrl(mxcUrl)
        return try {
            client.getMediaThumbnail(source, width.toULong(), height.toULong())
        } finally {
            source.close()
        }
    }

    override suspend fun getFile(mxcUrl: String, fileName: String, mimeType: String): String {
        val client = matrixRepository.getClient() ?: throw IllegalStateException("No Matrix client")
        val source = MediaSource.fromUrl(mxcUrl)
        return try {
            val handle = client.getMediaFile(source, fileName, mimeType, true, null)
            val tempPath = handle.path()
            // Copy to app cache dir before closing handle.
            // handle.close() may delete the SDK temp file.
            val safeName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val cacheFile = File(context.cacheDir, "media_${System.currentTimeMillis()}_$safeName")
            File(tempPath).copyTo(cacheFile, overwrite = true)
            handle.close()
            source.close()
            cacheFile.absolutePath
        } catch (e: Exception) {
            source.close()
            throw e
        }
    }
}
