package com.hermes.android.media.data

import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.BitmapFactoryDecoder
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MxcImageLoaderFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaRepository: MediaRepository
) : ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(BitmapFactoryDecoder.Factory())
                add(MxcRequestDataKeyer())
                add(MxcRequestDataFetcherFactory(mediaRepository))
            }
            .build()
    }
}
