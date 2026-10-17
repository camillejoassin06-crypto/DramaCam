package com.jedrama.fork.di

import com.jedrama.fork.library.PlaybackRepository
import com.jedrama.fork.library.PlaybackRepositoryImpl
import com.jedrama.fork.library.SeriesRepository
import com.jedrama.fork.library.SeriesRepositoryImpl
import com.jedrama.fork.library.Library
import com.jedrama.fork.generation.SeriesDraftStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

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
    fun providePlaybackRepository(
        library: Library,
    ): PlaybackRepository =
        PlaybackRepositoryImpl(library)
}