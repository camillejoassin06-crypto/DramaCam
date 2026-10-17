package com.jedrama.fork.di

import android.content.Context
import com.jedrama.fork.library.PlaybackRepository
import com.jedrama.fork.library.PlaybackRepositoryImpl
import com.jedrama.fork.library.SeriesRepository
import com.jedrama.fork.library.SeriesRepositoryImpl
import com.jedrama.fork.library.Library
import com.jedrama.fork.library.playbackPreferences
import com.jedrama.fork.generation.SeriesDraftStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    fun provideSeriesRepository(
        store: SeriesDraftStore,
        library: Library,
    ): SeriesRepository =
        SeriesRepositoryImpl(store, library)

    @Provides
    @Singleton
    fun providePlaybackRepository(
        library: Library,
        store: SeriesDraftStore,
        @ApplicationContext context: Context,
    ): PlaybackRepository =
        PlaybackRepositoryImpl(library, store, playbackPreferences(context))
}