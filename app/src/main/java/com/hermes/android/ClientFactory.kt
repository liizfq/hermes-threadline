package com.hermes.android

import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder

object ClientFactory {
    suspend fun create(homeserverUrl: String): Client {
        return ClientBuilder()
            .homeserverUrl(homeserverUrl)
            .threadsEnabled(true, false)
            .build()
    }
}
