package com.hermes.android.media.data

import coil.ImageLoader
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options

class MxcRequestDataFetcherFactory(
    private val mediaRepository: MediaRepository
) : Fetcher.Factory<MxcRequestData> {
    override fun create(
        data: MxcRequestData,
        options: Options,
        imageLoader: ImageLoader
    ): Fetcher {
        return MxcRequestDataFetcher(data, mediaRepository, options)
    }
}

private class MxcRequestDataFetcher(
    private val data: MxcRequestData,
    private val mediaRepository: MediaRepository,
    private val options: Options
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val bytes = when (val kind = data.kind) {
            is MxcRequestData.Kind.Content -> {
                mediaRepository.getContent(data.mxcUrl)
            }
            is MxcRequestData.Kind.Thumbnail -> {
                try {
                    mediaRepository.getThumbnail(data.mxcUrl, kind.width, kind.height)
                } catch (_: Exception) {
                    mediaRepository.getContent(data.mxcUrl)
                }
            }
        }
        val buffer = okio.Buffer().write(bytes)
        val source = coil.decode.ImageSource(buffer, options.context)
        return coil.fetch.SourceResult(
            source = source,
            mimeType = "image/jpeg",
            dataSource = coil.decode.DataSource.NETWORK
        )
    }
}
