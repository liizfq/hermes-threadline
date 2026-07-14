package com.hermes.android.di

import android.content.Context
import com.hermes.android.data.repository.MatrixRepository
import com.hermes.android.data.repository.MatrixRepositoryImpl
import com.hermes.android.data.repository.RoomRepository
import com.hermes.android.data.repository.RoomRepositoryImpl
import com.hermes.android.data.repository.SessionRepository
import com.hermes.android.data.repository.SessionRepositoryImpl
import com.hermes.android.data.repository.SettingsRepository
import com.hermes.android.data.repository.SettingsRepositoryImpl
import com.hermes.android.media.data.MediaRepository
import com.hermes.android.media.data.MediaRepositoryImpl
import com.hermes.android.push.PushEventParser
import com.hermes.android.push.PushEventStore
import com.hermes.android.push.SharedPreferencesPushEventStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    @Binds
    @Singleton
    abstract fun bindMatrixRepository(impl: MatrixRepositoryImpl): MatrixRepository

    @Binds
    @Singleton
    abstract fun bindRoomRepository(impl: RoomRepositoryImpl): RoomRepository

    @Binds
    @Singleton
    abstract fun bindSessionRepository(impl: SessionRepositoryImpl): SessionRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindMediaRepository(impl: MediaRepositoryImpl): MediaRepository

    companion object {
        @Provides
        @Singleton
        fun providePushEventStore(@ApplicationContext context: Context): PushEventStore =
            SharedPreferencesPushEventStore(context)

        @Provides
        fun providePushEventParser(): PushEventParser = PushEventParser()
    }
}
