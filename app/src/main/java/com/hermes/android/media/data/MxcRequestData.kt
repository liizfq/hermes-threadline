package com.hermes.android.media.data

/**
 * Custom data type for Coil AsyncImage model.
 * Using a custom type (instead of raw mxc:// String) ensures Coil
 * routes the request to our MxcRequestDataFetcherFactory, not its
 * built-in HTTP URI handler.
 *
 * Usage: AsyncImage(model = MxcRequestData(mxcUrl), ...)
 */
data class MxcRequestData(
    val mxcUrl: String,
    val kind: Kind = Kind.Content
) {
    sealed interface Kind {
        data object Content : Kind
        data class Thumbnail(val width: Long, val height: Long) : Kind {
            constructor(size: Long) : this(size, size)
        }
    }
}
