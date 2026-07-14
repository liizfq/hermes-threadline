package com.hermes.android.media.data

import coil.key.Keyer
import coil.request.Options

class MxcRequestDataKeyer : Keyer<MxcRequestData> {
    override fun key(data: MxcRequestData, options: Options): String? {
        return "${data.mxcUrl}_${data.kind}"
    }
}
